# Prism audit — desktop parity (Scope A) and global code health (Scope B)

Date: 2026-10-22
Tree: `F:\AndroidStudioProjects\Prism`
Method: static reading of the real sources. Every claim below was verified by reading the file and
line cited; nothing is taken from a comment, a README, or a prior audit.

## Limits of this audit (read before trusting the negative results)

* **No build, no test run, no `grep`/`find` via shell.** `pwsh` cannot start in this session:
  every invocation fails at workspace provisioning (`SetNamedSecurityInfoW failed (Win32 5):
  grantWrite(F:\AndroidStudioProjects\Prism)`). Approval prompts are disabled for this
  subagent, so the sandbox could not be repaired from inside. All findings are therefore
  static-analysis findings; none is a runtime reproduction.
* **The tree contains a shadow source tree.** `core/bin/main/**/*.kt` (178 files) and
  `desktop/bin/main/**/*.kt` (29 files) are stale *copies of source files*, not build output.
  They are older than `src/` (e.g. `getDatasetAutoDownloadEnabled` sits at
  `core/bin/.../PrismSettings.kt:1042` while the same declaration is at
  `core/src/.../PrismSettings.kt:1092`; `desktop/bin/.../BrowserPage.kt` has the
  `getAutoMirror()` guard at line 322 vs line 393 in `src`). Every repo-wide text search is
  inflated by them; all counts below were re-checked against `*/src/` only. This is reported as
  its own finding (B5).
* Consequently "not referenced anywhere" below means "no reference in any `*/src/` file",
  verified by reading the referencing files, not by a compiler.

---

# Scope A — desktop parity

## A1. Page inventory

`PageId` is declared at `desktop/src/main/kotlin/com/prism/desktop/ui/PrismWindow.kt:205-267`
with 49 entries. `PageBody` routes 47 of them (`PrismWindow.kt:151-202`); the `else ->
NotYetPortedPage(page)` arm (line 201) is reachable by exactly **one** entry.

| Status | Count | Entries |
|---|---|---|
| Real page composable, routed, `ported = true` | 48 | NORA_CHAT, NORA_TRAIN, NORA_SETTINGS, MODEL_TEST, AETHER, DESKTOP, BROWSER, CLOUD_AI, BLOCKLIST, AGENTIC, MODELS, IMAGE_GEN, SAM, MESSAGES_PAGE, NEBULA, TRUSTED, WALLET, WALLET_EXTRAS, SEARCH, ACCESS_POINT, TUNNEL, VIRTUALIZATION, NORA_TOOLS, APPS, FILES, SETTINGS, DIAGNOSTICS, MESH, NOTIFICATIONS, QUANTIZE, PLUGINS, CHARACTERS, STREMIO, SPEECH, KOKORO, VOICE_CLONING, LANGUAGE, GAMES, EDITOR, SCIENCE, PROTEINS, LOCK, LYKE, WRITER, MODEL_SHOP, NODES, GAME_HOST |
| Declared, **not** implemented (`ported = false`) | 1 | `MESSAGES` (`PrismWindow.kt:241-245`) |
| Android slot with **no desktop `PageId` at all** | 1+ | `SlotAssignment.Cloud` (mesh cloud/compute), plus plugin `SlotAssignment.Custom` |

All 48 routed entries resolve to a non-trivial composable — I checked each page's head and
size; none of the 48 is a placeholder. The smallest are wrappers (`PageId.SAM ->
ConversationPage()`, `PageId.MODEL_TEST -> ModelTestPage()` in `NoraPages.kt:415`), the largest
are real ports (`NebulaPage.kt` 1117 lines / 4 screens, `VirtualizationPage.kt` 325 lines with
QEMU + RFB, `EditorPage.kt` ~1000 lines with JCEF + Node).

### The unported page, and why it is confusing

`PageId.MESSAGES` (`PrismWindow.kt:241-245`) is a *second* entry labelled "Messages", marked
`ported = false`, whose blocker text says "AI conversations port directly. SMS/MMS does not
exist on desktop". Directly above it, `PageId.MESSAGES_PAGE` (line 226) is labelled "Messages"
too and routes to a real `MessagesPage()` (line 166). Consequences:

* The rail shows two rows both reading **"Messages"**: one opens the working page, one opens a
  grey "not ported" card (`PrismWindow.kt:309`, the `NOT YET PORTED` section) that tells the
  user Messages is missing while it is one row above them.
* `prism gui messages` resolves to the **unported** one: `Main.kt:5376-5378` does
  `PageId.entries.firstOrNull { it.name.equals(page, ignoreCase = true) }`; `"messages"` does
  not equal `"MESSAGES_PAGE"`, so it matches `MESSAGES` and the console opens the placeholder.
  **Severity: major** (a documented entry point opens the wrong page).

## A2. Implemented but unreachable from the rail

* **`KineticHaloPage`** (`desktop/.../ui/KineticHaloPage.kt:60`) has no `PageId`. It is
  reachable only through the launcher shell's `SlotAssignment.KineticHalo`
  (`LauncherShell.kt:233`). Android offers Kinetic Halo as a page choice
  (`PagePickerAdapters.kt:161`), so on desktop it is invisible unless the user is in launcher
  mode *and* has assigned it. **Severity: minor.**
* **Eight pages are unreachable in launcher mode** even though the rail implements them —
  see A3. That is the mirror image of this section and the bigger problem.

## A3. Two shells disagree about what is ported (the central parity defect)

`LauncherShell.SlotPage` (`LauncherShell.kt:228-244`) routes only 8 slot kinds to real pages
and sends everything else to `UnportedSlot` (`LauncherShell.kt:247-302`). But the rail routes
real pages for those same features:

| Slot | `LauncherShell` says | Reality in rail |
|---|---|---|
| `NebulaSocial` | "the feed UI is 675 lines of Android views and is the largest single screen left" (`:249-251`) | `NebulaPage()` — 1117 lines, 4 screens, wired at `PrismWindow.kt:168` |
| `VirtualizationOs` | "Android Virtualization Framework is Android-only; desktop would drive QEMU or libvirt" (`:252-253`) | `VirtualizationPage()` — QEMU + VNC, 325 lines, `PrismWindow.kt:195` |
| `Wallet` | "Desktop needs an equivalent WalletCipher before this can open a wallet safely" (`:256-259`) | `WalletPage()` + `DesktopWalletCipher` (DPAPI), `PrismWindow.kt:170` |
| `Editor` | "PHASE 93 … what is left is the Node runtime and the terminal" (`:266-269`) | `EditorPage()` with JCEF + `EditorHost`/`NodeHost`, `PrismWindow.kt:186` |
| `Science` | "PHASE 96 … need a per-platform implementation" (`:270-273`) | `SciencePage()` + `DesktopSensors`, `PrismWindow.kt:187` |
| `Notifications` | "Capturing notifications is not [portable]" (`:274-276`) | `NotificationsPage()` + `NotificationCapture`, `PrismWindow.kt:176` |
| `Language` | "This is a UI port, plus text to speech … before the spoken half works" (`:277-280`) | `LanguagePage()` 1000+ lines, `PrismWindow.kt:184` |
| `Minigames` | "PHASE 95 … the views are hand-drawn Canvas work" (`:281-283`) | `MinigamesPage()` + Pong/Chess/PaperWar, `PrismWindow.kt:185` |

Consequence: a user who prefers the launcher shell (the Android navigation model, which
`PrismWindow.kt:52-58` advertises as "not a lesser version of the other") is told eight
features do not exist on this platform, with authored reasons that are now false. Switching
one toggle changes the product's feature list. **Severity: major** (systemic; the block text
is stale, not the code).

Related, same file:

* `SlotAssignment.Messaging -> NoraChatPage()` (`LauncherShell.kt:236`) while the rail's
  "Messages" is `MessagesPage()`. Two entries with the same name open different products in
  the two shells. **major.**
* `SlotAssignment.ModelStore -> ModelsPage()` (`LauncherShell.kt:238`) while the rail has a
  separate `ModelShopPage()` for the market (`PrismWindow.kt:192`). "Model Store" opens the
  library, not the store. **major.**
* `SlotEditorDialog`'s add-page list (`LauncherShell.kt:414-421`) offers 11 options and omits
  `Wallet, Editor, Science, Notifications, Language, Minigames, Cloud, Custom` — i.e. 8 of the
  19 slot kinds Android offers (`PagePickerAdapters.kt:156-176`). Those pages therefore cannot
  be assigned at all in launcher mode. **major.**

## A4. Desktop-only features (verified)

| Feature | Where | Note |
|---|---|---|
| Workbench rail + launcher-shell toggle | `PrismWindow.kt:113-137`, `PrismSettings.getDesktopMobileMode` (`PrismSettings.kt:1186`) | no Android equivalent of the rail |
| 70+ console commands | `Main.kt:442-570` | `info/sizes/bench/selftest/train/generate/expose/hallucinate/image/see/dictate/whisper-model/speak/language/games/editor/science/protein/lock/lyke/writer/node/market/gamehost/virtualapp/chat/relay/mesh/trust/dns/nebula/threads/tls/host/mirror/dns-server/vpn/wallet/search/aether/profile/hotspot/models/bench-gguf/video/backup/commands/train-bg/use-model/shell/notifications/quantize/plugins/randomx/diagnose` |
| Replace the Windows shell | `ShellReplacement.kt`, UI at `PrismSettingsPage.kt:494-577` | registry + escape hatch |
| Cloud-gaming **host** | `Main.kt:158-179` (`hasSteam`, `/cloud` route), `games/GameHost.kt`, `GameHostPage.kt` | desktop-only eligibility; gated on `GameSandbox.canEnforce()` |
| Node process host | `wallet/DesktopNodeHost.kt`, `NodesPage.kt` | spawns real node binaries |
| QEMU guest + RFB client | `vm/QemuVm.kt`, `vm/VncClient.kt`, `VirtualizationPage.kt` | Android uses AVF |
| JCEF/Chromium browser | `browser/CefRuntime.kt`, `PrismCefHandlers.kt` | Android uses WebView |
| DPAPI wallet cipher | `wallet/DesktopWalletCipher.kt` (installed `Main.kt:210-212`) | Android Keystore |
| WinTun/VpnStack + proxy server | `net/TunDevice.kt`, `VpnStack.kt`, `DesktopPrismProxyServer.kt` | |
| Native library installer | `NativePayload.kt` (called `Main.kt:191`) | jpackage layout |
| JCodec H.264 muxer | `JCodecVideoEncoder.kt` (`Main.kt:196`) | Android hardware encoder |
| Plugin JAR host | `PluginPages` + `ui/PluginRenderer.kt` | Android loads APK plugin pages |
| Trusted-app **download** without virtualization | `Main.kt:284-293` (`canVirtualize = false`), `SystemPages.kt:56+` | Android can run them |
| Headless harness (selftest/bench) | `Main.kt:833-1048` | |

## A5. Silent divergences from Android (each verified)

**A5.1 — The lock is drawn *under* the app and half its declared gates have no caller.**
`PrismWindow.kt:96-113` emits `TrustedDeviceOfferWatcher()`, `LockOverlay()` (line 104) and
`OnboardingOverlay` (line 110) *before* `Surface(...)` (line 113). The comment at lines 98-100
claims the lock is "mounted LAST so it draws over everything"; the code does the opposite — and
`Surface` sizes to the full window (`Row(Modifier.fillMaxSize())` inside it), so in Compose's
window root (children stack in emission order, later on top) the lock and the first-run tour
are painted **behind** the app. `LockOverlay` is a plain `Box` (`LockPage.kt:625`), not a
`Dialog`/`Popup`, so nothing rescues the z-order. (I could not run the build to screenshot
this — the z-order half is high-confidence static reasoning; the verifiable half is that the
code order contradicts the code's own stated invariant, and that the overlay consumes nothing:
a `Box` with no `pointerInput`/`clickable` neither blocks clicks nor takes focus.)
Independently of the z-order: `PrismLockScope.Gate` declares 5 gates with written promises
(`PrismLockScope.kt:45-74`) but `permit()` (line 133) is called from exactly two places —
`WalletVault.kt:133` (WALLET) and `TrustedDevices.kt:460` (TRUSTED). `MESSAGES` ("conversations,
the clipboard relay and the trusted-device bridge stop answering", line 58-62) and `CONSOLE`
("commands that read private data refuse while locked", line 68-73) are enforced by nothing;
`Main.kt`'s clipboard consumer (`:265-279`) and `SmsRelay.listen` (`:410-414`) never consult the
lock. **Severity: critical** — a security control that presents itself as a lock and is not one.

**A5.2 — Settings rows refuse features the desktop actually has.**
* `Target.OPEN_LOCK_SCREEN` answers "The lock screen and its emergency card belong to the
  phone" (`SettingsActions.kt:225-226`) while the desktop ships `LockPage`, `LockOverlay` and
  `PrismLockScope`.
* `Target.OPEN_WRITER` answers "Prism Writer is an Android input method … there is nothing for
  a desktop to enable" (`SettingsActions.kt:221-223`) while `PageId.WRITER -> WriterPage()`
  exists (`PrismWindow.kt:191`) with `WriterKeyboard.kt` (600+ lines) behind it.
Consequence: the settings screen is a second, wrong inventory of what is ported.
**Severity: major.**

**A5.3 — The agentic-tool gate names a blocker that is false, and two never-working tools are
shown as available.** `AgenticToolsPage.kt:88-90` gates `p2p_host` on
`AgenticBuiltinTools.p2pHostHandler == null`; line 269 then explains it as "Needs the mesh DNS
manager, which is not ported to desktop yet." Mesh DNS *is* ported (`core/.../MeshDns.kt`,
`MeshCore.OP_DNS_WRITE` built in, `MeshPage`). What is missing is that only Android installs
the handler (`PrismApp.kt:296`); nothing in `desktop/` does. Separately,
`read_text`/`send_text`/`make_call` are not gated in the list even though their handlers are
also only installed on Android (`PrismApp.kt:314-343`), so they always answer "not available on
this platform" (`AgenticBuiltinTools.kt:222-230`) after the model has been told they exist.
**Severity: minor** (misleading, not silent data loss).

**A5.4 — Four Aether settings are read only by Android.** `getAetherAnnBaselineEnabled`,
`getAetherSurprisalWeightingEnabled`, `getAetherCooccurrencePriorEnabled`,
`getAetherCheckpointIntervalEpochs` are read only in `aether/AetherStudio.kt:421-462`,
`AetherSettingsActivity.kt:796-834`, `AetherTrainingActivity.kt:153`. `desktop/.../aether/
DesktopAether.kt` never reads them and the desktop settings page never offers them
(no `SettingsCatalog` row). The same profile trains differently on the two platforms, and
`AetherKnowledgeSync` is started only from `PrismApp.kt:427` and `AetherSettingsActivity.kt:634`
— never on desktop — so the desktop Aether page cannot share or receive knowledge at all.
**Severity: minor** (feature quietly absent on desktop).

**A5.5 — The rail's two sections are decided by enum ordinal.** `PrismWindow.kt:299` and
`:304` split the ported pages on `ordinal <= PageId.NORA_SETTINGS.ordinal`. Re-ordering the
enum (or inserting a page above NORA_SETTINGS) silently moves pages between "NORA" and
"SYSTEM" with no compile error. `PageId.AETHER` already sits at ordinal 4, i.e. first in
"SYSTEM", which is where it is not conceptually. **Severity: minor.**

**A5.6 — Two divergent implementations of "start the VPN server".** The settings page has a
full async one with logging (`PrismSettingsPage.kt:343-447`), and `SettingsActions.kt:116-129`
has a second that calls `WireGuardInterface.upAndConnectToSelf()` synchronously on the Compose
click path. The page intercepts the target first (`PrismSettingsPage.kt:343`), so the
synchronous copy is currently unreachable dead code that will freeze the UI the day it is
reached. **Severity: minor** (latent).

**A5.7 — Rail is scrollable, but the launcher shell claims keys it does not have.**
`PrismWindow.kt:52-55` documents the launcher shell as "paged with the arrow keys"; the
implementation deliberately claims Tab/Shift-Tab and gives the arrows back
(`LauncherShell.kt:98-125`). Documentation-only divergence, but it is the sentence a new
contributor reads first. **Severity: minor.**

---

# Scope B — global code health

## B1. Swallowed exceptions in load-bearing paths (the 15 most consequential)

Census: 33 one-line `catch (…: Exception) {}` sites in `*/src/` (27 in `app`, 5 in `core`,
1 in `desktop`; the 39 a naive grep reports includes 6 duplicates from the shadow `bin/` tree —
see B5), of which ~8 are legitimate cleanup (`stream.close()`, `wakeLock.release()`,
`socket.close()` on the shutdown path — `PrismCefHandlers.kt:202`, `AetherService.kt:400`,
`AetherChatStore.kt:87`, `VpnMultiplexer.kt:132`, `AetherKnowledgeSync.kt:166-167`,
`PrismSearchServer.kt:105`, `PrismMeshService.kt:253`). A further set are *body-empty but
multi-line* or comment-only (e.g. `DatasetDiscoveryService.kt:147,220`). The list below is
ordered by consequence, not by count.

| # | Site | Consequence | Sev |
|---|---|---|---|
| 1 | `app/.../mesh/PrismMeshService.kt:789` (`sendPacket`) | Every UDP send failure is discarded — no log, no counter, and `pendingRequests` is left behind. The mesh reports peers and uptime while packets are not leaving. This is the single point every gossip, DNS, market and social opcode goes through. | critical |
| 2 | `app/.../mesh/PrismMeshService.kt:639` (`handleDnsWrite`) | A throwing `P2pDnsManager.updateRecord` **and** a throwing `broadcastToOthers` are both silent. A mesh name registers locally and never propagates, with nothing in the log; the user sees a name that resolves on one device. | major |
| 3 | `app/.../mesh/PrismMeshService.kt:664` (`handleDnsSyncResp`) | The whole sync payload (DNS **and** peer list) is dropped on any parse error, silently. Presents as "mesh is up, no peers, no names". | major |
| 4 | `app/.../mesh/PrismMeshService.kt:688` (`ingestDnsJson`) | Per-record catch inside the loop: a peer's record with a missing/ill-typed `ts` (`getLong`) is skipped with no log → permanently partial DNS state that never self-heals because the next sync repeats it. | major |
| 5 | `app/.../mesh/PrismMeshService.kt:577` (`handleDiscoveryReq`) & `:565` (`handleHeartbeatResp`) | A bad `port` field means the peer's port is never learned; later packets go to `DEFAULT_MESH_PORT` and are lost. Peer is "discovered" and unreachable. Silent. | major |
| 6 | `app/.../browser/P2pDnsManager.kt:306` (`save()`) | The DNS index write is `try { writeText } catch {}` — a full disk or a locked file loses every mesh name on restart with no diagnostic. | major |
| 7 | `app/.../mesh/PrismMeshService.kt:629` (`handleModelAnnounce`) | Model gossip dropped silently; a peer's hosted model never appears in Models, and the 0x0C path (see B3) makes it look like the *other* model registry is at fault. | major |
| 8 | `app/.../browser/PrivateDnsVpnService.kt:537` + `PrismMeshService.kt:108` | `protectSocket` via reflection fails silently, and the caller of it also swallows the failure. With Prism's VPN up, the mesh socket is then routed *into* the tunnel: the mesh dies while `isRunning()` still reports true. | major |
| 9 | `app/.../browser/PrivateDnsVpnService.kt:247` | Per-route `addRoute(ip, 32)` failures (mesh peer subnets) are skipped silently. Traffic for those peers leaves by the normal interface instead of the tunnel — a routing leak the user cannot see. | major |
| 10 | `app/.../aether/AetherService.kt:492,496` | `startService(ACTION_APPLY_KNOWLEDGE)` / `ACTION_STOP` failures swallowed: the UI shows the action as taken, the service never runs, and the staged knowledge is never applied. | major |
| 11 | `app/.../aether/AetherService.kt:435` | Posting the foreground-service notification is swallowed; on Android 8+ a foreground service without its notification is killed/ANR'd, so a long Aether run dies with no trace. | major |
| 12 | `core/.../messaging/ModelDiscoveryService.kt:110` | Empty catch in a provider loop whose sibling lines (`:93`, `:146`) do log — the one path that swallows is the one that leaves model discovery returning an empty list for no stated reason. | major |
| 13 | `core/.../core/MeshUtils.kt:63` (`getAllLocalIps`) | On failure the set is just loopback. `MeshCore.handlePacket:374` uses it to drop the device's own broadcasts; losing it makes the device gossip with itself and appear in its own peer list. | major |
| 14 | `core/.../messaging/DatasetDiscoveryService.kt:147,220` | Comment-only catches (`/* best-effort */`): a provider that throws is indistinguishable from a provider that found nothing. Same class as #12, different feature. | minor |
| 15 | `app/.../browser/PrismTunnelEngine.kt:82` | SNI suppression failure is swallowed, so an HTTPS request the user routed through the tunnel can carry the hostname in clear — the exact metadata this class exists to hide. Also `PrismWebHost.kt:292,338` (path resolution → silent 404) and `VmController.kt:751` (a stub vsock path that looks implemented). | minor |

Counter-example worth keeping: `core/.../wallet/WalletNetwork.kt:160,254,452` catches broadly
but logs with the symbol and the throwable. That is the standard the 15 above do not meet.

## B2. TODO / FIXME / "not implemented" census

The reported "41 markers" **cannot be reproduced**. Across all `*/src/**/*.kt` there is
**exactly one** TODO/FIXME-style marker in the entire project:

* `app/src/main/java/com/prism/launcher/messaging/MessagingPageView.kt:417` —
  `// TODO: In a real implementation, we'd also search THROUGH message bodies` (consequence:
  in-thread search only matches participant/title, not message text; **minor**).

There are no `TODO()`, no `FIXME`, no `XXX`, no `NotImplementedError`. What does exist is a
different class of marker — *prose* promising absence, which is what a text search for "not
implemented" finds:

| Kind | Sites |
|---|---|
| Explicit stub with a silent fallback | `app/.../virtualization/VmController.kt:743-751` — "placeholder for native implementation", the `try` block builds a vsock socket and then discards it (`@Suppress("UNUSED_VARIABLE")`), catch is empty. The virtualization "command" path therefore does nothing at all. **major** |
| Documented gaps in features | `core/.../ModelListingScanner.kt:25` ("file-digest check is not implemented" → a re-listed model can differ from the verified bytes), `app/.../wallet/SoloMiner.kt:34` ("it is not implemented under any pretence that it will pay"), `agentic/AgenticBuiltinTools.kt:221-230` (platform-unavailable branches) |
| Stale "not ported" prose | `AgenticToolsPage.kt:269`, `LauncherShell.kt:249-287` (8 blockers, all false — see A3) |

So the honest summary is: **the codebase has no TODO backlog; it has a documentation-truth
backlog.** The three "not implemented" prose sites above are the only real gaps, and two of the
"not ported" claims are demonstrably wrong against the code.

## B3. Mesh opcode collisions and duplicate handler registration

Full enumeration of `OPCODE_*` constants (`grep 'OPCODE_.*=\s*0x'`, `src` only — 40
constants, 0x05–0x58):

| Range | Owner | Values |
|---|---|---|
| 0x05 | `core/MeshDns.OPCODE_DNS_WRITE` | built into `MeshCore` as `OP_DNS_WRITE` |
| **0x0C** | **`mesh/P2pModelRegistry.OPCODE_MODEL_ANNOUNCE` *and* `messaging/MeshModels.OPCODE_ANNOUNCE`** | **duplicate** |
| 0x0D/0x0E/0x0F | `P2pModelListings`, `P2pCoinOffers`, `ModelListingScanner.VERIFY_NOW` | |
| 0x11/0x12 | `wallet/MeshPool` (Android only) | |
| 0x14/0x15 | `ModelRefunds`, `ModelListingScanner.SALE_APPROVED` | |
| 0x20–0x23 | `MeshComputeRegistry` | |
| 0x24–0x27 | `science/MeshScience` | |
| 0x28 | `messaging/SmsRelay` | |
| 0x29–0x2C | `trusted/TrustedDevices` | |
| 0x30/0x31/0x32–0x34 | `AetherMeshSync`, `NebulaMeshSync`, `SocialMeshSync` | |
| 0x40–0x42 | `wallet/psc/PrismCoinNode` | |
| 0x50–0x57 | `minigames/MinigameMesh` | |
| 0x58 | `speech/KokoroMeshShare` | |

**The 0x0C duplicate is intentional and verified compatible**: both send
`{"model": "<name>"}` (`P2pModelRegistry.kt:56` and `MeshModels.kt:120-140`), `MeshModels.kt:39`
says so, and Android's dispatcher handles 0x0C once via `P2pModelRegistry`
(`PrismMeshService.kt:340`) while `MeshModels.extraSource` bridges the two registries
(`PrismApp.kt:162`). No misrouting today. **Severity: none as-is**; see the risk below.

**No handler is registered twice.** `MeshCore.register` callers: `TrustedDevices` ×4,
`MeshModels` ×1, `SocialMeshSync` ×3, `MeshMarket.install()` ×14 — 22 registrations, 22 distinct
opcodes. `MeshMarket.install()` is idempotent (`installed` flag, `MeshMarket.kt:47-49`).

**But the registry makes the next duplicate silent and expensive:**

* `MeshCore.register` (`MeshCore.kt:112-122`) does `handlers[opcode] = handler` — *"Later
  registrations replace earlier ones for the same opcode"* — and returns `Boolean` that no
  caller checks. A second feature claiming an opcode disables the first with no log at error
  level and no diagnostic.
* It also refuses every opcode `< 0x0C` (line 113) — correct for the control plane, but it
  means the reserved/built-in set is a magic-number rule enforced only at runtime, and
  `MeshDns.OPCODE_DNS_WRITE = 0x05` is a constant that would be rejected if anyone ever
  registered it.
* There is **no test asserting opcode uniqueness** (see B8). A one-line reflection test over
  `OPCODE_` constants would have caught both the 0x0C overlap (and could bless it explicitly)
  and any future collision.

**Severity: major (systemic risk)** — the mechanism that would surface a collision is the
same one that hides it.

## B4. Preferences read but never written / written but never read

Method: every accessor in `core/.../PrismSettings.kt` (402 declarations) has a matching
getter/setter *pair*, so key-level asymmetry is invisible there; the real asymmetries are at
the **caller** level. Checked the main feature toggles one by one.

| Finding | Site | Consequence | Sev |
|---|---|---|---|
| **`sms_relay_enabled` is read but never written.** `getSmsRelayEnabled()` is called only by `app/.../messaging/SmsRelayReceiver.kt:36`, which returns immediately when false (default `false`). `setSmsRelayEnabled` (`PrismSettings.kt:1854-1857`) has **zero callers** in `*/src/`, and there is no `SettingsCatalog` row for it. | `PrismSettings.kt:1854`, `SmsRelayReceiver.kt:36` | The entire SMS-relay *receive* path (PHASE 39) is permanently dead on Android, with no UI anywhere to enable it. The desktop relay listener (`Main.kt:410-414`) still runs. | critical |
| **`prism notifications` silently force-enables capture.** `notifications(args)` writes `setNotificationHistoryEnabled(true)` unconditionally (`Main.kt:1852`) before printing anything, so *inspecting* the history turns capture on and persists it. | `Main.kt:1852` | A read-only-looking diagnostic changes a privacy-relevant persistent setting; the user is never told. | major |
| **Aether's four settings are written/read only on Android.** (see A5.4) | `AetherStudio.kt:421-462`, `AetherSettingsActivity.kt:796-834` | Desktop Aether ignores them; no desktop UI can set them. | minor |
| **Cloud/compute toggles are unreachable on desktop.** `setCloudEnabled`, `setPayLaterEnabled`, `setDistributedInference`, `setComputeHostEnabled` have callers only in `cloud/CloudPageView.kt:200-794` and `ComputeMarketView.kt:148` (Android). The desktop `MeshPage.kt:243` displays `getPayLaterEnabled()` as an InfoRow with no control, and there is no desktop Cloud page. | `MeshPage.kt:243` | Desktop can see the state of features it cannot switch, and the shared `SettingsCatalog` has no row for them. | minor |
| **`NotPorted` composable is dead.** `PrismSettingsPage.kt:464-474` is private and called from nowhere. | `PrismSettingsPage.kt:465` | Dead code that looks like the page still has unported rows. | minor |
| **`SettingsActions.START_VPN_SERVER`/`STOP_VPN_SERVER` are dead on desktop** because `PrismSettingsPage.kt:343` intercepts the target first. | `SettingsActions.kt:116-135` | Two implementations of one action, one of them blocking. | minor |

No toggle was found that is written but never read.

## B5. Dead files

**B5.1 — A checked-in shadow source tree (207 files, the biggest dead-code item in the repo).**
`core/bin/main/**` contains 178 `.kt` files and `desktop/bin/main/**` contains 29, plus
generated Compose resource stubs (`desktop/bin/main/prism/desktop/generated/resources/Res.kt`,
`ActualResourceCollectors.kt`). They are *copies of source*, older than `src/`:

* `core/bin/main/com/prism/core/MeshUtils.kt` and `core/src/main/.../MeshUtils.kt` both exist;
  the `bin` copy is offset by ~2 lines.
* `core/bin/main/com/prism/launcher/PrismSettings.kt:1042` vs
  `core/src/main/.../PrismSettings.kt:1092` for `getDatasetAutoDownloadEnabled`.
* `desktop/bin/main/.../BrowserPage.kt:322` has the `getAutoMirror()` guard that is at
  `desktop/src/.../BrowserPage.kt:393` today.
* `desktop/bin/main/.../ui/PrismWindow.kt` still carries the old "LocalOnlyHotspot" blocker
  text (`:170`) that the current `PageId` no longer has.

Ten most significant stale copies (each is an older version of a security- or correctness-
relevant file, and each pollutes every code search): `core/bin/.../launcher/PrismSettings.kt`,
`core/bin/.../core/Platform.kt`, `core/bin/.../core/Json.kt`, `core/bin/.../core/MeshUtils.kt`,
`core/bin/.../launcher/AppDatabase.kt`, `core/bin/.../launcher/JvmDatabase.kt`,
`core/bin/.../launcher/nora/NoraBrain.kt`, `desktop/bin/.../ui/PrismWindow.kt`,
`desktop/bin/.../ui/LauncherShell.kt`, `desktop/bin/.../Main.kt`. **Consequence:** a search for a symbol, an opcode or a `catch`
returns two versions; a reviewer can quote the wrong one (this audit had to filter them out by
hand). **Severity: major** (no runtime effect, large correctness-of-review effect).

**B5.2 — Verified-unreferenced production symbols** (no reference in any `*/src/` file):
`PrismSettingsPage.NotPorted` (B4), `PrismSettings.setSmsRelayEnabled` (B4),
`PrismLockScope.Gate.MESSAGES` and `Gate.CONSOLE` (declared, never passed to `permit` — A5.1),
`SettingsActions.START_VPN_SERVER/STOP_VPN_SERVER` (B4). I found **no** whole-file orphan among
the 111 desktop sources: 13 leaf-looking files (`PluginRenderer`, `DesktopTour`, `SystemIcons`,
`Hotspot`, `NetworkControl`, `DesktopTypist`, `LykeVideoServer`, `DesktopVoiceAudio`,
`DesktopAether`, `DesktopCakeChat`, `DesktopNodeHost`, `DesktopAppVault`, `NoraBackgroundJobs`)
all have live callers, including `PluginNodeView` from `PluginsPage.kt:263` and
`DesktopGridPage.kt:480`.

## B6. AndroidManifest wiring

**B6.1 — An activity is started but not declared.** `cloud/CloudGamingActivity` is launched
from `CloudPageView.kt:629` (`CloudGamingActivity.launch(context, peerIp, title)` →
`CloudGamingActivity.kt:196-203` `startActivity(Intent(context, CloudGamingActivity::class.java))`),
but `app/src/main/AndroidManifest.xml` contains **no** `<activity>` for it (verified: no
occurrence of `CloudGaming` anywhere in the manifest). Tapping a cloud game on Android throws
`ActivityNotFoundException`. **Severity: critical.**

**B6.2 — A service is declared disabled and started anyway.**
`.browser.DnsProxyService` is declared `android:enabled="false"`
(`AndroidManifest.xml:753-759`) yet `PrismApp.kt:412-415` and `SettingsActivity.kt:1374,1377`
call `startService`/`stopService` on it. Nothing ever re-enables the component:
`setComponentEnabledSetting` is used in exactly one place, for `ExeLaunchActivity`
(`ExeLaunchActivity.kt:58`). Consequence: the access-point DNS proxy never runs, and the
settings toggle silently does nothing on both paths. **Severity: major.**

**B6.3 — `RECEIVE_BOOT_COMPLETED` is requested and no receiver uses it.**
`AndroidManifest.xml:30` declares the permission; the manifest contains no `BOOT_COMPLETED`
intent-filter, and `MiningService`'s "restarts itself" is `onTaskRemoved` only
(`MiningService.kt:726`). So mining does not survive a reboot despite holding the permission,
and the permission is an unused install-time grant. **Severity: minor.**

**B6.4 — Everything else declared is started.** I checked every declared activity and service
against every `Intent(..., X::class.java)` construction in `app/src/main`: each declared
component has a launch site (`TrustedDevicesActivity` `SettingsActivity.kt:1861`,
`KokoroSettingsActivity` `:1619`, `WriterDictionaryActivity` `:2029`, `WalletReceiveShim`
`ModelCheckoutActivity.kt:220`, `PrismLockActivity` `LockGate.kt:96`, `DummyLauncherActivity`
`LauncherActivity.kt:477`, etc.), and the two system-bound ones
(`PrismNotificationListener`, `PrismWriterService`) are bound by the OS by design.
`ExeLaunchActivity` is `enabled="false"` deliberately and is toggled by
`setComponentEnabledSetting` — correct.

## B7. `:core` importing `android.*`

**Not a violation — the premise does not hold.** `:core` is a plain
`org.jetbrains.kotlin.jvm` module with no Android plugin (`core/build.gradle.kts:20-24`), so
`android.jar` is not on its compile classpath; an `import android.…` would be a compile error.
Verified by grep over all of `core/src`: the only occurrences of `android.` are **string
literals** inside the Dalvik interpreter's reflective dispatch
(`core/.../virtualapp/AndroidSurface.kt:928-1443`, e.g. `owner == "android.os.Build\$VERSION"`)
— data, not imports. The real cross-platform leak is different and worth naming: `:core`
depends on `androidx.room:room-runtime` and `androidx.sqlite` (`core/build.gradle.kts:59,88`),
which are AndroidX-but-multiplatform. Those are a dependency choice, not an architecture
violation.

## B8. Test coverage

**Volume.** 77 test files in `core/src/test` (~853 `@Test` methods); 2 in `desktop/src/test`
(`PocketParityTest`, `SeedVcParityTest`); **0** in `app` (no `app/src/test` or `androidTest`
directory at all).

**What they actually test** (spot-read, not exhaustively):

| Area | Files | Quality |
|---|---|---|
| Wallet / crypto | `WalletDerivationTest`, `WalletSigningTest`, `WalletArchiveTest`, `WalletCoinMigrationTest`, `WalletReceiptsTest`, `MoneroKeysTest`, `MoneroAddressTest`, `LitecoinAddressTest`, `PrismCoinTest`, `MiningTest`, `MiningHasherTest`, `PscMinedBalanceTest` (12 files) | real vectors, real round-trips — the strongest area |
| Nora numerics | `NativeParityTest` (skips loudly without `nora_conv`), `CoreInvariantsTest`, `NoraExpertsTest`, `DeconvGradientTest`, 2 benchmarks named `*Benchmark` that run as `@Test` (`AetherLayerBenchmark:83`, `AetherKernelBenchmark:178,217`) | benchmarks in the test source set inflate run time and are not assertions |
| Search | `PrismSearchTest`, `SearchPortTest`, `SearchSeedsTest`, `PrismUrlQueryTest`, `PrismSearchSummary`-adjacent | good, includes a port-fidelity test |
| Minigames | 17 files (`ChessRulesTest`, `PaperWarTest`, `StatecraftTest`, `WmdTest`, …) | the single largest block by file count |
| Protein / language / speech / cloud / aether / cakechat / writer / trusted / messaging | 20+ files | present and non-trivial |
| Settings | `SettingsCatalogTest` (193 lines) | genuinely good: walks the catalog and round-trips every row against a real store precisely because a mis-wired row is silent |

**Gaps that matter for this report:**

* No test names opcodes at all — nothing would catch a duplicate or a re-registered handler
  (B3).
* No test covers the mesh transport's error paths, which is why six swallow-all catches in
  `PrismMeshService` survived (B1).
* No test covers `core/.../lock/` (`LockStore`, `PrismLockScope`) — the gate promises in A5.1
  are untested by construction.
* No test covers `NotificationCapture` / `NotificationHistory` enabling logic (B4's
  `prism notifications` side effect).
* `:app` has no unit tests at all, so B6.1/B6.2 (manifest ↔ start-service wiring) cannot be
  caught by anything short of running the app; a lint check on `startService` targets vs the
  manifest would.
* The desktop UI has no tests beyond two speech-parity harnesses, so every Scope A finding is
  invisible to CI.

---

# Summary of severities

| Sev | Count | Headline items |
|---|---|---|
| critical | 4 | lock drawn under the app + 3/5 gates unenforced (A5.1); `sendPacket` swallow-all (B1.1); `sms_relay_enabled` never written (B4); `CloudGamingActivity` undeclared (B6.1) |
| major | 14 | launcher/rail port-state divergence and wrong slot mappings (A3); duplicate Messages entry + `prism gui messages` (A1); settings rows refusing existing features (A5.2); six mesh/DNS swallow sites (B1.2-7); VPN protect/route swallow (B1.8-9); `prism notifications` side effect (B4); `DnsProxyService` disabled (B6.2); 207-file shadow tree (B5.1); opcode registry risk (B3) |
| minor | 15 | A2, A5.3-7, B1.10-15, B2 stub, B4 remainder, B6.3, benchmark-in-tests |

**Premises that did not survive verification:** the "41 TODO markers" (there is 1), and
"`:core` imports android.*" (it cannot: no Android plugin, and only string literals exist).
