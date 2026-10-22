# Prism — AI, Models & Agentic Tools Audit

Scope: AI messaging, models, agentic tools — `app/messaging`, model-store/shop/checkout/quant UI,
`core` messaging/agentic/quant/mesh/model-listing code, `cakechat`, and the desktop AI pages.
Read-only audit; no source was modified.

Method: every claim below is grounded in code read in this session (`file:line`). Comments and
README claims were **not** trusted; where a comment asserts something the code contradicts, the
contradiction is called out. No build/run was possible in this environment (the sandbox could not
provision write access to the workspace root for shells — see *Limitations*), so every status is a
static-analysis verdict, stated with the evidence that supports it.

---

## 0. Status at a glance

| # | Feature | Verdict |
|---|---------|---------|
| 1 | Sam AI chat (Android) | **PARTIAL** |
| 2 | Engine selection: on-device vs cloud vs Ollama vs CakeChat vs P2P | **WORKING** (selection) / **PARTIAL** (quality of each path) |
| 3 | Model store: browse + download | **BROKEN** (one provider dead, one mis-categorised, no timeouts) |
| 4 | Model import (file picker / SAF) | **WORKING** |
| 5 | Model shop: listings / payments / refunds | **BROKEN** (delivery path, purchased file location, settlement gaps) |
| 6 | Compute market (buy/sell mesh inference) | **BROKEN** (single-peer path is a permanent no-op) |
| 7 | Image generation | **PARTIAL** (cloud URL bug + API<29 save failure; local engine mis-wired) |
| 8 | Vision | **BROKEN on Android** (canned strings); WORKING on desktop |
| 9 | Dictation | **WORKING on Android** (SpeechRecognizer); registered Whisper engines unreachable |
| 10 | AI conversations (Sam / Nora / Aether threads) | **PARTIAL** |
| 11 | Dataset downloads (Nora/Aether training data) | **WORKING** (with caveats) |
| 12 | NPU support | **PARTIAL by design** (report-only; always "unavailable" in shipped builds) |
| 13 | Quantization | **WORKING** (with a registry-pruning bug on the output) |
| 14 | Agentic tools + builtin tools | **PARTIAL** (`make_call` permanently blocked; local tool loop duplicates context every round) |
| 15 | CakeChat | **PARTIAL** (real Python stack; needs manual repo install + training; no pretrained weights) |
| 16 | SMS relay | **WORKING** (Android side wired end-to-end) |

---

## 1. Sam AI chat

**What it does.** The chat surface behind the "Sam" thread. Sends a user turn through
`AiManager.getResponse`, which picks one of six backends (P2P mesh model → agentic tool loop →
image generation → cloud → Ollama → CakeChat → local GGUF), streams tokens into a live bubble, and
persists both sides to Room.

**Exact user path.**
`Messages` desktop page → `Sam` thread row (`MessagingPageView.kt:340`) →
`ConversationActivity` with `threadId = ConversationActivity.SAM_THREAD_ID (-100)`
(`ConversationActivity.kt:25`) → type / dictate → send (`:153-158`) → `sendMessage` (`:509`) →
`sendToSam` (`:746`) → `AiManager.getResponse` (`:784`).

**Status: PARTIAL.**

Evidence it is real: streaming into the bubble with a stable timestamp to defeat DiffUtil churn
(`ConversationActivity.kt:764-801`), reasoning-trace split for `<think>` models
(`GgufInferenceService.kt:503-551`), Prism-Swap degraded-mode toast (`:809-816`), Room persistence
(`:819-824`).

**Bugs.**

1. **Cloud mode answers every message containing the word "video" from a hardcoded mock, with a
   remote third-party media URL.** `AiManager.kt:147-149` routes to
   `CloudAiService.generateResponse()`, which never makes a network call: it returns canned prose
   and `picsum.photos` / `w3schools` URLs (`CloudAiService.kt:21-44`). Asking Sam "how do I record
   a video on this phone?" in Cloud mode returns *"Here is an AI-synthesized motion sequence based
   on your prompt."* with `https://www.w3schools.com/html/mov_bbb.mp4` attached. The file's own
   header even says "mock logic for image/video generation demonstration" (`:13`). This is the
   "AI path that falls back to canned text" the audit was asked to look for, and it is reachable
   from the primary chat surface.
2. **Cloud Sam has no memory of the conversation.** `getResponse` accepts `history`
   (`AiManager.kt:93`) and `ConversationActivity` dutifully passes the stored turns
   (`ConversationActivity.kt:782-801`), but the only consumer of `history` is the CakeChat branch
   (`AiManager.kt:202-204`). `CloudAiService.fetchResponse`/`fetchResponseStreaming` build a single
   user message (`CloudAiService.kt:58-78`, `:123-143`). So in Cloud mode every turn is a fresh
   conversation while the UI shows a continuous thread: follow-ups like "and the second one?"
   cannot resolve.
3. **Error strings are shown as Sam's answer.** The cloud path returns failures as prose
   (`CloudAiService.kt:99`, `:104`) and `AiManager` returns them unchanged as the reply text
   (`:162`). There is no `error` channel in `Pair<String, Pair<String?, String?>>`, so a 401 is
   persisted to the transcript as an assistant message.
4. **Attaching an image in Local mode silently does nothing.** `ConversationActivity` forwards the
   `Uri` (`:785`); `AiManager` only uses it on the cloud branch (`:155`), and the local branch
   (`:242-247`) never looks at it and adds no note. On desktop the equivalent path *does* report
   it (`SamConversation.kt:334-337`) — Android is the inconsistent one. `Vision` exists and is
   registered (`PrismApp.kt:82-87`) but is never called from this screen.
5. **Missing `SEND_SMS` permission discards the typed message.** `sendSms` requests the permission
   and `return`s (`ConversationActivity.kt:833-836`) *after* `sendMessage` already cleared the
   input box (`:529-531`). The text is gone and nothing was sent.

---

## 2. Engine selection (Local / Cloud / Local-Cloud / P2P / CakeChat)

**What it does.** `PrismSettings.getAiMode()` selects one of `local` / `cloud` / `local_cloud`
(`PrismSettings.kt:1731-1733`); a P2P model selection outranks the mode
(`AiManager.kt:101-105`); CakeChat outranks the local model inside local mode (`:178-219`).

**Exact user path.** `Settings → AI Engine` mode switch (`SettingsActivity.kt:1530-1541`);
`Models` page rows activate a specific model (`ModelsPageView.kt:279-382`); P2P rows come from
`ModelsPageView.kt:248-261`.

**Status: WORKING** for the selection logic (each branch is genuinely wired, and the shadowing order
is deliberate and documented at the call sites). **PARTIAL** for the engines themselves — see the
per-engine sections.

**Bugs.**

1. **`AiManager` and `SamConversation` disagree about what "has a backend" means.** In
   `SamConversation.backend()`, a configured cloud model wins even when the mode says Local
   (`SamConversation.kt:112-116`), whereas `AiManager` in Local mode with no local model returns an
   error string and never reaches cloud (`AiManager.kt:221-228`). Same settings, two answers,
   depending on which surface you use.
2. **`PrismSettings.getImageEngineId()` / `getVisionEngineId()` / `getDictationEngineId()` have no
   Android UI.** `SettingsActivity` never calls `setVisionEngineId`/`setImageEngineId`/
   `setDictationEngineId` (grep over the file: zero matches), so on Android these always fall back
   to "first usable engine in registration order" (`Vision.kt:115-119`, `ImageGeneration.kt:119-124`,
   `Dictation.kt:93-97`). The preference is persisted but unreachable — a setting with no UI.

---

## 3. Model store (browse + download)

**What it does.** `ModelStoreView` searches Hugging Face / GitHub / a curated Google list through
`ModelDiscoveryService`, lists results as cards, and downloads via Android `DownloadManager` into
app-external storage, then copies into `filesDir/models` and registers/activates the model.

**Exact user path.** Desktop page `ModelStore` → `ModelStorePageView` → "Download" tab →
`ModelStoreView` (`ModelStorePageView.kt:43`) — or `Settings → Search for Models` →
`ModelStoreActivity` (`ModelStoreActivity.kt:8-17`) → search/tab (`ModelStoreView.kt:129-156`) →
"Download" (`:158-166`) → `ModelDownloadManager.download` (`ModelDownloadManager.kt:36`) →
completion broadcast (`PrismApp.kt:402-406`) → copy + register (`ModelDownloadManager.kt:99-240`).

**Status: BROKEN.**

**Bugs.**

1. **The GitHub provider can never return a result.** `GitHubProvider` calls
   `https://api.github.com/search/code` with no `Authorization` header
   (`ModelDiscoveryService.kt:120-127`). GitHub's code-search endpoint requires authentication and
   answers 401; the `catch` (`:146`) turns that into an empty list, so the "GitHub" source silently
   contributes nothing, forever.
2. **The Google provider's "image/vision" models are face landmarkers, and downloading one
   hijacks image generation.** `GoogleAIProvider` returns `face_landmarker.task`,
   `object_detector…`, `image_classifier…`, `magic_touch…` with `category = "vision"`
   (`ModelDiscoveryService.kt:156-166`). `ModelStoreView.download` maps `"vision"` to
   `isImageModel = true` (`ModelStoreView.kt:161`), so tapping Download sets
   `LocalImageModelPath` to a face landmarker (`ModelDownloadManager.kt:198`) and every subsequent
   "draw me a cat" fails inside MediaPipe. None of these four files can generate an image.
3. **No timeouts on the store's HTTP calls.** `HuggingFaceProvider.search` and `fetchRepoFiles`
   use `Urls.of(...).openConnection().getInputStream()` with no `connectTimeout`/`readTimeout`
   (`ModelDiscoveryService.kt:75`, `:101`), serially, once per result (`:81-91`). A hung host hangs
   the search coroutine indefinitely; `ModelStoreView.runSearch` has no timeout or cancel path
   (`ModelStoreView.kt:135-146`), so the spinner stays up and the empty state never appears.
4. **The download name is a repo path, not a filename.** `name = "$repoId (${path.substringAfterLast('/')})"`
   (`ModelDiscoveryService.kt:84`, `:138`) is passed straight to
   `setDestinationInExternalFilesDir(..., "$name.$extension")` (`ModelDownloadManager.kt:43`), so
   the destination is a *nested directory* path containing spaces and parentheses, and the imported
   model's display name becomes `TheBloke/Mistral-7B-GGUF (x.gguf).bin`.
5. **A `.gguf` download is saved with the extension `.bin`.** `extension = if (url.endsWith(".task")) "task" else "bin"`
   (`ModelDownloadManager.kt:38`). Detection is by magic bytes so the model still loads, but the
   file on disk is misnamed for every text model in the store.
6. **Concurrent downloads lose all but the last.** The in-flight id is a single settings value
   (`PrismSettings.setAiDownloadId`, `ModelDownloadManager.kt:50`); the receiver ignores any
   completion whose id is not the stored one (`:60`). Start a second download before the first
   finishes and the first is never imported — its bytes sit in external files dir with no
   registration.
7. **Store failures are reported as success.** The card flips to "Started" and is disabled on
   click (`ModelStoreView.kt:195-197`) with no completion feedback at all, so a 401/404 from
   Hugging Face (gated repos) leaves a permanently disabled "Started" button and no file.

---

## 4. Model import (local file)

**What it does.** Copies a user-picked SAF/file-picker document into `filesDir/models` with byte
verification, validates the container, registers it as an imported model, activates it, and eagerly
preloads it.

**Exact user path.** `Settings → AI Engine → (import model)` → picker →
`ModelDownloadManager.copyUriToInternal` (`ModelDownloadManager.kt:247-291`) →
`registerImportedModel` (`:172-240`); or `Models` page → activate (`ModelsPageView.kt:279-319`).

**Status: WORKING.**

Evidence: real byte-for-byte copy with `fd.sync()` (`:134-154`), size verification (`:109-114`,
`:269-274`), magic-byte format gate that refuses and deletes a bad file (`:183-195`), per-type
activation including `PrismSpeaker.invalidate()` for audio (`:197-209`), and eager preload with
stage reporting (`:217-239`).

**Bugs.**

1. **The load-fit gate ignores model size entirely.** `GgufInferenceService.requiredBytes(modelPath, nCtx)`
   never uses `modelPath` (`GgufInferenceService.kt:647-658`): it returns `kvCache + 320 MB + 256 MB`
   ≈ **1.58 GB for every model**, from a 300 MB file to a 40 GB one. `LocalAiService` prints that
   constant to the user as the requirement (`LocalAiService.kt:171-177`: *"needs about 1.58GB to
   load"* for any file), and `ensureLoaded`'s whole three-tier Prism-Swap ladder
   (`GgufInferenceService.kt:392-447`) is driven by comparing that constant to free RAM — so an
   enormous model passes the guard on a small phone and a tiny one can be refused. `weightsExceedRam`
   (`:667-668`) knows the real file size but is only used for a UI note.
2. **The degraded tier's own context size isn't reflected in the check.** Tier 1 loads with
   `nCtx = 1024` (`:404`, `:411`) but the deficit was computed with the default 2048 (`:392` →
   `requiredBytes(modelPath)`), so the mitigation is sized for a context it does not use.

---

## 5. Model shop: listings, payments, refunds

**What it does.** Sellers list a model with a PSC price; listings gossip over the mesh; buyers
commit a purchase (coins *held*, not sent), download the file from the seller over the mesh
tunnel, and the purchase settles only after a GitHub/Hugging-Face "is this just a public
download?" check passes. Refunds are 3 hours.

**Exact user path.** `ModelStore → Model Shop` tab (`ModelStorePageView.kt:44`, `:59`) →
`ModelShopView` (`ModelShopView.kt:154-181`) → row → `ModelCheckoutActivity` (`:183-190`) → "Buy"
(`ModelCheckoutActivity.kt:123-161`) → download (`:372-399`). Selling:
`Model Shop → +` (`ModelShopView.kt:116-127`) → `ModelUploadActivity` → "List for sale"
(`ModelUploadActivity.kt:98-100`, `:162-213`).

**Status: BROKEN.** The UI, the ledger, the scanner and the refund machinery are all real and
thought through; the *delivery* path and the *file locations* are not connected, and settlement has
unrecoverable states.

**Bugs.**

1. **A purchased model can never be delivered: the seller serves from a different directory than
   the seller wrote to.** Listing a model copies it to
   `P2pModelTransfer.destinationFor(name)` → `ModelRegistry.directory()` → `documentsDir()/Models`
   (`ModelUploadActivity.kt:187`, `P2pModelTransfer.kt:53-56`, `ModelRegistry.kt:60`). The serving
   side resolves `/model/<name>` inside `File(context.filesDir, "models")`
   (`PrismAiHost.kt:238-247`). Every buyer's `GET /model/<name>` therefore 404s. The buyer's
   consequence is exactly the one the scanner exists to prevent: coins committed, no file.
2. **The request line is built from an unsanitised, un-encoded listing name.** Buyer sends
   `"GET /model/${listing.name}"` (`P2pModelTransfer.kt:83`) while the save path sanitises
   (`:55-56`). Any listing whose display name contains a space (the default is the file's
   basename-with-spaces, `ModelUploadActivity.kt:168`) produces a malformed request line and a
   filename mismatch on the way back.
3. **A delivered model is never registered, and the Models page actively deletes such entries.**
   `ModelCheckoutActivity.beginDownload` only notifies on success (`:386-390`) — no
   `ModelRegistry.install`, no `PrismSettings.addImportedModel` (the desktop path does:
   `ModelShopPage.kt:475`). Meanwhile Android's `ModelsPageView.loadModels` scans **only**
   `filesDir/models` (`ModelsPageView.kt:169`) and then *prunes the registry* of everything not in
   that folder (`:189-191`). So a purchased model lands at `Documents/Models`, is invisible to the
   app, and is silently removed from the registry on the next visit to the Models page.
4. **`ModelRegistry` — the file whose whole documented purpose is "a downloaded model stopped
   appearing after a restart" — is never called on Android.** `watchDownloads()` and
   `reconcile()` have exactly two call sites, both desktop (`Main.kt:344-345`, `ModelsPage.kt:99`).
   The Android repair path does not exist, and Android has a *third* models directory
   (`filesDir/models`) that `ModelRegistry.directory()` does not know about.
5. **A held purchase can become permanently unrecoverable, and its coins are locked forever.**
   `ModelRefunds.isRefundable` requires the 3-hour window to still be open
   (`ModelRefunds.kt:70-73`); `commit` refuses to re-buy a listing that already has a purchase
   (`ModelPayments.kt:85-90`); `owns()` counts `HELD` (`ModelPurchaseLedger.kt:140-143`); and
   `spendable()` subtracts all held amounts forever (`ModelPayments.kt:68-69`). The only thing that
   clears a `HELD` purchase is a settlement sweep, which runs **only at process start**
   (`PrismApp.kt:482-495`) and only if `dueNow()`. If both lookups keep failing (offline peer,
   Hugging Face/GitHub unreachable → `isPubliclyAvailable` returns null, `ModelListingScanner.kt:340-356`),
   the purchase stays `HELD` past the window with no UI able to cancel it: the buyer's coins are
   permanently marked committed, and they cannot buy the model again either.
6. **Settlement stamps the last-run clock before doing any work.** `runOnce()` writes
   `KEY_LAST_RUN` first (`ModelListingScanner.kt:302`), so a sweep that throws or is killed
   mid-loop delays every remaining held purchase by a full interval.
7. **The repackaging check is a substring match on normalised names.** `matches()` accepts
   `c.contains(n) || n.contains(c)` (`ModelListingScanner.kt:358-364`), so a legitimate original
   model named after its base architecture ("llama3", "mistral") is flagged as a repackaged public
   download and its purchase is cancelled (`:313-321`). The file's own comment admits accuracy is a
   deterrent, but the failure mode here is the buyer losing the sale, not the seller being caught.
8. **`commit()` can record a purchase it never validated against a live listing.** It takes a
   `P2pModelListings.Listing` snapshot; listings expire in memory. Fine, but combined with (5) the
   user can end up holding a purchase for a listing no longer in the registry, which also removes
   the only record of which peer sold it (`ModelRefunds.kt:141-143`) — so even an in-window `PAID`
   refund fails with "seller not reachable".

---

## 6. Compute market (sell / buy mesh inference)

**What it does.** Sells this device's RAM/CPU to the mesh via ggml's RPC backend
(`MeshInference.startHosting`), or routes a local-model generation to peers: one peer → send the
prompt; several peers → split layers over RPC. Charges inferenced tokens to a debt ledger with a
pay-later/mining policy.

**Exact user path.** `Wallet` page → compute market section (`WalletPageView.kt:251-253`), or
`ComputeMarketView` (`ComputeMarketView.kt:148-206`); consumption is automatic inside
`AiManager.meshResponse` (`AiManager.kt:236-240`, `:268-307`).

**Status: BROKEN** for the single-peer case; the distributed (≥2 peers) case is plausible but
unverified.

**Bugs.**

1. **`MeshBridges.generateOnPeer` is declared and never assigned anywhere in the repository.**
   `MeshBridges.kt:145` declares the hook; the only `MeshBridges.* =` assignments are `coin`,
   `mining` and `connect` (`AndroidMeshBridges.kt:86`, `:135`, `:169`). `MeshInference.runOnPeer`
   starts with `val ask = MeshBridges.generateOnPeer ?: return null` (`MeshInference.kt:242`), so it
   **always returns null**. Selecting exactly one peer in the compute market therefore does nothing
   at all: `AiManager.meshResponse` treats null as "stay local" (`:285-287`) and silently generates
   on-device. No error, no note, no slower/faster difference — the feature appears configured and is
   inert.
2. **The "tokens served" counter is never incremented.** `tokensServed` is declared
   (`MeshInference.kt:56`) and read (`:268`), but nothing ever calls `incrementAndGet` (grep: only
   those two lines). A device selling compute reports **0 tokens served forever**
   (`MeshPage.kt:174`, `DesktopDiagnostics.kt:301`) while also never crediting itself.
3. **Hosting is a one-way latch.** `startHosting()` sets `hosting = true` and never clears it on the
   user turning `getComputeHostEnabled()` off (`MeshInference.kt:128-155`); the only reset is on
   failure (`:151`). Turning "sell my compute" off does not stop the RPC server — and ggml's RPC
   server has no authentication (`:136-142` says so), so this is a security-relevant latch, not
   just a UI one.
4. **Billing charges for a generation that returned an error string.** `runOnPeer` charges on
   `answer.length` (`:248`) before any error filtering; an answer of `"P2P Model Error: …"` is
   billed as if it were output. (`AiManager.generateOnPeer` filters prefixes for *callers*, but the
   charge happens inside `runOnPeer` before that.)

---

## 7. Image generation

**What it does.** Turns a prompt into an image, either through an OpenAI-compatible
`images/generations` endpoint (cloud) or MediaPipe's diffusion `ImageGenerator` (local), and saves
the result to `DCIM/Prism/Nebula` via MediaStore on Android.

**Exact user path.** In chat: type "draw me a cat" (`AiManager.kt:127-144` →
`ImageGenManager.parseImageRequest` `ImageGenManager.kt:94-113` → `generateImage` `:22-42`). Via
tools: `generate_image` (`AgenticBuiltinTools.kt:366-382` → `AgenticImageTools`). Desktop:
`Image generation` page (`ImageGenPage.kt:88-125`).

**Status: PARTIAL.**

**Bugs.**

1. **The cloud image URL is built with the wrong slash convention, so it 404s for the natural
   URL form.** `CloudAiService.fetchImage` does `baseUrl + "images/generations"`
   (`CloudAiService.kt:364`, no separator) while `fetchResponse` does `baseUrl + "/chat/completions"`
   (`:51`). The default profile ends in `/` (`CloudModelsActivity.kt:82`), so image gen works and
   chat gets `…/v1//chat/completions`; a user who types the URL without a trailing slash — which the
   probe accepts and reports as valid (`CloudEndpointProbe.kt:81-92`) — gets the reverse: chat works,
   every image request hits `…/v1images/generations`. One of the two concatenations is wrong for
   every stored value.
2. **Saving a generated image fails on Android 8.0–9.0 (API 26–28), and the failure looks like a
   generation failure.** `ImageGenManager.saveToPublicStore` inserts into MediaStore and writes
   through `openOutputStream` (`ImageGenManager.kt:44-61`), and uses
   `MediaStore.MediaColumns.RELATIVE_PATH` (`:51`), which is API 29+. `minSdk` is 26
   (`app/build.gradle.kts:15`) and the manifest declares **no** `WRITE_EXTERNAL_STORAGE`
   (grep of `AndroidManifest.xml`: absent). On API 26–28 the insert/write throws, the catch returns
   null (`:62-65`), and `AiManager` reports *"I couldn't generate that image"* (`:137-142`) even
   though the model produced one.
3. **The local image engine is handed a model *file* where MediaPipe wants a model *directory*.**
   `LocalImageService.initGenerator` calls `setImageGeneratorModelDirectory(modelPath)`
   (`LocalImageService.kt:27-29`) with the path of an imported `.task`/`.bin` file
   (`ModelDownloadManager.kt:198`). MediaPipe's image generator loads a *directory* of `.tflite`
   assets. An imported single file therefore throws on `createFromOptions` (`:35`), `generateImage`
   returns null (`:58-61`), and import reports "imported, but couldn't be loaded yet"
   (`ModelDownloadManager.kt:238`). The comment on `:28` ("SD typically expects a directory or
   bundle") acknowledges the ambiguity without resolving it.
4. **`ImageGeneration`'s engine registry is registered on Android and never used there.**
   `PrismApp.kt:76-81` registers `CloudImageGenerator` and `NoraImageGenerator`; the only
   `ImageGeneration.generate` call sites are desktop (`ImageGenPage.kt:103`, `NebulaPage.kt:1001`,
   `Main.kt:5240`) and `NebulaEngine.kt:133` in core. Android's chat path uses
   `ImageGenManager` instead. So the Android registry exists only to back `NebulaEngine`, and the
   `Nora` generator — genuinely local, no model download — is unreachable from Sam on Android.
5. **The `[VISUAL]` bridge is dead code.** `ImageGenManager.extractVisualPrompt` (`:72-78`) parses a
   tag nothing ever emits (grep: no producer).

---

## 8. Vision

**What it does (claimed).** Describes an image, cloud or on-device (Nora's learned vocabulary).

**Exact user path.** Android: posting an image to Nebula (`NebulaComposeActivity.kt:142`).
Desktop: diagnostics/self-test (`Main.kt:3468`). The intended engines are registered at
`PrismApp.kt:82-87`.

**Status: BROKEN on Android. WORKING (cloud) on desktop.**

**Bugs.**

1. **Android's only reachable vision path is a literal stub returning canned brackets.**
   `VisionService.analyzeImage` returns `"[Identifying objects via Cloud Vision...]"` on the cloud
   branch and `"[Local Vision Processing... User posted an attachment]"` otherwise
   (`VisionService.kt:14-29`); the "cloud" function is one comment and a constant (`:26-29`). That
   string is then fed to the social bots as what they "see":
   `NebulaComposeActivity.kt:141-144` → `visionTags` → bot comment prompt
   (`NebulaComposeActivity.kt:180-186`), so every bot is told *"The user posted an image.
   [Identifying objects via Cloud Vision...]"* and comments on the brackets.
2. **The real Vision registry is registered but never called from Android.**
   `Vision.register(CloudVisionEngine())` / `NoraVisionEngine()` (`PrismApp.kt:82-87`), yet
   `Vision.describe` has exactly one call site in the repo, on desktop (`Main.kt:3468`). The
   `.core` docstring for `Vision.kt:14-20` explicitly says it "replaces `VisionService`" — but
   `VisionService` is still the wired one.
3. **Same dead error-prefix check as Sam.** `CloudVisionEngine` filters failures with
   `startsWith("Cloud AI Error")` (`Vision.kt:219`) while `CloudAiService` emits `"Cloud Error (…)"`
   and `"Network Error: …"` (`CloudAiService.kt:99`, `:104`). An HTTP error therefore becomes an
   image *description* (`Vision.kt:219-220`).

---

## 9. Dictation

**What it does.** Speech → text. Android uses `SpeechRecognizer` bound to a mic button; `:core`
also defines a portable `Dictation` registry with whisper.cpp and a hosted-Whisper engine, used by
desktop.

**Exact user path.** Android: mic button in the conversation composer
(`ConversationActivity.kt:301-313` → `VoiceInputController`, `VoiceInputController.kt:11-13`,
`:29-39`); auto-submits on AI threads (`:304-308`). Desktop: mic in the composer
(`ConversationPage.kt:210-229`, `VoiceInput.kt:160`).

**Status: WORKING** on both platforms — but the two use *different* stacks, and the Android half of
the portable registry is unreachable.

**Bugs.**

1. **The Whisper engines are registered on Android and never selected there.**
   `Dictation.register(WhisperCppEngine())` / `CloudTranscriptionEngine()` (`PrismApp.kt:90-95`),
   but Android dictation goes through `SpeechRecognizer`, and `Dictation.transcribe` is only called
   from desktop (`ConversationPage.kt:229`, `Main.kt:3388`, `:5191`). On Android the registry is dead
   weight — including the `getWhisperModelPath()` setting, which has no UI on Android.
2. **`WhisperCppEngine` depends on a native library and a model file nothing on Android obtains.**
   Availability is false without `whisper_bridge` (`WhisperEngine.kt:126-142`); the model URL list
   (`:210-227`) is only consumed by desktop. Not a crash — but it means the Android AI stack carries
   an engine that can never be Ready.

---

## 10. AI conversations (Sam / Nora / Aether threads)

**What it does.** Three permanent AI threads in the Messages page, each with its own backend:
Sam (`AiManager`), Nora (predictive-coding brain, separate service), Aether (spiking network,
separate process).

**Exact user path.** `Messages` page (`MessagingPageView.kt:334-346`) → thread row → conversation.
Nora/Aether are `GONE`-guarded per-thread in `ConversationActivity` (`:178-182`), with their own
slash-command palettes (`:686-714`).

**Status: PARTIAL.**

**Bugs.**

1. **The Messages page thread list is rebuilt from a `content://mms-sms` query on every attach, with
   no permission re-check after the first grant.** `checkPermissionsAndLoad` only branches on
   `READ_SMS` at attach time (`MessagingPageView.kt:289-297`); after the user taps "Grant", the page
   does not reload until it is attached again (no callback wired from
   `requestMessagingPermissions`, `LauncherActivity.kt:308-315`). The AI threads do still appear
   (`:299-312`), so this reads as "my texts are gone".
2. **Thread search is a stub.** `filterMessages` only filters the already-loaded snippets and leaves
   a `TODO: In a real implementation, we'd also search THROUGH message bodies`
   (`MessagingPageView.kt:404-424`, comment at `:417`). Searching for a word inside a conversation
   returns nothing.
3. **MMS is faked.** Attaching media to an SMS thread shows *"MMS feature simulation: Multi-media
   sent"* and sends nothing (`ConversationActivity.kt:842-845`). The user is told a photo went out.

---

## 11. Dataset downloads (Nora / Aether training data)

**What it does.** Periodically discovers datasets on Hugging Face/GitHub, downloads them (optionally
via a shallow JGit clone with an LFS-pointer fallback), and routes images to Nora+Aether and
text/parquet to Aether.

**Exact user path.** `Settings → AI Engine → Dataset downloads`
(`DatasetDownloadSettingsActivity`, manifest `:301`) → toggle →
`DatasetDownloadWorker.schedule` (`PrismApp.kt:398`), periodic
(`DatasetDownloadWorker.kt:73-95`).

**Status: WORKING.**

Evidence: unmetered + battery constraints (`:80-83`), interval re-checked inside `doWork` (`:31-35`),
already-downloaded dedupe (`:44-48`), real JGit implementation (`GitCloneProvider.kt:16-29`),
LFS-pointer detection and per-file HTTP fallback (`DatasetDownloader.kt:28-41`, `:172`).

**Bugs.**

1. **The worker reports success even when every download failed.** `Result.success()` unconditionally
   (`DatasetDownloadWorker.kt:62`); files that fail are "logged and skipped"
   (`DatasetDownloader.kt:69-71`). A permanently unreachable host therefore never backs off
   (`Result.retry()` is only used for discovery failure, `:37-42`).
2. **Only `.png/.jpg/.jpeg/.txt/.parquet` are routed** (`DatasetDownloader.kt:44-45`, `:60-67`);
   anything else is downloaded then dropped on the floor with no message, which for a
   webdataset/`.tar`/`.jsonl` repo means a "successful" dataset with zero files.

---

## 12. NPU support

**What it does.** Reports what neural accelerator the device has, whether Prism can use it, and why
not — Qualcomm Hexagon only, and only when built with the Hexagon SDK.

**Exact user path.** `Settings → AI Engine → (accelerator row)` (`SettingsActivity.kt:1781`).

**Status: PARTIAL by design.**

Evidence: honest vendor table (`NpuSupport.kt:57-97`), version floor for `ggml-hexagon`
(`:41`, `:138-145`), build-time question answered by native (`:147-152`,
`GgufInferenceService.kt:145-149`), and `nativeHasHexagonSupport()` returns false when the bridge
is absent (`:146-148`).

**Bugs.**

1. **In every build that does not set `HEXAGON_SDK_ROOT`, the feature is inert** — `usable` is
   always false (`NpuSupport.kt:147-152`). That is documented and correct, but it means "NPU
   support" is a Settings row that can never be enabled in a normal build; there is no runtime
   enable path (`AI_BACKEND` values are CPU/GPU only, `LocalAiService.kt:57-61`).
2. **`gpuMode` is the *backend* setting, not a GPU/NPU mode.** `ensureLoaded` passes
   `PrismSettings.getAiBackend()` straight through as `gpuMode` (`GgufInferenceService.kt:375`,
   `:461`), and Tier 2 forces `0` (`:436`). Whatever value backs "NPU" in settings would be
   interpreted by native as a GPU mode; there is no branch that maps an NPU selection to anything.

---

## 13. Quantization

**What it does.** Requantises an on-device GGUF with `llama_model_quantize` (streaming, so a 7B file
fits), in a foreground service with a real progress bar derived from output-file growth, then
registers the result as an imported model.

**Exact user path.** `Models` page → "Quantisation" section (`ModelsPageView.kt:60-157`) →
`QuantizationView` (`QuantizationView.kt:359-364`) → `QuantizationService.start`
(`QuantizationService.kt:366-371`). Desktop equivalent: `QuantizePage.kt:71-210`.

**Status: WORKING.**

Evidence: real native call (`PrismQuantizer.kt:170-181`), output validation and cleanup on failure
(`:176-185`), free-space pre-check with margin (`QuantizationService.kt:82-91`, `:217-223`),
foreground service correctly declared with `specialUse`
(`AndroidManifest.xml:402-404` + `FOREGROUND_SERVICE_SPECIAL_USE` at `:20`), progress capped below
100% until the native call returns (`:109-114`), cancellation documented honestly (`:162-178`).

**Bugs.**

1. **Quantising a model that lives outside `filesDir/models` produces a registered model that the
   Models page then deletes from the registry.** `destinationFor` writes *beside the source* when
   that directory is writable (`QuantizationService.kt:199-200`) — the normal case for an imported
   model — and `importResult` registers that path (`:226-238`). `ModelsPageView.loadModels` then
   keeps only paths inside `filesDir/models` and rewrites the registry (`ModelsPageView.kt:168-193`),
   so the freshly quantised model disappears from the list on the next visit (the file survives).
2. **The progress estimate reads the source's quantisation from its filename.** `sourceBitsPerWeight`
   (`PrismQuantizer.kt:110-115`, `:134-149`) defaults an unrecognised name to F16, so an
   unconventionally named Q4 source makes the estimated final size 4× too large and the bar sits at
   ~25% until it jumps to done.
3. **"Export" hands the file to the launcher's exporter only when hosted by `LauncherActivity`.**
   `ModelsPageView.kt:125-126` sets `onExportRequested = { host?.exportQuantisedModel(it) }` with
   `host = context as? LauncherActivity` (`:48`). In any other host the button does nothing
   silently.

---

## 14. Agentic tools + builtin tools

**What it does.** A backend-agnostic tool-calling loop (up to 5 iterations) over imported HTTP tools
and 13 builtin tools, dispatched from `AiManager` whenever `getAgenticToolsEnabled()` is on and no
image is attached.

**Exact user path.** `Agentic Tools` desktop page (`SlotAssignment.AgenticTools`, 
`MainDesktopPagerAdapter.kt:111`) → master switch (`AgenticToolsPageView.kt:66-69`) → then any Sam
turn. Tool CRUD/import and a per-tool "test" dialog live on the same page (`:46-52`, `:146+`).

**Status: PARTIAL.**

Evidence it is real: the loop runs tools for real and feeds results back
(`AgenticEngine.kt:88-109`, `:166-182`), HTTP tool execution with placeholder substitution and
result truncation (`AgenticToolExecutor.kt:29-74`), capability gating of tools before they are even
offered (`AgenticEngine.kt:33-35`), and every platform hook assigned in `PrismApp`
(`:296-338`).

**Bugs.**

1. **`make_call` can never succeed: `CALL_PHONE` is never requested at runtime.** The tool checks the
   permission and returns *"CALL_PHONE permission not granted"* (`AgenticMessagingTools.kt:128-130`),
   and the only messaging permission request lists `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`,
   `READ_CONTACTS` (`LauncherActivity.kt:308-315`). `CALL_PHONE` is declared in the manifest
   (`:42`) but no code path ever asks for it, so the tool is permanently broken while the model is
   still offered it (gating only checks `hasActiveCellularLine()`, `AgenticBuiltinTools.kt:348-352`).
2. **The local tool-calling loop duplicates its entire context every round, into an engine that
   already remembers it.** `runLocal` builds a growing plain-text `transcript` and re-sends the
   *whole* thing on each of up to 5 iterations (`AgenticEngine.kt:163-181`), justified by "Local
   models have no message-history API" (`:161-162`). That premise is false on both Android
   runtimes: `LocalAiService` caches a live `LlmInferenceSession` keyed on the model path and
   appends each call to it (`LocalAiService.kt:35-39`, `:201`, `:245`), and `GgufInferenceService`
   keeps the conversation in its KV cache with an explicit `resetConversation()` for exactly this
   case (`GgufInferenceService.kt:242-247`). Nothing in `AgenticEngine` calls it. So round 2 is
   given the transcript twice, round 5 five times, on top of whatever the previous user turn left
   behind — duplicated instructions and tool results until the context window truncates the front
   silently. (`Android`'s generator adapter is real, so `.task` models do run here —
   `PrismApp.kt:291-292`, `AndroidLocalAi.kt:33-48`; that part is fine.)
3. **Tool calling is silently skipped whenever an image is attached.** `AiManager.kt:112` requires
   `imageUri == null`; the reason is documented, but the user is not told, so a multimodal turn
   quietly loses every tool.
4. **`p2p_host` fails without explanation on desktop and can 404 on Android** — it calls
   `P2pDnsManager` through a hook installed only in `PrismApp.kt:296` (desktop leaves it null →
   *"P2P hosting is not available on this platform yet"*, `AgenticBuiltinTools.kt:220-221`).
5. **A failed tool result is fed back as if it were data.** `AgenticToolExecutor` returns error
   strings as normal results (`:24-26`), and the engine appends them to the transcript
   (`AgenticEngine.kt:180`); a model can (and typically will) narrate the error as an answer.

---

## 15. CakeChat

**What it does.** An optional, third-party seq2seq dialogue model (emotion-conditioned), run through
a bundled Chaquopy Python 3.8 + TensorFlow 2.1 stack, with a converted-TFLite fast path, on-device
training, and zip import/export of the weights + vocabulary.

**Exact user path.** `Models` page → CakeChat row (only listed once `TRAINED`,
`ModelsPageView.kt:233-244`) → tap to activate (`:355-366`); training/install via
`CakeChatTrainingActivity` (manifest `:436`) and the `CakeChatTrainingService` (`:444`).

**Status: PARTIAL.**

Evidence it is real: pinned, justified Python/TF stack (`app/build.gradle.kts:73-107`), source
install with zip-slip guard (`CakeChatInstall.kt:223-292`), TRAINED detected by HDF5 magic, not by
name (`:203-211`, `:311-317`), vocabulary-completeness validation on import/export (`:302-308`,
`:486-494`), and a documented readiness check that does not boot TensorFlow
(`CakeChatEngine.kt:41-42`).

**Bugs.**

1. **There are no pretrained weights, so CakeChat cannot answer anything until the user trains it
   on the phone.** Stated in the code (`CakeChatInstall.kt:24-26`) — the upstream fetch links are
   dead. Combined with the memory note ("850 MB peak for the default width, which is more than
   Android hands one process", `:114-117`), the default path is: install a repo, train for hours at
   a reduced width, or import a bundle from a PC. The UI does say so, but "CakeChat" as a feature is
   effectively unreachable for a normal user.
2. **Install depends on a live GitHub branch tarball.** `ARCHIVE_URL` is
   `https://github.com/lukalabs/cakechat/archive/refs/heads/master.zip` (`:31`). If the branch moved
   to `main` or the repo is archived, `install` fails with a logged `HTTP 404`
   (`:230-232`, `:259-262`) and no user-facing reason — `CakeChatTrainingActivity` is where that
   surfaces, but the failure itself is only a log line.
3. **Selection is silently outranked.** Choosing a local model clears the CakeChat flag
   (`ModelsPageView.kt:288`) and choosing CakeChat clears the P2P selection (`:362`), but nothing
   clears CakeChat when the *mode* is switched to Cloud in Settings — `getAiMode()==CLOUD` bypasses
   the local branch entirely (`AiManager.kt:146`), so the CakeChat row can show as active while
   replies come from the cloud.
4. **`isReady` is a filesystem guess.** It only proves the repo directory and an HDF5 file exist
   (`CakeChatEngine.kt:41-42`); a wrong-width or truncated weights file passes and fails later
   inside the inference process (documented at `:37-39`, but the failure surfaces as a generic null
   → `AiManager`'s fallback message, `:211`).

---

## 16. SMS relay (and relayed conversations)

**What it does.** Forwards an incoming SMS, sealed with a key derived from the wallet, to the user's
other mesh devices, where it appears as a read/writable conversation.

**Exact user path.** `Settings → (SMS relay toggle)` (`PrismSettings.kt:1854`) → incoming text →
`SmsRelayReceiver` (manifest `:178-186`) → mesh broadcast (`SmsRelayReceiver.kt:59-74`); reading on
another device: `Messages` page → relayed thread row (`MessagingPageView.kt:378-395`) →
`ConversationActivity` with `RELAYED_THREAD_ID` (`:363`, `:375-387`).

**Status: WORKING.**

Evidence: receiver declared with the required `BROADCAST_SMS` permission and priority
(`AndroidManifest.xml:178-186`), multipart PDU concatenation (`SmsRelayReceiver.kt:42-46`),
refusal to send plaintext without a wallet (`:59-67`), storage and per-device thread ownership
(`Core SmsRelay` + `TrustedMessages.inboxRoot`), reply routed back through the owning device
(`ConversationActivity.kt:396-418`), and a 20-case test suite (`SmsRelayTest.kt`).

**Bugs.**

1. **Relay is off by default and the toggle has no permission prompt.** `getSmsRelayEnabled()`
   defaults false (`PrismSettings.kt:1854`) and the receiver returns immediately (`:36`). The toggle
   lives in Settings, but `RECEIVE_SMS`/`READ_SMS` are only requested from
   `LauncherActivity.requestMessagingPermissions()` (`:308-315`) — if the user never visits the
   Messages page, enabling relay produces no relayed texts and no explanation.
2. **A reply to a relayed thread can be lost silently.** If the owning device is unreachable, the
   text is only stored locally and a Toast says so (`ConversationActivity.kt:408-416`) — the message
   is not queued, so it is never retried and never appears on the phone with the SIM.
3. **`fromDevice` is `Build.MODEL`, not an identity.** Two identical phones become indistinguishable
   (`SmsRelayReceiver.kt:56`), so `maxByOrNull { receivedAt }?.fromDevice` (the reply routing key,
   `ConversationActivity.kt:400`) can pick the wrong device on a household with two of the same
   model.

---

## 17. Worst bugs, ranked

1. **Model shop can never deliver a purchased model** — seller writes to `Documents/Models`
   (`ModelUploadActivity.kt:187` → `P2pModelTransfer.kt:53`), serves from `filesDir/models`
   (`PrismAiHost.kt:238`); buyer's coins are committed anyway. Then the delivered file is never
   registered (`ModelCheckoutActivity.kt:386-390`) and `ModelsPageView.kt:189-191` prunes the
   registry. *Coins at risk, feature non-functional.*
2. **Cloud mode answers "video" prompts from a hardcoded mock** with a remote `w3schools` media URL
   (`AiManager.kt:147` → `CloudAiService.kt:21-44`). *Fabricated content presented as model output.*
3. **A held purchase can lock a buyer's coins permanently** — refund window closes at 3 h
   (`ModelRefunds.kt:70-73`), re-buy is refused (`ModelPayments.kt:85-90`), and settlement only runs
   at process start (`PrismApp.kt:482-495`). *Irrecoverable state; no UI exits it.*
4. **Compute market's single-peer path is a permanent no-op** — `MeshBridges.generateOnPeer` is never
   assigned (`MeshBridges.kt:145`; compare `AndroidMeshBridges.kt:86/135/169`), so `runOnPeer`
   always returns null (`MeshInference.kt:242`). *Feature silently does nothing, and `tokensServed`
   never increments.*
5. **Android vision returns canned bracket strings** (`VisionService.kt:14-29`), and they are handed
   to the social bots as their description of the image (`NebulaComposeActivity.kt:180-186`) while
   the real `Vision` registry sits unused (`PrismApp.kt:82-87`; only desktop calls it).
6. **`requiredBytes` ignores the model file** (`GgufInferenceService.kt:647-658`) — every model
   "needs" ~1.58 GB, so the load guard and the entire Prism-Swap ladder are size-blind, and the
   "Insufficient RAM" message quotes a constant (`LocalAiService.kt:171-177`).
7. **Cloud image generation builds `…/v1images/generations`** for any base URL without a trailing
   slash (`CloudAiService.kt:364` vs `:51`).
8. **`make_call` is permanently blocked** — `CALL_PHONE` is never requested
   (`AgenticMessagingTools.kt:128`, `LauncherActivity.kt:308-315`).
9. **The model store's GitHub provider is dead** (unauthenticated code search,
   `ModelDiscoveryService.kt:120-127`), and its Google provider's "vision" files get installed as
   *image generators* (`ModelDiscoveryService.kt:156-166` + `ModelStoreView.kt:161`).
10. **Cloud Sam has no memory** — `history` reaches `AiManager` and is only used for CakeChat
    (`AiManager.kt:93`, `:202-204`; `ConversationActivity.kt:800`).

---

## 18. Cross-cutting observations

- **Two (arguably three) model directories.** `filesDir/models` (Android download + import +
  `ModelsPageView`), `documentsDir()/Models` (`ModelRegistry.directory()` → P2P transfer + listing +
  desktop), plus the documented legacy `dataDir/models` (`ModelRegistry.kt:69`). `ModelRegistry`'s
  docstring claims this was fixed; on Android the three consumers still disagree, and the Android
  repair path (`reconcile`) is never called.
- **Cloud API keys are stored in plain SharedPreferences**, serialised into a `::`-joined string
  (`PrismSettings.kt:1777-1798`). No keystore encryption, unlike `WalletKeystoreCipher` used
  elsewhere in the app. Also, `::` is the field separator, so a key or model id containing `::`
  corrupts the whole list on read (`:1781-1784`).
- **Duplicate engine layers.** Each capability has a "legacy" Android implementation and a newer
  `:core` registry: `VisionService` vs `Vision`, `ImageGenManager` vs `ImageGeneration`,
  `VoiceInputController` vs `Dictation`, `AiManager` vs `SamConversation`, `PrismAiHost` vs
  `MeshAiHost`. In each pair the newer one is registered and the older one is the one wired to the
  UI — the exact "engine registered but never selected" shape asked about, four times over.
- **Hardcoded endpoints exist but no hardcoded credentials.** Grep for key-shaped literals
  (`sk-…`, `AIza…`, `Bearer <token>`) found none. Hardcoded URLs that matter:
  `picsum.photos` + `w3schools` mock media (`CloudAiService.kt:31`, `:35`), Hugging Face / GitHub
  APIs (`ModelDiscoveryService.kt:72`, `:120`), CakeChat GitHub tarball (`CakeChatInstall.kt:31`),
  whisper.cpp model host (`WhisperEngine.kt:227`).
- **Settings with no UI on Android:** whisper model path, vision/dictation/image engine choice
  (`PrismSettings.kt:1818-1845`; no `set*` call sites in `SettingsActivity`).
- **Comment/code drift worth flagging to maintainers:** `ModelRegistry.kt:48-59` (directory claim),
  `Vision.kt:14-20` ("replaces VisionService"), `P2pModelTransfer.kt:24-28` (modelsDir "shared with
  the store's downloads"), `ModelListingScanner.kt:23-25` (accuracy claims are accurate),
  `GgufInferenceService.kt:630-646` (`requiredBytes` doc describes behaviour the code does not
  implement).

---

## 19. Limitations of this audit

- **No build or runtime execution.** Every `pwsh` invocation failed at workspace provisioning
  (`SetNamedSecurityInfoW failed (Win32 5): grantWrite(F:\AndroidStudioProjects\Prism)`), so I could
  not compile, run tests, or inspect the APK. Approval to retry unconfined is disabled for this
  session, so this is reported rather than retried. All statuses are static-analysis verdicts.
- Several `grep`/`glob` calls timed out on repo-wide patterns (build output and `.git` appear to be
  in scope for the walker); narrow-path calls were used instead. Absence claims ("never called",
  "never assigned") are based on full-repository greps of `*.kt` that returned complete results, and
  exclude `core/bin/**` and `desktop/bin/**` copies of the same sources.
- `core/bin` and `desktop/bin` contain stale compiled copies of several audited files (e.g.
  `core/bin/main/com/prism/launcher/agentic/AgenticBuiltinTools.kt` is 30 lines shorter than its
  `src` counterpart). Only `src/main` was audited; the `bin` copies are not what the build uses, but
  they will confuse anyone grepping the tree.
- Not audited (out of scope or unreachable): native `gguf_bridge.cpp` internals, `MeshCore`
  transport correctness, the payment chain (`PscChain`) itself, Nora's and Aether's brains beyond
  their conversation plumbing, and the cloud gaming/storage pay-later paths.
