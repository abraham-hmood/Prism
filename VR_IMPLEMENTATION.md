# Prism VR — Implementation Plan

Prism on standalone headsets: Meta Quest (Horizon OS), Android XR devices, Pico, HTC Vive Focus / XR Elite and other OpenXR-on-Android headsets. They all run Android, so Prism VR is the Android launcher plus a spatial layer, not a rewrite.

This plan is in phases, like the desktop port. Each phase says what it builds on (the real files in this repository), what it adds, and how to know it is done. Parts A and B build the platform and the skeuomorphic shell. Part C brings every existing feature across, domain by domain. Part D, the last, turns ordinary mobile apps into VR apps.

> **How the inventory was built.** Every source file in `app/`, `core/` and `desktop/` was swept: 2,477 files, of which about 515,000 lines are Prism's own and the rest vendored engines (llama.cpp/ggml, stable-diffusion, whisper, RandomX, Vulkan/OpenCL headers). Each file's declarations and doc comments were read, which covers every feature and subsystem, but not every line of every file. Where a phase depends on a detail inside a file, it says so and names the file to read first.

---

## Design direction

### The thesis

Most VR interfaces are flat 2D panels floating in a void. That is the "pretending" problem: the panels behave like nothing in the real world, so they never feel like they belong there. Prism VR's built-in default is **skeuomorphic**: interface elements are objects with thickness, material, weight, sound and a place in the room. Users can still pick any theme (xOS, Aurora, Fusion, their own); a flat theme turns into clean floating slates, and nothing is lost.

Skeuomorphism in VR means *physically plausible*, not *ornamental*:

| Do | Don't |
|---|---|
| Give surfaces real depth: a card is 4 mm thick, a button depresses 2 mm, and a drawer slides. | Put wood grain or leather *behind text*. Text sits on matte, even surfaces. |
| Light everything from the room (passthrough estimate or the environment), with soft contact shadows. | Fake glossy highlights that do not move when the head moves. |
| Give each object a sound when touched, dropped or opened (synthesised, like `PaperAudio`). | Add sounds to things you only look at. |
| Map each Prism concept to a real object: drives are drives, the wallet is a wallet, chess is a board. | Make everything a literal object. A settings list is still a list, just on a physical clipboard. |
| Anchor things to real surfaces (desk, wall) in mixed reality. | Lock UI to the head. Only toasts and the hand menu follow you. |

### Two layers

1. **The Room** is the skeuomorphic shell: launcher-level objects such as the desk, the shelf, the pinboard, the bin, the hand menu, notifications that arrive like letters, and drives you can pick up. This layer is new.
2. **The Panels** are every existing Prism page, rendered by the existing Android code onto a physical slate (default theme) or a flat pane (flat themes). This is how the whole feature set arrives on day one without rewriting 220,000 lines of views.

Over time, features that gain from it get **native 3D forms** (Part C): the chess board, the PaperWar tabletop, the protein you can hold, the arcade cabinets, Aether standing in the room, and the drive cabinet. Everything else stays a panel, a good panel.

### Mixed reality first

"Based on reality" points at passthrough. The default environment is the user's own room: Prism's desk snaps to their real desk and its pinboard to a real wall. A fully virtual room is part of the theme, and the built-in one is a calm workshop, for users who want to be elsewhere.

### Comfort rules (non-negotiable, enforced in code)

- Panels default to **1.2 m** away; the usable range is **0.5–3 m**. Reading text is never closer than 0.5 m.
- Text is never smaller than **~1.3° of visual angle** for body copy. Panels choose their dp-per-metre density from distance, never from a fixed pixel size.
- **No head-locked content** except transient toasts and the wrist menu.
- **No artificial locomotion by default.** The world comes to you: summon a panel, rotate the carousel.
- **Frame budget** is the display refresh rate (72/90/120 Hz). A panel that misses it drops to a lower update rate, but head tracking never drops.
- **Seated, standing and one-hand modes** are first-class, chosen at setup.

### Input

Hands (pinch to select, poke to press), controllers (a ray with a trigger), gaze-and-pinch where the runtime offers it (Android XR), physical Bluetooth keyboards (shown through passthrough), and Prism Writer as a keyboard object for poke typing. Every existing long-press menu becomes a pinch-and-hold or grip action. Dictation (`VoiceInputController`) is on every text field.

---

## Part A — Platform

### Phase 1 — Targets, build flavor and manifest

**Goal:** a `vr` build of the Android app that installs on a headset and shows the existing launcher as a 2D panel. No XR yet.

**Builds on:** `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, [LauncherActivity.kt](app/src/main/java/com/prism/launcher/LauncherActivity.kt).

**Work:**
- Add a `form` flavor dimension with `phone` (today's app, unchanged) and `vr`. VR-only code goes in `app/src/vr/`, so it reuses all of `app/src/main` and `:core` with no copy.
- In the VR manifest, declare the OpenXR loader (`org.khronos.openxr.OPENXR_RUNTIME_BROKER` query) and the immersive intent categories: `org.khronos.openxr.intent.category.IMMERSIVE_HMD`, Meta's `com.oculus.intent.category.VR`, and Android XR's `android.software.xr.api.openxr` feature. Also declare `android.hardware.vr.headtracking` (required=false so one APK can still run on phones), hand-tracking permissions, and passthrough/scene permissions per runtime.
- Strip what a headset cannot use from the `vr` manifest: telephony roles (`PrismInCallService`), SMS receivers (`SmsRelayReceiver`), call recording (`CallRecordingAccess`), and the home-screen role where the runtime forbids replacing its launcher.
- A device matrix in CI: Quest 3/3S, an Android XR device, Pico 4 Ultra, and a phone (to prove the `phone` flavor is untouched).

**Done when:** `assembleVrDebug` installs on a Quest and the launcher appears as a standard 2D Android panel. `assemblePhoneDebug` is byte-for-byte the current app.

### Phase 2 — The XR runtime: Prism's own renderer

**Goal:** an OpenXR session, a frame loop and a renderer that Prism owns. No Unity, no engine: the same "Prism's own runtime" stance as the desktop's Android runtime.

**Builds on:** the native build in `app/src/main/cpp` (the JNI bridges show the CMake and loading pattern), [NativePayload.kt](desktop/src/main/kotlin/com/prism/desktop/NativePayload.kt) for the library-loading pattern, and `MeshScene.kt` / `AetherModelView.kt` for Prism's existing 3D drawing code.

**Work:**
- `app/src/vr/cpp/prism_xr.cpp`: create the OpenXR instance and session, local and stage reference spaces, the view configuration (stereo), and swapchains. Run the frame loop (wait, begin, render, end) on its own thread with the runtime's prediction times.
- Use the Vulkan renderer where the runtime has it, with GLES 3.2 as a fallback. Start with one PBR material model; the theme defines materials (Phase 9).
- `VrActivity` in `app/src/vr/java`: owns the session lifecycle (focus, visible, stopping), recentering, boundary changes, and pause/resume. It maps them to the existing page lifecycle so pages pause exactly as they do when the phone screen turns off.
- Hand off to and back from the runtime's own home on exit.

**Done when:** an empty room renders at the native refresh rate with correct stereo and head tracking, and session state changes never crash or leak.

### Phase 3 — The panel compositor

**Goal:** every existing Prism page renders, unchanged, onto a panel in 3D. This is the phase that brings the whole app across.

**Builds on:** [PageWindowView.kt](app/src/main/java/com/prism/launcher/PageWindowView.kt) (pages as real windows), [DisplayPageFactory.kt](app/src/main/java/com/prism/launcher/display/DisplayPageFactory.kt) and [PageMirror.kt](app/src/main/java/com/prism/launcher/display/PageMirror.kt) (rendering a page somewhere other than the screen), `MainDesktopPagerAdapter.kt`, and `SlotAssignment.kt`.

**Work:**
- Each panel is a `Presentation` on a `VirtualDisplay` that Prism owns, with `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`. Prism's own content needs no capture consent.
- The `VirtualDisplay` renders straight into an OpenXR **Android surface swapchain** (`XR_KHR_android_surface_swapchain`). The page's pixels reach the compositor with **zero copies** and are submitted as a compositor *quad or cylinder layer*. That gives the sharpest possible text, because the compositor samples the panel once instead of Prism sampling it into the eye buffer and the compositor sampling it again.
- A fallback for runtimes without that extension: render the panel to a `SurfaceTexture`, then draw it as a textured mesh in the eye buffer.
- The page factory: any `SlotAssignment` produces its page inside a panel. The views are the same views the phone uses.
- A panel's update rate follows its content: a still page redraws on change, while video, games and the browser redraw continuously.

**Done when:** every page in `PageCatalog` opens as a panel and looks right, and panels keep their state across hide and show.

### Phase 4 — Input into panels

**Goal:** hands and controllers operate every existing page as if it were touched.

**Builds on:** the page views' own touch handling, the long-press menu suppression in `FileExplorerAdapter`, [AppMenuDrag.kt](app/src/main/java/com/prism/launcher/AppMenuDrag.kt), `DragAndDrop.kt` (desktop, for cross-page drag semantics), and [PrismWriterService.kt](app/src/main/java/com/prism/launcher/writer/PrismWriterService.kt).

**Work:**
- Ray-cast (controller or pinch ray) and poke (index fingertip) against the panel planes. Hits become `MotionEvent`s dispatched into the `Presentation`'s view hierarchy, in Prism's own process, so no injection permission is needed.
- Hover (`ACTION_HOVER_*`) under the ray, so pages show pressed and hover states.
- Gestures:
  - Pinch-drag scrolls.
  - Pinch-and-hold is a long press, so every existing long-press menu works, including the drive menus with Compress and Decompress.
  - Two-hand pinch is pinch-zoom.
- When a text field takes focus, the Writer keyboard object appears under the panel (Phase 31). A physical Bluetooth keyboard goes straight in.
- Back is a button on the controller or a palm-up gesture, mapped to the existing back handling (`handleBack` in each page).

**Done when:** every page can be used end to end with hands only and with controllers only, including drag-and-drop between pages.

### Phase 5 — Legibility

**Goal:** text that is comfortable for an hour.

**Builds on:** [PrismFontEngine.kt](app/src/main/java/com/prism/launcher/PrismFontEngine.kt), `FontEngine.kt` (core), and the `PrismThemes` typography and density.

**Work:**
- Panel density from distance: choose dp-per-metre so a 14 sp line covers at least 1.3°, and re-lay out (not just rescale) when a panel moves far enough.
- Supersample panels by 1.25–1.5× when the compositor layer path is not available, with a light sharpening pass.
- Clamp any theme's typography to the VR minimum, so a user theme cannot become unreadable.
- Use contrast rules for panels over passthrough: an opaque matte backing is always behind text.

**Done when:** a reading test (the Journal and Email pages for 30 minutes) passes on each headset without users leaning in.

### Phase 6 — The spatial window manager

**Goal:** pages are windows in space: placed, moved, resized, minimised and remembered.

**Builds on:** [PageWindowView.kt](app/src/main/java/com/prism/launcher/PageWindowView.kt) (move, resize, minimise semantics, and "a minimised page is hidden, not destroyed"), `SlotAssignment.kt`, `PagePickerSwipe.kt`, `PrismWindow.kt` (desktop), and `LauncherShell.kt` (desktop pager).

**Work:**
- **Grab bar:** grab a panel's bar to move it, and grab its corner to resize it. Panels can snap to real surfaces (Phase 8) or curve when wide (cylinder layer).
- **Carousel:** the phone's horizontal pager of slots becomes a carousel around the user. "Swipe to the next page" rotates the carousel, and the existing slot order is kept.
- **Workspaces:** saved arrangements of panels per room (anchored when passthrough is on), stored with the profile so `ProfileArchive` and Time Machine carry them.
- **Page picker:** the existing page-picker gesture (`PagePickerSwipe`) becomes a hand gesture captured the same way (Phase 13).
- **Offload:** hidden panels are offloaded through [PageOffload.kt](app/src/main/java/com/prism/launcher/offload/PageOffload.kt) unless they are busy.

**Done when:** a layout of six panels survives a restart and a recenter, and returns to the same real-world places in mixed reality.

### Phase 7 — Comfort, performance and accessibility

**Goal:** Prism stays inside the headset's frame, heat and battery budgets.

**Builds on:** [AndroidThermalGovernor.kt](app/src/main/java/com/prism/launcher/platform/AndroidThermalGovernor.kt), `PrismSustain.kt`, `PrismPower.kt`, `PrismCpu.kt`, and `NpuSupport.kt`.

**Work:**
- Fixed and eye-tracked foveated rendering where the runtime offers it. Choose the refresh rate per scene: 72 Hz for reading, 90/120 Hz for games.
- Feed the headset's thermal signals into `PrismSustain`. Heavy background work pauses before the runtime throttles: mining, Nora and Aether training, quantisation, VideoCraft renders and drive compression.
- **Defaults on a headset:** mining is off, and `MiningService` refuses to start unless the user explicitly overrides it. A Prism server role warns about battery.
- **Accessibility:** one-hand mode, seated mode, a reduced-motion flag (no carousel animation, no physics bounce), high-contrast panels, captions everywhere audio plays (Whisper, already in Prism), and hold-to-select for tremor.

**Done when:** a mixed session (browser, video, chess, AI chat) holds frame rate for an hour without thermal shutdown on a Quest 3S.

### Phase 8 — Passthrough, scene understanding and anchors

**Goal:** the real room is the default environment.

**Builds on:** [GeoTagArRenderer.kt](app/src/main/java/com/prism/launcher/minigames/GeoTagArRenderer.kt), `GeoTagGeometry.kt` (the plane hit-testing and placement logic, written against ARCore), and `GeoTagStore.kt`.

**Work:**
- Passthrough is on by default, with a toggle to the theme's virtual room.
- Scene understanding uses the runtime's planes and meshes (Meta's scene API, Android XR's plane tracking, `XR_FB_scene`/`XR_ANDROID_trackables`). Prism keeps one internal plane model, the one GeoTag already uses, so features do not care which runtime found the desk.
- Spatial anchors (persistent) for workspaces, the desk and pinboard, and GeoTag art.
- Room setup on first run: "This is my desk" and "This is my wall".

**Done when:** the desk snaps to a real table in under ten seconds and is still there the next day.

---

## Part B — The skeuomorphic theme and the Room

### Phase 9 — Themes learn depth and material

**Goal:** the theme system can describe physical things, and any theme still works.

**Builds on:** [PrismThemes.kt](core/src/main/kotlin/com/prism/launcher/theme/PrismThemes.kt) (`PrismThemeSpec`, `ThemeModeSpec`, palette, shape, density, edge effects and backdrops), [ThemeXml.kt](core/src/main/kotlin/com/prism/launcher/theme/ThemeXml.kt) (the portable layout tree), `AndroidThemeRenderer.kt`, `ThemeSkin.kt`, `ThemeEditorActivity.kt` and `ThemeDrawables.kt`.

**Work:**
- **`vr` block:** add an optional block to `PrismThemeSpec`:
  - A material per role (surface, card, button, field, chrome), each from `paper`, `card-stock`, `matte-plastic`, `brushed-metal`, `glass`, `felt`, `wood` or `stone`, with roughness and tint.
  - Thickness in mm per role.
  - Corner bevel.
  - Contact-shadow strength.
  - Sounds per interaction (press, release, drop, open, close), named synthesis presets in the `PaperAudio` style.
  - The environment (virtual room preset) and the lighting preset.
- **ThemeXml primitives** (`card`, `button`, `field`, `row`, `column`) take optional `material`, `depth` and `press` attributes. On a phone they are ignored. On a panel they become shading (bevel, inner shadow), and in the Room they become geometry.
- **Flat themes:** a theme without a `vr` block (xOS, Aurora, Fusion and every user theme today) renders as flat slates with Prism's neutral glass chrome. Nothing breaks, and the user's choice is respected.
- **Theme editor:** gains a VR tab that edits the `vr` block, with a live 3D preview on the headset or a rotating preview on the phone and desktop. Themes stay portable files that move with the profile.

**Done when:** one theme file renders on a phone (2D), on the desktop (Compose) and in the headset (3D), and the three agree.

### Phase 10 — The built-in theme: "Workshop"

**Goal:** the default VR theme, skeuomorphic and calm.

**Builds on:**
- Prism's drawn-not-shipped approach: `ThemeDrawables.kt`, `PencilStyle.kt`, `EchoAssets.kt` and `ZombiesAssets.kt`.
- The PBR material set in `ZombieKit.kt` (`PbrSet`, `Material`).
- `PaperAudio.kt` and `MinigameSfx.kt` for synthesised sound.

**Work:**
- **Materials:** matte card stock for panels, felt for the desk surface, and brushed aluminium for controls. Buttons are low-profile keys with a 2 mm travel and a click. Toggles are real rocker switches, and sliders are faders.
- **Generated, not shipped:** materials are generated procedurally at first run, as Zombies' and Echo's assets are, so the APK does not grow by hundreds of megabytes.
- **Virtual room:** a workshop with daylight through a window, for passthrough-off use. It is optional and lightweight.
- **Palette:** taken from the user's chosen accent, so Workshop still feels like *their* Prism.

**Done when:** first launch on a headset is Workshop over passthrough, and switching to Fusion makes every object flat and glowing without a restart.

### Phase 11 — The Room shell: the launcher as a place

**Goal:** the launcher's own pieces become objects.

**Builds on:**
- [DesktopGridPage.kt](app/src/main/java/com/prism/launcher/DesktopGridPage.kt) and `DesktopShortcutStore.kt` (items, folders, files, network folders).
- [FolderPopupView.kt](app/src/main/java/com/prism/launcher/FolderPopupView.kt) and `DesktopFolders.kt` (folders are real directories).
- `DeleteZoneView.kt`, `KineticHaloPageView.kt`, `HotseatPredictor.kt`, `DrawerPageView.kt`, `DrawerAppsAdapter.kt`, `DesktopWidgetLayer.kt` and `PrismWidgetHost.kt`.

**Work:**

| Phone | Room |
|---|---|
| Desktop grid | A pinboard on the wall. Icons are pinned cards, and files and directories are paper tags. |
| Folders | Real folders you open on the desk. They are still real directories underneath. |
| Hotseat (`HotseatPredictor`) | A tray at the front of the desk, ordered by time of day as now. |
| App drawer | A shelf or cabinet: alphabetic drawers, and search on the drawer front. |
| Kinetic Halo | A ring around the wrist, flicked to spin. Its physics are already written. |
| Widgets | Objects on the desk (clock, weather card). They are rendered as panels until a widget has a native form. |
| Delete zone | A bin beside the desk: throw something in it to delete it, with the same confirmations. |

Drag and drop is in 3D: pick up an app card and drop it on the pinboard, into a folder or in the bin, using the same `DesktopShortcutStore` operations.

**Done when:** every launcher operation the phone has (add, move, folder, delete, widget) can be done in the Room, and the data is the same data the phone syncs.

### Phase 12 — The hand menu, notifications and system UI

**Goal:** the things a phone keeps in its status bar and shade.

**Builds on:**
- `PrismNotificationListener.kt`, `NotificationHistory.kt` and [NotificationsPageView.kt](app/src/main/java/com/prism/launcher/notifications/NotificationsPageView.kt).
- `LockGate.kt`, `PrismLockActivity.kt`, `PatternView.kt`, `DuressResponder.kt`, `PrismLockScope.kt` and `MedicalRecord.kt`.

**Work:**
- **Wrist menu** (look at your palm): the clock, battery, mesh state, wallet balance, quick toggles, the carousel, and recenter.
- **Notifications** arrive as small cards (letters, in Workshop) that slide onto the desk edge, with an optional spatial chime. Persistent progress notifications become small objects with a progress bar:
  - drive compression (`DriveCompressionService`)
  - downloads
  - VideoCraft and ImageCraft renders
  - quantisation
  - Nora and Aether training
  - mining

  They are touchable and have their Stop buttons.
- **Lock screen:**
  - The pattern is drawn in the air on a 3×3 grid.
  - The duress credential behaves exactly as on the phone.
  - The headset's own presence sensor triggers the lock gate, like screen-off.
  - Entry is hidden in any cast or recording, because a headset can be mirrored.
- **Medical card:** it is a phone feature. On a headset, show it only from the lock screen's emergency button.

**Done when:** a notification from a trusted phone (SMS relay, clipboard, call) reaches the headset and can be acted on without opening a page.

### Phase 13 — Onboarding in VR

**Builds on:**
- [OnboardingContent.kt](app/src/main/java/com/prism/launcher/onboarding/OnboardingContent.kt) ("everything Prism does, written down once"), `OnboardingOverlay.kt`, `OnboardingTour.kt` and `DesktopTour.kt`.
- `SwipeTriggerSetupView.kt`, `WalletSetupView.kt` and `LykeAccountSetupView.kt`.

**Work:**
- The tour's steps play out in the Room. Each step points at the real object it describes.
- Gesture capture: the page-picker swipe becomes a hand gesture recorded with the same three-sample capture.
- Wallet creation and restore, with the recovery phrase shown on a physical card. Warn that a phrase on screen can be captured by a cast; when restoring, offer phone pairing so the phrase is typed on the phone instead.
- Comfort setup (seated or standing, handedness, IPD check) and room setup (Phase 8).

**Done when:** a new user goes from first launch to a paired phone and a placed desk without reading anything off-headset.

---

## Part C — Every feature, in VR

Each phase lists the features in a domain. Everything works as a panel from Phase 3; these phases add what VR does better, and name what a headset cannot do.

### Phase 14 — Browser, DNS and privacy

**Builds on:**
- [BrowserPageView.kt](app/src/main/java/com/prism/launcher/browser/BrowserPageView.kt) and `BrowserTabsAdapter.kt`.
- `PrismWebViewClient.kt`, `PrismWebCache.kt`, `PrismWebHost.kt`, `P2pDnsManager.kt`, `PrismMirrorManager.kt` and `PrismDownloadsSheet.kt`.
- `WebAppBuilder.kt`, `WebShortcuts.kt`, `HostBlocklist.kt`, `PrivateDnsVpnService.kt`, `BlockedIpv4RouteTable.kt`, `PrismTunnelEngine.kt` and `VpnBridge.kt` (desktop).

**Work:**
- Tabs are a fan of slates you can spread out across the room: open four sites side by side.
- Each tab keeps its per-tab VPN, private mode, blocklist, and P2P DNS for mesh sites (`.p2p`, mesh names from `MeshDns`).
- "Install as app" (`WebAppBuilder`) produces a VR panel app the Room can pin.
- **WebXR:** Android WebView does not do immersive WebXR. Phase 14b evaluates bundling Chromium (the desktop already runs CEF via `CefRuntime.kt`) only for `immersive-vr` sessions. This is a separate decision.
- Video goes full-screen into the cinema environment (Phase 25).

**Done when:** browsing, downloads, hosting a site to the mesh and the per-tab VPN all work from the headset.

### Phase 15 — Messaging, Sam, characters and agents

**Builds on:**
- [MessagingPageView.kt](app/src/main/java/com/prism/launcher/messaging/MessagingPageView.kt), `ConversationActivity.kt`, `LinkedChatActivity.kt`, `MessagingBridgesView.kt`, `MessagingApi.kt`, `RcsChat.kt` and `TrustedMessages.kt`.
- `SmsRelay.kt`.
- `SamConversation.kt`, `ChatContext.kt`, `SamCompaction.kt`, `AiManager.kt` and `GgufInferenceService.kt`.
- `CharacterConversationActivity.kt`, `CharacterStore.kt` and `CharacterPrompt.kt`.
- `CakeChat*`, `AgenticEngine.kt` and `AgenticBuiltinTools.kt`.

**Work:**
- Threads become a notebook on the desk; open one to read it at a comfortable size.
- **Texts:** a headset has no radio. Texts arrive through the paired phone (SMS relay, trusted messages and Prism RCS). Prism RCS and the messaging-network bridges already go through APIs and the mesh, so they work unchanged.
- Sam gets a presence: a soft light on the desk that pulses while thinking (`ThinkingTextAnimator`'s states). Call mode (`CallModeActivity`) is a natural fit, a conversation by voice while doing something else.
- **Characters:** a user character's backdrop becomes the environment for the conversation. A character with an image or 3D asset (AssetCraft, Phase 23) stands in it.
- Agentic tools are unchanged, with the headset's own permission prompts.

**Done when:** a Sam conversation with tools, a character chat and a relayed SMS reply all work hands-only.

### Phase 16 — Nora and Aether

**Builds on:**
- [NoraBrainView.kt](app/src/main/java/com/prism/launcher/nora/NoraBrainView.kt) and `NoraTelemetry.kt`.
- `AetherBrainView.kt` and `AetherTelemetry.kt`.
- [AetherModelView.kt](app/src/main/java/com/prism/launcher/aether/AetherModelView.kt) and `AetherSkinnedModel.kt` (Aether is already a skinned, animated 3D body, from `assets/3dmodels/Aether.pskn`).
- `NoraService.kt`, `AetherService.kt` and the training activities.

**Work:**
- **Brains in volume:** the connectome views become volumetric. The regions sit in a translucent 3D brain, and the pathways light up between them from the same lock-free telemetry. You can walk around Nora's cortex while she trains.
- **Aether, physically present:** she stands in the room at human scale, with her existing arrival and idle animations and gaze toward the user. Her chat panel floats beside her.
- **Training on the headset:** training pauses on thermal pressure (Phase 7). It can be offloaded to a trusted phone or desktop through the existing mesh and background-job paths (`NoraBackgroundJobs.kt` on the desktop).

**Done when:** Aether stands in a passthrough room and holds a spoken conversation, and Nora's training is visible in 3D.

### Phase 17 — Calls

**Builds on:**
- [MeshCalls.kt](core/src/main/kotlin/com/prism/launcher/calls/MeshCalls.kt), `MeshGroupCalls.kt`, `MeshVideo.kt` and `MeshGreeting.kt`.
- `CallActivity.kt`, `GroupCallActivity.kt`, `VideoCallActivity.kt`, `CallTranscriber.kt`, `CallTranscriptOverlay.kt` and `MeshCallRecorder.kt`.
- `MeshvoicePageView.kt`, `CallRinger.kt` and `CallDevices.kt`.

**Work:**
- **Spatial audio:** each participant's voice comes from their tile's position, which makes group calls understandable.
- **Video tiles:** they become portrait frames standing on the desk. The headset's camera is not available as a "selfie" camera. Video out sends the user's avatar card (Phase 17b can add a stylised avatar driven by face and hand tracking), and video in is full.
- **The dialer:** a physical keypad (`DialKeyView`'s iOS layout, with keys that depress).
- **Carrier calls:** a headset has no telephony. They are answered by handing off to the paired phone, or relayed from it as audio where the phone allows it. `PrismInCallService` and carrier call recording stay phone-only.
- **Ringtones:** these come from the direction of the phone's tile, or the wrist.

**Done when:** a four-person mesh group call works with spatial audio and live transcription.

### Phase 18 — Files, drives, compression and storage

**Builds on:**
- [FileExplorerPageView.kt](app/src/main/java/com/prism/launcher/files/FileExplorerPageView.kt), `StorageSheets.kt`, `StorageVolumes.kt`, `UsbDrives.kt` and `UsbMassStorage.kt`.
- `PhoneDrives.kt` and `TrustedDrives.kt`.
- [DriveCompression.kt](core/src/main/kotlin/com/prism/launcher/storage/compress/DriveCompression.kt), `CompressionJobs.kt`, `CompressedFile.kt` and `DriveCompressionService.kt`.
- `HostedStorageServer.kt`, `TrustedStorageBridge.kt`, `NetworkStorage.kt`, the drive file systems (`Ntfs`/`Ext4`/`ExFat`/`Fat` `FileSystem.kt`), `IsoImage.kt` and `StorageAdmin.kt`.

**Work:**
- **A filing cabinet.** Each drive is a drawer: internal storage, a USB-C stick plugged into the headset, an SD card, and the drives of every trusted device. Your desktop's C: and F: are drawers labelled with the PC's name.
- **The drive menu** (Compress drive…, Decompress drive) opens on grabbing a drawer's handle. Compression runs in the background exactly as now, and its progress object sits on the cabinet (Phase 12).
- **Previews:** files open by being pulled out onto the desk. Images and PDFs are flat sheets, and 3D files open in AssetCraft's viewer.
- **Physical USB drives:** a stick plugged into the headset appears as a new drawer within a second (the attach receiver already exists). Format and burn need the same privileges as on a phone, and are hidden where unavailable.

**Done when:** browsing the desktop's drives from the headset is as fast as on the phone (the cached drive list from the last fix), and a compression started on the headset shows progress there and on the phone.

### Phase 19 — Trusted devices, displays, mouse and clipboard

**Builds on:**
- [TrustedDevices.kt](core/src/main/kotlin/com/prism/launcher/trusted/TrustedDevices.kt), `DeviceKeys.kt`, `TrustedSeal.kt`, `PairingCode.kt` and `PairLink.kt`.
- `TrustedLocations.kt`, `ClipboardHistory.kt` and `TrustedFileTransfer.kt`.
- [WirelessDisplay.kt](core/src/main/kotlin/com/prism/launcher/display/WirelessDisplay.kt), `MirrorStream.kt`, `DisplayReceiverActivity.kt`, `PageMirror.kt` and `DesktopMirror.kt`.
- `RemotePointer.kt`, `MousePageView.kt` and `DesktopPointer.kt`.

**Work:**
- **Pairing:** the code is shown on a card you can read from the other device, or typed on the keypad. Per-device keys mean the headset gets its own key pair sealed by its own wallet, with nothing new to design.
- **The headset as a display: the big win.** A trusted PC or phone can *extend* or *mirror* to the headset (`WirelessDisplay`'s Show and Mirror). Each extended page or mirrored screen becomes a panel, which gives virtual monitors for the desktop with no extra software on it.
- **The mouse page** becomes a trackpad on the desk that drives the PC's pointer (`RemotePointer`), for controlling the mirrored desktop.
- **Clipboard:** a clipboard tray on the desk, with history shared across devices as now.
- **Locations:** trusted devices' locations on the tabletop map (Phase 28).

**Done when:** a Windows desktop shows two extended Prism pages and its mirrored screen as three panels in the headset, driven by the desk trackpad.

### Phase 20 — Meshnet, VPN, server roles and the access point

**Builds on:**
- [MeshCore.kt](core/src/main/kotlin/com/prism/core/MeshCore.kt), `MeshMembership.kt`, `MeshTransport.kt`, `MeshDns.kt`, `MeshTls.kt` and `PrismMeshService.kt`.
- `VpnPageView.kt`, `VpnRoute.kt`, `WireguardController.kt`, `WireguardServerEngine.kt`, `VpnMultiplexer.kt` and `L2tpEngine.kt`.
- `ServerDirectory.kt`, `DirectoryServer.kt`, `HolePunch.kt`, `PhonePunching.kt`, `FleetBridge.kt`, `PrismProxyServer.kt` and the access point package.

**Work:**
- **A mesh globe** on the desk: peers as lights, routes as arcs. The data comes from `MeshComputeRegistry` and the peer list.
- **VPN country picking** on the globe (`VpnRoute`).
- **Server role:** allowed, with a battery and heat warning. Headsets are almost always on Wi-Fi, so hole punching rarely applies; the existing mobile-data-only rule already covers this.
- **Verify on each runtime:**
  - whether `VpnService` is permitted (Horizon OS and Android XR policies differ)
  - whether Wi-Fi Direct and LocalOnlyHotspot exist for the access point

  Hide what is not there.

**Done when:** the headset joins the mesh, resolves mesh names, uses a VPN route and serves as a mesh node where the OS allows it.

### Phase 21 — Wallet, PrismCoin, markets and payments

**Builds on:**
- [WalletPageView.kt](app/src/main/java/com/prism/launcher/wallet/WalletPageView.kt), `WalletVault.kt` and `WalletKeystoreCipher.kt`.
- `PrismCoin*` (psc), `WalletNetwork.kt`, `CoinConvertDialog.kt` and `MiningService.kt`.
- `ComputeMarketView.kt`, `ComputeDebtLedger.kt`, `PayLaterPolicy.kt`, `ModelShopView.kt`, `ModelCheckoutActivity.kt`, `ModelPayments.kt`, `ModelRefunds.kt` and the helper package (bills).

**Work:**
- **The wallet object:** a wallet on the desk. Opening it shows coins as stacks sized by balance, and receive-QR cards. Sending needs a confirm gesture (pinch and hold), plus the wallet unlock.
- **Mining:** off by default on headsets (Phase 7). Pay-later and the compute market are unchanged.
- **The phrase:** never displayed while a cast or recording is active (Phase 13).

**Done when:** receiving, sending, converting and paying for a mesh model all work with the same ledgers as the phone.

### Phase 22 — Models and on-device AI infrastructure

**Builds on:**
- [ModelsPageView.kt](app/src/main/java/com/prism/launcher/ModelsPageView.kt), `ModelStoreView.kt`, `ModelDownloadManager.kt`, `QuantPickerDialog.kt`, `QuantizationService.kt`, `PrismQuantizer.kt` and `SafetensorQuantizer.kt`.
- `GgufInferenceService.kt` and the vendored llama.cpp.
- `NpuSupport.kt`, `AndroidNpuReporter.kt` and `PrismSwap.kt`.
- `MeshInference.kt`, `MeshAiHost.kt`, `P2pModelRegistry.kt` and `OllamaDiscoveryService.kt`.

**Work:**
- **NPU support:** XR2-class chips are Snapdragon with a Hexagon NPU. `NpuSupport` gains their profiles, and the vendored ggml Hexagon backend applies.
- **Memory:** headsets have less spare RAM than phones while rendering. `PrismSwap` and `InferenceMode` choose smaller defaults, and mesh offload to a trusted desktop is suggested first.
- **The Models page:** becomes a shelf of model "cartridges" (Workshop) with size and speed labels.

**Done when:** a 1–3 B GGUF runs on-headset at an acceptable speed, and larger models offload transparently to a trusted desktop.

### Phase 23 — The creative suites

**Builds on:**
- [AssetCraft.kt](core/src/main/kotlin/com/prism/launcher/assetcraft/AssetCraft.kt), `MeshScene.kt` (meshes, bounds, animation, nodes, camera), `GameCraft.kt`, `GameBuild.kt` and `GameCode.kt`.
- `ImageCraft.kt`, the videocraft package, `AudioCraft.kt`, `DatasetCraft.kt`, `ModelCraft.kt` and `ModelCraftPageView.kt`.
- The photopix package and `PhotoPixView.kt`.
- `PhotosPageView.kt` and `PhotoBackup.kt`.
- `EditorPageView.kt` and `TimeMachine.kt`.

**Work:**
- **AssetCraft and GameCraft:** generated or imported meshes stand in the room at true scale, and you can walk around them, grab them and scale them. This is the most natural VR fit in Prism. GameCraft games built for a flat screen play on a panel. Phase 38's stereo techniques can apply to them because Prism owns their renderer.
- **VideoCraft:** films play in the cinema environment (Phase 25), and the storyboard is cards on the desk.
- **PhotoPix:** layers explode into depth so you can see the stack, while editing stays on the panel.
- **Photos:** a spatial gallery. Spatial (stereo) photos, from depth estimation (Phase 37) or from stereo captures where the headset can take them.
- **ModelCraft and DatasetCraft:** training graphs (`VoiceTrainingGraphView`) as panels, and datasets as folders on the desk.
- **The code editor:** multi-panel. The file tree, editor and terminal become three slates, with the Writer keyboard or a physical keyboard.

**Done when:** a mesh generated in AssetCraft can be placed on the real floor, and a VideoCraft film plays in the cinema.

### Phase 24 — Speech

**Builds on:**
- [PrismSpeaker.kt](core/src/main/kotlin/com/prism/launcher/speech/PrismSpeaker.kt), `KokoroEngine.kt` and the clone engines (`OpenVoice`, `KnnVc`, `FreeVc`, `SeedVc`, `PocketTts`).
- `Dictation.kt`, `WhisperEngine.kt` and `VoiceInputController.kt`.
- `KokoroMeshShare.kt` and `SpeechMeshServer.kt`.

**Work:**
- **Spatialised TTS:** speech comes from whoever is speaking (Aether, a character, a tutor), not from everywhere.
- **Dictation:** a mic button on every field plus a push-to-talk wrist gesture.
- **Heavy voice conversion:** offloaded over the mesh as now.

**Done when:** every TTS surface is spatial and every text field takes dictation.

### Phase 25 — Social, media and learning

**Builds on:**
- [LykeView.kt](app/src/main/java/com/prism/launcher/social/LykeView.kt), `LykeStore.kt` and `LykePipActivity.kt`.
- `NebulaSocialPageView.kt` and `NebulaEngine.kt`.
- The Stremio packages (`TorrentStreamer.kt`, `DebridClient.kt`, `StremioPlayback.kt`).
- The language packages (`LessonRunner.kt`, `ScenarioBank.kt`, `TutorPortrait.kt`, `TutorFace.kt`, `LearningPathView.kt`).
- `JournalPageView.kt`.

**Work:**
- **The cinema environment:** a dimmable theatre (or a darkened passthrough room) for Stremio and long-form video, with a big curved screen and spatial audio.
- **Lyke:** a vertical video wall you swipe up through. PiP (picture-in-picture) becomes a small screen you can leave on a shelf.
- **The language tutor:** the tutor's portrait becomes a 3D tutor at the table. Conversation lessons happen inside their `ScenarioBank` situation (a café, a station) as a light environment, which turns rehearsal into immersion. The learning path is a winding trail on the tabletop.
- **Journal:** a physical notebook, written by voice or keyboard.

**Done when:** a conversation lesson runs inside its scenario with the tutor present.

### Phase 26 — Games

**Builds on:**
- [Arcade.kt](core/src/main/kotlin/com/prism/launcher/minigames/Arcade.kt) and `ArcadeExpansion.kt`.
- `Chess.kt` and `ChessReview.kt`, `Pong.kt`, `Pinball.kt` and `Minesweeper.kt`.
- PaperWar: `PaperBase.kt`, `WorldMap.kt`, `Battle.kt` and `PaperWarView.kt`.
- [Fps.kt](core/src/main/kotlin/com/prism/launcher/minigames/Fps.kt) (the shared first-person floor), `Doom.kt`, `Zombies.kt` and `ZombieNet.kt`.
- `EchoChamber.kt` and `EchoAssets.kt`.
- `GeoTagStore.kt`, `GeoTagNet.kt` and `MinigameMesh.kt`.

**Work:**

| Game | VR form |
|---|---|
| Arcade (13 + 6 games) | Real arcade cabinets in a corner of the room. The game renders on the cabinet screen and you play with the stick and buttons. |
| Chess | A real board on your desk. Pieces are picked up by hand, and the review is spoken by the coach. |
| Pong, Pinball | Physical tables, with your paddle on your hand. Pinball's segment table becomes geometry with real flippers on the triggers. |
| Minesweeper | Pencil tiles on a paper sheet on the desk. |
| PaperWar | A tabletop strategy game. The pencil-drawn world map unrolls across your real desk, bases are paper cut-outs, and battles play out on the table. It is the purest skeuomorph in Prism. |
| Echo Chamber | Full immersion. The maze becomes a room-scale or seated stereo world. The hunter hears you through the real microphone, as it already does. |
| Zombies, DOOM | True stereo first-person: `Fps.kt`'s renderer gains two eye cameras and head-look. Multiplayer is unchanged through `ZombieNet`. |
| GeoTag | Mixed-reality art on real surfaces. Its ARCore placement logic moves to the OpenXR planes of Phase 8, and mesh multiplayer is unchanged. |

**Done when:** chess, PaperWar, Echo Chamber and Zombies are native VR, and the arcade cabinets work.

### Phase 27 — Science

**Builds on:**
- [ProteinPanel.kt](app/src/main/java/com/prism/launcher/science/ProteinPanel.kt), `StructureView.kt` and the protein package (`FoldingModel.kt`, `ProteinChemistry.kt`).
- `MeshFolding.kt` and `ProteinHost.kt`.
- `CosmicRayPanel.kt`, `RfSurveyPanel.kt`, `ClinicalPanel.kt`, `Instruments.kt` and `LabNotebook.kt`.

**Work:**
- **Proteins:** a predicted structure becomes a molecule you hold and rotate, with confidence as colour along the backbone.
- **The RF survey:** samples placed in real space where they were measured, which is a natural VR heat map.
- **Audiometry** (`Instruments.Audiometry`): a headset has calibrated, sealed audio close to the ear, so this suits it. Its calibration needs re-validation per headset model.
- **Spirometry:** uses the headset microphone, with validation needed.
- **The cosmic-ray detector** needs camera frames:
  - Meta's Passthrough Camera API (with permission, on newer Horizon OS) might supply them.
  - Elsewhere it is unavailable, so it is hidden.
- **The lab notebook:** as is, on a physical notebook.

**Done when:** a structure can be folded on the mesh and held in the hand.

### Phase 28 — Maps, mail, system tools and settings

**Builds on:**
- [MapsPageView.kt](app/src/main/java/com/prism/launcher/maps/MapsPageView.kt), `OsmMapView.kt`, `MapTiles.kt` and `MapServices.kt`.
- The email packages (`EmailServer.kt`, `EmailClient.kt`).
- `TaskManager.kt`, `SystemHealth.kt`, `DiagnosticsActivity.kt`, `PrismLogger.kt`, `UpdateCenter.kt` and `UpdateSandbox.kt`.
- `Pdb.kt` and `PdbTerminalActivity.kt`.
- [SettingsActivity.kt](app/src/main/java/com/prism/launcher/SettingsActivity.kt) and `SettingsCatalog.kt` (every setting, declared once).

**Work:**
- **Maps:** a tabletop map of OSM tiles. Pinch to zoom, and you can see routes and trusted devices' locations. The headset has no GPS, so its own position comes the way the desktop's does (`DesktopGeolocation.kt`'s Wi-Fi estimate) or from the paired phone.
- **Settings:** a control panel generated from `SettingsCatalog`, so every setting appears automatically, with toggles as rocker switches and choices as dials (Workshop). VR adds its own section: comfort, input, passthrough, room and stereo profiles.
- **The update center:** quarantines VR APKs the same way, and its repositories gain a VR asset.
- **The PDB terminal and diagnostics:** panels. The task manager also shows XR runtime stats.
- **The mail client and server:** unchanged as panels.

**Done when:** every setting in `SettingsCatalog` is reachable in VR, including the new VR section.

### Phase 29 — Cloud storage and cloud gaming

**Builds on:** [CloudGaming.kt](app/src/main/java/com/prism/launcher/cloud/CloudGaming.kt), `CloudGamingActivity.kt`, `CloudStorage.kt`, `CloudHost.kt`, `CloudVault.kt`, `CloudManifest.kt`, and on the desktop `GameHost.kt`, `SteamLibrary.kt` and `GameSandbox.kt`.

**Work:**
- **Cloud gaming:** a Steam game running on a mesh PC streams to a huge curved screen, with controllers as a gamepad. This is "PC games in a headset" over the mesh, using what already exists.
- **Cloud storage:** a drawer in the cabinet.

**Done when:** a Steam game hosted by a trusted desktop is played on the headset with controllers.

### Phase 30 — Virtualization: guest OSes, Windows programs and Android apps

**Builds on:**
- [VmController.kt](app/src/main/java/com/prism/launcher/virtualization/VmController.kt) and `VncSurfaceRenderer.kt`.
- [NineRuntime.kt](app/src/main/java/com/prism/launcher/virtualization/NineRuntime.kt), `WineContainer.kt` and `WineSession.kt`.
- [VirtualAppHostService.kt](app/src/main/java/com/prism/launcher/virtualapp/VirtualAppHostService.kt), `VirtualAppEmbedder.kt`, `VirtualAppRunner.kt`, `VirtualPackage.kt`, `VirtualContext.kt`, `SystemServiceHook.kt` and `VaultService.kt`.
- The core virtualapp package (`DalvikInterpreter.kt`, `AndroidSurface.kt`).

**Work:**
- **Guest OS displays:** a guest OS's VNC display renders into a panel, which makes a virtual monitor for PrismOS or an ISO.
- **Windows and Linux programs** run through NINE (x86-64 → AArch64, and XR2 is AArch64). Each window is a panel. `third_party/nine` is never edited; only Prism's side changes.
- **Android app virtualization:** the `SurfaceControlViewHost` path already hands Prism a `SurfacePackage` and an input channel for each virtualized app. In VR that surface is placed on a panel, and touches arrive through the same channel. This is the foundation of Part D.

**Done when:** a virtualized Android app, a Wine program and a VM each show as a panel and take input.

### Phase 31 — Prism Writer as a keyboard object

**Builds on:** [PrismKeyboardView.kt](app/src/main/java/com/prism/launcher/writer/PrismKeyboardView.kt), `KeyboardLayout.kt`, `SwipeDecoder.kt`, `Autocorrect.kt`, `WriterSuggestions.kt`, `WriterEmojiPanel.kt`, `WriterGifPanel.kt` and `WriterKeyboard.kt` (desktop).

**Work:**
- **Poke typing:** a physical keyboard laid on the desk, built from `KeyboardLayout`'s key grid. Keys depress, and audio and haptics confirm each press.
- **Ray glide:** drawing across the keys with a ray uses `SwipeDecoder` unchanged.
- **Suggestions:** the strip floats above the keys. Emoji and GIF panels are trays.
- **Physical keyboards:** a Bluetooth keyboard is detected and shown in passthrough, and the virtual keyboard steps aside.

**Done when:** a paragraph can be typed by poke or glide at a usable speed, with autocorrect and suggestions.

### Phase 32 — Desktop and phone interop

**Builds on:** [DesktopDisplay.kt](desktop/src/main/kotlin/com/prism/desktop/DesktopDisplay.kt), `DesktopMirror.kt`, `DesktopPageMirror.kt`, `AdbBridge.kt`, `PdbAdb.kt`, `ProfileArchive.kt` and `TimeMachine.kt`.

**Work:**
- **Pages to the headset:** the desktop app gains "Send this page to the headset" (the EXTEND path) and "Mirror this screen to the headset".
- **Debugging:** the headset shows up in the desktop's `adb` through PDB, exactly as phones do, which makes development of Prism VR itself easier.
- **Profiles:** move between phone, desktop and headset, and VR workspaces travel with them.

**Done when:** a desktop page is extended to the headset with one click, and the headset is debuggable through PDB.

### Phase 33 — Plugins, dynamic pages and the theme creator

**Builds on:** [PrismPlugin.kt](core/src/main/kotlin/com/prism/launcher/plugins/PrismPlugin.kt) (a node-tree contract), `PluginPages.kt`, `DynamicPageLoader.kt`, `PluginRenderer.kt` (desktop), `ThemeEditorActivity.kt` and `ThemeDragCanvas.kt`.

**Work:**
- **Plugins:** they already describe their UI as a node tree. A VR renderer for that tree lets plugins appear as panels or simple 3D objects without any plugin changes.
- **The theme creator in VR:** drag primitives in 3D and set materials (Phase 9) with a live preview.

**Done when:** an existing plugin page renders in VR without being rebuilt.

---

## Part D — Turning mobile apps into VR apps

The goal is to take ordinary Android apps and present them as VR apps. At minimum, the app's UI is shown to *both eyes* so it reads as a real object in 3D space. At best, it has real depth, head-tracked control and controller input.

Two things define the approach:

1. **The correct stereo picture of a 2D app is not two identical images side by side.** The app's single image is placed on a surface *in 3D space*, and each eye sees that surface from its own position. That is "the same UI in both eyes" done right: the brain fuses it as a solid screen at a real depth. Identical side-by-side halves are only correct for phone-in-a-viewer headsets, which Phase 35 also covers.
2. **Prism hosts the apps itself.** Android does not let one app launch another app's activities onto a private virtual display. Prism's own runtime, `:virtualapp` with `SurfaceControlViewHost` (Phase 30), is how an app's pixels and input reach Prism, and it is why Prism can go further than any launcher: the app's code runs inside a process Prism controls. This matches Prism's rule of using its own runtime and never an emulator.

### Phase 34 — The app library in VR

**Builds on:**
- [VirtualizedAppLauncher.kt](app/src/main/java/com/prism/launcher/virtualization/VirtualizedAppLauncher.kt), `VirtualizedAppVault.kt`, `VirtualAppProbe.kt` and `VirtualAppCheckService.kt`.
- `AndroidAppCatalog.kt`, `TrustedAppTransfer.kt`, `TrustedApps.kt` and `AppStoreCatalog.kt`.

**Work:**
- **Every installed app:** shown on the drawer shelf. Each one carries a verdict from `VirtualAppProbe` ("would this run inside Prism?"): runs in Prism, runs natively only, or won't run.
- **"Open in VR"** runs the app in Prism's virtualization and places its surface on a panel (Phase 30). Apps that cannot be virtualized open as the runtime's own 2D panel, which Prism launches and cannot alter.
- **Apps from the phone:** move them from a trusted phone with `TrustedAppTransfer`, which already exists for virtualization.
- **Per-app VR profiles:** screen size, distance, curvature, stereo mode, depth strength, input mapping and refresh. Stored with the vault, and shareable over the mesh (Phase 39).

**Done when:** ten popular apps open in VR panels from the shelf, each with a remembered profile.

### Phase 35 — Stereo presentation modes

**Work:**
- **Mode A, the screen in space (default).** The app's surface is a quad or cylinder compositor layer at the profile's distance and size. Both eyes see it with correct parallax, so it is solid, sharp and comfortable. A 2D app needs nothing more to look like it belongs in VR.
- **Mode B, theatre.** The same surface very large and curved, in the cinema environment, for video and games.
- **Mode C, viewer split for phone headsets** (Cardboard-style viewers, where the phone *is* the display):
  - Prism splits its own screen into two eye viewports.
  - Both halves show the same app surface, each rendered from its eye's position (the Mode A picture computed on the phone).
  - Lens correction: barrel distortion and chromatic-aberration correction per viewer profile (QR viewer parameters).
  - IPD: adjustable.
  - Head tracking: the phone's rotation-vector sensor, rendered with low-persistence timing.

  This makes the phone flavor a VR device too.
- **Recentering and comfort:** apply to all three, and the panel never follows the head.

**Done when:** one app shows correctly in all three modes, and Mode C works in a standard viewer on a phone.

### Phase 36 — Input translation

**Builds on:**
- [SystemServiceHook.kt](app/src/main/java/com/prism/launcher/virtualapp/SystemServiceHook.kt) and `VirtualContext.kt`: the virtualized app's system services already pass through Prism.
- `AndroidSurface.kt` (core): the framework surface an app's startup touches.

**Work:**
- **Touch:** the ray and poke of Phase 4 become multi-touch on the app's surface: one hand per pointer, with two-hand pinch-zoom.
- **Gamepad:** controllers present as a standard Android gamepad (`InputDevice.SOURCE_GAMEPAD`) to apps that take one. Apps that only take touch get **touch mapping profiles**: buttons mapped to screen regions, and sticks to virtual joysticks, as on handheld emulators.
- **Head pose as the phone's sensors.** The virtualized app's `SensorManager` is served by Prism:
  - the rotation vector, gyroscope, accelerometer and magnetometer come from the headset's head pose
  - "gyro aim" in games becomes head-look
  - AR-style apps that turn with the phone turn with your head
  - each app profile chooses which axes are mapped, and with what sensitivity and recentering

  This is the single biggest step from "a 2D app in VR" to "a VR app".
- **Orientation:** apps that lock to portrait or landscape get a panel of the matching shape. Rotation requests resize the panel, not the world.

**Done when:** a gyro-aimed mobile shooter is played with head aim and controller buttons, and a touch-only game is played through a mapping profile.

### Phase 37 — Depth for 2D apps

Depth makes a flat app look three-dimensional on its panel. There are two sources.

**A. Depth from the view hierarchy (apps Prism hosts).** A virtualized app's views live in a process Prism controls. `VirtualAppRunner` can read the window's view tree, each view's bounds, elevation and translation Z, and its drawing order. Each layer is rendered to its own surface and offset in depth by its elevation and nesting:
- cards float above the list
- dialogs float above the screen
- the FAB floats above everything

This is real stereo parallax from the app's own structure, with no guessing. It works for most standard-widget apps, and is off for apps that draw everything into one canvas.

**B. Depth from pixels (anything).** A monocular depth model (a small Depth-Anything or MiDaS-class network, run through the existing ONNX path, `OnnxAccelerators`/NNAPI, or the Hexagon NPU) estimates depth per frame. Each eye's image is then synthesised by depth-image-based rendering:
- disocclusion inpainting
- temporal smoothing to stop shimmer
- a comfort clamp on maximum disparity

This applies to games, video and photos (it also gives spatial photos in Phase 23). It is expensive, so it is used per app profile, with frame-rate and thermal guards, and can be offloaded to a trusted desktop over the mesh as `MeshInference` already does for models.

ModelCraft could train a depth model ("Depth" as a ModelCraft kind is a natural follow-up), but the first version should use a proven pretrained network.

**Done when:** a standard-widget app shows layered depth, a video shows estimated depth without shimmer, and both respect the comfort clamp.

### Phase 38 — True stereo for 3D games

For games that render real 3D scenes with OpenGL ES or Vulkan, Prism can produce *true* stereo. That is possible because the game's code runs inside Prism's virtualization process, where Prism controls which graphics libraries it loads.

**Work:**
- **Interception:** Prism provides its own `libEGL`/`libGLESv3` and Vulkan layer shims to the virtualized process. It never patches the game's files, and never runs in an emulator.
- **Mode 1, depth reprojection.** Capture each frame's colour and depth buffer at `eglSwapBuffers` / `vkQueuePresentKHR`. Reproject that frame into the second eye using the depth buffer (or both eyes from a centre view). This is cheap, works for most games, and has small disocclusion artefacts.
- **Mode 2, geometry stereo.** Identify the game's projection and view matrices as they are uploaded (`glUniformMatrix4fv`, uniform buffers, push constants) by per-game profile or heuristics. Render each frame twice with per-eye offsets, so both eyes see true geometry. Head-tracked camera injection makes the game's camera follow the head where the profile maps it. This is the most convincing mode, and the most per-game work.
- **Budgets:** both modes keep the panel (Mode A) as the fallback, and drop to it automatically if the game misses frame budget twice in a row.
- **Per-game profiles:** which mode to use, matrix locations, depth range, convergence and separation, and UI-layer handling (HUD at screen depth). They are shared through Phase 39.

**Online games:** anti-cheat systems can treat graphics interception as tampering. Profiles must mark online games, and Mode 2 should be refused for games that forbid it. Prism's interception only ever runs in its own sandboxed process, on games the user chose.

**Done when:** an offline 3D mobile game plays in true stereo with head-look, and falls back gracefully when it cannot keep up.

### Phase 39 — Profiles, sharing and the VR app shelf

**Builds on:** [AppStoreCatalog.kt](core/src/main/kotlin/com/prism/launcher/appstore/AppStoreCatalog.kt), `AppStoreInstaller.kt`, `PublisherStore.kt`, `AppFingerprint.kt` and `SocialMeshSync.kt` (mesh sharing patterns).

**Work:**
- **App profiles** (Phases 34–38) are small signed documents keyed by `AppFingerprint`. They are shared across the mesh and rated, so one person's work getting a game into true stereo helps everyone.
- **Comfort ratings** per profile (comfortable, moderate, intense) are shown before launch.
- **App Store category:** a "Works in VR" category in the App Store catalogue, with the probe verdict and the best profile.

**Done when:** a profile made on one headset is found and applied on another.

### Phase 40 — Hardening and release

**Work:**
- **Device QA matrix:** Quest 3/3S, an Android XR device, Pico 4 Ultra, Vive XR Elite, and a phone in a viewer (Mode C).
- **Performance certification** against each store's requirements: Meta's VRC frame and comfort rules and Android XR's quality guidelines. Keep a clear account of what a launcher-replacement may do on each OS.
- **Store policies:** Prism virtualizes other apps and intercepts graphics. Before submitting, review each store's rules on code loading and app hosting. Sideloading and Prism's own update channel (`UpdateCenter`) remain the reliable route.
- **Docs:** the README gains a VR section, and the onboarding content gains the VR tour.

**Done when:** the VR flavor ships through Prism's update channel and passes its performance targets on every device in the matrix.

---

## Appendix A — What a headset cannot do

| Feature | Reason | What happens instead |
|---|---|---|
| Carrier calls, SMS and the phone plan (`PrismInCallService`, `SmsRelayReceiver`, `CallRecorder`, `CarrierVoicemail`, `PrismPhonePlan`) | No telephony radio or SIM. | Handled by the paired phone: relayed SMS, call handoff, and visual voicemail through the phone. |
| GPS | No GNSS receiver. | A Wi-Fi estimate (`DesktopGeolocation`'s method) or the paired phone's fix. |
| ARCore (GeoTag) | Not on Quest or Pico. | OpenXR planes and anchors (Phase 8). |
| Camera frames (cosmic-ray detector, selfie video, photo capture) | Restricted. Some runtimes expose a permissioned passthrough camera API. | Used where permitted, hidden elsewhere. Video calls send an avatar. |
| Google Play services | Absent on Quest and Pico. | Prism already avoids depending on them for core features. Each MediaPipe path must be verified. |
| Replacing the system launcher | Often not allowed. | Prism is an immersive app the user starts. The Room is Prism's home inside it. |
| Mining by default | Heat and battery. | Off; an explicit override is needed (Phase 7). |
| `VpnService`, Wi-Fi Direct, hotspot | These vary by OS policy. | Checked at runtime, and hidden when unavailable (Phase 20). |

## Appendix B — Feature coverage

Every feature area in the codebase, and the phase that brings it to VR:

| Area (packages) | Phase |
|---|---|
| Launcher shell, pages, slots, picker, grid, folders, halo, widgets, drawer (`launcher/*`, `widgets`, `SlotAssignment`) | 3, 6, 11 |
| Theming, theme creator, fonts (`theme`, `PrismThemes`, `ThemeXml`, `PrismFontEngine`) | 5, 9, 10, 33 |
| Onboarding, lock, duress, medical (`onboarding`, `lock`) | 12, 13 |
| Notifications, history, diagnostics, logger, health (`notifications`, `history`, `health`, `PrismDiagnostics`) | 12, 28 |
| Browser, P2P DNS, web hosting and cache, blocklist, downloads, install as app (`browser`, `webapp`, `search`) | 14 |
| Messaging, Sam, AI backends, characters, CakeChat, agentic tools, SMS relay, RCS (`messaging`, `characters`, `cakechat`, `agentic`) | 15 |
| Nora, Aether (`nora`, `aether`) | 16 |
| Mesh and carrier calls, video, group, voicemail, greetings, recording (`calls`) | 17 |
| Files, drives, USB, compression, hosted and bridged storage, format and burn (`files`, `storage/*`) | 18 |
| Trusted devices, keys, clipboard, locations, app transfer, displays, mirroring, remote pointer, mouse (`trusted`, `clipboard`, `display`, `mouse`) | 19 |
| Meshnet, VPN protocols, server roles, directories, hole punching, proxy, access point (`core/*`, `mesh`, `vpn`, `accesspoint`) | 20 |
| Wallet, PrismCoin, coins, mining, compute market, model shop, payments and refunds, helper bills (`wallet`, `mesh`, `helper`) | 21 |
| Models, model store, quantisation, NPU, swap, GGUF, mesh inference, Ollama (`messaging`, `quant`, `platform`) | 22 |
| ImageCraft, VideoCraft, AudioCraft, AssetCraft, GameCraft, DatasetCraft, ModelCraft, PhotoPix, Photos, editor, Time Machine | 23 |
| Speech: Kokoro, voice cloning, dictation, Whisper, speech mesh (`speech`, `voice`) | 24 |
| Nebula, Lyke, Stremio and torrents, language tutor, journal (`social`, `stremio`, `language`, `journal`) | 25 |
| Minigames: arcade, chess, pong, pinball, minesweeper, PaperWar, Echo Chamber, DOOM, Zombies, GeoTag (`minigames`) | 26 |
| Science: proteins, folding on the mesh, cosmic rays, RF survey, clinical instruments, lab notebook (`science`, `protein`) | 27 |
| Maps, email client and server, task manager, settings, update center, PDB (`maps`, `email`, `taskmanager`, `update`, `pdb`) | 28 |
| Cloud storage and cloud gaming (`cloud`, desktop `games`) | 29 |
| VMs, Wine and NINE, Android app virtualization and vault (`virtualization`, `virtualapp`, `nine`) | 30, 34–38 |
| Prism Writer keyboard (`writer`) | 31 |
| Desktop interop: display, mirror, ADB bridge, profiles (desktop) | 32 |
| Plugins and dynamic pages (`plugins`, `PluginPages`) | 33 |
| Background services and notifications (VideoCraft, ImageCraft, quantisation, compression, mining, training) | 7, 12 |
