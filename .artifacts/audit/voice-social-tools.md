# Prism audit — assistants, speech, social, productivity/tools

Scope: `assistants (Nora, Aether, Characters)`, `speech (Kokoro, cloning, call mode)`, `social (Nebula, Lyke, bots)`,
`productivity/tools (Language, Writer, Editor, Science, Minigames, Stremio)` plus their desktop (`:desktop`) counterparts.

Read-only audit. No source file was modified; the only write is this report.

## Confirming statuses

### Conventions

| Status | Meaning in this report |
|---|---|
| **WORKING** | The whole path (UI → state → engine/backend → output) exists in code and I found no defect that would stop it working on a normal device. Not runtime-tested — see limits. |
| **PARTIAL** | The feature works but a documented sub-part is missing, gated, stale, or only true on one platform. |
| **BROKEN** | A defect stops the feature or a named sub-feature from doing what its UI claims, in a way I can point at. |
| **SCAFFOLD-ONLY** | UI/state exists, the backend does not. |

### Limits of this audit (read before trusting a status)

1. **No build, no run.** Every shell command in this session failed before executing:
   `SetNamedSecurityInfoW failed (Win32 5): grantWrite(F:\AndroidStudioProjects\Prism)`. The sandbox could not
   provision write access to the workspace, and approvals are disabled, so `gradlew`, `adb`, unit tests and APK/ABI
   inspection were all unavailable. Every status below is **static reading**, not observed behaviour.
2. **Network checks were limited to one probe.** `https://github.com/ibrahim-hmood/Prism/releases/download/voice-engines-v1/flow_lm_main_int8.onnx`
   redirects to `release-assets.githubusercontent.com`, i.e. the release object exists at the time of the audit. I did
   **not** verify the other ~13 weight URLs, their sizes, or their contents.
3. **Comments were treated as claims, not evidence.** Several are wrong (e.g. §Nora self-test, §desktop launcher mode).
   Where a comment and the code disagree, the code wins and the disagreement is listed as a finding.
4. `F:\...\core\bin\main\**` and `F:\...\desktop\bin\main\**` contain **stale duplicate copies of the sources** inside
   the workspace (different line numbers, older content). All citations below are from `src/`, never `bin/`.

---

## Status table

| # | Group | Status |
|---|---|---|
| 1 | Kokoro-82M TTS + voices | **WORKING** (one documented-broken export left in the picker) |
| 2 | Voice cloning engines (OpenVoice / FreeVC / kNN-VC / Seed-VC / Pocket TTS / custom folders) | **PARTIAL** |
| 3 | Call mode (spoken Sam / Nora / Aether) | **WORKING**, with a dead fallback voice on API 30+ (BUG-3) |
| 4 | Nora chat | **WORKING** |
| 5 | Nora training | **BROKEN** reporting (real training; false success) — BUG-1 |
| 6 | Nora brain view / self-test | **WORKING** |
| 7 | Nora video (and hallucination) | **WORKING** (encoder edge case — BUG-11) |
| 8 | Aether brain + training | **PARTIAL** (real training; false success summary — BUG-2) |
| 9 | Aether knowledge sharing (AKEF) + mesh | **PARTIAL** (receive is staged, merge off by default) |
| 10 | AI characters | **WORKING** |
| 11 | Nebula social | **PARTIAL** (every post/comment/DM is AI-invented; engagement is random — BUG-7) |
| 12 | Lyke short video | **WORKING** |
| 13 | Social bots worker | **PARTIAL** (reports success for no-op cycles — BUG-7) |
| 14 | Language learning | **WORKING** |
| 15 | Prism Writer keyboard (swipe / autocorrect / live translate) | **WORKING** on Android; **PARTIAL** vs desktop (no translate) |
| 16 | Code editor / terminal / Node | **PARTIAL** (arm64-only, 80 MB download + 1.1 GB free) |
| 17 | Science instruments + protein folding | **PARTIAL** (instruments real; folding needs user training) |
| 18 | Minigames (chess / pong / paper war) | **WORKING** |
| 19 | Stremio | **WORKING** (playback lives inside Lyke, no player screen of its own) |
| 20 | Desktop ↔ Android parity | **BROKEN in launcher mode** (BUG-4) |

---

## 1. Kokoro-82M TTS + voices — WORKING

**What it does.** On-device neural TTS. Text → IPA (lexicon) → token ids → ONNX `Kokoro-82M` with a per-voice
510×256 style tensor → 24 kHz mono audio, streamed sentence by sentence into the platform audio sink. Five preset
engines' voices and any cloned voices share the same path (`PrismSpeaker` → `KokoroEngine`).

**User path.**
Android: `SettingsActivity` → Kokoro settings (`KokoroSettingsActivity`, manifest `app/src/main/AndroidManifest.xml:574-578`);
voices picked through `KokoroVoicePicker.show(activity, speaker)`. Speak from **Call mode**
(`.speech.CallModeActivity`), Writer dictation replies, Language lessons, Messages/Nora/Aether speech.
Desktop: rail → **Speech** → *Kokoro settings* (`KokoroSettingsPage.kt:109`) or **Speech** (`SpeechPage.kt`).

**Evidence it is real.**
- Downloads: `core/src/main/kotlin/com/prism/launcher/speech/KokoroInstall.kt:162-183` fetches
  `tokenizer.json`, the misaki lexicon (`:56-57`), and `onnx/<variant>.onnx` from
  `onnx-community/Kokoro-82M-v1.0-ONNX` (`:42`, `:47`); `:186-191` fetches `voices/<id>.bin` on selection.
  All writes are staged to `.part-<nanoTime>` and renamed (`:201`, `:231-234`).
- Inference: `KokoroEngine.kt:266-316` builds `input_ids` / `style` / `speed` tensors, binds them **by name**
  (`:291-305`) and reads the first output; `:336-364` reads the style row at `tokenCount*256*4 + skip` bytes.
- Progressive playback: producer/consumer pair with a 4-slot queue (`:87-142`), cancellation by generation counter
  (`:63-64`, `:440-441`), and one completion callback per utterance.
- Voices are enumerated from `KokoroVoices` (54 ids, language/gender/quality), grouped by language in the picker
  (`KokoroVoicePicker.kt:65-80`).
- Desktop uses the same class: `desktop/.../ui/KokoroSettingsPage.kt` and `desktop/.../Main.kt:3515-3582`
  (`prism speak`, `prism voice download`).

**Bugs / caveats.**
- `KokoroInstall.kt:110-116` deliberately keeps a **known-broken export** (`model_q8f16`, 86 MB) in the list,
  labelled "(BROKEN: this export is missing a weight)". Correct decision, but it means one of four choices in the
  Quality picker always fails at first sentence with `Missing Input` (`KokoroEngine.kt:247-254`). Default is
  `model_quantized` (`core/.../PrismSettings.kt:2320`), so the default path is fine.
- `KokoroEngine.kt:348`: `input.skip(offset)` return value ignored. A short skip silently reads a *different* style
  row (wrong pacing/prosody) instead of failing. Low severity (`FileInputStream.skip` normally fulfils), but it is
  the one unguarded I/O assumption in the style path.
- No checksum on any download (`KokoroInstall.fetch`, `:193-242`), only `length() > 0`.

---

## 2. Voice cloning engines — PARTIAL

**What it does.** Kokoro cannot be cloned into, so a clone stores the *recording* plus a base Kokoro voice, and one of
five engines re-voices Kokoro's audio toward the speaker (OpenVoice, FreeVC, kNN-VC, Seed-VC) or speaks the text
itself in the speaker's voice (Pocket TTS). Users can also point Prism at a folder of their own ONNX models.

**User path.**
Android: `VoiceCloningActivity` (manifest `:580-584`) — record/upload → `VoiceCloner.profile` → engine downloads →
save → the clone appears under **YOUR VOICES** in every voice picker.
Desktop: rail → **Speech** → *Voices* → `VoiceCloningPage.kt:69` (`PageId.VOICE_CLONING`, `PrismWindow.kt:183`).

**Evidence it is real (and not a stub).**
- `VoiceConverter.kt` really implements OpenVoice v2 end to end: linear magnitude STFT (Hann 1024 / hop 256 /
  384-sample reflect pad, `:205-235`), own radix-2 FFT (`:273-315`), encoder (`:76-102`) and converter (`:110-158`)
  with the correct frames-major vs channels-major tensors (`:82-86`, `:123-127`).
- `SeedVcEngine.kt` really runs six graphs including a CFG Euler loop (`:319-363`) and HiFT vocoding (`:366-381`).
- `PocketTtsEngine.kt` really runs an autoregressive flow LM with 18 state tensors (`:403-421`), per-frame
  diffusion (`:603-643`) and a 56-tensor Mimi decoder (`:162-219`, `:648-688`).
- `CustomCloneEngine.kt` is a genuine generic ONNX wrapper with a `prism.engine.json` manifest (`:23-88`).
- `ClonedVoices.kt` stores reference PCM16 + pitch + per-engine caches, and rolls back a failed save (`:207-261`).

**Bugs / caveats.**
- **BUG-5 (High): FreeVC conditions short clones on mostly silence.** `FreeVcEngine.kt:132-137` pads/cuts every
  reference to a fixed `REF_SAMPLES = 128_000` (8 s @16 kHz). `VoiceCloner.kt:82-87` accepts recordings from
  **0.75 s**. A 1-second clone therefore feeds 7 seconds of silence into the WavLM speaker encoder; the converted
  audio reverts toward the model's average voice, and nothing warns the user. Concrete failure: "clone my voice"
  from a short sample sounds like a stranger.
- **BUG-6 (Medium): install detection requires exact byte-length equality.**
  `VoiceCloningModels.kt:42-43`: `it.length() == model.sizeBytes`. Any artefact revision, CDN re-pack, or
  interrupted-then-resumed copy leaves `isInstalled() == false` **forever**, so each save re-downloads up to 358 MB
  inside `ClonedVoices.save` (`:224-232`) and the UI shows "Fetching FreeVC… 100%" repeatedly. There is no digest
  anywhere in `Model` (`:28-36`).
- **BUG-8 (Medium): 3 of 5 engines' weights live on an unrelated third-party GitHub release.**
  `PocketTtsEngine.kt:92-93`, `SeedVcEngine.kt:73-74`, `FreeVcEngine.kt:50-51` all use
  `https://github.com/ibrahim-hmood/Prism/releases/download/voice-engines-v1`. Nothing in this repository builds or
  verifies them; there is no hash. A renamed/taken-down release turns three engines into "download failed"
  (`VoiceCloningModels.kt:148-152`). At audit time the asset URL did redirect (release exists).
- Size reality check: Pocket TTS ≈ 160 MB (`:95-143`), Seed-VC ≈ 267 MB (`:76-117`), FreeVC 358 MB (`:53-59`,
  single file), OpenVoice ≈ 131 MB. On a phone, choosing an engine is a 130–360 MB download; only OpenVoice is
  realistically sized.
- `PocketTtsEngine.convert` throws by design (`:816-826`) — correct, but it means the "engine" dropdown mixes
  converters and text-to-speech engines in one list (`CloneEngines.kt:23`), which the user cannot tell apart from the
  labels alone.

---

## 3. Call mode — WORKING (fallback voice broken, BUG-3)

**What it does.** Full-duplex spoken conversation: `SpeechRecognizer` → the selected persona (Sam via `AiManager`,
Nora via `NoraService`, Aether via `AetherService`) → `PrismSpeaker` → listen again.

**User path.** Messages/Nora/Aether screen → call/loudspeaker action → `CallModeActivity`
(manifest `:568-572`, not exported).

**Evidence.** Permission is requested at first use with the recognizer gated on availability
(`CallModeActivity.kt:95`, `:225-228`); partial results are enabled (`:303`); recognizer errors are classified
(`:276-292`); the turn loop ends in exactly one place (`:341-344`); replies route per speaker
(`:348-364`), waiting on the service's busy flag rather than a timer (`:366-420`).

**BUG-3 (High): the system-voice fallback is unreachable on Android 11+.**
`SystemTtsEngine.kt:68-89` calls `PackageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"))`
to find a usable engine when the default TTS engine is unset. With `targetSdk = 35`
(`app/build.gradle.kts:16`), package-visibility filtering applies and the manifest's `<queries>` block
(`AndroidManifest.xml:59-75`) declares only `MAIN/LAUNCHER`, `com.prism.launcher.action.DESKTOP_PAGE` and
`android.speech.RecognitionService`. **`android.intent.action.TTS_SERVICE` is missing**, so the query returns an
empty list, the code logs "No speech engine is installed" (`:78`) and call mode stays silent — on exactly the device
state the fallback exists for (engine installed, default unset). Fix: add
`<intent><action android:name="android.intent.action.TTS_SERVICE"/></intent>` to `<queries>`.

---

## 4. Nora chat — WORKING

**What it does.** A conversational shell over the Nora brain: slash commands for state/control and a default path that
generates images/video from the connectome.

**User path.** Messages page → Nora column/thread (`SlotAssignment.Messaging`) → `NoraChat`; desktop
`PageId.NORA_CHAT` → `NoraChatPage()` (`PrismWindow.kt:152`, `desktop/.../ui/NoraPages.kt`).

**Evidence.** Command dispatch is real and complete: `/status` reads trained state, dataset size, feedback
(`NoraChat.kt:165-176`), `/train` opens the training activity (`:178-195`), `/forget` wipes connectome + chat store
(`:197-201`), `motion:`/`video:` are validated against `Mst.TEMPLATE_NAMES` (`:203-222`), and everything else goes
to `generateImage` (`:226-268`) which streams saccade progress. Generation is the brain's own predictive-coding
imagery (`NoraStudio.kt:230-245`), not a diffusion model — the reply text says so.

**Caveats.** Output quality is entirely a function of training (§5) and there is no LLM fallback in this path, so an
untrained brain answers with abstract images. That is a design choice, not a bug, but it makes chat unusable until a
dataset exists.

---

## 5. Nora training — BROKEN reporting (training loop itself is real)

**What it does.** Two-phase training (wake: per-image predictive-coding learning + hippocampal imprint; sleep: replay
+ synaptic downscaling), driven from the dataset folder where the filename is the caption.

**User path.** `NoraTrainingActivity` (manifest `:217-224`) or `/train` in chat; autonomous variant
`NoraAutoTrainWorker` (WorkManager, idle + battery-not-low, `NoraAutoTrainWorker.kt:104-119`).

**Evidence it is real.** `NoraTrainer.kt:131-201`: shuffled epochs, three fixations per image with random fovea
(`:149-165`), optional denoising curriculum with a fresh corruption strength per fixation (`:158-160`), caption binding
(`:166-168`), sleep phase every third epoch (`:206-209`, `:263-282`), checkpoint after every epoch (`:220-223`), and a
divergence abort on `NoraHealth`/non-finite error (`:186-196`).

**BUG-1 (Critical): a run that learns nothing reports "Training complete" and marks the brain trained.**
- `NoraTrainer.kt:137`: `val bitmap = decodeScaled(item.file) ?: continue` — an undecodable image is skipped silently.
- `:128` `var trained = 0` is incremented only on success (`:170`) and **never checked**.
- `:186` `lastError` is initialised to `0f` (`:127`), which is finite, so the divergence guard cannot fire on a run
  that never trained.
- `:220-223`: `NoraPersistence.save(brain)` and `if (NoraHealth.healthy) NoraPersistence.markTrained()` run
  unconditionally after each epoch.
- `:226-249`: returns `"Training complete.\n\n$trained presentations over $epochs epochs on ${dataset.size} images."`
  even when `trained == 0`, and only appends the "no IT pattern" warning when `unbound > 0` (a decode failure never
  increments `unbound`).

**Concrete failure.** Put HEIC/CMYK-JPEG/truncated PNGs (or photos too large for the heap, `decodeScaled` returns
null) in `Prism/Nora/dataset/`, run 20 epochs: the UI reports "Training complete. 0 presentations over 20 epochs on
12 images" and `/status` then says "Connectome loaded" (`NoraChat.kt:166-171`), while generation is blank/noise
because nothing was learned. The user has no signal that the dataset was rejected.

**Fix.** `if (trained == 0) return "Nothing trained: none of the N images could be decoded…"` before the save, and
don't `markTrained()` when `trained == 0`.

---

## 6. Nora brain view / self-test — WORKING

**What it does.** `NoraBrainView` renders the live hierarchy; `NoraSelfTestActivity` trains a throwaway brain on nine
generated shapes and reports fit / trains / renders.

**User path.** Nora settings → *Model test* (`NoraSelfTestActivity`, manifest `:234-240`); brain visualiser from the
Nora chat header.

**Evidence.** The test is genuinely sandboxed: its own `NoraBrain`, `sandbox = true` so no connectome is loaded,
saved, or marked trained (`NoraTrainer.kt:81-87`, `:110-123`), and it reports three separate measurements
(`NoraSelfTestActivity.kt:19-35`).

**BUG-10 (Low): comment contradicts code.** `NoraSelfTestActivity.kt:52-53` says "Saccadic is the default because it
is Nora's" but the field is `NoraImageryMode.DETERMINISTIC` (`:53`). The test therefore measures a different
generation route than the comment promises.

---

## 7. Nora video — WORKING

**What it does.** Generates frames through the brain (`MentalImagery.generateVideo` / `generateHallucination`) and
muxes them to H.264 MP4.

**User path.** `video: <prompt>` or `/video` in Nora chat (`NoraChat.kt:218-222`); desktop Nora tools page.

**Evidence.** `NoraVideoWriter.kt` is a complete MediaCodec byte-buffer encoder: colour format negotiated from the
codec (`:57-58`, `:161-172`), NV12 **and** I420 written correctly (`:178-209`), muxer started on
`INFO_OUTPUT_FORMAT_CHANGED` (`:122-128`), EOS queued (`:100-107`), bounded idle spins so a stuck codec cannot hang
(`:111-121`), output deleted on failure (`:147`). Desktop uses JCodec instead (`desktop/build.gradle.kts:78-87`).

**BUG-11 (Low): frames must all match frame 0, and that is not checked.**
`NoraVideoWriter.kt:46-47` derives width/height from `frames[0]` and `:92` uses that stride for `getPixels` on every
frame. One differing frame throws inside the `try`, the `catch` deletes the output (`:147`), and the whole clip is
lost with only a log line.

---

## 8. Aether brain + training — PARTIAL

**What it does.** A second, separate brain-inspired AI (spiking neurons, backprop or STDP/biologically plausible
mode), trained on image+text pairs, served from its own `:aether` process.

**User path.** Aether page (`AetherPage.kt`) → **Train** (`AetherTrainingActivity`, manifest `:275-282`);
desktop `PageId.AETHER` → `AetherPage()`.

**Evidence it is real.** `AetherTrainer.kt:195-275` runs epochs with a real forward/backward (`trainBackprop`) or
STDP update (`trainStdp`), a **reverse pass** for flashcards (`:231-235`), sleep every third epoch (`:245-248`),
held-out validation from `dataset/validation/` (`:253-259`), a one-shot final test from `dataset/testing/`
(`:280-292`), checkpoints at epoch 1 / interval / last (`:265-274`), and a genuine cross-entropy-over-slots loss
(`:315-340`). The process split is real (`AndroidManifest.xml:313-321`) and the UI is told not to build a second
connectome (`AetherStudio.kt:73-86`).

**BUG-2 (High): same false-success pattern as Nora.** `AetherTrainer.kt:236-241` catches every per-sample exception
and only logs it; the return at `:294` is unconditional:
`"Trained ${dataset.size} sample(s) over $epochs epochs … Final loss %.4f."`. If every sample throws (bad images,
tokenizer failure, OOM pressure below the fatal threshold), the user sees "Trained 40 samples … Final loss 0.0000"
and a `weights` file written at `:267`. There is no `trained == 0` guard and no failure counter.

**Caveats.**
- `AetherStudio.brain()` is still callable from the main process (brain visualiser / generation), which contradicts
  its own doc comment (`:73-86`) and can build a second connectome — the exact heap pressure the `:aether` split
  exists to avoid.
- Aether's output is a 95-character slot codec (`AetherTextCoding.ASCII_LOW..HIGH`, `:319-322`), so "chat" is
  constrained vocabulary, not free text.

---

## 9. Aether knowledge sharing (AKEF) + mesh — PARTIAL

**What it does.** Exports a trained connectome as a snapshot, serves it over LAN HTTP and the mesh tunnel, peers
fetch it, and the receiver merges it into a trained brain.

**User path.** `AetherSettingsActivity` → *Knowledge sharing* (`AetherSettingsActivity.kt:622-673`) — LAN peers row
"LAN"/"Mesh", fetch-and-stage per peer, `AetherMeshSync.announce/revoke`.

**Evidence.** `AetherKnowledgeSync` provides `start(share, receive)`, `exportSnapshot`, `serveHttp`, `stageBlob`,
`fetchAndStage`, `applyPending`, `broadcastDiscover`; the HTTP surface is reached through reserved hostnames
(`AetherMeshSync.AETHER_HOST_DOMAIN`) intercepted by both the proxy and the mesh
(`PrismProxyServer.kt:217-219`, `:281-283`, `:338-340`); exports happen during training (`AetherTrainer.kt:268-273`,
`:287-291`).

**Caveat — the important one: merging is off by default.** `AetherConfig.kt:46` sets
`var akefAutoMerge: Boolean = false`, and `applyPending` is only called when it is true
(`AetherStudio.kt:63-67`, `AetherService.kt:209`). So a default install will **export and receive** snapshots and
never apply them, which from the UI ("shared with 2 peers") looks like knowledge sync working when the learned weights
never change. The comment explains the reason (merging independently initialised connectomes destroys both), but the
default is silently receive-only.

---

## 10. AI characters — WORKING

**What it does.** User-created characters with a name, prompt, backdrop image or imported 3D model, holding their own
conversation in front of that stage.

**User path.** Characters page → *Create* (`CharacterCreatorActivity`, manifest `:430-433`) → a character row opens
`CharacterConversationActivity` (manifest `:449-453`).

**Evidence.** `CharacterStore` is the model store; the conversation screen decodes the backdrop image or mounts the
same `AetherModelView` used by Aether (`CharacterConversationActivity.kt:68-90`), has a back-handled model stage
(`:49-51`), and answers through `AiManager` (`:19`, imported). Missing character is handled with a real message
(`:57-65`). Desktop counterpart is `CharactersPage.kt` wired at `PrismWindow.kt:179`.

**Caveat.** Its quality is exactly the configured model's quality (local GGUF / MediaPipe / cloud); nothing
character-specific is faked.

---

## 11. Nebula social — PARTIAL (simulated network, fabricated engagement)

**What it does.** A Twitter-like feed + comments + DMs + follow graph stored in Room, populated **entirely** by AI
personas the model invents, alongside the user's own posts.

**User path.** `NebulaSocialPageView` on the `SlotAssignment.NebulaSocial` desktop slot; compose via
`NebulaComposeActivity` (manifest `:683-689`); desktop `PageId.NEBULA` → `NebulaPage()`.

**Evidence.** Persistence and UI are real (`NebulaSocialManager.kt:82-94` inserts a `SocialPostEntity`;
`NebulaSocialPageView.kt:506-507` triggers `NebulaMeshSync.announce()/syncOnce()`), and mesh replication of posts is
wired (`PrismMeshService.kt:503`, `:545-549`). Personas are generated by the model, not hardcoded
(`NebulaSocialManager.kt:46-52`).

**BUG-7 (Medium): engagement numbers are fabricated.**
`NebulaSocialManager.kt:90-91`: `likesCount = (10..500).random()`, `repostCount = (1..50).random()`. Every AI post
therefore claims tens-to-hundreds of likes that no user gave, and the numbers never change. This is exactly the
"hardcoded placeholder content" class: the UI presents synthetic metrics as social proof.
Also note `personaVoiceBriefing` drives *all* content, so a fresh install's "community" is 3+ AI personas talking to
each other (`:18-22`, `MIN_BOT_FLOOR = 3`), and any real multi-user content only arrives over the mesh
(`NebulaMeshSync`), never the internet.

---

## 12. Lyke short video — WORKING

**What it does.** A vertical short-video feed: record/upload your own clips, play them full-screen, plus Stremio
add-on streams injected into the same feed.

**User path.** `LykeView` on its slot / `LykePage.kt` on desktop; `+` opens `showCreateSheet` (Record / Upload,
`LykeView.kt:264-273`); floating window via `LykePipActivity` (manifest `:701-710`).

**Evidence.** `VideoView` playback with only the visible page playing (`:33-34`, `:122`, `:293-302`), local file path
playback (`:374`), Stremio URL playback **with per-stream proxy headers** (`:354-373`), and an error state that names
the next step ("Another add-on may have a stream that does"). PiP is declared and configured
(`supportsPictureInPicture`, `resizeableActivity`, own task affinity, `:701-710`).

**Caveat.** `VideoView` (no ExoPlayer/Media3, per `:33-34`) means no HLS/DASH subtitle track support — reasonable
trade-off, but Stremio streams that are HLS will not play.

---

## 13. Social bots worker — PARTIAL

**What it does.** Periodic WorkManager job that asks `NebulaSocialManager` to invent/refresh content.

**User path.** Automatic; scheduled from `PrismApp.kt:390`, `LauncherActivity.kt:836/966`,
`SettingsActivity.kt:1844`; interval from `PrismSettings.getNebulaGenerationIntervalHours()`.

**Evidence.** Correctly cancelled when the Nebula slot is unassigned (`SocialBotWorker.kt:32-36`), interval applied
with `ExistingPeriodicWorkPolicy.UPDATE` (`:51-55`), network constraint only in cloud mode (`:41-43`).

**BUG-7b (Medium): the worker reports success for cycles that did nothing.**
`SocialBotWorker.kt:14-15` always returns `Result.success()` after `generateNewContent`; but
`NebulaSocialManager.kt:33-44` returns **early and silently** in local mode unless the device is both idle and
charging. So every run on an unplugged phone is a no-op recorded as a success. Also `Result.retry()`
(`SocialBotWorker.kt:17`) has no attempt cap, so a persistent failure retries with exponential backoff indefinitely.

---

## 14. Language learning — WORKING

**What it does.** An AI-tutor language course: setup wizard (native/target language, tutor persona, daily minutes),
a CEFR-ish syllabus rebuilt into a plan, spaced-repetition lessons, XP/streak stats, and spoken tutor voices.

**User path.** Language slot → `LanguagePageView`; setup `LanguageSetupActivity` (manifest `:586-591`); a lesson
`LessonActivity` (manifest `:593-598`). Desktop: `PageId.LANGUAGE` → `LanguagePage.kt:92`.

**Evidence.** The syllabus/plan/stats all live in `:core` (`LanguageCatalog`, `LanguageSetupFlow`, `LanguagePlanStore`,
`LanguageStats`), and the desktop page is driven by the same `LanguageSetupFlow.steps()` list as the phone
(`LanguagePage.kt:132-141`, `:216-224`) — i.e. one implementation, two UIs. Desktop exercises it from the console
harness too (`Main.kt:3639-3733`). Speech uses the shared `LanguageVoices`/`PrismSpeaker` path
(`LanguagePage.kt:744-767`).

**Caveats.** `LanguageSetupFlow.kt:149` still ships the footer "More languages are coming soon" — the catalogue is
finite, and `LanguageCatalog.learnable(target)` can be null (handled as an error, `Main.kt:3647-3650`). Any language
Kokoro does not publish falls back to an American base voice for speech (documented at
`VoiceCloningPage.kt:639-651`).

---

## 15. Prism Writer keyboard (IME) — WORKING on Android; live translate is Android-only

**What it does.** A full input method: QWERTY + symbols, swipe typing, autocorrect with a learned/user dictionary,
next-word suggestions, emoji/GIF panels, dictation, and a **live translate** key that rewrites the selection or
sentence into a target language and optionally speaks it.

**User path.** Android Settings → System → Languages & input → on-screen keyboard → **Prism Writer**; then
`SettingsActivity` for its own settings. Manifest declaration: `AndroidManifest.xml:360-371`.

**IME wiring is correct — verified explicitly, since this was a listed suspicion.**
- Service: `.writer.PrismWriterService`, `android:exported="true"`, `android:permission="android.permission.BIND_INPUT_METHOD"`,
  `<intent-filter><action android:name="android.view.InputMethod"/></intent-filter>`, and
  `<meta-data android:name="android.view.im" android:resource="@xml/prism_writer_method"/>` (`:360-371`).
- Metadata exists and is valid: `app/src/main/res/xml/prism_writer_method.xml` with one `en_US` subtype,
  `isAsciiCapable="true"`, `supportsSwitchingToNextInputMethod="true"`, settings activity wired.
- Mic for dictation: an `InputMethodService` cannot request a runtime permission itself, so the manifest hosts a
  transparent `WriterPermissionActivity` (`:352-358`) — that problem is solved, not papered over.
- GIF hand-off: `FileProvider` with `${applicationId}.fileprovider` + `res/xml/writer_file_paths.xml`
  (`:337-345`), which `commitContent` needs.
- Service itself is a real `InputMethodService` (`PrismWriterService.kt:49`) overriding
  `onCreateInputView`/`onStartInputView` (`:101`, `:182`).

**Evidence of the features.** `SwipeDecoder` is real and lives in `:core`
(`core/src/main/kotlin/com/prism/launcher/writer/SwipeDecoder.kt:30`) with a diagnostic test; the view only treats a
path as a gesture when swiping is enabled and `SwipeDecoder.isGesture(path)` says so
(`PrismKeyboardView.kt:715-738`); autocorrect only fires on word completion (`PrismWriterService.kt:46`);
live translate is gated on a configured model (`aiAvailable()`, `:207-220`) and either translates the selection or the
sentence before the cursor (`:779-817`), commits the result and can speak it (`:835-855`).

**Divergence.** The desktop keyboard (`desktop/.../ui/WriterKeyboard.kt`) implements layout, suggestions, dictionary,
autocorrect, swipe and the emoji/GIF panels (`:49`, `:75`, `:167`, `:320-327`; `DesktopTypist.kt:29`) but has **no
translate path** — grep for `translate` in `desktop/src/main` returns only unrelated hits. Live translate is
Android-IME-only, despite the desktop having the same `AiManager`.

**Minor.** Only one subtype (`en_US`) is declared, so the IME advertises no other language even though Kokoro has 54
voices across many languages.

---

## 16. Code editor / terminal / Node — PARTIAL

**What it does.** A VS Code-like editor (web front end in JCEF/WebView), a real shell, and a Node runtime for
extensions that need `fs`/`child_process`/native addons.

**User path.** Editor slot → `EditorPageView` (Android) / `PageId.EDITOR` → `EditorPage.kt`; extensions marketplace
`MarketplaceActivity` (manifest `:600-605`).

**Evidence.** Terminal is real: it execs a shell under PRoot inside the Linux guest when Node is installed and falls
back to `/system/bin/sh` (`TerminalPanel.kt:110-140`), with pipes/redirection inside the guest. Node is a genuine
Ubuntu 22.04 arm64 rootfs + official `linux-arm64` Node tarball, ~80 MB download, rooted in PRoot, and the reason is
documented correctly (`NodeInstaller.kt:15-47`). Availability is handled honestly, not by crashing:
`unavailableReason()` names PRoot absence and non-arm64 (`NodeInstaller.kt:109-120`), pre-checks 1.1 GB free
(`:87`, `:137-141`), uninstalls a half-finished rootfs before retrying (`:143-146`), and stamps only on success
(`:163`).

**Caveats / PARTIAL reasons.**
- **arm64 only.** `libproot.so`, `libproot-loader.so` and `libqemu-system-aarch64.so` exist **only** under
  `app/src/main/jniLibs/arm64-v8a/`, while `app/build.gradle.kts:52-56` ships four ABIs by default. On x86/x86_64/
  armeabi-v7a the Node runtime and Windows/Linux VM features are unavailable by design (they degrade with a message,
  which is correct behaviour — but the APK still carries the other 3 ABIs' dead weight for these features).
- Android needs an 80 MB download + ~700 MB unpacked + PRoot; desktop needs neither.
- `CMakeLists.txt:311-321` still probes `jniLibs/<abi>/libnode.so` and reports "Node extensions will be unavailable",
  which is stale: `NodeInstaller` explicitly abandoned the linked-library approach (`NodeInstaller.kt:20-33`). Dead
  build logic that will confuse the next reader.

---

## 17. Science instruments + protein folding — PARTIAL

**What it does.** A lab notebook plus phone-as-instrument panels: spirometry (mic), audiometry (tones), cosmic-ray
particle detection (camera), RF survey (Wi-Fi scan), and a protein structure model with training, folding, structure
viewing, an archive/library, and mesh-distributed folding.

**User path.** Science slot → `SciencePageView`; protein import `ProteinImportActivity` (manifest `:188-193`); desktop
`PageId.SCIENCE` → `SciencePage.kt`, `PageId.PROTEINS` → `ProteinPage.kt`.

**Evidence the instruments are real (not mock data).** `ClinicalPanel.kt` records actual audio with `AudioRecord`
(`:183`), detects a forced exhale and reports measured duration/flow numbers (`:229-256`), plays calibrated
attenuation tones through `AudioTrack` in stereo with ramps (`:400-460`), and draws real envelopes/audiograms
(`:479-535`). The camera particle detector's rationale and the manifest permission are consistent
(`AndroidManifest.xml:47-52`).

**Evidence protein is real.** `protein/FoldingModel.kt:408` (`fold`), `FoldingTrainer.kt` (training + Kabsch RMSD
validation, `:347`), `ProteinLibrary.kt` (PDB/mmCIF/FASTA corpus), `ProteinChemistry.kt` (PDB + mmCIF parsers,
`:365`, `:442`, `kabschRmsd` `:557`), `science/ProteinHost.kt:182` (HTTP fold endpoint), `science/MeshFolding.kt:128`
(`foldDistributed`) — a full pipeline, not a placeholder.

**PARTIAL reason.** A freshly installed Prism has **no trained folding weights**, so `fold()` produces a physically
meaningless backbone with a low pLDDT rather than refusing. The UI's value depends entirely on the user running
`FoldingTrainer` on a downloaded corpus first; there is no bundled checkpoint and no "untrained" hard stop I could
find in `fold()` (`FoldingModel.kt:408-430`).

---

## 18. Minigames — WORKING

**What it does.** Chess (full rules, four difficulties, review, mesh play), Pong, and Paper War (map/province/battle
playback).

**User path.** Minigames slot → `MinigamesPageView`; desktop `PageId.GAMES` → `MinigamesPage.kt`.

**Evidence.** Real game catalogue and menus (`MinigamesPageView.kt:53-54`, `:216-217`), difficulty settings persisted
(`:252-270`, `:394-411`), chess review computed off the UI thread and fed back into a style accumulator
(`:371-386`), mesh matchmaking (`findOpponent(MinigameMesh.GAME_CHESS/PONG)`, `:314`, `:428`), PaperWar is a full
view with back handling (`:236`, `:542`). Rules live in `:core` and are exercised from the desktop console harness.

**Caveat.** Mesh play is the only multiplayer; there is no online matchmaking, which the UI correctly presents as
"the mesh".

---

## 19. Stremio — WORKING

**What it does.** A complete Stremio add-on client: repository lists, `stremio://` install links, manifest parsing,
catalogue browsing, search, metadata/episodes, and stream resolution with per-stream proxy headers — with playback
delegated to Lyke.

**User path.** Settings → *Stremio* → `StremioRepositoriesActivity` / `StremioAddonsActivity`
(manifest `:649-661`); install links via the exported `StremioInstallActivity` (`:668-681`); content appears in the
**Lyke** feed. Desktop `PageId.STREMIO` → `StremioPage.kt`.

**Evidence.** `StremioStore.kt` implements the real protocol: manifests (`:584-651`), catalogues
(`:395-435`), search restricted to catalogues that declare it (`:417-435`), meta/episodes (`:466-508`), and
**streams queried across every capable add-on** because metadata and streams come from different add-ons
(`:510-552`) — the comment explains the exact bug that pattern fixes. Streams carry `behaviorHints` proxy headers
(`:553-570`) which Lyke applies at playback (`LykeView.kt:358-362`).

**Caveats.**
- No player screen of its own: a stream plays inside Lyke (`LykeView.kt:354-373`). Desktop `StremioPage` is
  management/browse only.
- `StremioInstallActivity` is exported and accepts `stremio://` from any browser; it does preview + confirm before
  storing (`StremioInstallActivity.kt:43-91`), which is the right shape, but it is the app's only web-reachable
  write path and deserves the scrutiny it got.

---

## 20. Desktop ↔ Android parity — BROKEN in launcher mode (BUG-4)

**What it is.** The desktop app has two shells: the normal rail shell (`PrismWindow.PageBody`) and a phone-emulating
shell (`LauncherShell`, enabled by `PrismSettings.getDesktopMobileMode()`).

**BUG-4 (High): launcher mode hides ~12 pages that are already implemented and wired in the other shell.**

- `PrismWindow.kt:78` reads `desktopMobileMode`; `:114-118` renders `LauncherShell(...)` when it is on;
  `:120-136` renders the rail shell when it is off.
- `LauncherShell.kt:228-244` maps only nine slots: DesktopGrid, Browser, AgenticTools, KineticHalo, AppDrawer,
  FileExplorer, Messaging→NoraChatPage, Models, ModelStore. Everything else falls to `else -> UnportedSlot(slot)`.
- `LauncherShell.kt:246-289` then tells the user these pages are **not ported**:
  Nebula ("the feed UI is 675 lines of Android views and is the largest single screen left"), Editor ("PHASE 93 …
  what's left is the Node runtime and the terminal"), Science ("PHASE 96"), Language ("PHASE 94"), Minigames
  ("PHASE 95"), Cloud ("PHASE 98"), plus Wallet/Virtualization/Notifications/Custom.
- But `PrismWindow.kt:168-198` **wires all of them**: `NebulaPage()`, `EditorPage()`, `SciencePage()`,
  `ProteinPage()`, `LanguagePage()`, `MinigamesPage()`, `CharactersPage()`, `StremioPage()`, `LykePage()`,
  `WriterPage()`, `AetherPage()`, `SpeechPage()`, `KokoroSettingsPage`, `VoiceCloningPage`.

**Concrete failure.** Turn on the desktop's phone mode (or launch with it persisted), assign **Language** to a page:
`SlotPage` falls through to `UnportedSlot`, and the user is told "PHASE 94 … This is a UI port" for a page that is
fully implemented three files away. The same applies to Editor, Science, Minigames, Nebula. The two shells disagree
about the state of the port, and the stale one is the one a user asking for "the phone layout" gets.

**Other divergences (deliberate or not).**

| Area | Android | Desktop |
|---|---|---|
| TTS | Kokoro ONNX (`onnxruntime-android`) + system TTS floor | Kokoro ONNX (`onnxruntime`) + `spd-say`/`espeak` floor (`DesktopTts.kt`) |
| Video encode | MediaCodec H.264 (`NoraVideoWriter`) | JCodec software H.264 (`desktop/build.gradle.kts:78-87`) |
| Writer live translate | **yes** (IME TRANSLATE key) | **no** |
| Node/terminal | 80 MB download, PRoot, arm64 only | host shell, no download |
| Nebula feed | implemented (`NebulaSocialPageView`) | implemented (`NebulaPage.kt`) but called "not ported" in launcher mode |
| Chat surface | View/RecyclerView | Compose (`NoraPages.kt`, `AetherPage.kt`, …) |

---

## The five worst bugs

1. **Nora training reports success when it learned nothing** — `NoraTrainer.kt:137`, `:127`, `:220-223`, `:226-249`.
   Undecodable images are skipped, `trained` is never checked, `markTrained()` runs anyway, and the summary is
   unconditional. User sees "Training complete. 0 presentations" and `/status` says "Connectome loaded"; every
   generated image afterwards is blank. (Aether has the identical defect, `AetherTrainer.kt:239-241`/`:294`.)
2. **The system-voice fallback can never find an engine on Android 11+** — `SystemTtsEngine.kt:69-78` queries
   `android.intent.action.TTS_SERVICE` but `AndroidManifest.xml:59-75` has no `<queries>` entry for it (targetSdk 35).
   On a device with an engine installed but no default set, call mode is silent and Write dictation speaks nothing.
3. **Launcher mode contradicts the rest of the desktop app** — `LauncherShell.kt:246-289` prints "not ported /
   PHASE 93/94/95/96" for Language, Editor, Science, Minigames and Nebula, which `PrismWindow.kt:168-191` renders
   from fully implemented pages. `PrismWindow.kt:78/114` decides which of the two the user sees.
4. **FreeVC clones short recordings on mostly silence** — `FreeVcEngine.kt:132-137` pads to a fixed 8 s window while
   `VoiceCloner.kt:82-87` accepts 0.75 s samples; a normal 2–3 s clone is 60–75 % silence into the speaker encoder,
   so the result drifts to an averaged voice with no warning.
5. **Voice-clone installs are gated on exact byte length with no hash** — `VoiceCloningModels.kt:42-43` plus
   `ClonedVoices.kt:224-232`: one byte of drift (CDN re-pack, revised artefact) makes an engine permanently
   "not installed", so every save re-downloads up to 358 MB and never converges. Compounded by three of five engines
   sourcing weights from an unrelated third-party release (`PocketTtsEngine.kt:92`, `SeedVcEngine.kt:73`,
   `FreeVcEngine.kt:50`).

**Runners-up:** fabricated Nebula engagement + no-op worker successes (`NebulaSocialManager.kt:90-91`,
`SocialBotWorker.kt:14-15`); AKEF receiving without merging by default (`AetherConfig.kt:46`); unguarded
`InputStream.skip` in the Kokoro style lookup (`KokoroEngine.kt:348`); Nora video clips lost on one mis-sized frame
(`NoraVideoWriter.kt:46-47`, `:92`, `:147`); stale `libnode.so` build logic (`app/src/main/cpp/CMakeLists.txt:311-321`);
stale duplicate source trees under `core/bin/main` and `desktop/bin/main`.

## What I could not verify

- Any runtime behaviour (no build/emulator possible — see limits).
- The real size/content of the 13 non-OpenVoice weight files, or that HF still serves
  `onnx-community/Kokoro-82M-v1.0-ONNX` at the exact paths used.
- Whether the mesh paths (`AetherMeshSync`, `NebulaMeshSync`, `KokoroMeshShare`, `MinigameMesh`) actually traverse the
  VPN/tunnel on a real pair of devices; I verified only that both ends' handlers exist and agree on hostnames/opcodes.
- `NoraBrainView`, `AetherBrainView` rendering correctness (read only their call sites and state plumbing).
- Exact Settings menu labels/order on Android; entry points are cited by activity/page class, not by traced menu row.
