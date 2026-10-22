# Prism

**The Swiss-army-knife Android launcher — and its desktop sibling.**

Prism is a modular, AI-integrated launcher/workspace. On Android it replaces the home screen with a
swipeable set of pages (browser, desktop grid, app drawer, and any of ~19 other page types); on
Windows/Linux/macOS the same product runs as a Compose Desktop application. Almost all of the
non-UI logic lives in a shared `:core` Kotlin/JVM module, so the phone and the desktop run the same
mesh, AI, speech, wallet and social code.

> **Status snapshot:** 2026-10-22.
> This README is an **honest, code-derived inventory**. Every feature below was located in the
> source, read, and judged against what its code actually does. Where a feature is broken, the bug
> is named with a file reference. Where behaviour could not be proven without running the app on a
> device (network, VPN, ONNX runtime, cross-app loading), it is marked **unverified**.
>
> The assessment was static: the full source tree was read, `:core`, `:app` and `:desktop` were
> compiled, and the new mesh-speech unit tests were run. No phone or second desktop was available,
> so no runtime test of the mesh/VPN/crypto paths was performed. Line references are to the audited
> tree; a later refactor may shift them.

## Status legend

| Mark | Meaning |
|---|---|
| ✅ **Complete** | The feature does what it claims end-to-end; only cosmetic/minor issues remain. |
| 🟡 **Partial** | The main path works, but there are real bugs, missing pieces, or platform gaps that a user will hit. |
| 🔴 **Broken** | The feature cannot achieve its stated purpose as shipped. |
| 🧪 **Scaffold** | UI/types/plumbing exist, but the actual mechanism is a stub or no-op. |
| ⚠️ | Security or data-loss issue. Read the bug before using the feature. |

## At a glance

| Area | Status | One-line summary |
|---|---|---|
| Three-pane launcher / slots | 🟡 | Pager, page picker and drag work, but page add/remove renumbers desktop contents. |
| App drawer / desktop grid / folders | 🟡 | Functional; drag occupancy and folder drag-out bugs. |
| Plugins (custom pages) | 🟡 / 🧪 | APK-discovered pages load; the JAR plugin SPI is scaffold-only on Android. |
| Theming / icon packs / fonts | 🟡 | Theme switching works; icon packs and typography are narrower than advertised. |
| Lock screen / duress | 🟡 ⚠️ | Credential math is real; the lock can silently stop working and cannot be disabled. |
| Browser / tabs / private mode | 🟡 ⚠️ | Tabs and bookmarks work; private mode is cosmetic and leaks. |
| Content blocking / downloads / mirroring | 🟡 | Blocklist is real; download "open" always fails; mirror verification is real. |
| P2P DNS | 🟡 ⚠️ | Works in-process; unauthenticated remote writes allow domain hijack. |
| DNS proxy / private DNS VPN | 🔴 ⚠️ | DNS proxy is disabled in the manifest; the "VPN" does not tunnel. |
| Mesh discovery & gossip | 🟡 | Android + desktop transports interoperate; desktop DNS gossip is unwired. |
| WireGuard / L2TP / IKEv2 | 🟡 / 🔴 | WG client works; Android WG server, L2TP and IKEv2 are broken. |
| Access point / isolation | 🟡 / 🧪 | AP creates a group; isolation reports success and does nothing. |
| Sam AI chat | 🟡 | Real local/cloud plumbing; cloud memory and error handling are wrong. |
| Model store / model shop | 🔴 | Browse/download is broken; purchases can never be delivered. |
| Compute market | 🔴 | Single-peer offload is a permanent no-op. |
| Image gen / vision / dictation | 🟡 / 🔴 / ✅ | Image gen partial, Android vision is a stub, dictation works. |
| Agentic tools / CakeChat | 🟡 | Real tool loop; several tools are unreachable or re-send context. |
| Kokoro TTS | ✅ | Genuine 82M ONNX pipeline on both platforms; system fallback is broken on Android 11+. |
| Voice cloning | 🟡 ⚠️ | Five engines; three fetch weights from an unrelated third-party release. |
| **Meshnet Kokoro & clone sharing (new)** | 🟡 | Announced, listed, and usable over HTTP; compile- and unit-tested, not hardware-tested. |
| Nora | 🟡 | Chat/brain/self-test/video work; training reports false success. |
| Aether | 🟡 | Real connectome/training; received knowledge is never auto-applied. |
| AI characters / Lyke / language / Writer | ✅ | These are genuinely implemented (Writer is Android-only). |
| Nebula social / bots | 🟡 | Real mesh + Room store, but 100 % AI personas and fabricated engagement. |
| Editor / science / minigames / Stremio | 🟡 / ✅ | Editor and science partial; minigames and Stremio work. |
| Wallet / PrismCoin / mining | 🔴 / 🟡 | Chain and mining are real; PrismCoin cannot be sent. |
| Virtualization (VM / Wine / apps) | 🔴 / 🟡 | PrismOS never boots; Wine and Android app virtualization partially work. |
| Cloud storage / gaming | 🟡 / 🔴 | Storage partial; cloud gaming activity is missing from the manifest. |
| Files / notifications / trusted devices | 🟡 / ✅ / 🟡 ⚠️ | Files partial; notifications work; trusted-device consent can be forged. |
| Desktop app | 🟡 | Broad parity, but launcher mode and the lock screen are wrong. |

---

## What's new in this revision

**Desktop/mesh speech sharing.** A machine that is on a Prism meshnet — whether it started the mesh
or joined it — now announces its Kokoro-82M install and its voice-cloning encoders. Other desktops
and phones see those machines in:

- **Kokoro-82M Voice & Speech → “KOKORO ON YOUR MESHNET”** (desktop: the *Kokoro speech* page).
  Only rendered when a machine on the mesh is actually offering Kokoro (or this machine is sharing
  its own). Tapping a machine makes Prism speak through it; “This device” switches back.
- **Desktop only: a “Share models with the mesh” switch** in the Kokoro screen. It is always
  visible, enabled only while the machine is serving or connected to a meshnet, and **checked by
  default**. Turning it off closes the speech port immediately and withdraws the machine from
  peers’ lists; turning it on starts sharing immediately.
- **Clone a voice → “CLONE ON YOUR MESHNET”** (desktop: the *Clone a voice* page). Only rendered
  when a machine is offering an encoder. Selecting one runs the ONNX encoder pass there.

Under the hood: one new mesh opcode (`KokoroMeshShare.OPCODE_KOKORO_ANNOUNCE = 0x58`), a small HTTP
listener on port **47812** (`SpeechMeshServer`) serving `/health`, `/synthesize` and `/clone`, and
`MeshSpeechEngine`, which plugs remote speech back into the ordinary `TtsEngine` interface so Sam,
Nora, Aether and the language lessons all work unchanged.

Limitations of the new feature are documented in [Meshnet speech sharing](#54-meshnet-speech-sharing-kokoro--voice-cloning).

---

## 1. Launcher shell, pages and customization

### 1.1 Swipeable page launcher — 🟡 Partial

**What it does.** The home screen is a `ViewPager2` whose pages are produced one per *slot*. A slot
can hold any page type (browser, desktop grid, drawer, messages, wallet, …). The default install has
four slots: Browser, Desktop grid, App drawer, Notifications. A shake toggles “page lock”.

**How to use.** It is the HOME app: swipe left/right; shake to lock/unlock paging; long-press an
icon to drag it; overscroll vertically at the edge of a page to open the page picker.

**Known bugs.**
- Background blur/scale is forced on for every page at index ≥ 2, because the code treats
  “drawer” as “any page after the second” (`LauncherActivity.kt:358-365`). Notifications therefore
  renders permanently zoomed with a live 160 px blur.
- Overscroll-to-picker ignores overlays layered above the pager (tour, folder popup), so a swipe
  can change page assignment invisibly (`LauncherActivity.kt:685-731`).
- Opening the picker clamps the current page to 0..2, so on a 7-page setup it targets page 3
  (`LauncherActivity.kt:804-808`).
- Back on the home screen finishes and relaunches the activity, dropping browser/editor state
  (`LauncherActivity.kt:412-416`).
- `startActivity` failures on app launch are swallowed, so a dead tap and a missing app look
  identical (`LauncherActivity.kt:776-778`).

### 1.2 Page picker and slots — 🟡 Partial ⚠️

**What it does.** A two-step overlay: choose a position, then a page type (18 built-ins plus
discovered plugin pages). Applying rewrites `SlotPreferences` and rebuilds the pager.

**How to use.** Swipe down/up hard at the top/bottom of a page (700 px travel and >1500 px/s), then
choose a slot and a page type. “+” adds a page left/right.

**Known bugs.**
- **Adding a page on the left silently gives every desktop page its neighbour's icons.** Desktop
  grids are keyed by slot index (`DesktopShortcutStore.kt:140-151`); inserting at index 0 shifts
  every store key (`LauncherActivity.kt:425-428`, `SlotPreferences.kt:179-185`). Deleting a page
  does the reverse (`LauncherActivity.kt:829-844`). This is data loss, not a visual bug.
- The picker is undiscoverable: the onboarding tour teaches a long-press that does nothing.
- `SettingsActivity.buildSlotPickers()` is dead code and would mis-assign every unknown value to
  Desktop Grid if wired (`SettingsActivity.kt:2556-2597`).

### 1.3 Desktop grid, drag reorder, folders, delete zone — 🟡 Partial

**What it does.** A 4-column icon grid with drag-and-drop, folders, widget cells, resize, and a
delete zone. Folders open a popup and can nest.

**How to use.** Long-press an icon and drag; drop on another icon to make a folder; drop on the
delete zone to remove; long-press a folder to rename.

**Known bugs.**
- Reordering swaps any two cells, including widget/occupied cells, leaving widget occupancy
  pointing at the wrong anchor (`DesktopGridAdapter.kt:47-51`). Two competing drag systems start
  from the same view (`DesktopGridPage.kt:90-106`).
- The delete zone is set to `INVISIBLE`, not `GONE`, and keeps 10 % of the screen height after the
  drag, permanently shifting the grid (`DesktopGridPage.kt:246-260`).
- Dragging an icon out of a folder duplicates it instead of moving it (folder audit).
- “Show drawer labels” is written by Settings and never read by the drawer.
- The alphabet rail scrolls to the wrong row; icon packs apply to the drawer only, not the desktop
  or hotseat.

### 1.4 Kinetic Halo, widgets, app sync — 🟡 Partial

- **Kinetic Halo:** a physics-ish circular app picker. Functional, but the layout math is wrong at
  non-default densities.
- **Widgets:** placement/resize/remove are implemented; the only entry point is a search result, and
  the desktop cell for an Android widget is a labelled placeholder.
- **App sync:** an `AppSyncWorker` seeds the installed-app database and a receiver updates it.
  Failure modes are silent staleness, not errors.

### 1.5 Plugins (custom pages) — 🟡 / 🧪

**What it does.** A third-party APK can advertise `com.prism.launcher.action.DESKTOP_PAGE` and a
`PAGE_VIEW_CLASS` metadata value; Prism loads the view with a foreign `ClassLoader` and inserts it
as a page.

**How to use.** Install the sample plugin (`sample-custom-page/`) or your own; it appears in the
page picker automatically.

**Known bugs / gaps.**
- Cross-app view inflation and `createPackageContext` loading is unverified at runtime; a failure
  shows as an empty page.
- The separate JAR/SPI plugin path (`sample-plugin/`) is **scaffold-only on Android**.
- `DynamicPageLoader` swallows reflection failures, so a broken plugin looks like a blank slot.

### 1.6 Theming, icon packs, typography — 🟡 Partial

**What it does.** Light/dark/system theme, an accent colour, neon-glow effects, per-app icon pack
selection, and font selection (system/default/custom).

**How to use.** Settings → Appearance / Theme; Settings → Icon pack; Settings → Typography.

**Known bugs.**
- The accent is applied only to newly created views; already-open pages keep the old colour until
  recreated.
- Icon packs are discovered via `PackageManager` but the manifest has no `<queries>` for icon-pack
  theme actions, so packs can be invisible on Android 11+ (unverified at build).
- The “system-wide font” claim is very likely false: the injection hook is not invoked by
  AppCompat's inflater path (`PrismFontEngine`).

### 1.7 Onboarding — 🟡 Partial

**What it does.** A first-run tour over the launcher explaining pages, the drawer, gestures and
privacy features.

**Bug.** Several instructions do not match the implementation — most notably it teaches a
long-press to open the page picker, while the only trigger is a vertical overscroll.

### 1.8 Lock screen, pattern, duress — 🟡 ⚠️

**What it does.** A PIN/pattern lock gate with an optional duress code and a decoy launcher, plus a
contacts/medical card on the lock screen.

**How to use.** Settings → Lock screen → set a PIN/pattern. The duress code is set during setup.

**Known bugs.**
- **The lock can silently stop working.** `LockGate` sets `showing = true` before `startActivity`;
  if the background activity launch is blocked (common on Android 10+), the flag stays true and the
  lock never appears again for that process (`LockGate.kt:92-109`).
- **A configured lock cannot be disabled or cleared** on Android: `setLockScreenEnabled` is only
  ever written `true`, and `LockStore.setEnabled/clear/clearDuress` have no callers.
- **Duress has no real exit.** `DuressResponder.standDown` is only reachable from a normal unlock;
  the decoy launcher says “go to Settings”, but there is no such control. Re-running lock setup
  wipes the existing duress hash (`LockStore.kt:107-109`).
- `getLockBiometric` defaults to true and is never written.

---

## 2. Browser, DNS and privacy

### 2.1 Tabbed browser — 🟡 Partial

**What it does.** A Chromium `WebView` page with multiple tabs, public/private categories, an
address bar, bookmarks, downloads, a tab-preview overlay, and a mesh “download site” action.

**How to use.** Swipe to the Browser page → `+` for a tab; the tabs button opens the grid; ⋮ opens
reload/bookmarks/downloads/mirror.

**Known bugs.**
- Every background tab's preview bitmap is 1×1: the capture clamps a `GONE` view's zero width to 1
  (`BrowserPageView.kt:359`, `BrowserTabsAdapter.kt:71-75`).
- Previews are captured synchronously on the UI thread for every tab.
- The `context as LauncherActivity` cast would crash if the page were inflated with a wrapped
  context (latent today).

### 2.2 Private mode / DNT — 🟡 ⚠️

**What it does.** Private tabs request `DNT: 1` and `Sec-GPC: 1`, disable third-party cookies and
DOM storage, never enter history/cache, and can be gated by biometrics.

**Known bugs.**
- **DNT/Sec-GPC are dropped on every in-page navigation**: `shouldOverrideUrlLoading` reloads with
  no headers (`PrismWebViewClient.kt:34`). Only the first URL carries them.
- **The cookie jar is process-global.** There is no per-tab profile and no clear on tab close, so a
  login from a public tab is visible in a private tab and survives it (`BrowserPageView.kt:216`).
- **Private-tab downloads are written to the persistent download list** (`BrowserPageView.kt:380-424`,
  `:415`).
- The biometric unlock is never reset, so one unlock unlocks the overlay for the process lifetime.

### 2.3 Content blocking / blocklist — 🟡 Partial

**What it does.** A Room-backed host blocklist seeded from StevenBlack, plus user import and custom
domains. At the packet/DNS layer, blocked hosts become /32 routes so traffic never leaves.

**How to use.** Settings → Privacy & VPN → Blocklist; add/import domains there.

**Known bugs.**
- In the WebView, blocking runs **only for private tabs** (`PrismWebViewClient.kt:139`) and returns
  a synthetic 200 with an empty body.
- The blocked-IP route table is IPv4-only, capped at 100 hosts, and resolves blocked names through
  the system resolver (leaking them) before blocking.
- The per-app whitelist is scaffold: nothing calls `addDisallowedApplication`.

### 2.4 Bookmarks — ✅ Complete

Star toggle, multi-select removal, and correct state on navigation all work
(`BrowserPageView.kt`).

### 2.5 Downloads — 🟡 Partial

**What it does.** Hands downloads to Android's `DownloadManager` and records completed files.

**Known bugs.**
- “Open” always fails: it starts a `file://` URI, which throws `FileUriExposedException`; the catch
  reports “no app can open this” (`BrowserPageView.kt:509-514`).
- The guessed filename can differ from the name `DownloadManager` actually used.
- The notification poll loop can spin forever if the download row disappears
  (`PrismDownloadNotifications.kt:56-91`).

### 2.6 Whole-site download / mirroring — 🟡 Partial ⚠️

**What it does.** Crawls a site, stores it under `/storage/emulated/0/Prism/Mirrors/<domain>`, and
verifies a manifest with SHA-256.

**Known bugs.**
- The `.remote` “fetch from a peer” hint is stripped before DNS sees it, so a re-mirror reads from
  itself (`PrismTunnelEngine.kt:116-130`).
- Auto-mirror re-crawls the site on every navigation; there is no in-flight guard.
- A peer-controlled manifest path is used as a filesystem path without a canonical check
  (`PrismMirrorManager.kt:83`) — arbitrary write under external storage.

### 2.7 P2P site hosting and virtual-host mapping — 🟡 Partial

**What it does.** Serves a hosted folder under a `.p2p` domain through the tunnel; the browser maps
the domain to the mesh host while preserving the `Host` header.

**Known bugs.**
- The plain-HTTP/`Host:` branch of the proxy is unreachable; only `PRISM_CONNECT` serving works.
- SAF-hosted manifests are empty (`PrismWebHost.kt:524-542`).
- Unpublishing leaves a stale cache entry (`P2pHostingActivity.kt:63-71`).

### 2.8 P2P DNS — 🟡 ⚠️

**What it does.** A mesh-replicated domain→IP ledger with verification and alternates, seeded back
into the mesh after successful global lookups.

**Known bugs.**
- **Any reachable host can write or delete DNS records without authentication.**
  `DiscoveryEngine.kt:105-129` has no secret check (unlike its registration path), and
  `P2pDnsManager.updateRecord` persists and re-broadcasts. That is mesh-wide domain hijack.
- The ledger is written world-readable to `/storage/emulated/0/Prism/p2p_dns_index.json`.
- The in-memory map is unsynchronised and no TTL is enforced.
- DNS-sync payloads are unbounded; past ~140 records they exceed the 16 KB UDP receive buffer and
  are silently truncated.

### 2.9 DNS proxy (Access Point mode) — 🔴 Broken

`DnsProxyService` is declared **`android:enabled="false"`** in the manifest
(`AndroidManifest.xml:753-759`), yet `PrismApp` and Settings start it; nothing ever enables the
component. Even if it ran, it binds port 53 unprivileged and the “fallback DNS” picker is read by
nobody.

### 2.10 Private DNS VPN / tunnel — 🟡 ⚠️

**What it does.** A `VpnService` that intercepts UDP/53, resolves through the P2P ledger, and (per
the UI) tunnels traffic to Cloudflare.

**Known bugs.**
- **It is not a tunnel.** No default route (`0.0.0.0/0`) is added, and `PrismTunnelEngine` drops
  everything except `10.8.0.x` (`PrivateDnsVpnService.kt:195-205`, `PrismTunnelEngine.kt:318-335`).
  Only DNS filtering happens.
- **The Settings “Connect” button is a no-op**: the service stops immediately when `alwaysOn` is
  false (the default) (`SettingsActivity.kt:375`, `PrivateDnsVpnService.kt:62-70`).
- `protectSocket` failures are swallowed, so the mesh dies under the VPN while `isRunning()` still
  reports true.

### 2.11 TLS and mesh certificates — 🟡 ⚠️

Custom `SSLSocketFactory` suppresses SNI so `.p2p` names handshake. **Peer certificate verification
is disabled** (`PrismTunnelEngine.kt:46-53,79-83,131-132`; `PrismSslManager.kt:64-68`): any peer is
trusted, and hostname verification always returns true. The CA is generated per device but two
different CA implementations exist (Android and desktop), and the Android CA is unencrypted and
eligible for Auto Backup.

### 2.12 Proxy server/client — 🔴 Plain HTTP / 🟡 CONNECT

The proxy server drops every plain-HTTP request with no response at all
(`PrismProxyServer.kt:237,272,294`); `CONNECT` works. With empty credentials it listens as an open
proxy on `0.0.0.0`.

### 2.13 Blocked-IP route table — 🟡 Partial

The IPv4 destination parser bounds-checks version and IHL and produces correct /32 routes. It is
IPv4-only, silently skips IPv6, and stops at 100 hosts.

---

## 3. Meshnet, VPN protocols and access point

### 3.1 Mesh discovery, heartbeat, peer list — 🟡 Partial

**What it does.** UDP broadcast discovery on port 8081, heartbeats, peer-list exchange, RTT
measurement, DNS sync and feature gossip. Two implementations exist: `PrismMeshService` (Android,
in `:app`) and `MeshCore` (desktop/`:core`), deliberately speaking the same wire format.

**How to use.** Settings → Mesh (Android) / the *Mesh* page (desktop); enable and optionally set a
bootstrap port. Peers appear in the Mesh page.

**Known bugs.**
- The desktop ignores the configured bootstrap port and address: `MeshCore.portProvider` and
  `bootstrapNodes` are never installed, while the Mesh page displays those settings as if used.
- `MeshDns.registerWithMesh()` / `install()` have no callers on desktop, so the `.p2p` registry
  never gossips or persists there.
- `MeshDns.announce` is called before `MeshCore.start()`, so the announcement goes nowhere.
- UDP send failures are swallowed with no log or counter (`PrismMeshService.kt:789`), so “mesh up,
  nothing arrives” is undiagnosable.
- `MeshCore.register` silently replaces a handler for the same opcode; no caller checks the result
  and no test asserts opcode uniqueness.
- Opcodes `0x11, 0x12, 0x24-0x28, 0x30, 0x31, 0x50-0x57` are Android-only; the desktop drops them
  as unclaimed. That is expected for unported features, but there is no diagnostic for “this peer
  does not support that”.

### 3.2 Mesh roles, kick and ban — 🟡 Partial

Server/client roles are real; kicking and persisting a ban work, but only in the gossip path — a
banned node can still reach the model-hosting HTTP port.

### 3.3 WireGuard, L2TP, IKEv2, proxy — 🟡 / 🔴

| Protocol | Status | Notes |
|---|---|---|
| Proxy tunnel (`PRISM_CONNECT`) | ✅ | The most complete VPN path; Basic auth enforced on both ends. |
| WireGuard client (Android) | 🟡 | Real client; some route/protect failures are swallowed. |
| WireGuard server (Android) | 🔴 ⚠️ | No Noise handshake: replies with 92 near-zero bytes and injects any matching datagram into the VPN. All peers are handed `10.8.0.2`. |
| WireGuard server (desktop) | ✅ | Delegates to `wg-quick` / `wireguard.exe`. |
| L2TP | 🔴 | Reads the AVP two bytes off, so it can never answer a real SCCRQ. |
| IKEv2 | 🔴 / 🧪 | Sends a packet; no IKE SA can be established. |
| Split tunnelling / app whitelist | 🔴 | Whitelisting one app pauses the whole tunnel (proxy, multiplexer, WG client); `PACKAGE_USAGE_STATS` is never actually granted. |

### 3.4 Access point, portal, connected devices — 🟡 / 🧪

- **AP creation:** 🟡 creates a hotspot/group and persists the config. The config store's `::`
  separator corrupts any SSID/password containing `::`.
- **Portal:** 🟡 lists and creates APs; “Stop” only works in the same process lifetime.
- **Connected devices:** 🧪 the list is literally `emptyList()`
  (`ConnectedDevicesActivity.kt:93`); `addDevice` has no callers.
- **DNS override:** 🟡 the responder works; nothing sends it a query.
- **Network isolation:** 🧪⚠️ `NetworkIsolationEngine` logs and returns `Result.success` — the
  `iptables` execution is commented out (`NetworkIsolationEngine.kt:170-181`). Per-device
  blocking and bandwidth limits do nothing.

### 3.5 Mesh compute market, listings, coin offers — 🟡 / 🔴

- **Compute market:** 🟡 gossip, pricing and debt ledger are real; the single-peer offload path is
  a permanent no-op because `MeshBridges.generateOnPeer` is declared and never assigned
  (`MeshInference.kt:242`, `MeshBridges.kt:145`).
- **Model listings / transfers:** 🔴 the listing and checkout work, but the seller serves
  `filesDir/models` while the file is copied to `documentsDir()/Models`, so every paid download
  404s (see 4.4).
- **Coin offers:** ✅ discovery/quoting works; taking an offer is the separate (scaffold) conversion
  feature.
- **Mesh AI RPC host:** ✅ streaming and non-streaming prompts work; the pre-read-header trap is
  fixed.
- **Mesh TLS:** 🟡 Android and desktop generate separate, incompatible CAs.

### 3.6 Mesh compute/storage/gaming cloud — 🟡 / 🔴

Cloud storage has real HKDF encryption and chunk distribution, but a chunk no peer accepted is
still recorded with `holders=[]`; `CloudVault.namespace()` is never used, so there is no per-user
namespace. Cloud gaming is broken twice: the Android `CloudGamingActivity` is **not declared in the
manifest** (`CloudPageView.kt:629` starts it), and `startHosting()` returns null.

---

## 4. AI: Sam, models, agents

### 4.1 Sam chat — 🟡 Partial

**What it does.** A conversation UI over a shared AI manager with on-device GGUF, cloud
OpenAI-compatible, local-cloud (Ollama), P2P shadowing, and CakeChat backends. Messages persist in
Room; replies can stream and be spoken.

**How to use.** Messages page → Sam; Settings → Intelligence & Messaging to choose the backend and
model; Models page to download/import a GGUF.

**Known bugs.**
- Cloud Sam has **no conversation memory**: the history parameter is only consumed by CakeChat
  (`AiManager.kt:202`).
- A prompt containing “video” is answered from a hardcoded mock with a remote sample MP4 and an
  image, presented as model output (`CloudAiService.kt:21-44`).
- Engine errors are sometimes displayed as the answer instead of an error.
- The agentic local loop re-sends its whole transcript into an engine that already caches it
  (`AgenticEngine.kt:163-181`).

### 4.2 Engine selection — ✅ Complete

Local / Cloud / Local-cloud / P2P / CakeChat selection is genuinely wired with a defined shadowing
order. The *quality* of each engine varies (see above).

### 4.3 Model store (browse + download) — 🔴 Broken

**What it does.** Browses Hugging Face/GitHub for models and downloads them through
`DownloadManager`/OkHttp into the model registry.

**Known bugs.**
- The GitHub provider uses **unauthenticated code search** and dies on rate limits
  (`ModelDiscoveryService.kt:120`).
- Google “vision” files are installed as image generators (`ModelStoreView.kt:161`).
- There are no HTTP timeouts, and one global download id is reused.
- `ModelRegistry` (the fix for “model vanished after restart”) is never called on Android.

### 4.4 Model shop: listings, payments, refunds — 🔴 ⚠️

**What it does.** Sellers list a model with a price; buyers pay in PrismCoin; a scanner verifies the
listing; refunds are possible within a window.

**Known bugs.**
- **A purchase can never be delivered.** The seller copies the file to
  `documentsDir()/Models` (`ModelUploadActivity.kt:187`, `P2pModelTransfer.kt:53-56`) but serves
  `GET /model/<name>` from `filesDir/models` (`PrismAiHost.kt:238-247`) → every buyer 404s after
  paying. The delivered file is never registered, and the Models page then prunes it.
- The model host ignores the “hosting enabled” setting and checks no payment, so the same port
  serves any model in that folder to anyone on the mesh.
- **Coins can be locked forever:** the refund window closes at 3 h, re-buying is refused, and
  settlement only runs at process start (`ModelRefunds.kt:70-73`, `ModelPayments.kt:85-90`,
  `PrismApp.kt:482-495`). A scan that never decides leaves the purchase HELD with no UI exit.
- A purchase counts as paid when it is merely accepted into the mempool.

### 4.5 Image generation, vision, dictation — 🟡 / 🔴 / ✅

- **Image generation:** 🟡 cloud URL building omits a slash for base URLs without one
  (`CloudAiService.kt:364`); MediaStore save fails on API 26–28; the local path passes a file where
  MediaPipe wants a directory.
- **Vision:** 🔴 on Android — `VisionService` returns hardcoded bracket strings
  (`VisionService.kt:14-29`), and those strings are fed to social bots as “what they see”. The real
  `Vision` registry is only used on desktop.
- **Dictation:** ✅ Android uses `SpeechRecognizer`; desktop uses the shared Whisper engine. The
  Android Whisper engines are registered but unreachable.

### 4.6 AI conversations, dataset downloads, NPU, quantization — 🟡 / ✅

- **AI conversations:** 🟡 thread search is a `TODO`; a permission grant does not refresh the list;
  MMS is reported as “sent” without sending.
- **Dataset downloads:** ✅ JGit + LFS fallback with unmetered/battery constraints; reports success
  when nothing was downloaded.
- **NPU:** 🟡 by design — detection and a preference exist, but only some engines honour it.
- **Quantization:** ✅ runs real conversion steps; the output registry entry gets pruned, so the
  result may disappear from the Models page.

### 4.7 Agentic tools and CakeChat — 🟡

**Agentic tools:** 🟡 the tool loop, sandboxed file tools and builtin handlers are real.
`make_call` is permanently blocked because `CALL_PHONE` is never requested
(`AgenticMessagingTools.kt:128`); the local loop re-sends context.

**CakeChat:** 🟡 Chaquopy + TensorFlow plumbing is real, but no pretrained weights ship, so it
cannot answer out of the box.

### 4.8 SMS relay — 🟡

Sending and receiving are implemented over the mesh with sealed payloads. **The receive gate
`sms_relay_enabled` is read but never written** (`SmsRelayReceiver.kt:36`;
`setSmsRelayEnabled` has zero callers and no Settings row), so relayed texts are never ingested
unless something else sets that preference.

---

## 5. Speech: Kokoro, cloning, call mode

### 5.1 Kokoro-82M text to speech — ✅ Complete

**What it does.** Downloads the ONNX Kokoro-82M export plus per-voice style tensors, phonemises,
chunks and streams speech through a platform `AudioSink`. 54 voices across 9 languages, 4
quantisation variants.

**How to use.** Settings → Intelligence & Messaging → **Kokoro-82M Voice & Speech** → Download;
pick Sam’s voice, speed, quality; preview any voice. Nora and Aether choose their voices on their
own settings pages. “Speak replies aloud” reads every reply.

**Known bugs.**
- **The system-voice fallback is dead on Android 11+.** `SystemTtsEngine` queries
  `android.intent.action.TTS_SERVICE`, but the manifest’s `<queries>` block does not declare it
  (`SystemTtsEngine.kt:69-78`, `AndroidManifest.xml:59-75`). On a device where Kokoro is not
  installed and no default TTS is set, speech says “No speech engine is installed”.
- An unguarded `InputStream.skip` in the style lookup can short-read a tensor
  (`KokoroEngine.kt:348`).
- The default quantisation was changed away from the broken `q8f16` export (a real bug fix — the
  old default loaded and then failed on the first sentence).

### 5.2 Voice cloning — 🟡 Partial ⚠️

**What it does.** Records/uploads a sample, analyses it (trim, levelling, pitch, gender guess),
saves it as a clone, and re-voices Kokoro through a chosen engine. Five engines exist: OpenVoice
v2, FreeVC, kNN-VC, Seed-VC and Pocket TTS.

**How to use.** Kokoro settings → **Clone a voice** → pick/download an engine → hold the microphone
(or upload) → name, language, gender → Save. The clone then appears in every Kokoro voice picker.

**Known bugs.**
- **Three of five engines download weights from an unrelated third-party GitHub release**
  (`github.com/ibrahim-hmood/Prism`) with **no hash verification**.
- Engine “installed” is decided by exact byte length only (`VoiceCloningModels.kt:42-43`); a CDN
  byte of drift marks an engine permanently missing and re-downloads up to 358 MB per save.
- FreeVC pads/cuts every reference to a fixed 8 s, so a normal 2–3 s recording is mostly silence in
  the speaker encoder and the voice drifts (`FreeVcEngine.kt:132-137`).
- Pocket TTS is a `ReferenceSpeechEngine`; on a legacy clone with no reference it must refuse.
- Cloned voices are not portable between machines unless their artifacts are copied (see the new
  mesh feature for the partial answer).

### 5.3 Call mode — 🟡 Partial

**What it does.** A hands-free call screen: speech recognition → conversation → spoken reply, with
Sam/Nora/Aether voices and barge-in.

**Known bugs.** The spoken reply uses the engine chain above, so the broken system fallback
(5.1) makes call mode silent on affected devices. The recognizer has no explicit on-device
requirement, so audio leaves the device where the platform chooses cloud recognition.

### 5.4 Meshnet speech sharing (Kokoro + voice cloning) — 🟡 New

**What it does.**
- Every machine on a meshnet that has Kokoro and/or a clone encoder announces the fact (name, two
  booleans, voice count) over one new UDP opcode, `0x58`, every 60 s.
- The serving machine opens an HTTP listener on port **47812**, bound to every local interface but
  answering only peers already in the mesh peer table:
  - `GET /health` → capabilities.
  - `POST /synthesize` `{text, voice, speed}` → WAV (Kokoro renders here).
  - `POST /clone` float32 samples → the active engine’s prepared artifacts (base64), so another
    device can run the encoder remotely.
- Clients appear in the Kokoro and Clone screens as described in [What's new](#whats-new-in-this-revision).

**How to use (Android).**
1. Join (or serve) the mesh on both devices: Settings → Mesh → enable.
2. Make sure the desktop has Kokoro installed and/or one clone engine downloaded.
3. On the phone, open **Kokoro-82M Voice & Speech** → the new **KOKORO ON YOUR MESHNET** section
   lists the desktop. Tap it; the header changes to “Speaking with: Kokoro on <desktop>”.
4. For cloning, open **Clone a voice** → **CLONE ON YOUR MESHNET**, pick the desktop, then record and
   save. The desktop runs the encoder; the clone is stored on the phone.

**How to use (desktop).** On the *Kokoro speech* page, the **MESH SHARING** switch is on by
default whenever this machine is on a meshnet; leave it on to serve this computer's models, or turn
it off to stop sharing immediately. The same **KOKORO ON YOUR MESHNET** and **CLONE ON YOUR
MESHNET** lists appear on the *Kokoro speech* and *Clone a voice* pages.

**Known limitations.**
- **The sharing switch is desktop-only.** A phone always announces when it has a model and is on a
  mesh; there is no phone UI to opt out yet.
- **Compile- and unit-tested only.** The HTTP round trip has not been exercised on two real devices
  in this revision. `SpeechMeshWireTest` covers WAV/float framing; mesh reachability does not.
- **No authentication or encryption on the speech port.** Like the rest of Prism's mesh, a device
  that can reach the LAN/tunnel can call it; the peer-table check is a filter, not a security
  boundary. Do not enable the mesh on an untrusted network.
- **A cloned voice spoken on another machine falls back to its published base voice**, because the
  clone's artifacts are local to the machine that saved it. This is logged, not silent.
- **Remote cloning still needs the conversion engine on the receiving device to speak the
  resulting voice locally.** Only the encoder pass is remote; the 128 MB+ converter is still a
  local download. The UI footer says so.
- Blocking calls (`KokoroMeshShare.synthesizeOn` / `prepareCloneOn`) must run on a worker thread;
  the built-in engines do.

---

## 6. Nora, Aether and AI characters

### 6.1 Nora chat — ✅ Complete

**What it does.** A persistent chat over Nora’s connectome, with commands (`/…`), memory,
tool calls, archive/backup, and spoken replies.

**How to use.** Nora page → chat; Nora Settings → brain size, voice, memory, training.

### 6.2 Nora training — 🔴 Broken reporting

The training loop is real (backprop/STDP, held-out validation), **but it reports success having
learned nothing**: undecodable samples are skipped with `?: continue`, the `trained` counter is never
checked, the guard compares against an initial `lastError = 0f` that can never be exceeded, and
`save()`/`markTrained()` run regardless (`NoraTrainer.kt:127-137,186,220-249`). The summary can read
“Training complete. 0 presentations over N epochs” while `/status` says “Connectome loaded”.

### 6.3 Nora brain view, self-test, video — ✅

- **Brain view / self-test:** real sandboxed measurement, training and rendering.
- **Video:** a MediaCodec byte-buffer encoder (NV12/I420) with bounded EOS. A single frame whose
  size differs aborts the file (`NoraVideoWriter.kt:46-47,92,147`).

### 6.4 Aether — 🟡 Partial

A second, connectome-based assistant with its own training, mesh knowledge sharing (AKEF) and
desktop port. **Training reports a perfect loss unconditionally** (`AetherTrainer.kt:239-241,294`).
**Received knowledge is never auto-applied** because `akefAutoMerge` defaults to false
(`AetherConfig.kt:46`), so the feature appears to do nothing until the user finds the setting.

### 6.5 AI characters — ✅ Complete

Character creator, conversation, and Aether-backed staging are real
(`CharacterCreatorActivity`, `CharacterConversationActivity`, `CharacterStore`).

---

## 7. Social: Nebula, Lyke, language, Writer

- **Nebula** — 🟡 a Room + mesh social feed with posts, comments, likes, chat and notifications.
  The network is **100 % AI personas**, and engagement counts are random
  (`NebulaSocialManager.kt:90-91`). The bot worker reports success on idle-gated no-ops
  (`SocialBotWorker.kt:14-15`).
- **Lyke** — ✅ short-video feed/recording/upload, real PiP, Stremio streams through the proxy.
- **Social mesh sync** — 🟡 announces posts and delivers them; offline peers depend on the
  re-announce timer.
- **Language learning** — ✅ a real syllabus, lesson flow, tutor voices, vocabulary and stats,
  shared by both platforms.
- **Prism Writer** — ✅ on Android: a full IME with swipe, autocorrect, emoji/GIF panels, dictation
  and (with an AI model) live translate + speak. Manifest wiring is correct
  (`BIND_INPUT_METHOD`, `prism_writer_method.xml`, FileProvider, permission activity). The desktop
  has a Writer page, but the IME itself is Android-only.

---

## 8. Tools: editor, science, minigames, Stremio

- **Code editor / terminal / Node** — 🟡 a real PRoot Ubuntu guest, a terminal, a file tree and a
  Node runtime. Arm64-only assets (~80 MB download, ~1.1 GB free); `libnode.so` build logic is
  stale; no non-arm64 runtime.
- **Science instruments** — 🟡 microphone/audio/camera/Wi-Fi instruments genuinely measure and log;
  cosmic-ray/RF/clinical panels and a lab notebook are real.
- **Protein folding** — 🟡 the structure pipeline and viewer are real, but **no folding weights are
  shipped**, so an untrained fold produces a meaningless backbone.
- **Minigames** — ✅ chess (rules, review, mesh play), Pong, Paper War/provinces.
- **Stremio** — ✅ addon protocol (browse/search/meta/episodes/streams), repository and addon
  management; playback is inside Lyke.

---

## 9. Wallet, PrismCoin and mining

### 9.1 PrismCoin — 🔴 (send), 🟡 (chain/mining)

**What it does.** A local PoW chain with consensus, transactions, mesh gossip, a node, a wallet and
mining.

**Known bugs.**
- **PrismCoin cannot be sent from the Wallet page.** `WalletNetwork.send` has no PSC branch and
  `endpoints` has no `"PSC"` entry, so every PSC send returns “Prism has no network endpoint for
  PSC” (`WalletNetwork.kt:442-443`, called from `WalletPageView.kt:1334`). The local-chain payment
  path used by the model shop does work.
- Mining a share is real, but `mining.authorize`’s `true` is counted as an accepted share on every
  connect (`StratumClient.kt:138-140`, `MiningService.kt:496-505`).
- `MiningService` sets `State.running = true` before early-returning for an ineligible coin or a
  missing native library, so the UI shows a live miner doing nothing.

### 9.2 Other coins — 🟡

Address derivation and transaction signing are real (EIP-155 verified byte-for-byte in a unit
test). Broadcast and balance lookups are unverified against live explorers; DGB/VTC/RVN cannot be
queried.

### 9.3 Solo mining — 🔴

The user’s own full-node binary is not shipped (`BinaryMissing`), and the service never starts the
node it points at.

### 9.4 Pool mining / Monero — 🟡

Stratum V1 is implemented correctly (endianness handled carefully). Monero’s `randomx_jni` really
ships and hashes. Remaining issues are liveness and UX, not the crypto.

### 9.5 Coin conversion / compute-market payments — 🧪 / 🟡

Conversion has no accept/settle path, so no offer can ever be taken, yet the UI toasts “It settles
when a peer takes it”. Compute-market PSC settlement works, but mempool acceptance is treated as
paid.

### 9.6 Native compilation — 🧪 Scaffold

Explicitly labelled unfinished; the one shipped plan is unpinned.

---

## 10. Virtualization and cloud

### 10.1 PrismOS VM — 🔴

**What it does.** Boots a PrismOS (or custom ISO) VM via AVF or bundled QEMU and renders it through
VNC, with app-launch routing into the guest.

**Known bugs.**
- **PrismOS can never boot**: `prism_os.img` / `vm_config.json` are referenced but written by
  nothing (`VmController.kt:241,760-761`). The first swipe ends with “QEMU exited immediately
  (code 1)”.
- On AVF, `vm.run()` blocks the single executor, so `stop()` can never run
  (`VmController.kt:265-266` vs `:96-98`).
- `pauseQemu()` is an explicit no-op behind a `PAUSED` state (`:662-664`).
- **Turning on app routing breaks launching every app**: `launchComponent` returns after paging to
  the virtualization view (`LauncherActivity.kt:754-767`), and delivery is a stub (vsock placeholder
  that discards its args, or `am start` text to a QEMU started with `-net none`).

### 10.2 VNC display — ✅

The RFB client, keysym map and surface renderer are real and work for QEMU/Wine/x11vnc.

### 10.3 Wine / Windows executables — 🟡

PRoot and QEMU ship; Wine install and session management are substantial. `WineSession` is owned by
the recyclable page view, so a second run can launch a duplicate Xvfb and fail.

### 10.4 Android app virtualization / vault — 🟡 ⚠️

Genuine reflection work runs apps in a `:virtualapp` process. **The vault’s threat model is void**:
the docs say the guarantee holds only in a guest VM, but the shipped path is an in-process,
same-UID process that can read `vault.key` and use the Keystore alias. Separately,
`passphrase()` regenerates and overwrites the vault passphrase whenever unsealing fails, making all
existing `data.enc` permanently undecryptable (`VirtualizedAppVault.kt:29-37,113-123`).

### 10.5 Cloud storage and gaming — 🟡 / 🔴

Storage has HKDF encryption, chunking and manifests, but records holders for chunks nobody accepted
and has no per-user namespace. Gaming is broken: the activity is missing from the manifest and the
host returns null.

---

## 11. Files, notifications, trusted devices, diagnostics

- **File explorer** — 🟡 real copy/move/delete/rename, archives and clipboard; the clipboard is the
  clearest bug (long-press operations can act on the wrong selection).
- **Notification history** — ✅ the listener is declared and permission-guarded, and history persists
  across restarts. It rewrites the whole file on the listener thread for out-of-order inserts and
  has no per-app exclusion despite claiming one.
- **Trusted devices** — 🟡 ⚠️ pairing, offers, clipboard/SMS/history/wallet/app transfer and
  virtualization are real. **An unsolicited `TRUST_REPLY` creates a confirmed trusted device with no
  offer and no pairing code** (`TrustedDevices.kt:616-667`), after which the sender receives
  clipboard/SMS/history. Do not pair on an untrusted network.
- **Diagnostics** — ✅ in-app logs and a terminal; file logging lives on `/sdcard` and silently
  disables after five write failures, so the file a support request asks for may not exist.

---

## 12. Desktop application

**What it is.** `:desktop`, a Compose Desktop app sharing `:core` with Android. `./gradlew :desktop:run`.

**Status:** 🟡 broad parity, with three structural problems.

**Working:** desktop grid and drawer, browser (JCEF), mesh page, trusted devices, wallet, Nora,
Aether, Nebula, Lyke, language, editor, science, minigames, Stremio, speech (Kokoro + cloning),
model market, notifications, diagnostics, plugins, VM (QEMU+VNC), Wine, cloud AI, search.

**Known bugs.**
- **The desktop lock is cosmetic.** `LockOverlay` is emitted before the full-window `Surface` and
  consumes no input (`PrismWindow.kt:104` vs `:113`); only 2 of 5 declared `PrismLockScope` gates
  ever call `permit()`. A “locked” desktop is still usable.
- **Launcher mode contradicts the app.** `LauncherShell.kt:246-289` prints “not ported” for
  Language, Editor, Science, Minigames, Nebula and more, while `PrismWindow.kt:168-198` renders
  those same pages. Its slot editor offers only 11 of Android’s 19 kinds.
- `PageId.MESSAGES` is a duplicate, unported “Messages” row beside the working `MESSAGES_PAGE`;
  `prism gui messages` opens the placeholder.
- Keys 1–9 are stolen from every text field by the shell’s key handler (`LauncherShell.kt:114-122`).
- `prism notifications` force-enables notification-history capture just by running
  (`Main.kt:1852`).
- Implemented-but-unreachable from the rail: Mobile cloud, the SMS half of Messages, Android
  widgets.

---

## 13. Cross-cutting bugs worth knowing before you trust anything

These are not tied to one screen; they affect many features.

1. **The mesh control plane swallows send failures.** `PrismMeshService.sendPacket` discards every
   exception without a log, counter or pending-request cleanup (`:789`). DNS writes/syncs and model
   gossip swallow the same way.
2. **The VPN path fails silently.** `protectSocket` reflection failures kill the mesh under VPN
   while `isRunning()` says true; per-route `addRoute` failures leak traffic.
3. **Two independent CAs** (Android and desktop) mean mesh TLS works only within one platform.
4. **Many features are disabled by a preference that has no UI writer** — SMS relay receive,
   `NetworkIsolationEngine`, several security toggles. If a toggle seems inert, check whether
   anything writes its key.
5. **Silent catches are everywhere on load-bearing paths** (launch, mesh, model downloads, wallet).
   A failure usually looks like “nothing happened”.
6. **No `:app` tests at all.** `:core` has ~850 tests (wallet crypto is the strongest area);
   `:desktop` has two parity tests. Opcodes, mesh error paths, the lock and manifest wiring are
   untested.
7. **A stale `bin/` shadow source tree is committed** (`core/bin/main`, `desktop/bin/main`, 207
   files). It is not compiled but poisons every text search and audit; delete it.

---

## 14. Architecture

```
:core      plain Kotlin/JVM. No android.*, by design. All shared logic: mesh, AI registries,
           speech (Kokoro, cloning), wallet/chain, Nora/Aether numerics, social, files, cloud,
           settings, database (Room KMP), JSON, platform capability interfaces.
:app       Android launcher. Activities/pages, WebView, VpnService, ONNX/llama JNI, platform
           adapters (AndroidPlatform/AndroidHost/AndroidAudioSink/...), manifests.
:desktop   Compose Desktop. JCEF browser, AWT audio, JNA/TUN, QEMU/VNC, AWT clipboard, tray.
:core tests  ~850 unit tests.
```

Key seams: `PrismPlatform` (host/log/images/scheduler/notifier/apps/downloader/audio/main),
`MeshTransport` (Android's `PrismMeshService` or desktop's `MeshCore`), `AudioSink`,
`TtsEngine`, `CloneEngine`, `AppCatalog`, `Downloader`.

**The wire contract:** `"PRISM"` + one opcode byte + UTF-8 payload on UDP 8081. Android and desktop
deliberately keep separate transports that interoperate. Opcodes 0x00–0x0B are built-in; features
start at 0x0C.

---

## 15. Build and run

**Prerequisites:** JDK 17, Android SDK API 35, KSP; for the desktop, a JVM with Compose and (for
the browser) JCEF.

```bash
# Android launcher
./gradlew :app:assembleDebug
# install on a connected device
./gradlew :app:installDebug

# Desktop (Windows/Linux/macOS)
./gradlew :desktop:run
# package a native installer
./gradlew :desktop:packageDistributionForCurrentOS

# Shared logic + tests
./gradlew :core:test

# Sample pages/plugins
./gradlew :sample-custom-page:assembleDebug
./gradlew :sample-plugin:assembleDebug
```

`local.properties` needs `sdk.dir`. Linux desktop needs `libfuse2`, a working `wg-quick` for the
WireGuard server, and `x11vnc`/QEMU for the VM pages.

---

## 16. Security and privacy caveats (read before enabling features)

- The mesh has **no authentication**. DNS writes, trusted-device pairing and the speech HTTP port
  all trust the network. Use it on a LAN you control.
- TLS peer verification is disabled and SNI is suppressed. Mesh TLS protects against passive
  observers, not active peers.
- The speech-sharing port (47812) is unauthenticated and plain HTTP.
- The VPN is not a VPN: it filters DNS, and split tunnelling is a global tunnel kill switch.
- Android’s system-speech fallback is broken on Android 11+, so “speech does not work” may be the
  manifest, not the model.
- Voice-clone engine weights are fetched from a third-party release without hashes.
- The lock screen can fail open (silently stop appearing).
- PrismCoin transfers from the Wallet page fail; do not treat a displayed balance as spendable
  until this is fixed.

---

## 17. Not implemented / deliberately absent

- **SMS/MMS on desktop** — no public API on Windows; Linux needs a modem. Desktop Messages is
  AI-only, with the phone relay as the intended bridge.
- **Android AppWidgets on desktop** — a widget cell exists as a placeholder.
- **Android UI in Compose (Phase 74)** — not started.
- **JAR plugin SPI on Android** — scaffold.
- **PrismOS guest image** — referenced, never produced.
- **Solo mining** — no bundled full node.
- **Cloud gaming host** — returns null.
- **Native on-device compilation** — labelled unfinished.
- **Model shop delivery** — broken end-to-end (see 4.4).

---

## 18. Feature completion matrix

| # | Feature | Status |
|---|---|---|
| 1 | Swipeable page launcher | 🟡 Partial |
| 2 | Page picker / slots | 🟡 Partial ⚠️ |
| 3 | Drag & drop desktop grid | 🟡 Partial |
| 4 | Folders | 🟡 Partial |
| 5 | Delete zone | 🟡 Partial |
| 6 | App drawer | 🟡 Partial |
| 7 | Kinetic Halo | 🟡 Partial |
| 8 | APK plugin pages | 🟡 Partial |
| 9 | JAR plugin SPI | 🧪 Scaffold |
| 10 | Theming / neon | 🟡 Partial |
| 11 | Icon packs | 🟡 Partial |
| 12 | Typography | 🟡 Partial |
| 13 | Onboarding | 🟡 Partial |
| 14 | Lock / pattern / duress | 🟡 Partial ⚠️ |
| 15 | Widgets | 🟡 Partial |
| 16 | Search host | 🟡 Partial |
| 17 | App sync | 🟡 Partial |
| 18 | Tabbed browser | 🟡 Partial |
| 19 | Private mode | 🟡 Partial ⚠️ |
| 20 | Blocklist | 🟡 Partial |
| 21 | Bookmarks | ✅ Complete |
| 22 | Downloads | 🟡 Partial |
| 23 | Site mirroring | 🟡 Partial ⚠️ |
| 24 | P2P site hosting | 🟡 Partial |
| 25 | P2P DNS | 🟡 Partial ⚠️ |
| 26 | DNS proxy | 🔴 Broken |
| 27 | Private DNS VPN | 🟡 Partial ⚠️ |
| 28 | Mesh TLS | 🟡 Partial ⚠️ |
| 29 | Proxy server/client | 🔴 Broken |
| 30 | Mesh discovery/gossip | 🟡 Partial |
| 31 | Mesh roles/kick/ban | 🟡 Partial |
| 32 | WireGuard client | 🟡 Partial |
| 33 | WireGuard server (Android) | 🔴 Broken ⚠️ |
| 34 | WireGuard server (desktop) | ✅ Complete |
| 35 | L2TP | 🔴 Broken |
| 36 | IKEv2 | 🔴 Broken |
| 37 | Proxy tunnel | ✅ Complete |
| 38 | Split tunnelling | 🔴 Broken |
| 39 | Access point | 🟡 Partial |
| 40 | Access point portal | 🟡 Partial |
| 41 | Connected devices | 🧪 Scaffold |
| 42 | DNS override | 🟡 Partial |
| 43 | Network isolation | 🧪 Scaffold ⚠️ |
| 44 | Compute market | 🔴 Broken |
| 45 | Model listings/transfers | 🔴 Broken |
| 46 | Coin offer discovery/quoting | ✅ Complete |
| 47 | Mesh AI RPC | ✅ Complete |
| 48 | Mesh speech sharing | 🟡 Partial (new) |
| 49 | Sam chat | 🟡 Partial |
| 50 | Engine selection | ✅ Complete |
| 51 | Model store | 🔴 Broken |
| 52 | Model import | ✅ Complete |
| 53 | Model shop / payments | 🔴 Broken ⚠️ |
| 54 | Image generation | 🟡 Partial |
| 55 | Vision | 🔴 Broken (Android) |
| 56 | Dictation | ✅ Complete |
| 57 | AI conversations | 🟡 Partial |
| 58 | Dataset downloads | ✅ Complete |
| 59 | NPU support | 🟡 Partial |
| 60 | Quantization | ✅ Complete |
| 61 | Agentic tools | 🟡 Partial |
| 62 | CakeChat | 🟡 Partial |
| 63 | SMS relay | 🟡 Partial |
| 64 | Kokoro TTS | ✅ Complete |
| 65 | Voice cloning | 🟡 Partial ⚠️ |
| 66 | Call mode | 🟡 Partial |
| 67 | Nora chat/brain/video | ✅ Complete |
| 68 | Nora training | 🔴 Broken reporting |
| 69 | Aether | 🟡 Partial |
| 70 | Aether knowledge sharing | 🟡 Partial |
| 71 | AI characters | ✅ Complete |
| 72 | Nebula social | 🟡 Partial |
| 73 | Lyke | ✅ Complete |
| 74 | Social bots | 🟡 Partial |
| 75 | Language learning | ✅ Complete |
| 76 | Prism Writer (Android) | ✅ Complete |
| 77 | Code editor / terminal | 🟡 Partial |
| 78 | Science instruments | 🟡 Partial |
| 79 | Protein folding | 🟡 Partial |
| 80 | Minigames | ✅ Complete |
| 81 | Stremio | ✅ Complete |
| 82 | PrismCoin chain/wallet | 🔴 Broken (send) |
| 83 | Other coins | 🟡 Partial |
| 84 | Solo mining | 🔴 Broken |
| 85 | Pool mining | 🟡 Partial |
| 86 | Monero/RandomX | 🟡 Partial |
| 87 | Coin conversion | 🧪 Scaffold |
| 88 | Compute-market payments | 🟡 Partial |
| 89 | Native compilation | 🧪 Scaffold |
| 90 | PrismOS VM | 🔴 Broken |
| 91 | VNC display | ✅ Complete |
| 92 | Wine / Windows apps | 🟡 Partial |
| 93 | Android app virtualization | 🟡 Partial ⚠️ |
| 94 | Cloud storage | 🟡 Partial |
| 95 | Cloud gaming | 🔴 Broken |
| 96 | File explorer | 🟡 Partial |
| 97 | Notification history | ✅ Complete |
| 98 | Trusted devices | 🟡 Partial ⚠️ |
| 99 | Diagnostics | ✅ Complete |
| 100 | Settings | ✅ Complete |
| 101 | Desktop app | 🟡 Partial |

---

## 19. Honest summary

Prism is an exceptionally ambitious codebase: a working on-device LLM pipeline, a genuine Kokoro
TTS implementation, real meshnets on two transports, real cryptography, real VMs, and a large
shared core. Much of it works.

It is also a codebase where **the UI often promises more than the implementation delivers**. The
biggest gaps are not missing files — they are wiring gaps: a button that starts a disabled service,
a seller that serves the wrong directory, a compute path whose callback is never installed, a lock
overlay under the window, a training summary that is computed from nothing. Those are fixable, and
they are listed above with file references so they can be found.

If you are evaluating Prism for use, start with the features marked ✅. If you are contributing,
the highest-value fixes are the critical items in sections 3, 4, 9, 10 and 13 — especially the
model-shop path mismatch, the unauthenticated DNS write, the fake network isolation, the broken
PrismCoin send, and the mesh speech port’s authentication story.
