git commit -m "Add lock screen, Science and Editor pages, mesh compute market, and Stremio in Lyke" -m "
LOCK SCREEN (app/.../lock/)
- Prism draws its own lock screen: PIN, password or 3x3 pattern, chosen at setup.
- Fingerprint/face via androidx.biometric where a sensor is enrolled.
- Background is the user's own lock wallpaper (WallpaperManager FLAG_LOCK), falling back to
  the home wallpaper then a gradient. Wrapped in runCatching: wallpaper reads are
  permission-guarded and have tightened across releases, and a lock screen that throws locks
  the user out of their phone.
- DURESS CREDENTIAL. A second code that also unlocks. Checked first, constant-time compare,
  identical work on both branches - nothing observable may differ, because the threat model is
  someone watching the screen, not someone guessing the PIN.
- On duress: last-known location sent by SMS to emergency contacts from a background thread,
  and the launcher flips to a decoy. No toast, no vibration, no delay, no notification.
  Deliberately does not call anyone or contact emergency services.
- Decoy launcher shows five boring capabilities (dial, browser, camera, clock, calendar) and
  filters CATEGORY_SOCIAL/CATEGORY_IMAGE. Probe-and-show rather than enumerate-and-subtract,
  because subtracting fails open. Banking cannot be auto-detected and needs a user list - not
  yet built.
- Duress PIN is forced to the same length as the normal one: a PIN pad submits at a known
  length, so a different-length duress code is countable by anyone watching.
- Medical card, readable without unlocking because the reader is a stranger giving first aid.
  Blood type, allergies, conditions, medications, DNR, organ donor, notes, emergency contacts.
  Hidden entirely when empty. DNR labelled as the owner's stated wish, not an instruction.
- Honest limitation documented throughout: Android has had no API to replace the keyguard
  since 5.0. This is a second lock unless the system lock is set to None or Swipe.

MESSAGING + CONTACTS
- Messaging page gains tabs: Messages, Contacts.
- Contacts lists ContactsContract with emergency contacts pinned to the top.
- Contact detail can designate an emergency contact, gated by fingerprint or, failing that,
  the duress credential. Verifying it there never fires the alert.
- Emergency contacts stored by number, not contact id: ids move on sync/restore/merge.

MESH COMPUTE MARKET + DISTRIBUTED INFERENCE (app/.../mesh/)
- ggml RPC backend enabled (GGML_RPC=ON). One model's layers now run across several phones:
  peers become devices, llama.cpp spreads layers, weights upload at load time so a peer never
  needs the model file. Local CPU is appended last so remote memory fills first.
- Three new JNI entry points: nativeStartRpcServer, nativeLoadModelDistributed,
  nativeAcceleratorMemoryBytes.
- RPC binds to the mesh interface, not 0.0.0.0 - ggml's server has no authentication and
  upstream documents it as unsafe to expose.
- Compute market as a third Wallet tab: capability gossip (opcodes 0x20-0x23), re-announced
  every 5 minutes, sorted strongest-first, tap to pick one, long-press for multi-select.
- Prices derived identically on every device from declared hardware - nobody names their own
  price, because a self-set price is a claim and a market of claims needs escrow to mean
  anything. Speeds are labelled indices, not GFLOPS.
- Debt ledger: work runs when the balance is short, is announced mesh-wide, and settles
  oldest-first when funded. A reputation system, not escrow - a scriptless chain cannot do more.
- allowOffload flag prevents two phones pointed at each other from deadlocking.

SCIENCE PAGE (app/.../science/)
- New non-default desktop page with a collapsing icon rail, same behaviour as the Editor's.
- Cosmic-ray detector: camera as a particle sensor, 60-frame hot-pixel calibration, light-leak
  rejection, and mesh coincidence. Reports the accidental-coincidence rate (2*r1*r2*tau)
  alongside the observed one - a run that has not beaten chance has measured nothing.
- Tamper-evident lab notebook: hash-chained entries signed with the wallet key, countersigned
  by mesh peers. Commercial ELNs are databases with audit logs an administrator can edit.
- RF survey: Ekahau-style bands and gradient, several phones walking at once so the samples are
  contemporaneous. Ranked points, not an interpolated heatmap nobody measured.
- Spirometry: FEV1/FVC as the headline, because a ratio cancels the unknown microphone scale
  factor. Peak flow and volume labelled relative. This is why SpiroSmart never shipped.
- Audiometry: real Hughson-Westlake staircase, 20ms ramps so the click is not mistaken for the
  tone, conventional O/X audiogram. Plotted in dB below full scale, NOT dB HL - a phone cannot
  know what pressure its headphones make.

EDITOR PAGE (app/.../editor/, assets/editor/)
- Monaco in a WebView with a collapsing rail: File, Edit, Selection, View, Go, Run, Terminal,
  Marketplace. iOS-styled throughout.
- Two extension hosts: a Web Worker for 'browser' extensions, and a real Node.js for 'main'
  ones, sharing one vscode API shim.
- libnode.so build recipe added (tools/node/build-libnode.sh, nodejs-mobile/Node 24, 16KB
  pages). Derives -j from RAM not cores, and provisions swap - a .wslconfig that declares swap
  is not evidence of swap.
- CMake builds the Node bridge only when libnode.so is present, so a checkout without a 90MB
  binary still builds.
- .vsix ceiling raised 64MB -> 512MB with streaming enforcement and free-space checks.
- Extensions screen is tabbed: Installed first, then Browse.
- Built-in completion for Java, Kotlin and Python; working shell below the editor.

STREMIO ADD-ONS IN LYKE (app/.../stremio/)
- Repositories and add-ons managed from Settings > Other. No account, ever: an add-on IS its
  manifest URL and the protocol has no registry or gatekeeper.
- Three install routes - paste a URL, Stremio's public community catalogue, or a stremio://
  link from any add-on's own Install button (verified end to end on device).
- Streams are requested from EVERY capable add-on filtered by type and idPrefixes, not from the
  add-on the item came from. Cinemeta declares no 'stream' resource at all; getting this wrong
  makes every catalogue add-on look broken.
- Series expand into episodes via /meta, then resolve streams per episode id.
- Stream picker lists unplayable sources with the reason (BitTorrent, YouTube, external link)
  rather than hiding them.
- behaviorHints.proxyHeaders honoured - without them some sources 403 and look like dead links.
- Comments and likes work on add-on content; mirroring is off for it.

LYKE + NEBULA
- Description prompt after recording or picking a video; the file is copied first, asked second.
- Search expands from the magnifier: Lyke captions and mesh videos with thumbnails, add-on
  catalogues and titles with posters.
- Nebula's compose button and Home/Chats nav hidden while Lyke is showing.
- Lyke picture-in-picture when leaving Prism mid-video.

WEB CACHE + PERSONAL HISTORY
- Capture-on-page-finish web cache with optional mesh hosting under .cache.p2p, media capture,
  and automatic upload of cached video to Lyke.
- Append-only JSONL history of pages, videos, messages and files, searchable by LLMs through a
  search_personal_history agentic tool.

WALLET, MINING, MODEL MARKET
- PrismCoin chain, HD multi-coin wallet, RandomX mining, mesh pool, Monero stratum.
- Model store with PSC payments, listing verification, refunds and purchase ledger.

WINDOWS RUNTIME (app/.../virtualization/)
- Wine + box64 under PRoot, X server through the existing VNC renderer, .exe intent filter
  shipped disabled and toggled by the Virtualization setting. Needs user-supplied libproot.so
  and a rootfs archive.

FIXES
- Editor page NPE: views declared below init were null while init ran.
- ClinicalPanel NPE: same bug, same cause.
- Stremio activities crashed on launch: Activity field initialisers run inside
  Class.newInstance(), before attachBaseContext, so constructing a View with 'this' throws out
  of getResources(). Now by lazy.
- PIN pad never rejected a wrong code - it auto-submitted only when the code was already
  correct, so a wrong PIN accumulated digits forever.
- Lockout disabled a ViewGroup, which does not propagate to children; the keypad stayed tappable.
- PrismTunnelEngine.start() was a one-shot that left currentMode null forever, so enabling VPN
  tunnelling did nothing until app restart.
- ModelsPageView refreshed 10,385 times in 50 seconds via an onVisibilityAggregated loop.

KNOWN LIMITS
- No app can replace the Android keyguard; this is a second lock unless the system lock is off.
- The decoy is not a second profile and does not hide data at the filesystem level.
- Fingerprint cannot trigger duress - it is one identity, and duress needs a second secret.
- Subtitles from Stremio add-ons need a real player; VideoView cannot render them.
- Stremio and lock-screen UI are built and compile but are not verified on hardware.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
