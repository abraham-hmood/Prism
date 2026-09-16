"""CakeChat on Android: the seam between Prism and an unmaintained 2018 Keras project.

WHY A SEAM AND NOT A PATCH SET
------------------------------
CakeChat asks for TensorFlow 1.12 and standalone Keras 2.2.4. Chaquopy's package repository holds
exactly one TensorFlow build -- 2.1.0, cp38 only, published January 2020 -- and no standalone Keras
at all. So the plan is to run CakeChat's own code against the keras TensorFlow 2.1 bundles, and to
put everything that has to change in this one file rather than editing the repository. The source
stays byte-identical to upstream, so re-downloading it does not undo the port.

WHAT ACTUALLY HAS TO BE BRIDGED
-------------------------------
Read from CakeChat's real `tools/train.py` and `cakechat/utils/env.py`, not assumed:

  tf.set_random_seed(42)            gone from TF2's top level  -> tf.compat.v1
  tf.ConfigProto()                  gone from TF2's top level  -> tf.compat.v1
  tf.Session(config=config)         gone from TF2's top level  -> tf.compat.v1
  keras.backend.set_session(...)    not on tf.keras.backend    -> tf.compat.v1.keras.backend
  import keras.*                    no standalone keras        -> aliased to tensorflow.keras

And two things that are not API drift at all:

  S3FileResolver / get_reverse_model   the S3 bucket is gone with the project. Training a forward
                                      model asks for a reverse model from it, so that call has to be
                                      neutralised or training cannot start at all.
  processed corpus + index files       train() does NOT read raw dialogs. It requires an already
                                      processed corpus plus index_to_token and index_to_condition,
                                      built by tools/prepare_index_files.py. A user handing over raw
                                      JSONL has supplied an input CakeChat will not accept until that
                                      step has run.

`prepare()` installs the bridges. `preprocess()` runs the corpus/index step. `train()` drives
CakeChat's own trainer. Every one of them returns a bool and leaves a real traceback in
`last_error()` -- a training run that silently does nothing is worse than one that fails loudly.
"""
import inspect
import io
import json
import os
import sys
import time
import traceback

_error = None
_state = {
    "step": 0,
    "total": 0,
    "epoch": 0,
    "epochs": 0,
    "loss": 0.0,
    "started_at": 0.0,
    "running": False,
    "phase": "idle",
    "logs": [],
}


def last_error():
    """The most recent traceback, or None. Read by Kotlin whenever a call returns False."""
    return _error


def _log(line):
    _state["logs"].append(line)
    # Bounded: a long run would grow this without limit and the view only shows the tail.
    if len(_state["logs"]) > 400:
        del _state["logs"][:200]


def _fail(summary):
    """Records a failure into the log buffer as well as [last_error].

    BOTH, not either. `last_error` is a single slot the next call overwrites, and only Kotlin reads
    it -- so a failure that landed only there was absent from the trainer's own LOG panel, which is
    the first place anyone looks. The traceback is written line by line so the panel shows the same
    thing diagnostics does, instead of "Training failed; see the error above" with no error above it.
    """
    global _error
    _error = summary
    text = str(summary).rstrip()
    lines = text.splitlines() or ["unknown failure"]
    _log("FAILED: " + lines[-1].strip())
    for line in lines:
        _log("  " + line)


def recent_logs(limit=60):
    return _state["logs"][-limit:]


def progress():
    """A snapshot for the activity and the notification. Cheap enough to poll."""
    elapsed = 0.0 if not _state["started_at"] else time.time() - _state["started_at"]
    step, total = _state["step"], _state["total"]
    eta = (elapsed / step) * max(0, total - step) if step > 0 and total > 0 and elapsed > 0 else 0.0
    return {
        "step": step,
        "total": total,
        "epoch": _state["epoch"],
        "epochs": _state["epochs"],
        "loss": float(_state["loss"]),
        "elapsed": elapsed,
        "eta": eta,
        "running": _state["running"],
        "phase": _state["phase"],
    }


def stop():
    """Cooperative. Keras has no cancel, so the flag is read at batch boundaries."""
    _state["running"] = False


# ── Bridges ────────────────────────────────────────────────────────────────

def _install_tf1_compat(for_training=False):
    """Restores the TF1 names CakeChat calls, from tf.compat.v1 where TF2 still keeps them.

    Eager execution is disabled because CakeChat builds a static graph and feeds it through a
    Session, which is not something eager mode runs.
    """
    import tensorflow as tf

    # TF1 has no compat.v1 and needs none of this -- every name below already exists at the top
    # level, which is what CakeChat was written against.
    if not hasattr(tf, "compat") or not hasattr(tf.compat, "v1"):
        _log("TensorFlow %s is TF1-era; no compatibility shims needed"
             % getattr(tf, "__version__", "?"))
        return

    v1 = tf.compat.v1

    v1.disable_eager_execution()

    # REQUIRED FOR AN RNN TRAINED IN GRAPH MODE ON TENSORFLOW 2 -- AND ONLY FOR TRAINING.
    #
    # TF2 builds control flow with the v2 while_loop, which exposes only the loop outputs its own
    # graph declares. Keras RNN layers -- CakeChat's decoder among them -- need intermediate values
    # from inside the loop to compute GRADIENTS, and without this the backward pass asks the loop
    # for a tensor it does not publish:
    #
    #     InvalidArgumentError: Connecting to invalid output 51 of source node
    #     decoder_training_model/decoder_1/while which has 51 outputs.
    #     Try using tf.compat.v1.experimental.output_all_intermediates(True).
    #
    # The cost is memory, and it is not small: the loop retains EVERY per-step intermediate for the
    # whole sequence instead of just its outputs. That is a fair price for a backward pass that
    # works at all, and a pure waste for inference, which computes no gradients and reads none of
    # those tensors. Enabling it there inflates the peak allocation of the one operation most
    # likely to fail on a phone -- building the model and loading its weights -- for no benefit.
    #
    # Absent on TF1, where the v1 while_loop already behaves this way.
    experimental = getattr(v1, "experimental", None)
    output_all_intermediates = getattr(experimental, "output_all_intermediates", None)
    if output_all_intermediates is not None:
        output_all_intermediates(bool(for_training))
        if for_training:
            _log("Enabled output_all_intermediates for graph-mode RNN gradients")
        else:
            _log("output_all_intermediates left off; inference needs no gradients")

    # tf.summary survives into TF2 but loses FileWriter, which only exists under compat.v1.
    v1_summary = getattr(v1, "summary", None)
    tf_summary = getattr(tf, "summary", None)
    if v1_summary is not None and tf_summary is not None:
        for name in ("FileWriter", "FileWriterCache", "scalar", "merge_all"):
            if not hasattr(tf_summary, name) and hasattr(v1_summary, name):
                setattr(tf_summary, name, getattr(v1_summary, name))

    for name in (
        "set_random_seed", "ConfigProto", "Session", "placeholder", "get_default_graph",
        "global_variables_initializer", "variable_scope", "get_variable", "assign",
        "trainable_variables", "sparse_softmax_cross_entropy_with_logits",
        # RunOptions and RunMetadata are used by dialog_model/model.py for run-time profiling;
        # variable_scope by the same file. All three moved under compat.v1 in TF2.
        "RunOptions", "RunMetadata", "GraphOptions", "OptimizerOptions",
        # Summary is TF1's protobuf type, used by the TensorBoard metrics plotter.
        "Summary",
    ):
        if not hasattr(tf, name) and hasattr(v1, name):
            setattr(tf, name, getattr(v1, name))


def _alias_keras():
    """Makes CakeChat's keras imports resolve against TensorFlow's bundled keras.

    Aliasing the top package is nowhere near enough. Keras 2.2.4 exposed internals that tf.keras
    simply does not have, and CakeChat imports them by name. Every shim below exists because a
    specific line in the repository fails without it:

      cakechat/utils/env.py:6
          from keras.backend.tensorflow_backend import set_session
          -> `keras.backend.tensorflow_backend` was a Keras 2.2.4 implementation module. There is no
             equivalent; a synthetic module is registered exposing set_session/get_session from
             tf.compat.v1.keras.backend, which is where TF2 kept them.

      cakechat/dialog_model/keras_model.py
          K.tf.Graph(), K.tf.Session()
          -> Keras 2.2.4's backend re-exported the whole tensorflow module as `K.tf`. tf.keras.backend
             has no such attribute, so it is attached.

      cakechat/dialog_model/layers.py, model.py
          from keras.layers import K, ...
          -> Keras 2.2.4's `keras.layers` re-exported the backend as `K`. tf.keras.layers does not.

      cakechat/dialog_model/model.py
          from keras.layers import ..., CuDNNGRU, GRU
          -> CuDNNGRU was REMOVED in TF2; the cuDNN path was folded into GRU with automatic device
             selection. tf.compat.v1.keras.layers still ships the class, so it is taken from there,
             and falls back to plain GRU. On a phone there is no cuDNN either way, so the fallback
             costs nothing but has to exist or the import fails.

    Submodules are registered in sys.modules rather than only set as attributes, because
    `from keras.layers import X` is resolved through sys.modules['keras.layers'].
    """
    import sys as _sys
    import types

    import tensorflow as tf

    # SKIPPED ENTIRELY WHEN A REAL KERAS IS INSTALLED. This file is shared with the desktop build,
    # which installs CakeChat's own Keras 2.2.4 against TensorFlow 1.12 -- exactly what the code was
    # written for. Aliasing there would REPLACE a correct Keras with tf.keras and reintroduce every
    # incompatibility this function exists to work around.
    # THE VERSION TEST IS THE WHOLE TEST. TensorFlow 2 ships a package importable as `keras` too, so
    # "is keras installed" answers nothing. Only multi-backend Keras below 2.4 has the module layout
    # CakeChat imports -- keras.backend.tensorflow_backend chief among them. Keras 2.4 and later are
    # thin wrappers over tf.keras and need every shim below exactly as much as tf.keras does.
    _real_keras = None
    try:
        import keras as _real_keras

        _version = getattr(_real_keras, "__version__", "")
        _parts = _version.split(".")
        _major, _minor = int(_parts[0]), int(_parts[1])
        if (_major, _minor) < (2, 4) and hasattr(_real_keras, "backend"):
            _log("Using multi-backend Keras %s, which CakeChat targets; no aliasing needed"
                 % _version)
            return
    except (ImportError, ValueError, IndexError):
        _real_keras = None

    # THE REAL MODULE, NEVER tf.keras ITSELF, WHEN ONE EXISTS.
    #
    # From TensorFlow 2.13 or so, `tf.keras` is a LazyLoader rather than a module. Resolving it runs
    # `import keras` and reads `keras.__version__` to decide between Keras 2 and Keras 3 -- so
    # binding sys.modules["keras"] to the loader BEFORE it has resolved makes it import itself, and
    # the version check recurses until Python gives up:
    #
    #     File "tensorflow/python/util/lazy_loader.py", line 147, in __getattr__
    #       if self._keras_version == "keras_3":
    #     [Previous line repeated 986 more times]
    #     RecursionError: maximum recursion depth exceeded in comparison
    #
    # Using the standalone `keras` package sidesteps the loader completely: it is the very module
    # tf.keras would have resolved to, and it is a genuine module with real submodules to alias.
    # Older TensorFlow (2.1, which is what Chaquopy ships for Android) has no standalone keras, and
    # there tf.keras is an ordinary module -- but it is touched below before anything is rebound, so
    # that a lazy loader resolves while sys.modules["keras"] still means what it meant.
    if _real_keras is not None:
        keras = _real_keras
    else:
        keras = tf.keras
        getattr(keras, "__name__", None)   # force a LazyLoader to resolve while it still can
        keras = sys.modules.get("keras", keras)

    sys.modules["keras"] = keras

    for sub in (
        "backend", "layers", "models", "optimizers", "callbacks", "initializers",
        "regularizers", "constraints", "activations", "losses", "metrics", "utils",
        "preprocessing",
    ):
        module = getattr(keras, sub, None)
        if module is not None:
            sys.modules["keras." + sub] = module

    v1_keras = getattr(tf.compat.v1, "keras", None)
    v1_backend = getattr(v1_keras, "backend", None)
    backend = getattr(keras, "backend", None)

    # --- keras.backend extras -------------------------------------------------
    if backend is not None:
        if v1_backend is not None:
            for name in ("set_session", "get_session", "clear_session"):
                if not hasattr(backend, name) and hasattr(v1_backend, name):
                    setattr(backend, name, getattr(v1_backend, name))
        # K.tf, which keras_model.py uses to build its own Graph and Session.
        if not hasattr(backend, "tf"):
            setattr(backend, "tf", tf)

    # --- keras.backend.tensorflow_backend ------------------------------------
    source = v1_backend if v1_backend is not None else backend
    if source is not None:
        tb_name = "keras.backend.tensorflow_backend"
        if tb_name not in sys.modules:
            tb = types.ModuleType(tb_name)
            for name in ("set_session", "get_session", "clear_session"):
                if hasattr(source, name):
                    setattr(tb, name, getattr(source, name))
            # Anything else the module is asked for is forwarded to the real backend, so a call
            # this list did not anticipate resolves instead of raising AttributeError.
            tb.__getattr__ = lambda name, _b=backend: getattr(_b, name)
            sys.modules[tb_name] = tb
            if backend is not None:
                setattr(backend, "tensorflow_backend", tb)
            _log("Shimmed keras.backend.tensorflow_backend")

    # --- keras.layers extras -------------------------------------------------
    layers = getattr(keras, "layers", None)
    if layers is not None:
        if backend is not None and not hasattr(layers, "K"):
            setattr(layers, "K", backend)
        if not hasattr(layers, "CuDNNGRU"):
            v1_layers = getattr(v1_keras, "layers", None)
            cudnn = getattr(v1_layers, "CuDNNGRU", None) if v1_layers is not None else None
            if cudnn is None:
                cudnn = getattr(layers, "GRU", None)
                if cudnn is not None:
                    _log("CuDNNGRU unavailable; mapped to GRU (no cuDNN on this device anyway)")
            if cudnn is not None:
                setattr(layers, "CuDNNGRU", cudnn)
        if not hasattr(layers, "CuDNNLSTM"):
            v1_layers = getattr(v1_keras, "layers", None)
            cudnn_lstm = getattr(v1_layers, "CuDNNLSTM", None) if v1_layers is not None else None
            if cudnn_lstm is None:
                cudnn_lstm = getattr(layers, "LSTM", None)
            if cudnn_lstm is not None:
                setattr(layers, "CuDNNLSTM", cudnn_lstm)

    _ = _sys


def _disable_tensorboard_plotter():
    """Replaces the TensorBoard metrics plotter with a silent one of the same shape.

    `cakechat/dialog_model/quality/metrics/plotters.py` writes event files through three TF1 APIs
    that TF2 moved -- `tf.Summary()`, `tf.summary.FileWriter()` and `writer.add_run_metadata()` --
    so the first evaluation hook, which fires on batch 0, died with:

        AttributeError: module 'tensorflow' has no attribute 'Summary'

    Disabling it is better than restoring those names. TensorBoard event files are written for a
    tool nobody is going to point at an Android app's private storage, so the work is pure cost --
    disk, CPU and a protobuf write on every evaluation -- and removing the sink kills the whole
    class of TF1-summary breakage rather than the one call that failed first.

    NOT CakeChat's own DummyMetricsPlotter, which is what a first version of this used and which
    is not a drop-in. Its full definition is:

        class DummyMetricsPlotter(object):
            def plot(self, model_id, metric_name, metric_value): pass

    No `__init__`, so `DummyMetricsPlotter(log_dir)` fails with "takes no arguments"; and no
    `log_run_metadata` or `log_dir`, both of which the real plotter has and callers use. The
    replacement below matches TensorboardMetricsPlotter's interface exactly instead.

    Replaced in every module holding a reference: `from x import y` binds by value, so a module
    that already imported the real plotter keeps it unless it is replaced there too.
    """
    import cakechat.dialog_model.quality.metrics.plotters as plotters

    class _SilentMetricsPlotter(object):
        """TensorboardMetricsPlotter's interface, doing nothing."""

        def __init__(self, log_dir=None, *args, **kwargs):
            self._log_dir = log_dir

        def plot(self, model_name, metric_name, metric_value):
            pass

        def log_run_metadata(self, model_name, run_metadata):
            pass

        @property
        def log_dir(self):
            return self._log_dir

    plotters.TensorboardMetricsPlotter = _SilentMetricsPlotter
    # Left pointing at the same no-op, so code selecting the "dummy" explicitly also gets something
    # with the full interface rather than the two-thirds one upstream ships.
    plotters.DummyMetricsPlotter = _SilentMetricsPlotter

    for module_name in (
        "cakechat.dialog_model.keras_model",
        "cakechat.dialog_model.abstract_model",
        "cakechat.dialog_model.model",
        "cakechat.dialog_model.callbacks",
        "cakechat.dialog_model.quality.metrics",
    ):
        module = sys.modules.get(module_name)
        if module is None:
            continue
        for attr in ("TensorboardMetricsPlotter", "DummyMetricsPlotter"):
            if hasattr(module, attr):
                setattr(module, attr, _SilentMetricsPlotter)

    _log("TensorBoard metrics plotter disabled (event files are useless on a phone)")


def _patch_model_saving():
    """Saves weights instead of a full SavedModel.

    CakeChat's own pair gives the game away:

        def _save_model(self, model_file_path):
            self._model.save(model_file_path, overwrite=True)
            self._logger.info('Saved model weights to {}'.format(model_file_path))

        def _load_model(self, fresh_model, model_file_path):
            fresh_model.load_weights(model_file_path, by_name=True)

    It writes an entire SavedModel and only ever reads WEIGHTS back. Under Keras 2.2.4 `.save()`
    wrote an HDF5 file and the two matched; TF 2.1 turned `.save()` into a SavedModel export that
    re-traces every layer through `trace_with_training` in a fresh FuncGraph.

    That re-trace is what now fails. It rebuilds the graph with tensors no `_input_map` has seen, so
    TimeDistributed hands its wrapped encoder an unflattened input:

        ValueError: Input 0 of layer bidir_utterance_encoder is incompatible with the layer:
        expected ndim=3, found ndim=4. Full shape received: [None, 3, 30, 128]

    Repairing the trace is the wrong target. The serialisation it produces is never loaded, costs a
    full graph re-trace on a phone at every evaluation, and only exists because a Keras 2.2.4 call
    changed meaning underneath. `save_weights` writes exactly what `load_weights` reads.

    Forced to HDF5 rather than the TF checkpoint format `save_weights` defaults to for extensionless
    paths, so one file lands where CakeChat expects one file -- a checkpoint would scatter
    `.index`/`.data-00000-of-00001` beside it and `load_weights(by_name=True)` would not find them.
    """
    try:
        import cakechat.dialog_model.keras_model as keras_model
    except Exception:
        _log("Could not import keras_model; model saving left as upstream")
        return

    model_cls = getattr(keras_model, "AbstractKerasModel", None)
    if model_cls is None or getattr(model_cls, "_prism_save_patched", False):
        return

    def _save_model(self, model_file_path):
        self._model.save_weights(model_file_path, overwrite=True, save_format="h5")
        self._logger.info("Saved model weights to {}".format(model_file_path))

    model_cls._save_model = _save_model
    model_cls._prism_save_patched = True
    _log("Model saving switched to weights-only HDF5 (no SavedModel re-trace)")


def _patch_lambda_masks():
    """Restores Keras 2.2.4's mask shape for multi-input Lambda layers.

    CakeChat's decoder builds Lambda layers with a mask function that INDEXES its mask argument:

        cakechat/dialog_model/model.py:388
            mask=lambda inputs, inputs_masks: inputs_masks[0]

    Under Keras 2.2.4 a layer called on a list of inputs always received a LIST of masks, one per
    input, with None for the unmasked ones -- so `inputs_masks[0]` was safe even when nothing was
    masked. TF 2.1 changed that: `_collect_input_masks` returns a bare None when every input mask is
    None, rather than a list of Nones. Indexing it gives

        TypeError: 'NoneType' object is not subscriptable

    which is what killed model construction.

    This is a BEHAVIOURAL difference, not a missing symbol, so no amount of aliasing fixes it. The
    narrowest correction is to normalise the argument back to the old shape at the one place it is
    consumed: if a Lambda has a callable mask, was called on a sequence of inputs, and TF handed it
    None, it gets a list of Nones of the right length instead.

    Deliberately scoped to Lambda layers with a callable mask. Every other layer, and every Lambda
    without one, is left exactly as TF 2.1 behaves -- a blanket change to mask handling would alter
    real masking semantics rather than restoring a lost list shape.
    """
    import tensorflow as tf

    layers = getattr(tf.keras, "layers", None)
    lambda_cls = getattr(layers, "Lambda", None) if layers is not None else None
    if lambda_cls is None or getattr(lambda_cls, "_prism_mask_patched", False):
        return

    original_compute_mask = lambda_cls.compute_mask

    def compute_mask(self, inputs, mask=None):
        if mask is None and callable(getattr(self, "mask", None)) \
                and isinstance(inputs, (list, tuple)):
            mask = [None] * len(inputs)
        return original_compute_mask(self, inputs, mask)

    lambda_cls.compute_mask = compute_mask
    lambda_cls._prism_mask_patched = True
    _log("Lambda mask shape restored to Keras 2.2.4 semantics")


def _patch_timedistributed_masks():
    """Reshapes TimeDistributed's input when its identity map misses.

    TF 2.1's TimeDistributed.compute_mask resolves the inner (flattened) tensor by IDENTITY:

        input_uid = generic_utils.object_list_uid(inputs)
        inner_inputs = self._input_map.get(input_uid, inputs)
        output_mask = self.layer.compute_mask(inner_inputs, inner_mask)

    `_input_map` is filled during call(), keyed on the tensor object it saw. CakeChat nests a Model
    inside TimeDistributed inside another Model, and the outer Model's compute_mask runs
    `_run_internal_graph`, which re-invokes every layer with FRESH tensors. Those have uids that
    were never registered, so `.get` falls through to its default -- the raw, unflattened input --
    and the wrapped encoder is handed 4 dimensions where it declares 3:

        ValueError: Input 0 of layer bidir_utterance_encoder is incompatible with the layer:
        expected ndim=3, found ndim=4. Full shape received: [None, 3, 30, 128]

    (batch, 3 context utterances, 30 tokens, 128 embedding -- the context dimension never collapsed.)

    The fix registers what call() would have registered: on a miss, the input is reshaped to
    (-1,) + shape[2:], which is exactly the transformation TimeDistributed.call performs, and cached
    under the new uid. Nothing else changes -- a hit still uses the tensor call() stored, so the
    normal path is untouched.
    """
    import tensorflow as tf

    layers = getattr(tf.keras, "layers", None)
    time_distributed = getattr(layers, "TimeDistributed", None) if layers is not None else None
    if time_distributed is None or getattr(time_distributed, "_prism_mask_patched", False):
        return

    try:
        from tensorflow.python.keras.utils import generic_utils
        from tensorflow.python.ops import array_ops
    except Exception:
        _log("Could not import TF internals; TimeDistributed mask patch skipped")
        return

    original_compute_mask = time_distributed.compute_mask

    def compute_mask(self, inputs, mask=None):
        try:
            input_map = getattr(self, "_input_map", None)
            if input_map is not None and not isinstance(inputs, (list, tuple)):
                uid = generic_utils.object_list_uid(inputs)
                if uid not in input_map:
                    shape = getattr(inputs, "shape", None)
                    # Only when there is a time axis to collapse. A 3D input is already what the
                    # wrapped layer wants, and reshaping it would break the case that works.
                    if shape is not None and len(shape) > 3:
                        inner_shape = self._get_shape_tuple((-1,), inputs, 2)
                        input_map[uid] = array_ops.reshape(inputs, inner_shape)
        except Exception:
            # Never let the repair itself break mask computation; the original behaviour is still
            # correct wherever the map hits.
            pass
        return original_compute_mask(self, inputs, mask)

    time_distributed.compute_mask = compute_mask
    time_distributed._prism_mask_patched = True
    _log("TimeDistributed mask input reshaping restored")


def _stub_gensim():
    """Satisfies CakeChat's module-level gensim import without shipping gensim.

    `cakechat/utils/w2v/model.py` does `from gensim.models import Word2Vec` at import time, and
    `tools/train.py` imports that module -- so gensim has to be IMPORTABLE even though word2vec is
    switched off. It is not shipped because its armeabi-v7a build fails Chaquopy's post-install
    step and takes the whole four-ABI resolve down with it.

    The stub raises if anything actually tries to USE Word2Vec, rather than returning a silent
    dummy. Pretrained embeddings are disabled deliberately; if some path still reaches for them the
    right outcome is a clear error, not a model quietly trained on garbage vectors.
    """
    if "gensim" in sys.modules:
        return

    import types

    class _Word2VecUnavailable:
        def __init__(self, *args, **kwargs):
            raise RuntimeError(
                "word2vec is not available in Prism's CakeChat build. Pretrained embeddings came "
                "from the project's S3 bucket, which no longer exists, so training runs with "
                "use_pretrained_w2v=False."
            )

        @staticmethod
        def load(*args, **kwargs):
            raise RuntimeError("word2vec model loading is disabled in Prism's CakeChat build.")

    gensim = types.ModuleType("gensim")
    models = types.ModuleType("gensim.models")
    models.Word2Vec = _Word2VecUnavailable
    gensim.models = models
    sys.modules["gensim"] = gensim
    sys.modules["gensim.models"] = models
    _log("gensim stubbed (word2vec disabled)")


def _stub_missing_dependencies():
    """Satisfies imports for the packages CakeChat lists but Prism does not ship.

    requirements.txt asks for fourteen packages. Prism installs the ones that do real work and
    leaves the rest out, because each wheel is size in the APK and some do not build for every ABI
    -- gensim's armeabi-v7a build fails Chaquopy's post-install step outright. But leaving a package
    out is not the same as it being unreachable: Python still executes the import.

    Two kinds of stub, chosen per package rather than uniformly:

      RAISE ON USE -- boto3, botocore, flask, telepot, gunicorn. Every one of these serves a path
      that is switched off: the S3 bucket died with the project, and the HTTP API and Telegram bot
      have no meaning inside an Android app. If some code path genuinely reaches them, a loud error
      naming the package is the right outcome, not a dummy that quietly returns nothing.

      WORK PROPERLY -- tqdm, cachetools, unicodecsv. These are used for their behaviour, not their
      service. A raising stub would break a progress-wrapped loop or a cached lookup that CakeChat
      depends on, so they get small real implementations instead: tqdm passes its iterable through,
      cachetools' decorators become identities, and unicodecsv is the standard library's csv, which
      is already unicode-correct on Python 3.

    Nothing here overrides a package that IS installed -- each is skipped if the real import works.
    """
    import importlib
    import types

    def already_present(name):
        if name in sys.modules:
            return True
        try:
            importlib.import_module(name)
            return True
        except Exception:
            return False

    # --- inert -----------------------------------------------------------------
    #
    # THESE RETURN HARMLESSLY RATHER THAN RAISING, and that was a correction. Raising looked like
    # the honest option -- fail loudly if dead code is reached -- but it is wrong for exactly the
    # case that matters: the whole point of this build is that Prism TRAINS the weights, so every
    # S3 fetch is a path that should quietly do nothing and let training proceed. A raise turns a
    # dead path into a crash, which is what happened: `botocore is not available` aborted a run
    # that had no need of S3 in the first place.
    #
    # Anything asked of them yields another inert object, so a chain like
    # boto3.resource('s3').Bucket(x).download_file(...) completes without touching the network.
    class _Inert(object):
        def __init__(self, *args, **kwargs):
            pass

        def __call__(self, *args, **kwargs):
            return _Inert()

        def __getattr__(self, name):
            return _Inert()

        def __bool__(self):
            return False

        def __iter__(self):
            return iter(())

    def _inert_module(name, inert):
        """A module whose every ordinary attribute is inert, but which still introspects normally.

        DUNDERS MUST RAISE AttributeError RATHER THAN ANSWER. A module-level `__getattr__` that
        replies to every name, `__file__` included, breaks anything that inspects the module -- and
        the standard library inspects modules while FORMATTING A TRACEBACK, walking every frame's
        globals to find where each object was defined:

            File "inspect.py", line 897, in getfile
              raise TypeError('{!r} is a built-in module'.format(object))
            TypeError: <module 'boto3' from <_Inert object>> is a built-in module

        The consequence is worse than the error itself: this happens while reporting some other
        exception, so the stub replaces the real failure with a confusing one of its own and the
        original is never seen. Answering only non-dunder names keeps the stub inert where it is
        meant to be and invisible everywhere else.
        """
        module = types.ModuleType(name)
        # A real string, because getfile returns it directly. It names no actual file, which is
        # fine -- findsource raises OSError from there and every caller in inspect handles that.
        module.__file__ = "<prism inert stub: %s>" % name

        def __getattr__(attr, _i=inert):
            if attr.startswith("__") and attr.endswith("__"):
                raise AttributeError(attr)
            return _i()

        module.__getattr__ = __getattr__
        return module

    for name, why in (
        ("boto3", "the S3 bucket CakeChat used for weights no longer exists"),
        ("botocore", "the S3 bucket CakeChat used for weights no longer exists"),
        ("flask", "CakeChat's HTTP API is not served from inside Prism"),
        ("telepot", "CakeChat's Telegram bot is not run from inside Prism"),
        ("gunicorn", "CakeChat's HTTP API is not served from inside Prism"),
    ):
        if already_present(name):
            continue
        stub = _inert_module(name, _Inert)
        sys.modules[name] = stub
        # Registered in sys.modules so `import boto3.session` resolves, but deliberately NOT set
        # as attributes on the parent. A submodule attribute SHADOWS the parent's __getattr__, so
        # `boto3.resource("s3")` would find a module object and fail with "'module' object is not
        # callable" -- which is the opposite of inert. Left unset, every attribute chain falls
        # through to _Inert and stays callable to any depth.
        for sub in ("session", "client", "resource", "exceptions", "config"):
            sys.modules[name + "." + sub] = _inert_module(name + "." + sub, _Inert)
    _log("Stubbed unused packages (inert): boto3, botocore, flask, telepot, gunicorn")

    # --- work properly --------------------------------------------------------
    if not already_present("tqdm"):
        tqdm_module = types.ModuleType("tqdm")

        def _tqdm(iterable=None, *args, **kwargs):
            return [] if iterable is None else iterable

        _tqdm.write = lambda *a, **k: None
        tqdm_module.tqdm = _tqdm
        tqdm_module.trange = lambda *a, **k: range(*a)
        sys.modules["tqdm"] = tqdm_module

    if not already_present("cachetools"):
        cachetools = types.ModuleType("cachetools")
        cachetools.cached = lambda *a, **k: (lambda fn: fn)
        cachetools.LRUCache = dict
        cachetools.TTLCache = lambda *a, **k: {}
        sys.modules["cachetools"] = cachetools

    if not already_present("unicodecsv"):
        import csv as _csv
        sys.modules["unicodecsv"] = _csv


def collect_garbage():
    """Public wrapper, for Kotlin to call between two memory-heavy steps.

    Named without the underscore because Chaquopy resolves attributes by name and a private helper
    is not part of any contract; the training service calls this before converting to TFLite, while
    the graph it just finished with is still reachable.
    """
    _collect_garbage()


def _collect_garbage():
    """Releases what the failed attempt held before the next one allocates.

    Both halves matter: Python's collector frees the wrappers, and clear_session drops the graph and
    its variables, which is where nearly all of the memory actually is. Without the second, a retry
    starts with the previous attempt's model still resident and runs out sooner than it did.
    """
    import gc

    try:
        import keras
        keras.backend.clear_session()
    except Exception:
        pass
    gc.collect()


def _load_upstream_train(repo_root):
    """Returns CakeChat's `train` function, loaded from tools/train.py by path.

    NOT `from cakechat.dialog_model.train import train` -- that module does not exist. The training
    entry point lives in `tools/train.py`, which is a script rather than part of the package, so it
    is loaded by file path. Importing it by bare name would also risk colliding with any other
    `train` module on the path.
    """
    import importlib.util

    path = os.path.join(repo_root, "tools", "train.py")
    if not os.path.isfile(path):
        raise RuntimeError("tools/train.py is missing; the CakeChat install is incomplete.")

    spec = importlib.util.spec_from_file_location("cakechat_tools_train", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.train


def _neutralise_s3(repo_root):
    """Removes every path that would fetch weights, so training is the only source of them.

    PRISM MAKES THE WEIGHTS. There are no pretrained ones to download -- the bucket
    `tools/fetch.py` served died with the project -- so every remote lookup here is not merely
    broken, it is pointless. The goal is that nothing reaches the network and CakeChat falls through
    to its own "no cached model, train one" path.

    A FIRST VERSION OF THIS PATCHED ONLY S3FileResolver, and that was not enough. The fetch surface
    is wider than one class:

        cakechat/utils/s3/resolver.py   get_s3_resource()        builds the boto3 client
                                        get_s3_model_resolver()  wraps it in a package resolver
                                        S3FileResolver._resolve  downloads through S3Bucket
        cakechat/dialog_model/factory   _get_index_to_token(fetch_from_s3)
                                        _get_index_to_condition(fetch_from_s3)
                                        get_trained_model(fetch_from_s3=True)

    Missing any one of them left a live call into boto3, which is how a run with no need of S3
    still died on it.

    The replacement resolver answers from the LOCAL FILE, which is the behaviour that makes training
    work: index files built by prepare_index_files exist on disk, so resolve() reports True and
    CakeChat proceeds; a genuinely absent file reports False and CakeChat trains from scratch.
    Returning None unconditionally, as the first version did, would have denied files that were
    sitting right there.
    """
    import os as _os

    class _LocalOnly:
        """Stands in for S3FileResolver: answers from disk, never from the network."""

        def __init__(self, file_path=None, *args, **kwargs):
            self.file_path = file_path

        def resolve(self, *args, **kwargs):
            path = self.file_path
            return bool(path) and _os.path.exists(path)

        def _resolve(self, *args, **kwargs):
            return False

        @staticmethod
        def init_resolver(*args, **kwargs):
            # Upstream returns a partial that is later called with a file_path; a plain constructor
            # reference behaves the same way at every call site that uses it.
            return _LocalOnly

    def _no_resource(*args, **kwargs):
        return None

    def _no_model_resolver(*args, **kwargs):
        return _LocalOnly

    # Patched in every module that holds a reference. `from x import y` binds by VALUE, so a module
    # that already imported the real name keeps it unless it is replaced there too.
    import cakechat.utils.s3 as s3_pkg
    import cakechat.utils.s3.resolver as s3_resolver

    for module in (s3_pkg, s3_resolver):
        for attr, replacement in (
            ("S3FileResolver", _LocalOnly),
            ("get_s3_resource", _no_resource),
            ("get_s3_model_resolver", _no_model_resolver),
        ):
            if hasattr(module, attr):
                setattr(module, attr, replacement)

    import cakechat.dialog_model.factory as factory

    for attr, replacement in (
        ("S3FileResolver", _LocalOnly),
        ("get_s3_model_resolver", _no_model_resolver),
        ("get_s3_resource", _no_resource),
    ):
        if hasattr(factory, attr):
            setattr(factory, attr, replacement)

    factory.get_reverse_model = lambda *args, **kwargs: None
    _log("Reverse model disabled; a forward-only model trains without it")


    # fetch_from_s3 defaults to True on all three. Forced False so the local branch is taken before
    # any resolver is consulted at all.
    for name in ("get_trained_model", "_get_index_to_token", "_get_index_to_condition"):
        original = getattr(factory, name, None)
        if original is None:
            continue

        def _local_only(*args, _original=original, **kwargs):
            # BOUND THROUGH THE SIGNATURE, not forced into kwargs. CakeChat calls some of these
            # positionally -- `_get_index_to_token(fetch_from_s3)` -- and adding the keyword on top
            # of a positional value is an error, not an override:
            #
            #     TypeError: _get_index_to_token() got multiple values for argument 'fetch_from_s3'
            #
            # Binding first means the argument is replaced wherever it already sits, and appended
            # only when it is absent.
            try:
                bound = inspect.signature(_original).bind_partial(*args, **kwargs)
                bound.arguments["fetch_from_s3"] = False
                return _original(*bound.args, **bound.kwargs)
            except (TypeError, ValueError):
                # A signature that cannot be read or bound: fall back to the keyword, which is
                # right for every call that did not pass it positionally.
                kwargs["fetch_from_s3"] = False
                return _original(*args, **kwargs)

        setattr(factory, name, _local_only)

    _log("S3 fetch paths disabled; weights come from training on this device")


def _restore_numpy_aliases():
    """Puts back the plain-name numpy aliases that NumPy 1.24 removed.

    CakeChat writes `np.zeros(batch_size, dtype=np.bool)`. `np.bool` was an alias for the builtin
    `bool`, deprecated in NumPy 1.20 and deleted in 1.24:

        AttributeError: module 'numpy' has no attribute 'bool'.

    Restoring the alias is exactly what NumPy's own message recommends the code be changed to --
    `np.bool` meant `bool` and nothing more. The same is true of the other four names, which are
    restored together so the next one to be reached is not a second round of this.

    Deliberately NOT `np.bool_` and friends: those are numpy's scalar TYPES, which are not what the
    aliases meant, and substituting them would quietly change what `dtype=` receives.
    """
    import numpy

    restored = []
    for name, builtin in (
        ("bool", bool), ("int", int), ("float", float), ("object", object), ("str", str),
    ):
        if not hasattr(numpy, name):
            setattr(numpy, name, builtin)
            restored.append("np." + name)

    if restored:
        _log("Restored numpy aliases removed in 1.24: %s" % ", ".join(restored))


def _patch_repeat_vector():
    """Replaces `RepeatVector` in CakeChat's layers with one that accepts a dynamic length.

    cakechat/dialog_model/layers.py repeats a vector along a sequence whose length is only known at
    run time, and gets that length from the graph:

        RepeatVector(n=K.shape(layer_for_getting_rep_num)[1], name='custom_repeat_vector')

    Keras 2.2 accepted a symbolic `n` here -- it went straight into a tile -- and upstream's own
    comment calls the whole function a "Temporary solution". Newer Keras validates the argument:

        TypeError: Expected an integer value for `n`, got
        <class 'tensorflow.python.framework.ops.SymbolicTensor'>.

    The validation is reasonable, because RepeatVector must report a static output shape and cannot
    when `n` is a tensor. But CakeChat does not need a layer here at all: it is inside a Lambda,
    which already handles shape inference, and the operation itself is one tile.

    So `RepeatVector` is rebound in that module to a plain function producing the same tensor:
    expand to (batch, 1, dim), then tile to (batch, n, dim). Identical result for an integer `n`,
    and it also works for a symbolic one. Only that module is affected -- Keras's real RepeatVector
    is untouched for any other caller.
    """
    import importlib

    import keras
    from keras import backend as K

    def repeat_vector(n, name=None):
        """Stands in for `RepeatVector(n)`, returning a callable exactly as the layer did."""

        def apply(x):
            # stack rather than a Python list, because n may be a scalar tensor and tile needs its
            # multiples as a single 1-D tensor.
            return K.tile(K.expand_dims(x, axis=1), K.stack([1, n, 1]))

        return apply

    patched = []
    for module_name in (
        "cakechat.dialog_model.layers",
        "cakechat.dialog_model.model",
    ):
        try:
            module = importlib.import_module(module_name)
        except Exception:
            continue
        if getattr(module, "RepeatVector", None) is not None:
            module.RepeatVector = repeat_vector
            patched.append(module_name)

    if patched:
        _log("Replaced RepeatVector with a dynamic-length equivalent in %s" % ", ".join(patched))


def _patch_optimizers():
    """Points Keras's optimizer names at the legacy, graph-mode implementations.

    CakeChat builds `optimizers.Adadelta(lr=..., clipvalue=...)` and immediately serialises it:

        keras_model.compile(..., optimizer=self._optimizer.get_config())

    Keras 2.11 replaced every optimizer with a rewrite that keeps hyperparameters as eager tensors,
    and its `get_config` reads them back with `.numpy()`:

        NotImplementedError: numpy() is only available when eager execution is enabled.

    Which cannot work here, because CakeChat is a TF1 graph-mode model and [_install_tf1_compat]
    disables eager execution on purpose. The two requirements are irreconcilable -- so this uses the
    optimizers Keras kept for exactly this situation, under `keras.optimizers.legacy`. They are the
    pre-2.11 implementations, they read hyperparameters through the backend rather than through
    eager tensors, and they are what CakeChat was written against in the first place.

    A no-op where `legacy` does not exist, which covers both the Keras 2.2 that the desktop installs
    on an old interpreter and the TensorFlow 2.1 that Chaquopy ships for Android: on those, the only
    optimizers are the ones this would substitute.
    """
    import keras

    optimizers = getattr(keras, "optimizers", None)
    legacy = getattr(optimizers, "legacy", None) if optimizers is not None else None
    if legacy is None:
        return

    swapped = []
    for name in ("Adadelta", "Adagrad", "Adam", "Adamax", "Ftrl", "Nadam", "RMSprop", "SGD"):
        replacement = getattr(legacy, name, None)
        if replacement is None or getattr(optimizers, name, None) is replacement:
            continue
        setattr(optimizers, name, replacement)
        swapped.append(name)

    if swapped:
        _log("Using legacy Keras optimizers (%s) for graph-mode training" % ", ".join(swapped))


def _patch_tee_file():
    """Makes CakeChat's `file_buffered_tee` work on Windows.

    Upstream spools an iterable to a temporary file and hands back N readers over it:

        def file_buffered_tee(iterable, n=2):
            _, filename = tempfile.mkstemp()
            try:
                _pickle_iterable(filename, iterable)
                return tuple(_unpickle_iterable(_open_pickle(filename)) for _ in range(n))
            finally:
                os.remove(filename)

    TWO THINGS THERE ARE POSIX-ONLY, and both are fatal on Windows.

    First, `mkstemp` returns an OPEN file descriptor, and discarding it into `_` leaks it -- the
    file stays open for the life of the process.

    Second, and more fundamental, `os.remove` runs in the `finally` while the readers it just
    returned are still holding the file open. Unlinking an open file is ordinary on POSIX: the name
    disappears, the data lives until the last handle closes, and the readers keep working. Windows
    has no such semantics and refuses outright:

        PermissionError: [WinError 32] The process cannot access the file because it is being
        used by another process

    So this replacement closes the descriptor and defers deletion until the last reader is finished,
    which is the moment upstream was relying on the kernel to detect for it. Same lifetime, made
    explicit rather than inherited from the filesystem.

    Only the desktop needs this -- Android is Linux and upstream's version is correct there -- but
    it is applied unconditionally, because a patch that behaves the same everywhere is one fewer
    difference between the two platforms when something else goes wrong.
    """
    import atexit
    import tempfile

    from cakechat.utils import tee_file

    #: Files still awaiting their last reader. Emptied by the readers themselves; anything left at
    #: exit had a reader abandoned mid-iteration and is cleaned up then.
    pending = set()

    def _cleanup(filename):
        pending.discard(filename)
        try:
            os.remove(filename)
        except OSError:
            pass

    @atexit.register
    def _cleanup_pending():
        for filename in list(pending):
            _cleanup(filename)

    def file_buffered_tee(iterable, n=2):
        handle, filename = tempfile.mkstemp()
        os.close(handle)        # mkstemp hands back an open descriptor; upstream drops it
        pending.add(filename)

        try:
            tee_file._pickle_iterable(filename, iterable)
        except BaseException:
            _cleanup(filename)
            raise

        remaining = [n]

        def reader():
            try:
                for entry in tee_file._unpickle_iterable(tee_file._open_pickle(filename)):
                    yield entry
            finally:
                # Runs on exhaustion, on close(), and on garbage collection, so the count is
                # decremented however the consumer stops reading.
                remaining[0] -= 1
                if remaining[0] <= 0:
                    _cleanup(filename)

        return tuple(reader() for _ in range(n))

    tee_file.file_buffered_tee = file_buffered_tee

    # dialog.py does `from cakechat.utils.tee_file import file_buffered_tee`, binding the original
    # into its own namespace -- so patching the defining module alone would leave the caller using
    # the version this exists to replace.
    for module_name, module in list(sys.modules.items()):
        if module_name.startswith("cakechat") and \
                getattr(module, "file_buffered_tee", None) is not None:
            setattr(module, "file_buffered_tee", file_buffered_tee)

    _log("Patched file_buffered_tee for filesystems that cannot unlink an open file")


def prepare(repo_root, for_training=False):
    """Puts CakeChat on the import path with every bridge installed.

    Returns True on success; on failure the traceback is in [last_error]. Called by both
    [preprocess] and [train] so neither can run against an unbridged import.

    `for_training` selects the shims a backward pass needs and inference does not -- see
    [_install_tf1_compat]. It defaults to False so that a caller which forgets it gets the cheaper,
    safer configuration rather than the expensive one.
    """
    global _error
    _error = None
    try:
        # SET BEFORE TENSORFLOW IS IMPORTED, because protobuf reads it once at import and caches the
        # choice. TF 2.1's *_pb2.py files were generated by protoc 3.x; a protobuf 4/5 runtime reads
        # them through its C++ fast path and misinterprets map fields as repeated ones, which is the
        # "list indices must be integers or slices, not str" failure inside NodeDef. The pure-Python
        # implementation still honours the old contract. This is independent of the version pin in
        # build.gradle.kts and costs nothing if that pin holds -- protobuf 3.x ignores it.
        os.environ.setdefault("PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION", "python")

        # LEAVE THE PHONE USABLE. TensorFlow sizes its thread pools to every core it can see, so an
        # unconstrained run saturates the CPU and the launcher itself stops drawing frames -- which
        # is what "the app slows down heavily during training" is. Held to half the cores, minimum
        # one, so training is slower but the device stays responsive. Set as environment variables
        # because TF reads them when its runtime initialises, before any config object exists.
        _cores = os.cpu_count() or 4
        _budget = str(max(1, _cores // 2))
        os.environ.setdefault("TF_NUM_INTRAOP_THREADS", _budget)
        os.environ.setdefault("TF_NUM_INTEROP_THREADS", "1")
        os.environ.setdefault("OMP_NUM_THREADS", _budget)
        _log("Thread budget: %s of %d cores" % (_budget, _cores))

        _restore_numpy_aliases()
        _install_tf1_compat(for_training)
        _alias_keras()
        _patch_optimizers()
        _patch_lambda_masks()
        _patch_timedistributed_masks()
        _stub_gensim()
        _stub_missing_dependencies()

        if repo_root not in sys.path:
            sys.path.insert(0, repo_root)

        # Importing config early turns a broken install into an error now rather than halfway
        # through a run.
        import cakechat.config  # noqa: F401
        _neutralise_s3(repo_root)
        _disable_tensorboard_plotter()
        _patch_model_saving()
        _patch_tee_file()
        _patch_repeat_vector()
        _log("CakeChat imported against tensorflow.keras")
        return True
    except Exception:
        _fail(traceback.format_exc())
        return False


# ── Dataset ────────────────────────────────────────────────────────────────

#: The conditions CakeChat's own sample corpus uses. Not enforced as a closed set --
#: prepare_index_files builds index_to_condition from whatever appears -- but anything outside it
#: cannot later be asked for through get_response, so it is worth warning about.
KNOWN_CONDITIONS = ("neutral", "joy", "sadness", "anger", "fear")


def validate_dataset(path):
    """Checks the corpus matches CakeChat's processed format before a long run starts.

    THE FORMAT IS OBJECTS, NOT STRINGS. Verified against upstream
    data/corpora_processed/train_processed_dialogs.txt:

        [{"text": "Hello", "condition": "neutral"}, {"text": "Oh, hi!", "condition": "joy"}]

    An earlier version of this check only asked "is it a list of at least two things", which
    accepted a JSONL of bare strings -- the shape the README's prose suggests -- and let it through
    to fail deep inside prepare_index_files with an error naming none of it. The per-utterance shape
    is checked here so the message can name the line and the missing key.

    Returns (ok, message, dialog_count).
    """
    global _error
    _error = None
    if not os.path.isfile(path):
        return False, "No dataset file at %s" % path, 0

    dialogs = 0
    unknown = set()
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for index, raw in enumerate(handle, start=1):
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    entry = json.loads(raw)
                except ValueError:
                    return False, "Line %d is not valid JSON." % index, dialogs
                if not isinstance(entry, list):
                    return (False,
                            "Line %d is a %s, not a list of utterances."
                            % (index, type(entry).__name__), dialogs)
                if len(entry) < 2:
                    return (False,
                            "Line %d holds %d utterance(s); a dialog needs at least 2."
                            % (index, len(entry)), dialogs)

                for position, utterance in enumerate(entry, start=1):
                    if not isinstance(utterance, dict):
                        return (False,
                                "Line %d utterance %d is a %s. Each utterance must be an object "
                                'like {"text": "...", "condition": "neutral"}.'
                                % (index, position, type(utterance).__name__), dialogs)
                    if "text" not in utterance:
                        return (False,
                                "Line %d utterance %d has no \"text\" key."
                                % (index, position), dialogs)
                    if "condition" not in utterance:
                        return (False,
                                "Line %d utterance %d has no \"condition\" key. Use one of: %s."
                                % (index, position, ", ".join(KNOWN_CONDITIONS)), dialogs)
                    if not str(utterance["text"]).strip():
                        return (False,
                                "Line %d utterance %d has empty text."
                                % (index, position), dialogs)
                    if utterance["condition"] not in KNOWN_CONDITIONS:
                        unknown.add(str(utterance["condition"]))

                dialogs += 1

        if dialogs == 0:
            return False, "The dataset is empty.", 0

        message = "%d dialogs look well-formed." % dialogs
        if unknown:
            # A warning rather than a rejection: the index is built from the corpus, so unusual
            # conditions do train -- they just cannot be requested at inference time.
            message += (" Note: unrecognised conditions %s will train but cannot be selected later."
                        % ", ".join(sorted(unknown)[:5]))
        return True, message, dialogs
    except Exception:
        _fail(traceback.format_exc())
        return False, "Could not read the dataset.", dialogs


#: Files in the weights directory that are INPUTS, not model output. Everything else there is
#: something a previous run produced and must not survive into the next one.
_PRESERVED_IN_WEIGHTS_DIR = (
    "train_dialogs.txt",                # the corpus the user imported
    "bundled_roleplay_dialogs.txt",     # the corpus shipped in assets
    "imported_source",                  # the raw file the converter read
)


#: Errors that mean "not enough memory", across the layers this can come from. TensorFlow raises
#: its own type rather than MemoryError, and on Android a native allocation failure can surface as
#: a bare RuntimeError with the reason only in its text.
def _is_memory_error(exc):
    name = type(exc).__name__
    if name in ("MemoryError", "ResourceExhaustedError", "InternalError"):
        return True
    text = str(exc).lower()
    return any(
        marker in text
        for marker in ("out of memory", "oom when allocating", "resourceexhausted",
                       "failed to allocate", "cannot allocate", "bad_alloc")
    )


def _apply_batch_size(batch_size):
    """Pushes a batch size into config and every module that already read it."""
    import cakechat.config as cakechat_config

    setattr(cakechat_config, "BATCH_SIZE", int(batch_size))
    for module_name, module in list(sys.modules.items()):
        if module_name.startswith("cakechat") and hasattr(module, "BATCH_SIZE"):
            setattr(module, "BATCH_SIZE", int(batch_size))


def suggest_batch_size(available_bytes, requested):
    """A batch size the device can plausibly hold.

    WHY BATCH SIZE IS THE LEVER. TensorFlow allocates activations proportional to batch size, and
    on this stack it is the only knob that moves memory by an order of magnitude without changing
    the model. Upstream's 196 is sized for a GPU with gigabytes to spare.

    Roughly 8 MB of activations per dialog in a batch, measured nowhere and estimated generously --
    the number only has to be the right order of magnitude, because [train] halves it on a real
    out-of-memory anyway. Being wrong high costs one wasted attempt; being wrong low costs nothing
    but speed.
    """
    if available_bytes <= 0:
        return requested
    affordable = int(available_bytes // (8 * 1024 * 1024))
    return max(4, min(int(requested), max(4, affordable)))


def reset_training_artifacts(repo_root, weights_dir):
    """Deletes everything a previous training run produced, keeping CakeChat's code and the corpus.

    WHY THIS RUNS BEFORE EVERY RUN, SUCCESSFUL OR NOT. A failed run does not fail cleanly. CakeChat
    saves at the batch-0 evaluation hook, long before an epoch completes, so a crash five minutes in
    still leaves a weights file on disk -- a partially-written HDF5 from a model that never finished
    training. The next run then loads it as if it were a checkpoint worth resuming from, and the
    failures that follow have nothing to do with the code that caused the first one.

    Index files are equally dangerous to keep. They are built from whatever corpus was loaded at the
    time, and the weights are SIZED by them, so a vocabulary left over from a different corpus loads
    old weights into the wrong shape -- which does not raise, it just answers nonsense.

    What is deliberately kept:
      * the cakechat repository itself, which is a download, not output
      * the corpus files, which the user chose and would have to re-pick every time

    What goes:
      * results/            trained and partially-trained weights
      * data/tokens_index/, data/conditions_index/     vocabularies
      * data/corpora_processed/                        the copy of the corpus CakeChat reads
      * any weights or index files staged in weights_dir

    Returns a list of what was removed, for the log.
    """
    import shutil

    removed = []

    def drop(path, label):
        if not os.path.exists(path):
            return
        try:
            if os.path.isdir(path):
                shutil.rmtree(path)
            else:
                os.remove(path)
            removed.append(label)
        except Exception as exc:
            _log("Could not remove %s: %s" % (label, exc))

    # Paths from CakeChat's own config where possible, so a layout change does not leave stale
    # directories behind under names this file guessed.
    try:
        from cakechat.config import (
            RESULTS_PATH, TOKEN_INDEX_DIR, CONDITION_IDS_INDEX_DIR, PROCESSED_CORPUS_DIR,
        )
        drop(RESULTS_PATH, "results/")
        drop(TOKEN_INDEX_DIR, "tokens_index/")
        drop(CONDITION_IDS_INDEX_DIR, "conditions_index/")
        drop(PROCESSED_CORPUS_DIR, "corpora_processed/")
    except Exception:
        # config is unimportable before prepare() has run; fall back to the layout the repository
        # ships with, which is where those constants point anyway.
        for relative, label in (
            ("results", "results/"),
            (os.path.join("data", "tokens_index"), "tokens_index/"),
            (os.path.join("data", "conditions_index"), "conditions_index/"),
            (os.path.join("data", "corpora_processed"), "corpora_processed/"),
        ):
            drop(os.path.join(repo_root, relative), label)

    if os.path.isdir(weights_dir):
        for name in os.listdir(weights_dir):
            if name in _PRESERVED_IN_WEIGHTS_DIR:
                continue
            drop(os.path.join(weights_dir, name), name)

    if removed:
        _log("Cleared previous training data: %s" % ", ".join(removed))
    else:
        _log("No previous training data to clear")
    return removed


def preprocess(repo_root, dataset_path, for_training=True):
    """Builds the corpus and index files CakeChat's trainer requires.

    THIS STEP IS NOT OPTIONAL AND IS EASY TO MISS. `tools/train.py` calls `_look_for_saved_files`
    over the processed corpus and index_to_token, and raises if either is absent. Handing it raw
    dialogs fails immediately.

    Upstream does this in `tools/prepare_index_files.py`, which has NO main() -- the work sits in an
    `if __name__ == '__main__'` block. Its two module-level functions are called directly here
    instead, which is also what lets the corpus path be chosen rather than taken from config.

    Returns True on success; the traceback is in [last_error] otherwise.
    """
    global _error
    _error = None
    try:
        if not prepare(repo_root, for_training):
            return False

        _state["phase"] = "preprocessing"
        _log("Preparing corpus and index files")

        from cakechat.config import (
            TRAIN_CORPUS_NAME, BASE_CORPUS_NAME, DEFAULT_CONDITION,
            CONTEXT_SENSITIVE_VAL_CORPUS_NAME, CONTEXT_SENSITIVE_TEST_CORPUS_NAME,
        )
        from cakechat.utils.text_processing import (
            get_processed_corpus_path, get_index_to_token_path, get_index_to_condition_path,
        )

        # THREE CORPORA, NOT ONE. tools/train.py checks for the train corpus AND the
        # context-sensitive validation corpus before it will start:
        #
        #     _look_for_saved_files(files_paths=[processed_train_corpus_path,
        #                                        processed_val_corpus_path,
        #                                        index_to_token_path])
        #
        # The repository ships sample train/val/test files, which is why this worked for as long as
        # they were left in place. Clearing previous training data removes them -- correctly, since
        # a validation corpus built against a different vocabulary is exactly the kind of stale
        # artefact that produces a model which loads and then answers nonsense -- so all three are
        # now derived from the user's own corpus instead.
        train_path = get_processed_corpus_path(TRAIN_CORPUS_NAME)
        val_path = get_processed_corpus_path(CONTEXT_SENSITIVE_VAL_CORPUS_NAME)
        test_path = get_processed_corpus_path(CONTEXT_SENSITIVE_TEST_CORPUS_NAME)
        os.makedirs(os.path.dirname(train_path), exist_ok=True)

        _split_corpus(dataset_path, train_path, val_path, test_path)
        processed_path = train_path

        # build_index_mappings RAISES if the default condition is missing, and its message names
        # neither the file nor the fix. Checked first so the failure is actionable.
        if not _corpus_has_condition(processed_path, DEFAULT_CONDITION):
            _fail(
                'The corpus contains no utterance with condition "%s". CakeChat requires its '
                'default condition to be present, so at least some lines must be labelled that.'
                % DEFAULT_CONDITION
            )
            _state["phase"] = "idle"
            return False

        tools_dir = os.path.join(repo_root, "tools")
        if tools_dir not in sys.path:
            sys.path.insert(0, tools_dir)

        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "cakechat_prepare_index", os.path.join(tools_dir, "prepare_index_files.py")
        )
        prepare_index = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(prepare_index)

        index_to_token, index_to_condition = prepare_index.build_index_mappings(processed_path)
        prepare_index.dump_index_to_item(index_to_token, get_index_to_token_path(BASE_CORPUS_NAME))
        prepare_index.dump_index_to_item(
            index_to_condition, get_index_to_condition_path(BASE_CORPUS_NAME)
        )

        _log("Vocabulary: %d tokens, %d conditions"
             % (len(index_to_token), len(index_to_condition)))
        _state["phase"] = "idle"
        return True
    except Exception:
        _fail(traceback.format_exc())
        _state["phase"] = "idle"
        return False


def _split_corpus(source, train_path, val_path, test_path):
    """Derives train, validation and test corpora from one file.

    HELD OUT FROM THE END rather than sampled at random. The split has to be reproducible: a run
    that resumes, or a second run on the same corpus, must see the same partition or the validation
    loss it reports is not comparable to the previous one. Taking a fixed tail is the simplest thing
    that guarantees that, and dialog order in these corpora carries no meaning worth preserving.

    The held-out sets are kept SMALL -- 5% capped at 200 dialogs each. CakeChat evaluates every
    EVAL_STATE_PER_BATCHES batches, and on a phone a large validation set turns each of those into a
    visible stall. Validation here is a signal that training is working, not a benchmark.

    A corpus too small to split still produces all three files, reusing lines rather than writing an
    empty one: `_look_for_saved_files` rejects an empty file as loudly as a missing one.
    """
    with io.open(source, "r", encoding="utf-8") as handle:
        lines = [line for line in (raw.strip() for raw in handle) if line]

    total = len(lines)
    if total == 0:
        raise ValueError("The corpus has no usable dialogs.")

    holdout = max(1, min(200, total // 20))
    if total < 4:
        # Too small to partition meaningfully; every split gets the whole thing so training can
        # still run on a toy corpus.
        train_lines = val_lines = test_lines = lines
    else:
        test_lines = lines[-holdout:]
        val_lines = lines[-2 * holdout:-holdout] or lines[-holdout:]
        train_lines = lines[:-2 * holdout] or lines[:1]

    for path, chunk, label in (
        (train_path, train_lines, "train"),
        (val_path, val_lines, "validation"),
        (test_path, test_lines, "test"),
    ):
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(chunk))
            handle.write("\n")
        _log("%s corpus: %d dialogs -> %s" % (label.capitalize(), len(chunk), os.path.basename(path)))


def _corpus_has_condition(path, condition):
    """Whether any utterance carries [condition]. Cheap scan; stops at the first hit."""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            for raw in handle:
                raw = raw.strip()
                if not raw:
                    continue
                try:
                    for utterance in json.loads(raw):
                        if isinstance(utterance, dict) and utterance.get("condition") == condition:
                            return True
                except ValueError:
                    continue
    except Exception:
        return False
    return False


# ── Training ───────────────────────────────────────────────────────────────

def _rebind_defaults(function, **values):
    """Rewrites a function's DEFAULT ARGUMENTS in place.

    THE ONLY WAY TO CHANGE THESE SETTINGS, and the reason overriding cakechat.config was not enough.
    CakeChat writes its configuration into signatures:

        def __init__(self, ..., batch_size=BATCH_SIZE, epochs_num=EPOCHS_NUM, ...)

    A default expression is evaluated ONCE, when the `def` executes at import. By the time a run
    starts, `batch_size` and `epochs_num` are already fixed to whatever config held when model.py
    was first imported, and reassigning cakechat.config.EPOCHS_NUM afterwards changes nothing --
    the default is a value, not a reference. tools/train.py then constructs CakeChatModel without
    passing either one, so those stale defaults are what every run actually used: 2 epochs and a
    batch of 196, no matter what the UI collected.

    Rewriting `__defaults__` fixes it for both call styles, positional and keyword, which a wrapper
    injecting kwargs would not -- a caller passing the argument positionally would then get
    "multiple values for argument".

    Returns the names it actually changed.
    """
    code = function.__code__
    names = code.co_varnames[:code.co_argcount]
    defaults = list(function.__defaults__ or ())
    if not defaults:
        return []

    # Defaults line up with the LAST len(defaults) parameters, never the first.
    offset = code.co_argcount - len(defaults)
    changed = []
    for index, name in enumerate(names[offset:]):
        if name in values:
            defaults[index] = values[name]
            changed.append(name)

    function.__defaults__ = tuple(defaults)
    return changed


#: The evaluator callback of the run in flight, captured on its way to Keras so its checkpoint
#: cadence can be set once the real step count is known.
_ACTIVE_EVALUATOR = None


#: The progress callback for the run currently in flight, read by the patched
#: `_to_keras_callbacks`. Module-level because that patch is installed once per process while runs
#: come and go.
_ACTIVE_REPORT = None


def train(repo_root, dataset_path, out_dir, epochs=1, train_subset_size=0, batch_size=32,
          subset_size=0, hidden_layer_dim=0):
    """Runs CakeChat's own trainer, reporting progress into the state the UI polls.

    Deliberately small defaults. A phone training a recurrent seq2seq is slow, and the honest
    starting point is a run a user can watch finish rather than one that looks hung.

    `train_subset_size` is passed straight through to CakeChat's own argument of that name, which is
    how upstream limits a run; 0 means the whole corpus. `subset_size` is the same thing under the
    name the UI uses, and wins when both are given -- the older argument is kept so existing callers
    do not break.

    `hidden_layer_dim` is the width of the recurrent layers, 0 meaning CakeChat's own 768. IT IS THE
    SETTING THAT DECIDES WHETHER THE RESULT RUNS ON A PHONE. A GRU's parameter count grows with the
    SQUARE of this number, so it dominates both the size of the weights file and the memory needed
    to load it -- measured at 850 MB peak for a 768-wide model with a 516-token vocabulary, which is
    more than Android will give one process. Halving it is roughly a quarter of the parameters.

    Changing it makes a model that is NOT interchangeable with one trained at another width: the
    weights and the architecture must agree, so an existing model has to be retrained, not adjusted.
    """
    global _error
    _error = None

    # MARKED RUNNING BEFORE THE FIRST SLOW STEP, not after. Validation, clearing the previous run
    # and preprocessing all happen before Keras exists to report a step count, and they are the
    # slowest part of a small run. Leaving the state at "idle" until then means the UI says "not
    # training" through the whole preparation and only comes alive at the very end of it.
    _state.update({
        "step": 0, "total": 0, "epoch": 0, "epochs": epochs, "loss": 0.0,
        "started_at": time.time(), "running": True, "phase": "preprocessing",
    })

    try:
        ok, message, dialogs = validate_dataset(dataset_path)
        if not ok:
            _fail(message)
            return False

        # BEFORE prepare() imports anything. A previous run's weights are loaded during model
        # construction, so clearing them afterwards would be too late -- the corrupt checkpoint
        # would already be in memory.
        reset_training_artifacts(repo_root, out_dir)

        if not preprocess(repo_root, dataset_path):
            return False

        os.makedirs(out_dir, exist_ok=True)
        _state.update({
            "step": 0, "total": 0, "epoch": 0, "epochs": epochs,
            "loss": 0.0, "started_at": time.time(), "running": True, "phase": "training",
        })
        _log("Training on %d dialogs for %d epoch(s)" % (dialogs, epochs))

        import keras  # the alias installed by prepare()

        class _Report(keras.callbacks.Callback):
            """Feeds Keras's own callbacks into the polled state, and honours stop()."""

            def on_train_begin(self, logs=None):
                params = getattr(self, "params", None) or {}
                per_epoch = int(params.get("steps") or params.get("samples") or 0)
                # Keras's own epoch count, not the one that was requested. If anything upstream
                # still overrides it, the bar should describe the run that is happening rather
                # than the run that was asked for.
                planned = int(params.get("epochs") or 0) or max(1, epochs)
                _state["total"] = per_epoch * planned
                _state["epochs"] = planned

                # CHECKPOINT CADENCE, SET FROM THE REAL STEP COUNT.
                #
                # Upstream evaluates and saves every EVAL_STATE_PER_BATCHES batches, which is 500 --
                # a sensible interval for the multi-thousand-batch GPU runs it was written for, and
                # useless here. A short run never reaches batch 500, so the only checkpoint ever
                # written is the one from batch 0, and since CakeChat RELOADS THE BEST SAVED MODEL
                # when fit returns, the finished model is the untrained one. Every step of training
                # is discarded at the very end.
                #
                # Four checkpoints per run: often enough that stopping early keeps most of the
                # work, rare enough that the hook's cost -- it generates sample answers and full
                # metrics each time -- stays a small share of the run.
                if _ACTIVE_EVALUATOR is not None and per_epoch > 0:
                    cadence = max(1, (per_epoch * planned) // 4)
                    _ACTIVE_EVALUATOR._eval_state_per_batches = cadence
                    _log("Checkpointing every %d batches (%d total)"
                         % (cadence, per_epoch * planned))

            def on_epoch_begin(self, epoch, logs=None):
                _state["epoch"] = epoch + 1
                _log("Epoch %d/%d" % (epoch + 1, epochs))

            def on_batch_end(self, batch, logs=None):
                _state["step"] += 1
                if logs and "loss" in logs:
                    _state["loss"] = logs["loss"]
                if not _state["running"]:
                    self.model.stop_training = True

            def on_epoch_end(self, epoch, logs=None):
                loss = (logs or {}).get("loss")
                _log("Epoch %d done%s"
                     % (epoch + 1, "" if loss is None else " (loss %.4f)" % loss))

        # THE EPOCH COUNT HAS TO BE PUSHED INTO CONFIG, because CakeChat's train() does not take
        # one. It reads EPOCHS_NUM from cakechat.config (2 in production, 4 in dev), so the value
        # collected in the UI was being ignored entirely and every run trained for 2 epochs while
        # the log claimed otherwise.
        #
        # Patched in cakechat.config AND in every module that already imported it by value.
        # BATCH_SIZE gets the same treatment: upstream's 196 is sized for a GPU, and on a phone it
        # is both a memory spike and a long time between progress updates.
        global _ACTIVE_REPORT, _ACTIVE_EVALUATOR
        report = _Report()
        _ACTIVE_REPORT = report
        _ACTIVE_EVALUATOR = None    # belongs to the run that is starting, not the previous one

        import cakechat.config as cakechat_config

        _override = {"EPOCHS_NUM": max(1, int(epochs)), "BATCH_SIZE": int(batch_size)}
        for name, value in _override.items():
            setattr(cakechat_config, name, value)
        for module_name, module in list(sys.modules.items()):
            if not module_name.startswith("cakechat"):
                continue
            for name, value in _override.items():
                if hasattr(module, name):
                    setattr(module, name, value)
        _log("Epochs: %d, batch size: %d" % (_override["EPOCHS_NUM"], _override["BATCH_SIZE"]))
        if hidden_layer_dim:
            _log("Recurrent layer width: %d (CakeChat's default is 768)" % int(hidden_layer_dim))

        _subset = int(subset_size or train_subset_size or 0)
        if _subset > 0:
            _log("Limiting training to %d dialogs" % _subset)

        # Loaded by path from tools/train.py, which is where CakeChat's entry point actually is.
        cakechat_train = _load_upstream_train(repo_root)

        # ATTACHED HERE, OR NOT AT ALL. _Report is a Keras callback, and Keras only calls it if it
        # is in the list handed to fit_generator. Upstream builds that list itself, deep inside
        # CakeChatModel, and its entry point takes no callbacks argument -- so defining the class
        # was never enough. Without this the polled state stays at step 0 of 0 for the entire run
        # and the UI shows "Starting..." from the first batch to the last, which is indistinguishable
        # from a run that has hung.
        #
        # `_to_keras_callbacks` is the one place every callback passes through on its way to Keras,
        # which makes it the single point that cannot be bypassed by a different code path.
        # REBOUND, NOT JUST REASSIGNED IN CONFIG. See [_rebind_defaults]: these two live as default
        # arguments captured at import, and tools/train.py constructs the model without passing
        # either, so this is the only point at which the UI's values can reach the run.
        from cakechat.dialog_model.model import CakeChatModel

        _sizing = {
            "epochs_num": max(1, int(epochs)),
            "batch_size": int(batch_size),
        }
        # Only when asked. Left alone, CakeChat's own default applies, and a model trained before
        # this setting existed keeps loading at the width it was built with.
        if hidden_layer_dim:
            _sizing["hidden_layer_dim"] = int(hidden_layer_dim)

        _rebound = _rebind_defaults(CakeChatModel.__init__, **_sizing)
        if _rebound:
            _log("Applied %s to the model constructor" % ", ".join(sorted(_rebound)))

        from cakechat.dialog_model.keras_model import AbstractKerasModel

        if not getattr(AbstractKerasModel, "_prism_reports_progress", False):
            _original_to_keras = AbstractKerasModel._to_keras_callbacks

            def _to_keras_callbacks(callbacks, _original=_original_to_keras):
                global _ACTIVE_EVALUATOR

                # The evaluator is only reachable here. It is built inside CakeChatModel and kept
                # privately, but every callback passes through this function on its way to Keras --
                # so this is where a handle on it can be taken.
                for candidate in callbacks:
                    inner = getattr(candidate, "callback", candidate)
                    if type(inner).__name__ == "CakeChatEvaluatorCallback":
                        _ACTIVE_EVALUATOR = inner
                        break

                converted = list(_original(callbacks))
                # Read from the module rather than captured: the patch is installed once, but the
                # Android trainer lives in a process that survives between runs, so a captured
                # callback would keep reporting into the state of whichever run installed it.
                if _ACTIVE_REPORT is not None:
                    converted.append(_ACTIVE_REPORT)
                return converted

            AbstractKerasModel._to_keras_callbacks = staticmethod(_to_keras_callbacks)
            AbstractKerasModel._prism_reports_progress = True
            _log("Progress reporting attached to training")

        # RETRIED AT A SMALLER BATCH RATHER THAN FAILED. Running out of memory is not a bug in the
        # corpus or the model, it is a batch size this device cannot hold -- and the fix is
        # mechanical. Halving and starting again costs the time already spent, which on a phone is
        # far better than an aborted run the user has to notice, diagnose and restart by hand.
        #
        # Four attempts, floor of 4: below that the run is so slow it is not worth continuing, and
        # a device that cannot hold a batch of 4 is not going to finish regardless.
        attempt_batch = int(batch_size)
        last_memory_error = None

        for attempt in range(4):
            _apply_batch_size(attempt_batch)
            _state["running"] = True
            _log("Training attempt %d at batch size %d" % (attempt + 1, attempt_batch))
            try:
                cakechat_train(
                    train_subset_size=_subset or None,
                    use_pretrained_w2v=False,   # the pretrained vectors lived in the same dead bucket
                )
                last_memory_error = None
                break
            except Exception as exc:
                if not _is_memory_error(exc) or attempt_batch <= 4:
                    raise
                last_memory_error = exc
                attempt_batch = max(4, attempt_batch // 2)
                _log("Ran out of memory; retrying at batch size %d" % attempt_batch)
                # Anything the failed attempt left behind would otherwise be loaded by the next one.
                reset_training_artifacts(repo_root, out_dir)
                if not preprocess(repo_root, dataset_path):
                    raise
                _collect_garbage()

        if last_memory_error is not None:
            raise last_memory_error

        saved = harvest_weights(repo_root, out_dir)
        _record_sizing(out_dir, {"hidden_layer_dim": int(hidden_layer_dim) if hidden_layer_dim else 0})
        _state["running"] = False
        _state["phase"] = "idle"
        if saved == 0:
            _fail(
                "Training completed but produced no weights file, so there is nothing to answer "
                "with. Check the log above for what the trainer reported."
            )
            return False
        _log("Training finished; %d weight file(s) saved" % saved)
        return True
    except Exception:
        _fail(traceback.format_exc())
        _state["running"] = False
        _state["phase"] = "idle"
        return False


# ── Inference ──────────────────────────────────────────────────────────────

_inference_ready = False


def infer(repo_root, weights_dir, context_lines, emotion="neutral"):
    """Generates one reply from the trained model.

    Uses CakeChat's own `cakechat.api.response.get_response` rather than reimplementing
    tokenisation, condition handling and the response filters -- those are the parts most likely to
    be subtly wrong if rewritten, and getting them wrong produces plausible-looking nonsense rather
    than an error.

    `context_lines` is the dialog so far, oldest first, which is the shape CakeChat's own API takes.
    `emotion` is its conditioning input -- the feature that gave Replika its affect -- and defaults
    to neutral.

    Returns the reply, or None with the traceback in [last_error].
    """
    global _error, _inference_ready
    _error = None
    try:
        # Before anything heavy, so a crash on THIS attempt is reported on the NEXT one.
        _report_previous_crash(weights_dir)

        _breadcrumb(weights_dir, "starting Python and importing TensorFlow")
        if not prepare(repo_root):
            _clear_breadcrumb(weights_dir)
            return None

        # CakeChat resolves weights through its own config paths, so the trained file has to be
        # where it expects rather than wherever Prism kept it.
        _link_weights(repo_root, weights_dir)

        # Both installed BEFORE the import below, because that module builds its model, resolves
        # its weights AND warms up its predictor as it is imported -- patching afterwards would be
        # patching something that has already failed.
        _patch_model_resolution(weights_dir)
        _force_non_reranking_mode()
        _apply_recorded_sizing(weights_dir)

        # THE STAGE THAT ACTUALLY FAILS ON A PHONE. Importing this module builds the network,
        # loads the weights and warms up the predictor -- hundreds of megabytes of allocation in
        # native code, which is where a device short of memory gives out.
        _breadcrumb(weights_dir, "building the model and loading weights")

        # Imported AFTER prepare(): the module builds its model at import time, so importing it
        # before the S3 and keras bridges are installed would fail on the way in.
        from cakechat.api.response import get_response

        if not _inference_ready:
            _inference_ready = True
            _log("Inference model loaded")

        lines = [line for line in (context_lines or []) if str(line).strip()]
        if not lines:
            _clear_breadcrumb(weights_dir)
            return None

        _breadcrumb(weights_dir, "generating a reply")
        reply = get_response(lines, emotion)

        # Cleared only on the way out. Anything left behind means the process did not get here.
        _clear_breadcrumb(weights_dir)
        return reply
    except Exception:
        # A Python exception is fully reported by the traceback, so the breadcrumb has done its job
        # and would otherwise be misread next time as an unexplained crash.
        _clear_breadcrumb(weights_dir)
        _fail(traceback.format_exc())
        return None


def _weights_root(repo_root):
    """Where CakeChat writes trained weights.

    `RESULTS_PATH` from config, NOT `NN_MODELS_DIR` -- that constant does not exist, and importing
    it is what an earlier version of this file did. The exact subdirectory under results/ is chosen
    by the model class at save time, so the tree is walked rather than assumed.
    """
    try:
        from cakechat.config import RESULTS_PATH
        return RESULTS_PATH
    except Exception:
        return os.path.join(repo_root, "results")


#: HDF5's file signature. Weights are written to whatever path CakeChat chose, which has no
#: extension, so matching on ".h5" alone would miss the file this build actually produces.
_HDF5_MAGIC = b"\x89HDF\r\n\x1a\n"


def _is_hdf5(path):
    try:
        with open(path, "rb") as handle:
            return handle.read(8) == _HDF5_MAGIC
    except Exception:
        return False


def _find_weight_files(root):
    found = []
    for base, _dirs, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            if name.endswith(".h5") or _is_hdf5(path):
                found.append(path)
    return found


def harvest_weights(repo_root, weights_dir):
    """Copies what training produced into Prism's own directory.

    Kept outside the repository so re-downloading the source does not delete a trained model, and so
    Kotlin can detect "trained" by looking in one place it owns.
    """
    import shutil
    produced = _find_weight_files(_weights_root(repo_root))
    if not produced:
        _log("Training finished but wrote no .h5 file under results/")
        return 0
    os.makedirs(weights_dir, exist_ok=True)
    for source in produced:
        target = os.path.join(weights_dir, os.path.basename(source))
        shutil.copyfile(source, target)
        _log("Saved weights: %s (%.1f MB)"
             % (os.path.basename(source), os.path.getsize(source) / 1048576.0))

    # THE INDEX FILES COUNT AS PART OF THE MODEL. Weights are SIZED by the vocabulary that produced
    # them -- the embedding and the output layer both have a dimension equal to the token count --
    # so weights without their index files load into the wrong shape, and weights paired with a
    # different corpus's indices answer nonsense without raising. [_link_weights] already restores
    # them from here, and the export bundle already looks for them here; nothing was ever putting
    # them here, which left every exported model missing half of itself.
    for index_dir in _index_dirs(repo_root):
        if not os.path.isdir(index_dir):
            continue
        for name in os.listdir(index_dir):
            if not (name.startswith("t_idx_") or name.startswith("c_idx_")):
                continue
            shutil.copyfile(os.path.join(index_dir, name), os.path.join(weights_dir, name))
            _log("Saved index: %s" % name)

    return len(produced)


def _index_dirs(repo_root):
    """Where the token and condition vocabularies are written.

    Read from CakeChat's config when it is importable, so a layout change does not leave this
    silently looking in directories that no longer exist; the shipped layout is the fallback.
    """
    try:
        from cakechat.config import TOKEN_INDEX_DIR, CONDITION_IDS_INDEX_DIR
        return [TOKEN_INDEX_DIR, CONDITION_IDS_INDEX_DIR]
    except Exception:
        return [
            os.path.join(repo_root, "data", "tokens_index"),
            os.path.join(repo_root, "data", "conditions_index"),
        ]


#: What a converted model is made of: two graphs plus everything the Kotlin sampler needs to drive
#: them. Named here so the exporter, the bundle and the Kotlin reader cannot disagree.
TFLITE_DECODER = "cakechat_decoder.tflite"
TFLITE_ENCODER = "cakechat_encoder.tflite"
TFLITE_META = "cakechat_tflite.json"


def export_tflite(repo_root, weights_dir):
    """Converts the trained model to TFLite, so it can run without TensorFlow.

    ## Why

    TensorFlow on a phone costs about 500 MB of peak memory to answer once -- measured -- of which
    the model itself is 11 MB. The rest is the graph-mode runtime, graph construction and unplanned
    activations. TFLite keeps none of that: the graph is prebuilt, weights are mmapped from the file
    rather than copied into resident variables, and activations are planned into one reused arena.

    ## Two graphs, not one

    CakeChat already generates one token per call -- the loop lives in Python, threading hidden
    state through as an ordinary argument -- so there is no loop inside the graph to convert, only
    two static functions:

      * ENCODER: context token ids -> thought vector
      * DECODER: (thought vector, previous token, condition, hidden state, temperature)
                 -> (token probabilities, updated hidden state)

    which is the shape TFLite converts well. The loop is reimplemented in Kotlin against the
    metadata written here.

    ## Static shapes and unrolled recurrences

    Both are needed to stay on TFLite's BUILTIN ops. Upstream declares token inputs as
    `Input(shape=(None,))`, and a dynamic length makes Keras emit a while_loop whose TensorList ops
    TFLite can only run through the Flex delegate -- tens of megabytes of extra native library per
    ABI. Pinning the lengths lets the GRUs unroll, after which nothing but builtins is used.
    Sequence length does not affect the shape of any weight, so the same trained file still loads.

    Returns True on success; on failure the traceback is in [last_error].
    """
    global _error
    _error = None
    try:
        if not prepare(repo_root):
            return False
        _link_weights(repo_root, weights_dir)
        _patch_model_resolution(weights_dir)
        _force_non_reranking_mode()
        _apply_recorded_sizing(weights_dir)

        import functools

        import cakechat.dialog_model.model as model_module

        # PATCHED BEFORE THE MODEL IS BUILT. Both decide the shape of the graph as it is
        # constructed, so applying them afterwards would convert the graph they exist to replace.
        original_input = model_module.Input
        original_gru = model_module.GRU

        def _static_input(*args, **kwargs):
            if kwargs.get("shape") == (None,):
                name = kwargs.get("name", "")
                # The decoder is driven one token per call, so its input is exactly one long.
                # Everything else is an utterance of the configured length.
                kwargs["shape"] = (
                    (1,) if name.startswith("y_") else (model_module.INPUT_SEQUENCE_LENGTH,)
                )
            return original_input(*args, **kwargs)

        model_module.Input = _static_input
        model_module.GRU = functools.partial(original_gru, unroll=True)

        try:
            from cakechat.dialog_model.factory import get_trained_model
            model = get_trained_model(reverse_model=None)
        finally:
            # Restored whatever happens: these are module-level names, and leaving them patched
            # would change any model built later in this process -- including a training run, which
            # needs the dynamic shapes this removes.
            model_module.Input = original_input
            model_module.GRU = original_gru

        import tensorflow as tf

        graph = model._keras_isolated_graph
        session = model._keras_isolated_session

        def convert(keras_model, filename):
            with graph.as_default():
                inputs = list(keras_model.inputs)
                outputs = list(keras_model.outputs)
                converter = tf.compat.v1.lite.TFLiteConverter.from_session(session, inputs, outputs)
                # Builtins only. If this ever fails, the answer is to find the op and fix the graph,
                # not to enable SELECT_TF_OPS and pull the Flex delegate in for one node.
                converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
                blob = converter.convert()
            with open(os.path.join(weights_dir, filename), "wb") as handle:
                handle.write(blob)
            _log("Converted %s (%.2f MB)" % (filename, len(blob) / 1048576.0))
            return [t.name for t in inputs], [t.name for t in outputs]

        decoder_in, decoder_out = convert(model._models["decoder"], TFLITE_DECODER)
        encoder_in, encoder_out = convert(model._models["context_encoder"], TFLITE_ENCODER)

        from cakechat.config import (
            DEFAULT_TEMPERATURE, INPUT_CONTEXT_SIZE, INPUT_SEQUENCE_LENGTH, OUTPUT_SEQUENCE_LENGTH,
            REPETITION_PENALIZE_COEFFICIENT,
        )
        from cakechat.dialog_model.inference.service_tokens import ServiceTokensIDs

        tokens = ServiceTokensIDs(model.token_to_index)
        meta = {
            "version": 1,
            "decoder": TFLITE_DECODER,
            "encoder": TFLITE_ENCODER,
            "decoder_inputs": decoder_in,
            "decoder_outputs": decoder_out,
            "encoder_inputs": encoder_in,
            "encoder_outputs": encoder_out,
            "hidden_layer_dim": int(model._params.hidden_layer_dim),
            "decoder_depth": int(model._decoder_depth),
            "input_seq_len": int(INPUT_SEQUENCE_LENGTH),
            "input_context_size": int(INPUT_CONTEXT_SIZE),
            "output_seq_len": int(OUTPUT_SEQUENCE_LENGTH),
            "default_temperature": float(DEFAULT_TEMPERATURE),
            "repetition_penalize_coefficient": float(REPETITION_PENALIZE_COEFFICIENT),
            "start_token_id": int(tokens.start_token_id),
            "eos_token_id": int(tokens.eos_token_id),
            "pad_token_id": int(tokens.pad_token_id),
            "unk_token_id": int(tokens.unk_token_id),
            # EXPORTED, NOT RECOMPUTED. Both lists come from CakeChat's own offensive-phrase file
            # and config; a Kotlin reimplementation that drifted from them would filter a different
            # set of tokens and never fail while doing it.
            "banned_token_ids": sorted(int(i) for i in tokens.banned_tokens_ids),
            "non_penalizable_token_ids": sorted(int(i) for i in tokens.non_penalizable_tokens_ids),
            "index_to_token": dict((str(k), v) for k, v in model.index_to_token.items()),
            "condition_to_index": dict((k, int(v)) for k, v in model.condition_to_index.items()),
        }
        with io.open(os.path.join(weights_dir, TFLITE_META), "w", encoding="utf-8") as handle:
            json.dump(meta, handle)

        _log("TFLite export complete: %d tokens, %d conditions"
             % (len(meta["index_to_token"]), len(meta["condition_to_index"])))
        return True
    except Exception:
        _fail(traceback.format_exc())
        return False


#: The name of the file recording how a model was built, written beside its weights.
_SIZING_NAME = "prism_model_sizing.json"


def _record_sizing(weights_dir, values):
    """Writes the architecture a model was trained with, next to the model.

    WITHOUT THIS, A NON-DEFAULT MODEL CANNOT BE LOADED BACK. Weights only fit the architecture that
    produced them, and CakeChat rebuilds that architecture from its config at load time -- so a
    model trained at a width of 256 and loaded by code defaulting to 768 does not merely perform
    badly, it fails to load at all. The training settings are not recoverable from the weights file,
    and an imported model carries no memory of the machine that made it, so they have to travel with
    it as data.
    """
    try:
        os.makedirs(weights_dir, exist_ok=True)
        with io.open(os.path.join(weights_dir, _SIZING_NAME), "w", encoding="utf-8") as handle:
            json.dump(values, handle)
    except Exception as exc:
        _log("Could not record the model's architecture: %s" % exc)


def _apply_recorded_sizing(weights_dir):
    """Rebuilds the architecture a model was trained with, before it is loaded.

    Silent when there is no record: models trained before this existed, and any trained at the
    default, load correctly under CakeChat's own defaults.
    """
    path = os.path.join(weights_dir, _SIZING_NAME)
    if not os.path.isfile(path):
        return
    try:
        with io.open(path, "r", encoding="utf-8") as handle:
            values = json.load(handle)
    except Exception:
        return

    width = values.get("hidden_layer_dim")
    if not width:
        return
    try:
        from cakechat.dialog_model.model import CakeChatModel
        if _rebind_defaults(CakeChatModel.__init__, hidden_layer_dim=int(width)):
            _log("Loading a model built with a recurrent width of %d" % int(width))
    except Exception as exc:
        _log("Could not apply the recorded architecture: %s" % exc)


#: Where the current inference stage is recorded, so a crash can still say what it was doing.
_BREADCRUMB_NAME = "inference_stage.txt"


def _breadcrumb_path(weights_dir):
    return os.path.join(weights_dir, _BREADCRUMB_NAME)


def _breadcrumb(weights_dir, stage):
    """Records the stage about to be attempted, on disk, immediately.

    WHY A FILE AND NOT A LOG LINE. TensorFlow runs as native code, and when it fails on a phone it
    does so by dying -- SIGSEGV, an abort, or the kernel reclaiming the process under memory
    pressure. None of those raise a Python or Java exception, so nothing catches them, the crash
    handler never runs, and the in-memory log buffer dies with the process. From the outside that
    looks exactly like what was reported: the app disappears and System Diagnostics says nothing.
    A file that has already reached the disk is the only thing that survives.

    fsync, not just close: Android's page cache can hold a written file for seconds, and a process
    killed in that window loses it -- which is precisely the window this is meant to cover.
    """
    try:
        os.makedirs(weights_dir, exist_ok=True)
        with io.open(_breadcrumb_path(weights_dir), "w", encoding="utf-8") as handle:
            handle.write("%s\n%s\n" % (stage, time.time()))
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        # Never fatal. A breadcrumb that cannot be written is a lost diagnostic, not a failed run.
        pass


def _clear_breadcrumb(weights_dir):
    try:
        os.remove(_breadcrumb_path(weights_dir))
    except OSError:
        pass


def _report_previous_crash(weights_dir):
    """Reports a breadcrumb left by a run that never finished.

    Read at the START of the next attempt, because that is the first moment anything is running
    again after a process death. The stage names what was in progress when it died -- which for a
    native crash is the only evidence there is.
    """
    path = _breadcrumb_path(weights_dir)
    if not os.path.isfile(path):
        return
    try:
        with io.open(path, "r", encoding="utf-8") as handle:
            stage = (handle.readline() or "").strip()
    except Exception:
        stage = ""
    _clear_breadcrumb(weights_dir)
    if not stage:
        return

    _log(
        "The previous inference did not finish; it died during: %s. A stage that ends without an "
        "error is a native crash or an out-of-memory kill inside TensorFlow, not a Python failure."
        % stage
    )


def _force_non_reranking_mode():
    """Switches inference to a prediction mode that does not need a reverse model.

    cakechat/api/config.py picks reranking:

        PREDICTION_MODE = PREDICTION_MODES.sampling_reranking

    which scores candidates with Maximum Mutual Information -- a measure computed by running a
    SECOND, reverse model that predicts the context from the response. Prism trains a forward model
    only, so that second model does not exist and the mode is unreachable. The two errors it
    produces contradict each other, which is what makes the situation clear:

        ValueError: Reverse model has to be supplied to MMI-reranker.
        If you don't have one, set mmi_reverse_model_score_weight to 0.

        ValueError: mmi_reverse_model_score_weight should be > 0 for reranking mode

    There is no weight that satisfies both. The reranker refuses to exist without a reverse model,
    and the factory refuses to build a reranking predictor without a weight -- so the mode itself
    has to change rather than its configuration.

    `sampling` is chosen over `beamsearch` because it is what upstream already uses for its own
    metrics (PREDICTION_MODE_FOR_TESTS), so it is the mode this model was evaluated in during
    training. Replies are less diverse than a reranked model's; that is what MMI contributes, and
    training a reverse model as well would roughly double every run.
    """
    import cakechat.api.config as api_config
    from cakechat.config import PREDICTION_MODES

    if api_config.PREDICTION_MODE == PREDICTION_MODES.sampling:
        return

    api_config.PREDICTION_MODE = PREDICTION_MODES.sampling

    # response.py does `from cakechat.api.config import PREDICTION_MODE`, binding the value into its
    # own namespace. Patching only api.config would be ignored by an already-imported response.
    response = sys.modules.get("cakechat.api.response")
    if response is not None and hasattr(response, "PREDICTION_MODE"):
        response.PREDICTION_MODE = PREDICTION_MODES.sampling

    _log("Prediction mode set to sampling (reranking needs a reverse model, which is not trained)")


def _patch_model_resolution(weights_dir):
    """Stages Prism's weights wherever CakeChat decides to look for them.

    CakeChat addresses a trained model by a HASH OF ITS HYPERPARAMETERS:

        model_id = md5(json.dumps(self.model_params, sort_keys=True))[:12]
        model_path = results/nn_models/cakechat_v2.0_keras_tf_<model_id>

    `model_params` includes batch_size and epochs_num. That is reasonable for the workflow it was
    designed around -- several experiments side by side, each addressable by its settings -- but it
    means the directory holding the weights depends on the settings used to TRAIN, while inference
    computes the hash from the settings in force when it RUNS. Train with a batch of 8 and answer
    with the default 196 and the two disagree:

        ValueError: Can't find previously trained model in
        results/nn_models/cakechat_v2.0_keras_tf_71e6d7d40451

    Matching the settings back up would work for a model trained on this device and fail for an
    imported one, whose training settings are not recorded anywhere. So instead of predicting the
    hash, this waits until the model has computed it -- `self.model_path` is known by the time
    resolve_model runs -- and puts Prism's copy there if nothing is there already.

    Deliberately does not overwrite: a file that is already in place belongs to this exact
    configuration and is a better match than Prism's general copy.
    """
    import shutil

    from cakechat.dialog_model.abstract_model import AbstractModel

    if getattr(AbstractModel, "_prism_stages_weights", False):
        return

    original = AbstractModel.resolve_model

    def resolve_model(self, _original=original):
        try:
            target_dir = self.model_path
            resource = os.path.join(target_dir, self._MODEL_RESOURCE_NAME)
            if not os.path.exists(resource):
                source = _saved_weights_file(weights_dir)
                if source is not None:
                    os.makedirs(target_dir, exist_ok=True)
                    shutil.copyfile(source, resource)
                    _log("Staged trained weights for this configuration (%s)"
                         % os.path.basename(target_dir))
        except Exception as exc:
            # Never fatal: if staging fails, upstream's own error is the more useful one.
            _log("Could not stage weights: %s" % exc)
        return _original(self)

    AbstractModel.resolve_model = resolve_model
    AbstractModel._prism_stages_weights = True


def _saved_weights_file(weights_dir):
    """The weights Prism holds, preferring the best-scoring file over the running one.

    CakeChat writes two: `model`, replaced only when evaluation improves, and `model.current`,
    replaced every checkpoint. The first is the one worth answering with.
    """
    if not os.path.isdir(weights_dir):
        return None

    candidates = []
    for name in sorted(os.listdir(weights_dir)):
        path = os.path.join(weights_dir, name)
        if os.path.isfile(path) and (name.endswith(".h5") or _is_hdf5(path)):
            candidates.append(path)
    if not candidates:
        return None

    for path in candidates:
        if os.path.basename(path) == "model":
            return path
    return candidates[0]


def _link_weights(repo_root, weights_dir):
    """Restores saved weights into the repository before inference.

    The reverse of [harvest_weights], for the case that matters: the source tree was re-downloaded
    (which empties results/) while Prism's own copy survived. Copied rather than symlinked because
    Android storage does not reliably support symlinks from app-private directories, and a broken
    link would surface as "no trained model" rather than as a filesystem error.
    """
    if not os.path.isdir(weights_dir):
        return

    import shutil

    # INDEX FILES FIRST, and unconditionally. A PC-trained import brings its own vocabulary, and the
    # weights are sized by it -- restoring weights while leaving whatever index files this device
    # happened to build would load them into the wrong shape and answer nonsense rather than fail.
    # t_idx_*.json and c_idx_*.json are the names CakeChat's own get_index_to_*_path build.
    try:
        from cakechat.config import TOKEN_INDEX_DIR, CONDITION_IDS_INDEX_DIR
        for name in os.listdir(weights_dir):
            if name.startswith("t_idx_"):
                destination = TOKEN_INDEX_DIR
            elif name.startswith("c_idx_"):
                destination = CONDITION_IDS_INDEX_DIR
            else:
                continue
            os.makedirs(destination, exist_ok=True)
            shutil.copyfile(os.path.join(weights_dir, name), os.path.join(destination, name))
            _log("Restored index file: %s" % name)
    except Exception:
        _log("Could not restore index files; a PC-trained model may not load")

    target_root = _weights_root(repo_root)
    if _find_weight_files(target_root):
        return                      # the repository already has weights

    os.makedirs(target_root, exist_ok=True)
    for name in os.listdir(weights_dir):
        source = os.path.join(weights_dir, name)
        if not (name.endswith(".h5") or _is_hdf5(source)):
            continue
        shutil.copyfile(source, os.path.join(target_root, name))
        _log("Restored weights into the repository: %s" % name)


def is_trained(weights_dir):
    """Whether anything has actually been trained, for a UI deciding what to offer.

    BY CONTENT, NOT BY EXTENSION. CakeChat writes its weights to `model` and `model.current`, with
    no suffix at all -- `_MODEL_RESOURCE_NAME = 'model'` -- and an imported bundle carries those
    same names. Matching only ".h5" therefore answered False for every model that has ever existed
    here, which is not a cosmetic error: Sam consults this before dispatching, so a perfectly good
    CakeChat was reported as untrained and every message fell through to the GGUF engine.

    The Kotlin twin of this check already reads the HDF5 magic number. This one did not, and the
    two disagreeing is what let the bug hide -- the model listed as trained in the UI and refused
    to answer.
    """
    if not os.path.isdir(weights_dir):
        return False
    for name in os.listdir(weights_dir):
        path = os.path.join(weights_dir, name)
        if os.path.isfile(path) and (name.endswith(".h5") or _is_hdf5(path)):
            return True
    return False
