# Prism

**The Swiss-army-knife Android launcher — and its desktop sibling.**

Prism is a modular, AI-integrated launcher/workspace. On Android it replaces the home screen with a
swipeable set of pages (browser, desktop grid, app drawer, and any of ~19 other page types); on
Windows/Linux/macOS the same product runs as a Compose Desktop application. Almost all of the
non-UI logic lives in a shared `:core` Kotlin/JVM module, so the phone and the desktop run the same
mesh, AI, speech, wallet and social code.

> **Status snapshot:** 2026-10-23.
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
| Three-pane launcher / slots | ✅ | Pager, page picker, drag and page add/remove all work; grids follow their page. |
| App drawer / desktop grid / folders | ✅ | Drag occupancy, folder drag-out, drawer labels, alphabet rail and icon packs fixed. |
| Plugins (custom pages) | 🟡 / 🧪 | APK pages load and say why when they cannot; the JAR plugin SPI is scaffold-only on Android. |
| Theming / icon packs / fonts | 🟡 | Listed bugs fixed; folder previews and WebView font injection remain narrow. |
| Lock screen / duress | 🟡 ⚠️ | Credential math is real; the lock can silently stop working and cannot be disabled. |
| Browser / tabs / private mode | 🟡 ⚠️ | Tabs and bookmarks work; private mode is cosmetic and leaks. |
| Content blocking / downloads / mirroring | 🟡 | Blocklist is real; download "open" always fails; mirror verification is real. |
| P2P DNS | 🟡 ⚠️ | Works in-process; unauthenticated remote writes allow domain hijack. |
| DNS proxy / private DNS VPN | 🔴 ⚠️ | DNS proxy is disabled in the manifest; the "VPN" does not tunnel. |
| Mesh discovery & gossip | 🟡 | Android + desktop transports interoperate; desktop DNS gossip is unwired. |
| WireGuard / L2TP / IKEv2 | 🟡 / 🔴 | WG client works; Android WG server, L2TP and IKEv2 are broken. |
| Access point / isolation | 🟡 / 🧪 | AP creates a group; isolation reports success and does nothing. |
| Sam AI chat | 🟡 | Real local/cloud plumbing and saved memory; “video” prompts are still mocked. |
| Model store / model shop | 🔴 | Browse/download is broken; purchases can never be delivered. |
| Compute market | 🔴 | Single-peer offload is a permanent no-op. |
| Image gen / vision / dictation | 🟡 / 🔴 / ✅ | Image gen partial, Android vision is a stub, dictation works. |
| Agentic tools / CakeChat | 🟡 | Real tool loop, read/build tools and context offload; some tools remain unreachable. |
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
| Files / notifications / trusted devices | 🟡 / ✅ / ✅ | Files partial; notifications work; trusted-device pairing requires an outstanding offer and its code. |
| Desktop app | 🟡 | Broad parity, but launcher mode and the lock screen are wrong. |

---

## What's new in this revision

**Prism Debug Bridge (2026-10-24).** A phone or desktop on the mesh can now open a shell on another
Prism without USB, wireless debugging or a `shell` user. The target shows a random twelve-character
code (upper case, lower case, digits and symbols) on its own screen; the controller types it once,
and the pair is remembered. The terminal understands the
ADB commands people already type (`adb shell`, `adb logcat`, `adb install`, `adb devices`) and
translates them to what the app can actually run, with real stdout, stderr and exit statuses. On
Windows, Linux and macOS the desktop can also expose a verified device to an adb that is already
installed: **Prism Settings → Developer options → Prism Debug Bridge → Open → Bridge to adb**
starts a loopback transport, runs `adb connect`, and the phone appears in `adb devices` for IDE
logcat windows and scripts. The bridge is optional; the in-app terminal works with no adb binary at
all. File transfer is real: `adb push` and `adb pull` move files and whole directories over the
bridge's own file channel, and `adb install` uploads a package and lets the target decide whether
to install it (an APK on Android, `.exe`/`.msi` on Windows, `.deb`/`.rpm`/AppImage on Linux,
`.dmg`/`.pkg` on macOS) or store it and say where it went.

**Meshnet, VPN protocols and the access point (2026-10-23).** The WireGuard server is real now.
`core/wg/WireGuardNoise.kt` implements Noise_IKpsk2 — BLAKE2s, HMAC, the WireGuard KDF, X25519,
ChaCha20-Poly1305, TAI64N, initiation/response, MAC1 and transport framing — with tests against
published vectors and a full client↔server handshake passing packets both ways. The Android server
uses it: MAC1 is checked before any costly work, the initiation is authenticated, each peer gets its
own 10.8.0.0/24 address, transport counters must increase, and a forger cannot move a session's
endpoint or inject a packet. This replaces a responder that answered with 92 near-zero bytes and
injected any datagram that carried the transport type byte. L2TP's control plane was rewritten
against the real header and AVP layout, so a client can at least establish a tunnel; the PPP data
path is still absent and is stated as such. IKEv2's fabricated SA_INIT is deleted — it could never
complete an exchange and made the client look broken instead.

Split tunnelling no longer pauses the whole tunnel when a bypass-listed app comes forward; the
whitelist is applied with `addDisallowedApplication`, and the foreground monitor that caused it
(and the un-grantable `PACKAGE_USAGE_STATS` permission) is gone. The mesh no longer broadcast-
storms when it has no peers, send failures are counted and reported, and the access point store is
JSON, so an SSID containing `::` no longer corrupts it. Cloud uploads refuse to record a manifest
when a chunk found no host, and the cloud-gaming session activity is finally declared.

On the AI side: model downloads go to the registry's directory and keep their extension, GitHub
discovery uses the unauthenticated repository API and release assets instead of authenticated code
search, vision models are no longer installed as image generators, and a backend error is shown
once instead of being stored as Sam's answer. Held model purchases are released after a day, a
refunded model can be bought again, and settlement runs on a timer rather than only at launch.

**Agentic tooling, context offload and saved conversations (2026-10-23).** The agentic tool set
adds `read_file`, `read_all_files` (a directory's listing plus every text file's contents) and
`make_plugin`, which builds a Prism desktop-page plugin -- a normal Android app advertising
`com.prism.launcher.action.DESKTOP_PAGE` -- from a source directory assembled with the existing
`mkdir`/`write_file`/`list_all_files` tools. A Settings switch under **Intelligence & Messaging**
turns on `context_offload` and `access_context_part`: any backend, cloud or local, can park long
material in a file on the device and retrieve only the lines it needs; the tools are not even
shown to a model while the switch is off. Sam, Nora, Aether and every custom character now keep
their transcripts: Sam's cloud/Ollama turns finally receive the stored dialogue, Nora and Aether
write to app-private storage (migrating their old Documents copy once) instead of a directory that
needs All Files Access, and characters get one transcript file each.

**Launcher, desktop grid, files and editor extensions (2026-10-23).** The bugs listed under
sections 1.1–1.3 are fixed: desktop grids follow their page when one is inserted or removed (no
more silent icon shifting), the page picker is reachable by long-press on an empty desktop cell,
back returns to the desktop instead of rebuilding the launcher, a single drag system now handles
reordering, folders and the delete zone, widget occupancy is never swapped, dragging out of a
folder moves the item, and drawer labels, the alphabet rail and icon packs are respected on the
desktop and hotseat as well as the drawer.

The file explorer's long-press now opens only its menu (never the file underneath), the context
menu offers Cut **and** Copy, and paste is a visible button in the header whenever the clipboard
holds something. Paste is the tested `FileOps.paste`: a folder cannot be pasted into itself, a
taken name is disambiguated instead of overwritten, cutting into the same folder is a no-op, and a
cross-volume cut falls back to copy-then-delete.

Installed editor extensions now appear in the editor: up to three as inline icon buttons at the
bottom of the collapsible left rail; more than three as one Extensions button that opens a second
collapsible rail between the left rail and the code. Selecting one opens a floating, draggable
view of the extension itself: webview panels and webview view providers are implemented in both
extension hosts, the HTML they set is rendered in the popup, and every later assignment of
`webview.html` replaces it, so the view an extension switches to is the one shown. The popup also
supplies `acquireVsCodeApi` (`postMessage`, `getState`, `setState`), so an extension view that
talks back to its extension works. Extensions that draw nothing fall back to a live metadata
panel with their commands. Tree views and debug adapters are still unimplemented and throw by
name. The host plumbing is covered by a Node harness; the popup rendering is compile-verified but
was not exercised on a device in this revision.

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

### 1.1 Swipeable page launcher — ✅ Complete

**What it does.** The home screen is a `ViewPager2` whose pages are produced one per *slot*. A slot
can hold any page type (browser, desktop grid, drawer, messages, wallet, …). The default install has
four slots: Browser, Desktop grid, App drawer, Notifications. A shake toggles “page lock”.

**How to use.** It is the HOME app: swipe left/right; shake to lock/unlock paging; long-press an
icon to drag it; overscroll vertically at the edge of a page to open the page picker.

**Fixed in this revision.** The drawer is now detected by page type rather than “index ≥ 2”, so
Notifications no longer renders permanently zoomed and blurred. Overscroll-to-picker is ignored
while the onboarding tour or a folder popup is above the pager. The picker no longer clamps the
current page to 0..2. Back returns to the desktop page instead of finishing and relaunching the
activity. A failed app launch now says so instead of doing nothing.

### 1.2 Page picker and slots — ✅ Complete

**What it does.** A two-step overlay: choose a position, then a page type (18 built-ins plus
discovered plugin pages). Applying rewrites `SlotPreferences` and rebuilds the pager.

**How to use.** Swipe down/up hard at the top/bottom of a page (700 px travel and >1500 px/s), then
choose a slot and a page type. “+” adds a page left/right.

**Fixed in this revision.** Inserting or removing a page now shifts every saved desktop grid with
its page (`DesktopShortcutStore.shiftPageIndices`, called from `SlotPreferences.addAt/removeAt`),
so no page inherits its neighbour's icons. A long-press on an empty desktop cell opens the picker,
matching what the tour already teaches. The dead, mis-mapping `buildSlotPickers()` was removed.

### 1.3 Desktop grid, drag reorder, folders, delete zone — ✅ Complete

**What it does.** A 4-column icon grid with drag-and-drop, folders, widget cells, resize, and a
delete zone. Folders open a popup and can nest.

**How to use.** Long-press an icon and drag; drop on another icon to make a folder; drop on the
delete zone to remove; long-press a folder to rename.

**Fixed in this revision.** A swap involving a widget or a covered cell is refused instead of
corrupting the widget's occupancy. The duplicate `ItemTouchHelper` drag was removed; the grid's
single drag path handles reordering, folders and the delete zone. The delete zone's container goes
`GONE` after a drag, so the grid no longer stays shifted. Dragging an item out of a folder now
deletes the source `.link` after a successful drop, so it moves rather than copies. The drawer reads
`showDrawerLabels`, the alphabet rail maps letters to adapter rows (groups included), and icon
packs now apply to the desktop, hotseat and group rows as well as the drawer.

### 1.4 Kinetic Halo, widgets, app sync — ✅ Complete

- **Kinetic Halo:** a physics-ish circular app picker. Its geometry is now derived from the view's
  measured size and the display density instead of fixed 120 px icons on a 300 px ring: the ring is
  a fraction of the shorter side, icons are 56 dp, the centre is the view's centre (not the touch
  point, which used to push the ring off screen near an edge), and size changes re-lay it out.
  It shows the first 24 installed apps.
- **Widgets:** real `AppWidgetHost` views are bound by `DesktopWidgetLayer` over the grid, so a
  placed widget is the widget itself rather than a labelled placeholder cell. Widgets can be
  dropped from the app drawer onto a desktop cell or tapped in the drawer's Widgets section (the
  drawer lists installed providers even with an empty search), and placement, resize and removal
  all release the host id. A drag onto or from a widget cell is refused rather than corrupting its
  occupancy.
- **App sync:** an `AppSyncWorker` seeds the installed-app database and a receiver updates it. A
  scan that returns nothing no longer clears the table -- it retries with backoff and keeps the
  stale listing -- and a daily periodic job corrects updates missed while the app was
  force-stopped. Both paths log their outcome.

### 1.5 Plugins (custom pages) — 🟡 / 🧪

**What it does.** A third-party APK can advertise `com.prism.launcher.action.DESKTOP_PAGE` and a
`PAGE_VIEW_CLASS` metadata value; Prism loads the view with a foreign `ClassLoader` and inserts it
as a page.

**How to use.** Install the sample plugin (`sample-custom-page/`) or your own; it appears in the
page picker automatically.

**Fixed in this revision.** `DynamicPageLoader` no longer swallows reflection failures: it returns
  either a view or the reason there is not one, and the pager draws that reason in the slot, so a
  renamed class, a disabled package or a constructor the loader cannot call is visible instead of
  blank. The loader checks that the class is a `View` before instantiating it, tries the foreign
  context first and the host context second, and logs each constructor that fails.

**Remaining gaps.**
- Cross-app view inflation and `createPackageContext` loading are still unverified on a real
  device in this revision; only the failure path is now honest.
- The separate JAR/SPI plugin path (`sample-plugin/`) is **scaffold-only on Android** and is not
  what the page picker discovers.

### 1.6 Theming, icon packs, typography — 🟡 Partial

**What it does.** Light/dark/system theme, an accent colour, neon-glow effects, per-app icon pack
selection, and font selection (system/default/custom).

**How to use.** Settings → Appearance / Theme; Settings → Icon pack; Settings → Typography.

**Fixed in this revision.** A theme, accent, font or icon-pack change made while a screen sits
  behind Settings is noticed on resume (`PrismBaseActivity.currentAppearanceKey`) and that activity
  recreates itself once, so open pages no longer keep the old accent until the app is killed. The
  manifest now declares the four icon-pack theme intents under `<queries>`, which Android 11+
  requires before the picker can see installed packs, and the desktop and hotseat resolve icons
  through the active pack (cache keyed by pack, so a switch is not cached over). Typography is
  applied by walking the decor view on content change and on resume rather than through the
  inflater hook AppCompat never calls, which is what made the font setting appear inert.

**Remaining gaps.** Folder previews draw icons straight from `PackageManager` rather than through
  the active pack, and the WebView font CSS exists but is not injected by every WebView in the app.

### 1.7 Onboarding — ✅ Complete (gesture + wallet flow)

**What it does.** A first-run tour over the launcher explaining pages, the drawer, gestures and
privacy features.

**What changed.** The tour now opens on the page picker, not on a welcome paragraph. The second
screen is the same three-swipe capture UI Settings uses: the user performs their intended gesture
three times, the samples must agree on direction, and the resulting direction/distance is stored
as the page-picker trigger. The third screen is wallet setup, and it is forced the same way: the
user creates a wallet or restores an existing phrase, sees the phrase once, and confirms it is
written down before `Next` becomes available. Every encrypted mesh feature — clipboard sharing
between the user's own devices, the P2P DNS identity, the cloud vault, the text relay — derives
its key from that phrase, so skipping it would leave half of Prism silently doing nothing.
`Skip` and `Jump to a section` stay hidden until BOTH the trigger and the wallet exist. Every other
screen is still skippable.

**Where the wallet can be managed later.** The Wallet page, and the recovery phrase is never shown
again without an explicit action.

**Where to change it later.** Settings → Other → Page picker swipe. The screen also resets to thebuilt-in vertical overscroll. The wallet is managed from the Wallet page; the device's mesh
  identity is shown on the Trusted devices screen. It is derived once — from the wallet namespace
  and the machine name, or from the machine name alone before there is a wallet — and then pinned,
  so importing a wallet cannot orphan a pairing that already exists.

**Remaining gap.** The desktop tour is a separate content list; only Android has the interactive
capture screen.

### 1.8 Lock screen, pattern, duress — 🟡 ⚠️

**What it does.** A PIN/pattern lock gate with an optional duress code and a decoy launcher, plus a
contacts/medical card on the lock screen.

**How to use.** Settings → Lock screen → set a PIN/pattern. The duress code is set during setup or
from Settings → Medical → Emergency code.

**Fixed.**
- **The lock no longer silently disappears.** `LockGate` timestamps the launch and retries when no
  lock activity has announced itself within two seconds. `PrismLockActivity` marks itself shown on
  resume and clears the flag on destroy; a launch blocked by Android 10+ background-activity rules
  is no longer indistinguishable from a visible lock.
- **The lock can be disabled and cleared.** Settings → Medical has an enabled toggle, the setup
  screen has “Turn off the lock screen”, and both clear `LockStore` and the settings flag. Fingerprint
  unlock is now a real, written setting (off by default) with a setup toggle and a Settings toggle.
- **Duress has a real exit.** Long-pressing the clock in the decoy launcher asks for the NORMAL
  credential and returns to the real launcher; the duress code does nothing there. Settings also has
  “Leave decoy mode” for a flag left behind by a killed process.
- **Re-running lock setup preserves the emergency code.** `LockStore.configure` reuses the existing
  salt when the mechanism is unchanged, refuses a new primary equal to the duress code, and only
  clears duress when the mechanism changes (where the old hash could no longer verify).
- `PatternView` no longer submits a cancelled gesture as a wrong attempt.

**Remaining limits.** This is still not a keyguard replacement — Android has no API for that, so the
system lock must be None/Swipe to avoid unlocking twice. The attempt counter is in memory and resets
with the process. A duress alert still needs SMS permission, a last-known location and configured
emergency contacts.

---

## 2. Browser, DNS and privacy

### 2.1 Tabbed browser — ✅ Complete

**What it does.** A Chromium `WebView` page with multiple tabs, public/private categories, an
address bar, bookmarks, downloads, a tab-preview overlay, and a mesh “download site” action.

**How to use.** Swipe to the Browser page → `+` for a tab; the tabs button opens the grid; ⋮ opens
reload/bookmarks/downloads/mirror.

**Fixed.** A tab's preview is captured only while it is the visible tab (and again as it is being
left); background tabs reuse their last real thumbnail instead of drawing a `GONE` 0×0 view. A
capture under two pixels now returns null rather than a 1×1 bitmap. The page no longer casts its
context directly to `LauncherActivity`: a wrapped context is walked to the host, and everything that
needs the launcher handles its absence.

### 2.2 Private mode / DNT — 🟡 ⚠️

**What it does.** Private tabs request `DNT: 1` and `Sec-GPC: 1`, disable third-party cookies and
DOM storage, never enter history/cache, and can be gated by biometrics.

**Fixed.** Every navigation now goes through `loadWithPrivacy`, so in-page links, redirects and the
cached-page fallback keep the DNT/Sec-GPC headers instead of only the first URL in the tab. A
private tab's downloads go to app-private storage, are absent from Prism's download list and are
deleted when the tab closes. The biometric unlock expires after two minutes and is cleared on
detach or when the last private tab closes.

**Remaining limit.** Android's `WebView` has one process-wide cookie jar and no per-tab profile.
When the last private tab closes, Prism clears session cookies; persistent cookies set by a public
tab can still be read by a private one. A true fix needs a separate WebView process/profile, which
is not available per tab.

### 2.3 Content blocking / blocklist — 🟡 Partial

**What it does.** A Room-backed host blocklist seeded from StevenBlack, plus user import and custom
domains. At the packet/DNS layer, blocked hosts become /32 and /128 routes so traffic never leaves.

**How to use.** Settings → Privacy & VPN → Blocklist; add/import domains there.

**Fixed.** Blocking now applies to every tab, not only private ones, and answers with a real 403 and
a short blocking page rather than a synthetic empty 200. The route table resolves up to 2,000 hosts
and 4,096 unique addresses (it stopped at 100), skip-lists the common public resolvers, supports
IPv6 /128 routes, and the per-app bypass list is finally passed to
`addDisallowedApplication`.

**Remaining gap.** Building the route table still resolves each blocked name through the active
network before installing the route, so the lookups themselves are visible to the local resolver.
The DNS proxy path blocks before forwarding, so this only affects direct-IP or non-Prism DNS paths.

### 2.4 Bookmarks — ✅ Complete

Star toggle, multi-select removal, and correct state on navigation all work
(`BrowserPageView.kt`).

### 2.5 Downloads — ✅ Complete

**What it does.** Hands downloads to Android's `DownloadManager` and records completed files.

**Fixed.** “Open” resolves the download through `DownloadManager` first, so it gets a grantable
`content://` URI and the name the manager actually used; when the row has aged out it falls back to
a `FileProvider` URI (the provider's paths now include `Download/`). The old `file://` URI threw
`FileUriExposedException` on every supported Android version. The progress poller gives up after
three consecutive missing rows and reports “no longer tracked” instead of looping forever.

### 2.6 Whole-site download / mirroring — 🟡 Partial

**What it does.** Crawls a site, stores it under `/storage/emulated/0/Prism/Mirrors/<domain>`, and
verifies a manifest with SHA-256.

**Fixed.** `.remote` now survives the OkHttp request URL, so the DNS resolver excludes this
device's own loopback mirror and the peer is actually asked; the proxy strips the suffix only when
dispatching to the host. A concurrent-mirror set prevents auto-mirror from starting the same crawl
on every navigation. A peer-supplied manifest path is canonicalised and rejected if it escapes the
mirrors directory (the previous code accepted `..` and absolute paths). A crawl now downloads into
`<domain>.partial` and swaps it into place only after every file's hash matches, keeping the
previous complete mirror as `.old` for the duration of the rename; a failed or killed crawl leaves
staging that `cleanupInterrupted()` deletes at startup, and the live site never serves a half-
downloaded copy.

**Remaining gap.** There is no resume: an interrupted crawl starts its downloads again rather than
continuing from the bytes already staged.

### 2.7 P2P site hosting and virtual-host mapping — ✅ Complete

**What it does.** Serves a hosted folder under a `.p2p` domain through the tunnel; the browser maps
the domain to the mesh host while preserving the `Host` header.

**Fixed.** The proxy reads the request head for both handshake and plain HTTP, so the plain-HTTP
`Host:` branch is reachable instead of reading zero bytes and closing. The hosting listener answers
ordinary HTTP clients as well as `PRISM_CONNECT`. A folder chosen through SAF is walked with
`DocumentFile` and produces a real manifest (it used to produce an empty one). Toggling a hosted
site off or deleting it clears `PrismWebHost`'s per-domain file cache.

### 2.8 P2P DNS — 🟡 (signed records)

**What it does.** A mesh-replicated domain→IP ledger with verification and alternates, seeded back
into the mesh after successful global lookups.

**Fixed.** A remote write is now bound to the sender: `P2pDnsManager.updateRecord` rejects a
change to a live record that points somewhere else, rejects an address that is not the sender's
own, and `deleteRecord` only lets a peer remove a record it owns. Sync ingestion applies the same
rule. The ledger moved from world-readable `/Prism/p2p_dns_index.json` to app-internal storage,
with a one-time migration; the map is concurrent; records expire after 24h (P2P) or 6h (global)
unless they back a locally hosted site; DNS-sync bodies are gzip+Base64-compressed and checked
against the 16 KB UDP budget instead of being silently truncated.

**Fixed — cryptographic identity.** Every record now carries an Ed25519 public key and a signature
over domain, address, timestamp and sorted alternates. The signing seed is HKDF-derived from the
wallet's BIP-39 seed under its own label (`prism-p2p-dns-ed25519-v1`), never from a coin private
key, and is verified per record on both the write and sync paths. A name that is live under one
identity cannot be repointed or deleted by another, even from the same address. An unsigned record
from a build that predates this still follows the address rules rather than being refused, so a
mixed-version mesh keeps working.

**Remaining gap.** A brand-new name is still first-come: the signature binds a name to an identity
once it exists, but there is no registry deciding who may publish it first. A sync body that
remains over budget is dropped with a logged warning rather than chunked.

### 2.9 DNS proxy (Access Point mode) — ✅ Complete

The service is enabled in the manifest (`android:enabled="true"`), so `PrismApp` and Settings can
start it. It binds 53 where the platform permits and 5353 otherwise, reports the bound port in
Settings, and the fallback mode now forwards the original query to the configured primary DNS
instead of returning NXDOMAIN regardless of the picker. Upstream sockets are VPN-protected.

**Remaining limit.** Android does not let an unprivileged app bind port 53; on those devices the
client must be told to use 5353, which the Settings row now says.

### 2.10 Private DNS VPN / tunnel — 🟡 ⚠️

**What it does.** A `VpnService` that intercepts UDP/53, resolves through the P2P ledger, and can
capture blocked destinations.

**Fixed.** The Settings “Connect” button now passes `establishTunnel = true`; the service no longer
stops itself immediately on a default install. `protectSocket` returns and logs failure instead of
swallowing it, and `isRunning()` means a TUN interface exists rather than merely that the service
object was created. Blocked IPv6 destinations are installed as /128 routes and dropped, and the
per-app bypass list is applied.

**Remaining gap.** It still is not a full tunnel. There is no default route and no userspace TCP
stack, so only DNS filtering and blocked destinations are captured; everything else continues to
use the normal network. Adding `0.0.0.0/0` without that stack would break connectivity, so it is
deliberately not done.

### 2.11 TLS and mesh certificates — 🟡 ⚠️

Custom `SSLSocketFactory` suppresses SNI so `.p2p` names handshake. Peer verification is now real:
the chain must be internally valid, the leaf SAN must match the host, and the peer's CA is pinned
per name on first use (TOFU) and refused if it changes. The Android CA key is wrapped with an AES
key in Android Keystore, moved to `noBackupFilesDir`, and the legacy locations are excluded from
Auto Backup and device transfer.

**Remaining gaps.** First use is still trust-on-first-use; the mesh has no shared CA to verify
against. The desktop uses its own CA implementation, so a certificate minted on Android does not
chain to the desktop CA. A CA rotation invalidates its pins until the user clears them.

### 2.12 Proxy server/client — ✅ Complete

The proxy now reads the request head once and forwards plain absolute-URI HTTP requests (the old
code only handled `CONNECT` and closed the socket for everything else). It writes the already-read
head to the target, relays both directions, strips `Proxy-Authorization` before forwarding, resolves
mesh names through the P2P ledger and uses `PrismSocket` for them, and protects non-mesh outbound
sockets so they do not loop through the VPN. With no credentials configured the proxy binds
`127.0.0.1` instead of `0.0.0.0`.

### 2.13 Blocked-IP route table — ✅ Complete

Destination parsing bounds-checks IPv4 version/IHL and now packs IPv6 destinations too. The table
resolves up to 2,000 hosts / 4,096 unique addresses, installs /128 routes for IPv6, and skip-lists
the common public resolvers so blocking a host cannot cut off the resolver itself.

---

## 3. Meshnet, VPN protocols and access point

### 3.1 Mesh discovery, heartbeat, peer list — 🟡 Partial

**What it does.** UDP broadcast discovery on port 8081, heartbeats, peer-list exchange, RTT
measurement, DNS sync and feature gossip. Two implementations exist: `PrismMeshService` (Android,
in `:app`) and `MeshCore` (desktop/`:core`), deliberately speaking the same wire format.

**How to use.** Settings → Mesh (Android) / the *Mesh* page (desktop); enable and optionally set a
bootstrap port. Peers appear in the Mesh page.

**Fixed in this revision.**
- The desktop now installs `MeshCore.portProvider`, `bootstrapNodes`, `MeshDns.install()` and
  `registerWithMesh()`, and re-announces local `.p2p` names after the socket is up — the Mesh page
  settings are no longer display-only, and the registry gossips and persists there.
- UDP send failures are counted and logged (first failure, then every fiftieth) in both
  `PrismMeshService` and `MeshCore`, and `listenerHealth()` reports the count and the last reason —
  “mesh up, nothing arrives” now has an answer.
- `MeshCore.register` refuses the built-in opcodes and logs when a feature opcode changes hands.
  Registering the same handler twice stays quiet, because that is what a re-run install looks like.
- **A no-peer broadcast storm is fixed.** The gossip loop's “nothing known, broadcast again” branch
  did `continue` without sleeping, so a device on a network where no other Prism peer runs (the
  common case) broadcast as fast as the CPU could build packets and pinned a core at 100%.

**Still true.** Opcodes `0x11, 0x12, 0x24-0x28, 0x30, 0x31, 0x50-0x57` are Android-only; the
  desktop drops them as unclaimed, which is expected for unported features. `registeredOpcodes()`
  is the diagnostic, but there is no wire-level “this peer does not support that” reply.

### 3.2 Mesh roles, kick and ban — 🟡 Partial

Server/client roles are real; kicking and persisting a ban work. **Fixed:** the ban is enforced on
the peer-facing TCP surfaces too. `PrismMeshService.isPeerBanned` / `MeshCore.isPeerBanned` map a
connection's address back to its node id, and the model host (Android `PrismAiHost`, desktop
`MeshAiHost`) refuses a banned peer before reading the request.

### 3.3 WireGuard, L2TP, IKEv2, proxy — 🟡 / 🔴

| Protocol | Status | Notes |
|---|---|---|
| Proxy tunnel (`PRISM_CONNECT`) | ✅ | The most complete VPN path; Basic auth enforced on both ends. |
| WireGuard client (Android) | ✅ | Real client. Parse failures and a refused `setState` now log a reason and `start()` returns whether the tunnel is up instead of swallowing both. |
| WireGuard server (Android) | ✅ | **Real Noise_IKpsk2 responder.** `WireGuardNoise` in `:core` implements X25519, ChaCha20-Poly1305, BLAKE2s, the IK handshake and transport framing; the device verifies MAC1, authenticates the initiation, allocates per-peer addresses from 10.8.0.0/24, and rejects replayed/forged transport packets. Covered by vectors and a full client↔server handshake test. Cookie replies and rekey timers are not implemented. |
| WireGuard server (desktop) | ✅ | Delegates to `wg-quick` / `wireguard.exe`. |
| L2TP | 🟡 | **Control plane rewritten**: header and AVPs are parsed by their real lengths, and SCCRQ→SCCRP, SCCCN, ICRQ→ICRP, ICCN and StopCCN are answered with correct ids and sequence numbers. The data path (PPP/LCP/CHAP/IPCP) is still absent, so a client reaches “PPP authentication” and fails there. |
| IKEv2 | 🔴 | **The fabricated SA_INIT is gone.** It answered with random “public values”, which cannot complete an IKE exchange and made the fault look like the client's. There is no IKEv2 stack; the engine now detects IKE and says so in the log. |
| Split tunnelling / app whitelist | ✅ | `addDisallowedApplication` is applied when the VpnService is built. The foreground-app monitor that paused the entire tunnel when a bypass-listed app came forward — including DNS, the proxy and the WG client — is deleted, along with the never-grantable `PACKAGE_USAGE_STATS` permission. |

### 3.4 Access point, portal, connected devices — 🟡 / 🧪

- **AP creation:** ✅ creates a hotspot/group and persists the config. **The store is JSON now**,
  so an SSID or password containing `::` or `;;;` no longer corrupts the record and makes the
  access point vanish; legacy records are migrated on read.
- **Portal:** ✅ **Stop works across a restart** — it uses the persisted record and `removeGroup`,
  which reaches the system's group rather than only this process's map. Stopping also closes the
  DNS listener and removes isolation rules.
- **Connected devices:** ✅ filled from the kernel neighbour table (`/proc/net/arp`), with the
  limitation stated on screen when that table is unreadable; per-device block, limit and disconnect
  act on real entries.
- **DNS override:** 🟡 the responder is started and stopped with the access point, and a copied
  packet buffer bug that answered one client with another client's query is fixed. Clients only use
  it when it can bind :53 (root) or when they are pointed at port 5353 by hand.
- **Network isolation:** 🟡⚠️ honest rather than silent: each `iptables`/`tc` call runs through
  `su` and a failure returns `Result.failure("Root is required…")`, which the portal now displays.
  It does not pretend to isolate on an unrooted device.

### 3.5 Mesh compute market, listings, coin offers — 🟡 / 🔴

- **Compute market:** ✅ gossip, pricing and the debt ledger are real, and the single-peer offload
  now works: `MeshBridges.generateOnPeer` defaults to a real `POST /generate` over the same
  `connect` hook both platforms use, instead of being a null field nobody assigned.
- **Model listings / transfers:** ✅ the seller and the store now resolve models through
  `ModelRegistry.directory()`, the same folder `P2pModelTransfer` writes to, so a paid download
  finds the file. The host also honours the hosting switch and refuses banned peers.
- **Coin offers:** ✅ discovery/quoting works; taking an offer is the separate (scaffold) conversion
  feature.
- **Mesh AI RPC host:** ✅ streaming and non-streaming prompts work; the pre-read-header trap is
  fixed.
- **Mesh TLS:** 🟡 Android and desktop generate separate, incompatible CAs.

### 3.6 Mesh compute/storage/gaming cloud — 🟡 / 🔴

Cloud storage has real HKDF encryption and chunk distribution. **A chunk no peer accepted is no
longer recorded as stored**: the upload reports how many chunks were refused and is not entered in
the manifest, because a file missing a chunk cannot be restored — previously such an upload looked
successful and failed only at restore time. `CloudVault.namespace()` is used as the display
identity but not yet as a storage key on peers, so there is no cross-device per-user namespace.
Cloud gaming works as a client: `CloudGamingActivity` **is declared in the manifest now** (it was
started but undeclared, so every session died with `ActivityNotFoundException`). Hosting remains
desktop-only — `startHosting()` returns null on Android with a reason, because Android offers no
way to capture another app's game framebuffer for encoding.

---

## 4. AI: Sam, models, agents

### 4.1 Sam chat — 🟡 Partial

**What it does.** A conversation UI over a shared AI manager with on-device GGUF, cloud
OpenAI-compatible, local-cloud (Ollama), P2P shadowing, and CakeChat backends. Messages persist in
Room; replies can stream and be spoken.

**How to use.** Messages page → Sam; Settings → Intelligence & Messaging to choose the backend and
model; Models page to download/import a GGUF.

**Fixed in this revision.** Cloud, Ollama and the agentic loop now fold the stored dialogue into
  the prompt (`AiManager.foldHistory`, `AgenticEngine.foldHistory`), so Sam no longer answers every
  message as if the conversation had just started. The local GGUF path is left unfolded because
  llama.cpp keeps its own context.

**Fixed in this revision.** The hardcoded “video” mock is gone from `CloudAiService` — every path
now goes to a real endpoint, so no prompt can be answered with somebody else's sample MP4. A
backend failure (an answer beginning `Error:` / `P2P Model Error:`) is shown once in a toast and is
**not persisted as Sam's reply**, which previously made the error look like an answer and fed it
back in as context on the next turn.

**Still true.** The local agentic loop re-sends a growing plain-text transcript each round, because
llama.cpp's local path exposes no message-history API here.

### 4.2 Engine selection — ✅ Complete

Local / Cloud / Local-cloud / P2P / CakeChat selection is genuinely wired with a defined shadowing
order. The *quality* of each engine varies (see above).

### 4.3 Model store (browse + download) — 🔴 Broken

**What it does.** Browses Hugging Face/GitHub for models and downloads them through
`DownloadManager`/OkHttp into the model registry.

**Fixed in this revision.**
- GitHub discovery uses `search/repositories` (no token needed) and takes the model file from the
  repository's **latest release assets**, instead of `/search/code`, which requires authentication
  and therefore returned 401 — and an empty list — on every device. Repositories with no release
  assets are skipped rather than offered as a download that cannot work.
- Vision-category models are no longer installed as the image **generator**; classifications and
  detectors stay vision models.
- The downloaded file keeps its real extension (`.gguf`, `.task`, `.tflite`, `.onnx`) instead of
  everything becoming `.bin`, and in-flight downloads are tracked per id, so starting a second
  model no longer makes the first completion get ignored.
- Downloads land in `ModelRegistry.directory()`, the folder the Models page and mesh transfers use,
  and register through the same path — so a downloaded model no longer vanishes or gets pruned.

### 4.4 Model shop: listings, payments, refunds — 🔴 ⚠️

**What it does.** Sellers list a model with a price; buyers pay in PrismCoin; a scanner verifies the
listing; refunds are possible within a window.

**Known bugs.**
- **Delivery is fixed.** The seller resolves models through `ModelRegistry.directory()`, the same
  folder the upload and mesh-transfer paths copy into, so `GET /model/<name>` finds the file and the
  buyer does not 404 after paying.
- The model host now **honours the hosting switch** and refuses banned peers. It still does not
  verify a buyer's payment before serving a listed model; access control is the hosting switch, not
  a receipt.
- **Coins are no longer locked forever.** A purchase still held after 24 h with no verdict is
  released (nothing was ever broadcast, so cancelling loses nothing), re-buying is refused only for
  a live HELD/PAID purchase rather than for a refunded one, and settlement runs on a 30-minute
  timer as well as at launch instead of only once per process.
- A purchase still counts as paid when the transaction is accepted by the node, not when it is
  confirmed in a block.

### 4.5 Image generation, vision, dictation — 🟡 / 🔴 / ✅

- **Image generation:** ✅ the endpoint builder adds the missing slash, the pre-API-29 MediaStore
  path writes the file and records `DATA`, and the local engine now passes MediaPipe the model's
  **directory** instead of a file path.
- **Vision:** 🔴 on Android — `VisionService` still returns hardcoded bracket strings, and those
  strings are fed to social bots as “what they see”. The real `Vision` registry is only used on
  desktop. This is the one item in 4.5 that is unchanged.
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

**Agentic tools:** 🟡 the tool loop, sandboxed file tools and builtin handlers are real. The built-in
set now includes `read_file`, `read_all_files` and `make_plugin`, plus the `context_offload` /
`access_context_part` pair gated behind the **On-device context offload** switch in Settings >
Intelligence & Messaging. `make_plugin` drives Gradle and needs a JDK and the Android SDK on the
device or a wrapper in the plugin; without either it returns the real reason rather than a
fabricated APK, and it never claims a build it did not run. `make_call` now asks for `CALL_PHONE` at
runtime through `AgentPermissionActivity` (a transparent, no-UI host for the permission dialog),
so a call can actually be placed after the user grants it; before this the tool always answered
“permission is not granted” because nothing ever requested it. The local loop still re-sends its
transcript.

**CakeChat:** 🟡 Chaquopy + TensorFlow plumbing is real, but no pretrained weights ship, so it
cannot answer out of the box.

### 4.8 SMS relay — 🟡

Sending and receiving are implemented over the mesh with sealed payloads. The receive gate is real
and has a switch: **Settings → Intelligence & Messaging → SMS relay** reads and writes
`sms_relay_enabled`, so relayed texts can actually be ingested. It still ships off by default — a
relay is a deliberate choice, and every device on the mesh must turn it on.

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
(`CharacterCreatorActivity`, `CharacterConversationActivity`, `CharacterStore`). Each character's
transcript is saved under its own id (`CharacterChatStore`) and restored when the conversation is
reopened, so two characters on the same backend do not share a history.

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
  Node runtime. Installed extensions now appear in the editor (inline icons up to three, a second
  collapsible rail above three, and a draggable popup that renders the extension's live webview);
  tree views and debug adapters are still unimplemented. Arm64-only assets (~80 MB download,
  ~1.1 GB free); `libnode.so` build logic is stale; no non-arm64 runtime.
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

- **File explorer** — 🟡 real copy/move/delete/rename, archives and clipboard. The long-press menu
  no longer also opens the file underneath it, and cut/copy/paste now works through the tested
  `FileOps.paste`; a paste button appears in the header while the clipboard is occupied. Remote
  locations still list placeholder entries, so the page as a whole remains partial.
- **Notification history** — ✅ the listener is declared and permission-guarded, and history persists
  across restarts. It rewrites the whole file on the listener thread for out-of-order inserts and
  has no per-app exclusion despite claiming one.
- **Trusted devices** — 🟡 pairing, offers, clipboard/SMS/history/wallet/app transfer and
  virtualization are real. An unsolicited `TRUST_REPLY` cannot create a confirmed device: a reply
  is only accepted against an offer this process is holding in memory, and the pairing code must
  be proved. A reply arriving after a restart (when the outstanding offer is gone) is refused and
  the far side re-offers. Two corrections make a pairing stick: the final `TRUST_ACK` and the
  answer to an offer are each sent again when they are not confirmed, so one lost datagram cannot
  leave the offering device listing a device the answering one never stored; and a device's
  fingerprint is now **pinned in `trust/device-fingerprint`** the first time it is derived. It used
  to be recomputed from the wallet namespace, so importing a wallet — which a pairing itself can
  do — changed the identity the other side had already written down. Renaming the machine no longer
  changes it either; the name is a label.
- **Clipboard, both directions** — ✅ open the history from the row under the Prism Search address on
  the Settings root (desktop: the Clipboard history row in the desktop group). Text copied in any
  app while Prism is focused, text copied
  elsewhere and picked up when Prism is resumed, explorer cut/copy of files and folders, and OS-level
  file copies on the desktop all become history entries and are shared with devices paired for the
  Clipboard kind. Tapping an entry makes it active: text is written to the system clipboard, a file
  is loaded into the explorer's clipboard, and a remote file is fetched when the explorer's paste is
  used. Pastes are zip-based over the trusted TCP channel, and a CUT deletes the source on the
  sender only after the receiver's download has fully written.
  **Instant sharing, and the rule that governs it.** Android 10+ hands clipboard reads and change
  callbacks only to an app with window focus or to the DEFAULT KEYBOARD's package (a signature
  `READ_CLIPBOARD_IN_BACKGROUND` grant is not available to Prism). Prism Writer is already in the
  build, so the clipboard screen carries a **Live sharing** switch: turning it on and selecting
  Prism Writer as the keyboard makes a copy reach paired devices within a second, from any app.
  `ClipboardWatchService` is a foreground service whose only job is keeping the listener's process
  alive; the system reports each change and nothing polls. The 1×1 focus-stealing overlay that other
  clipboard managers use is deliberately not used, because it hides the user's keyboard mid-sentence.
  With another keyboard selected the watcher stands down rather than show a notification that claims
  sharing it cannot do, and copies go out the next time Prism is opened; a keyboard change while it
  runs is noticed through `ACTION_INPUT_METHOD_CHANGED`. On the desktop the watcher polls once a
  second because AWT has no clipboard change listener.
- **Prism Debug Bridge** — ✅ a mesh shell with a device list, one-time code verification and a
  terminal on both Android and the desktop. The target runs `Pdb.install()` at app start, so it is
  reachable before anyone opens the list. The controller sends `hello` (its fingerprint, name,
  platform, and the pre-wallet fingerprint as an alias); an unverified pair gets a twelve-character code
  that is shown only on the target and is bound to a PBKDF2 challenge, while a verified pair opens
  directly. The alias is what lets a record written before the fingerprint was pinned still be
  found: one successful verification stores the pair secret under the identity the device
  announces now and moves the trust record to it. An alias only finds a record — the secret or the
  code is still proved, and the address still has to match. A target whose wallet is locked
  refuses with "unlock it there" rather than announcing a value it cannot derive. Commands execute as the Prism
  app through `PdbAdb`: `adb shell` becomes the command, `adb logcat` becomes `logcat -d`,
  `adb install` uploads the package and asks the target's `PdbHost` to install or store it, and
  `adb push`/`pull` move files and whole directories over the bridge's file channel. Output,
  stderr and the exit code are separate events, so `adb shell false` is visibly a failure. The
  desktop additionally offers **Bridge to adb**: `AdbBridge` binds a loopback ADB smart socket,
  registers `127.0.0.1:<port>` with the installed adb, and carries `shell`/`exec` streams in
  shell-v2 framing (stdout, stderr and exit code) plus the `sync:` protocol, so `adb push`,
  `adb pull` and `adb install` work from the machine's own adb as well as from IDE logcat windows
  and scripts. It binds only to `127.0.0.1`, and is absent from a machine without platform-tools.
  The terminal itself needs none of that.
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

### Secrets and credentials

**Prism ships no secrets, and no credential is required to build or run it.** Every key below is
supplied by the user at runtime and stored in app-private preferences (`PrismSettings`); the code
reads no `.env` file and compiles no key into the APK or the desktop build.

Add your own values for the integrations you enable, and never commit them:

| Credential | Where to add it | Needed for |
|---|---|---|
| Cloud-model **API key**, **base URL** and **model ID** | Settings → Intelligence & Messaging → **Manage Cloud Models** — one profile per provider | Sam's and Aether's cloud turns, dictation, vision and image generation |
| **Tenor API key** | Settings › Prism Writer, as the GIF panel instructs; stored as `writer_gif_api_key` | GIF and sticker search in the Writer keyboard. The settings row is not built yet, so until it exists the key has to be written through preferences |
| **WireGuard keys** | Generated by Prism when a tunnel or peer invitation is created; keep them in the generated config only | VPN tunnels and the WireGuard server |
| **Android release keystore** | Your own keystore, wired into your local Gradle setup | Signed release builds; debug builds need no signing material |

`.gitignore` already excludes `local.properties`, `*.jks`, `*.keystore` and `**/*.log`. Keep `.env`
files and service-account JSON out of the tree as well. A key committed by accident must be revoked
at the provider: deleting it in a later commit leaves it in history.

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
| 1 | Swipeable page launcher | ✅ Complete |
| 2 | Page picker / slots | ✅ Complete |
| 3 | Drag & drop desktop grid | ✅ Complete |
| 4 | Folders | ✅ Complete |
| 5 | Delete zone | ✅ Complete |
| 6 | App drawer | ✅ Complete |
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
