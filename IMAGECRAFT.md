# ImageCraft, and what the AI backend setting actually does

Two features, one document, because they meet in the same place: ImageCraft draws with whatever
backend the user chose, and the honest answer about which backends exist is per format, not per
phone.

---

## 1. The AI backend, and the NPU

`Prism Settings → Intelligence & Messaging → AI backend` offers **CPU**, **GPU** and **NPU**. The
rule the whole thing is built on:

> **The NPU is used only when NPU is what the user selected.**

Nothing opts into an accelerator on its own. `AiBackend.npuChosen()` reads the setting, every engine
checks it before considering the NPU, and a model that cannot use the chosen backend runs somewhere
else **and says so** in the page and the notification. A silent fallback is indistinguishable from
the feature working, which is why the note is not optional.

### What can reach an NPU, by format

| Format | NPU route | Qualcomm | Samsung | Google Tensor | MediaTek | Huawei |
|---|---|---|---|---|---|---|
| TFLite (`.tflite`, CakeChat) | NNAPI delegate | yes | yes | yes | yes | yes |
| ONNX (voice cloning, any graph) | ONNX Runtime NNAPI EP | yes | yes | yes | yes | yes |
| GGUF text (llama.cpp) | `ggml-hexagon` | Hexagon v73+ and a build made with the Hexagon SDK | no | no | no | no |
| MediaPipe `.task` (text and image) | none | CPU/GPU only | | | | |
| Diffusion checkpoints (`.gguf`, `.safetensors`) | none | no | no | no | no | no |
| Cloud endpoints | not applicable | runs on the provider's hardware | | | | |

The two NNAPI routes are what make NPU a real choice on all five vendors, which is why they are the
ones that are wired end to end. The table above is what `NpuSupport` computes on the device: vendor
from `Build.SOC_*`, `HARDWARE` and `BOARD`; NNAPI from the API level (8.1+); and the vendor runtime
by looking for its libraries (`libQnnHtp*`, `libenn*`, `libtpu*`, `libneuropilot*`/`libapu*`,
`libhiai*`/`libdavinci*`). The Settings row shows the accelerator, the runtimes it found and, format
by format, what NPU can take — and refuses the choice outright only when there is genuinely no NPU
path on the device.

Where a format has no NPU route, `NpuSupport.Blocker` names the missing piece rather than shrugging:
`NO_NNAPI`, `NO_RUNTIME` (no vendor runtime accepts an un-converted checkpoint),
`NO_LLAMA_BACKEND`, `HEXAGON_TOO_OLD`, `HEXAGON_NOT_BUILT`.

### Where each engine applies the choice

- `GgufInferenceService` passes the mode to native code, which restricts the device list to the
  Hexagon device for NPU (`app/src/main/cpp/gguf_bridge.cpp`).
- `CakeChatLite` adds TFLite's NNAPI delegate when NPU is chosen, and holds it for the life of the
  interpreters.
- `OnnxAccelerators` adds ONNX Runtime's NNAPI execution provider; the Android build installs the
  hook, because `addNnapi` only exists in `onnxruntime-android`.
- `StableDiffusion` maps the choice onto a real diffusion device: CPU to `CPU`, GPU to the first
  accelerator it offers, NPU to the best device it has **with a note saying the checkpoint has no NPU
  path**. NPU never becomes a silent GPU.

### Building with the Hexagon backend

`HEXAGON_SDK_ROOT=/path/to/hexagon-sdk cmake …` — `app/src/main/cpp/CMakeLists.txt` turns on
`GGML_HEXAGON` when it is set and explains its absence in the build log when it is not. The DSP-side
binaries cannot be redistributed inside an APK, which is why this is a build-time flag rather than a
download.

---

## 2. ImageCraft

A page, on both platforms: an image view with a prompt field below it, drawn in the user's theme.

- **Page** — `app/.../imagecraft/ImageCraftPageView.kt`, `desktop/.../ui/ImageCraftPage.kt`.
- **Engine** — `core/.../messaging/ImageCraft.kt`. Process-scoped, so a render survives the page.
- **Android background run** — `ImageCraftService`, a foreground service with a persistent
  notification (progress, stage, Stop action, and the completion message). Saving goes to
  MediaStore via `AndroidImageCraftSink`, so the picture is in the gallery.
- **Desktop saving** — `Pictures/Prism/ImageCraft`, announced with a tray balloon.

### The two-model gate

Both are required, and the page says which one is missing:

- **Text model** — local GGUF, cloud, Ollama, CakeChat or a MediaPipe `.task`. Its job is to rewrite
  the sentence into a diffusion prompt: "a fox in the snow" becomes a subject, a setting, a light, a
  lens. If it returns nothing, the user's own words are drawn instead of failing the run.
- **Image model** — an active local checkpoint (stable-diffusion.cpp), a MediaPipe `.task` bundle, a
  cloud image endpoint, or Nora. Local and cloud both count.

### Resolution

`Prism Settings → Intelligence & Messaging → AI Images → Image resolution` (256 to 1024, portrait and
landscape included). What happens to a size an engine cannot honour is the engine's own business, and
each says so: a checkpoint is clamped to what its family was trained for (768 for SD 1.5, 1536 for
XL/Flux) and reports the change, MediaPipe draws at its bundle's own size and says so, a cloud
endpoint gets the size in the request body and takes it literally.

### Progress

Steps where the engine has them (`stable-diffusion.cpp` publishes a step counter, which is polled),
indeterminate where it does not (MediaPipe's create-and-generate is one opaque native call, and a
remote render has nothing to observe). Percentages are never invented.
