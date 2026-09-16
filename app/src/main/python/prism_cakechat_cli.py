"""Command-line driver for CakeChat, used by the desktop app.

Android calls prism_cakechat directly through Chaquopy, in-process, and polls `progress()`. The
desktop has no in-process Python -- it runs a real interpreter as a subprocess -- so it needs
something that turns the same state into a stream Kotlin can read.

PROGRESS IS PRINTED AS JSON LINES, one object per line on stdout, flushed immediately. A subprocess
reader parses whatever parses and ignores the rest, which means TensorFlow's own chatter on stdout
cannot corrupt the channel: it simply is not JSON. The alternative -- a side-channel file the reader
polls -- would reintroduce exactly the polling the desktop does not otherwise need.

Every line carries a "kind" so the reader never has to guess what it is looking at:

    {"kind": "progress", "step": 12, "total": 400, ...}
    {"kind": "log", "line": "Epoch 1/2"}
    {"kind": "result", "ok": true, "message": "..."}
"""
import argparse
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import prism_cakechat as cake       # noqa: E402
import prism_corpus as corpus       # noqa: E402


def emit(payload):
    """One JSON object, one line, flushed.

    Flushed on every write because the reader is rendering a progress bar: buffered output would
    arrive in bursts and make a running trainer look stalled.
    """
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def _pump_progress(stop_event, seen_logs):
    """Relays state until told to stop. Mirrors the once-a-second poll the Android service does."""
    while not stop_event.is_set():
        try:
            snapshot = cake.progress()
            snapshot["kind"] = "progress"
            emit(snapshot)

            for line in cake.recent_logs(120):
                if line not in seen_logs:
                    seen_logs.add(line)
                    emit({"kind": "log", "line": line})
        except Exception:
            pass
        stop_event.wait(1.0)


def command_convert(args):
    """Converts a corpus, streaming progress as it goes.

    PUMPED FROM A THREAD, like training. A six-shard Parquet dataset takes minutes per file, and
    the previous shape of this command -- run to completion, then print one line -- gave the caller
    nothing to show for that time and no way to tell work from a hang.
    """
    stop_event = threading.Event()
    seen = set()

    def pump():
        while not stop_event.is_set():
            for line in corpus.recent_logs(200):
                if line not in seen:
                    seen.add(line)
                    emit({"kind": "log", "line": line})
            stop_event.wait(0.3)

    pump_thread = threading.Thread(target=pump, daemon=True)
    pump_thread.start()

    try:
        ok, message, count = corpus.convert(args.corpus, args.out)
    finally:
        stop_event.set()
        pump_thread.join(timeout=2)
        # One last drain, so the closing lines are not lost to the poll interval.
        for line in corpus.recent_logs(200):
            if line not in seen:
                emit({"kind": "log", "line": line})

    emit({"kind": "result", "ok": ok, "message": message, "dialogs": count})
    return 0 if ok else 1


def command_train(args):
    stop_event = threading.Event()
    seen = set()
    pump = threading.Thread(target=_pump_progress, args=(stop_event, seen), daemon=True)
    pump.start()

    ok = False
    try:
        ok = cake.train(
            args.repo, args.corpus, args.out,
            epochs=args.epochs,
            batch_size=args.batch_size,
            subset_size=args.subset,
            hidden_layer_dim=args.hidden,
        )
    finally:
        stop_event.set()
        pump.join(timeout=3)
        # One final drain, so the last lines -- which include the traceback when a run fails -- are
        # not lost to the poll interval.
        for line in cake.recent_logs(200):
            if line not in seen:
                emit({"kind": "log", "line": line})

    emit({
        "kind": "result",
        "ok": bool(ok),
        "message": "Training finished." if ok else (cake.last_error() or "Training failed."),
    })
    return 0 if ok else 1


def command_export_tflite(args):
    ok = cake.export_tflite(args.repo, args.out)
    for line in cake.recent_logs(200):
        emit({"kind": "log", "line": line})
    emit({
        "kind": "result",
        "ok": bool(ok),
        "message": "TFLite export complete." if ok else (cake.last_error() or "Export failed."),
    })
    return 0 if ok else 1


def command_infer(args):
    reply = cake.infer(args.repo, args.out, [args.text], args.emotion)
    emit({
        "kind": "result",
        "ok": reply is not None,
        "message": reply or (cake.last_error() or "No reply."),
    })
    return 0 if reply is not None else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    convert = sub.add_parser("convert", help="Convert any corpus into CakeChat's JSON format")
    convert.add_argument("--corpus", required=True)
    convert.add_argument("--out", required=True)
    convert.set_defaults(func=command_convert)

    train = sub.add_parser("train")
    train.add_argument("--repo", required=True)
    train.add_argument("--corpus", required=True)
    train.add_argument("--out", required=True)
    train.add_argument("--epochs", type=int, default=2)
    train.add_argument("--batch-size", type=int, default=32)
    # 0 means the whole corpus, matching CakeChat's own "unset" for train_subset_size.
    train.add_argument("--subset", type=int, default=0)
    # 0 means CakeChat's own 768. Smaller is the difference between a model that loads on a phone
    # and one that does not; see `train`'s docstring.
    train.add_argument("--hidden", type=int, default=0)
    train.set_defaults(func=command_train)

    export = sub.add_parser(
        "export-tflite", help="Convert a trained model to TFLite for on-device inference")
    export.add_argument("--repo", required=True)
    export.add_argument("--out", required=True)
    export.set_defaults(func=command_export_tflite)

    infer = sub.add_parser("infer")
    infer.add_argument("--repo", required=True)
    infer.add_argument("--out", required=True)
    infer.add_argument("--text", required=True)
    infer.add_argument("--emotion", default="neutral")
    infer.set_defaults(func=command_infer)

    args = parser.parse_args()
    try:
        sys.exit(args.func(args))
    except Exception:
        import traceback
        # Reported as a result rather than allowed to reach stderr as a bare traceback: the reader
        # only interprets stdout, and a crash that arrived on stderr alone would show up as a run
        # that ended with no explanation.
        emit({"kind": "result", "ok": False, "message": traceback.format_exc()})
        sys.exit(1)


if __name__ == "__main__":
    main()
