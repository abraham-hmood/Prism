# Prism — Android browser & DNS stack audit

Scope: `app/src/main/java/com/prism/launcher/browser/*`, `.../vpn/*`, `BlocklistActivity.kt`,
`vpn/WhitelistActivity.kt`, `AndroidManifest.xml`. Read-only audit: no source was edited and nothing
was built or run, so **every runtime claim below is "unverified" unless the code path is
deterministic from the source.** Line numbers are from the files as read.

Helper facts used throughout:

- `minSdk 26`, `targetSdk 35` (`app/build.gradle.kts:15-17`).
- `PrismSettings.documentsDir()` = `/storage/emulated/0/Prism` (`platform/AndroidPlatform.kt:51-52`).
  So mirrors live in `/storage/emulated/0/Prism/Mirrors/<domain>`, the web cache in
  `/storage/emulated/0/Prism/WebCache/<host>`, and the P2P DNS ledger at
  `/storage/emulated/0/Prism/p2p_dns_index.json`.
- Defaults that gate almost everything: `getVpnTunnelingEnabled()=false` (`PrismSettings.kt:1231`),
  `getVpnServerAlwaysOn()=false` (:1258), `getWebCacheEnabled()=false` (:1589),
  `getDnsProxyEnabled()=false` (:1144), `getAutoMirror()=false` (:1752), `getVpnAutoStart()=true`
  (:936), `getPrivateTabsLocked()=false` (:943), `DEFAULT_DNS_A/B = 1.1.1.1 / 1.0.0.1` (:2537-2538).
- Browser entry: the browser is a launcher desktop slot (`MainDesktopPagerAdapter.kt:66`,
  `SlotAssignment.Browser`), i.e. **swipe to the "Browser" home page**. Settings > Browser
  (`SettingsActivity.kt:898`) holds its preferences.

---

## 1. Tabbed browser

**What it does.** `BrowserPageView` (`browser/BrowserPageView.kt:34`) is a `FrameLayout` page that
owns `ArrayList<Tab>`, one `WebView` per tab, a `GridLayoutManager(2)` tab-card overlay
(`BrowserTabsAdapter`), a URL field with go/reload, a bookmark star, a mesh "download site" button,
and a bottom-sheet menu. Tab cards show a captured preview bitmap.

**User path.** Home → swipe to Browser page → `+` (add standard tab) / tabs button → tab overlay →
Public/Private category buttons → card tap selects, `×` closes. Menu: ⋮ → Reload / Bookmarks /
Downloads / Mirror.

**Status: PARTIAL.** Tab creation, selection, close, back (`handleBack`, :165), URL normalisation
(:620-652) and title/URL plumbing all work. Three concrete defects:

- **Tab previews are 1×1 for every background tab.** `refreshTabsList` calls
  `captureWebPreview(tab.webView, 720, 900)` (:359) for *all* tabs, but only the active tab is
  `VISIBLE`; the rest are `View.GONE` (:309). A never-laid-out / GONE view reports `width=0`,
  and `captureWebPreview` clamps with `coerceAtLeast(1)`
  (`BrowserTabsAdapter.kt:71-75`), so the bitmap is 1×1. Even for the active tab the capture is
  `webView.draw(canvas)` (:90) on a hardware-accelerated WebView, which on Android commonly yields
  an empty surface — *unverified*, but the GONE case is deterministic.
- **Preview capture is synchronous on the UI thread** for every tab on every overlay open
  (`BrowserPageView.kt:359` → `BrowserTabsAdapter.kt:69-94`); with N tabs this is N software draws
  per open (`refreshTabsList` is also called on category switches).
- **`context as LauncherActivity`** in the constructor (:36) throws `ClassCastException` if the page
  is ever inflated with a context wrapper (theme/inflater); it is currently only built from
  `MainDesktopPagerAdapter.kt:66` with the activity, so latent.

---

## 2. Private mode / DNT

**What it does.** Private tabs get `DNT: 1` and `Sec-GPC: 1` request headers, third-party cookies
off, DOM storage/database off, `MIXED_CONTENT_NEVER_ALLOW`, and are excluded from history and from
the web cache. Optional biometric gate; optional "private by default".

**User path.** Browser page → tab overlay → **Private** category → `+`. Settings > Browser →
"Private by default" (`SettingsActivity.kt:1022-1027`); Settings > Privacy & VPN → "Locked private
tabs" (:1264-1269).

**Status: PARTIAL — private mode is cosmetic.** Evidence:

- **DNT/Sec-GPC are lost on every in-page navigation.** They are passed only to `loadUrl`
  (`BrowserPageView.kt:200, 523, 651`). `shouldOverrideUrlLoading` re-loads the target with
  **no headers**: `view.loadUrl(request.url.toString())` (`PrismWebViewClient.kt:34`). Clicking any
  link therefore strips DNT and Sec-GPC, and so does every redirect. The initial load is the only
  request that carries them.
- **The cookie jar is process-global and shared.** `CookieManager.getInstance()` is a singleton;
  `setAcceptCookie(true)` (:216) and `setAcceptThirdPartyCookies(wv, !isPrivate)` (:217) are
  per-WebView only for the third-party flag. There is no per-tab profile, no `clearAllCookies` on
  tab close (`closeTab`, :318-337) and no `WebStorage`/`WebViewDatabase`/cache clearing. A login in
  a normal tab is visible in a private tab and persists after it closes. Deterministic from the API,
  and it contradicts the "private" label.
- Private tabs share the same on-disk WebView HTTP cache for subresources (no `clearCache`).
- `privateAuthenticated` is only used to blank previews (:350, :359); nothing else is gated, and it
  is never reset, so one successful unlock unlocks the overlay for the process lifetime.
- **Private-tab downloads are recorded to the persistent download list.** `attachDownloadListener`
  (:380-424) is attached to every WebView including private ones (:214) and unconditionally calls
  `PrismSettings.recordDownloadedFile(fileName, url, …)` (:415). The class comment at :30-31 says
  private URLs must never reach a persistent DB; the download path ignores `isPrivate`.
- `applyVpnForTab` (:672-697) is the only thing private mode does that is real, and it is
  destructive — see §10.

---

## 3. Content blocking / blocklist

**What it does.** `HostBlocklist` (`core/.../browser/HostBlocklist.kt`) merges a 10-entry compiled-in
seed list, a downloaded StevenBlack hosts file, and user custom domains; exposes exact-host and
suffix matching plus whitelisting. `PrismBlocklist` is the process singleton. `BlocklistActivity`
lists/imports/adds/removes domains. Two enforcement points: `PrismWebViewClient` and the VPN's DNS
interception.

**User path.** Settings > (section containing `SettingItem.Header("Blocklist")`,
`SettingsActivity.kt:1883`) → "Blocklist" → `BlocklistActivity` (:1886-1890). Add domain, Import
file (`text/*`), search. Whitelisting is implicit: pressing × on a built-in domain.

**Status: PARTIAL — the browser half only runs in private tabs.**

- **Blocking is private-tab-only in the browser.** `PrismWebViewClient.kt:139`:
  `if (isPrivateTab && blocklist.shouldBlockHost(host))`. A normal tab never consults the list. The
  class comment does not say so; the Settings row says "Total blocked: N" with no scope.
- Blocked subresources are answered with a **200 OK + empty body** (3-arg `WebResourceResponse`,
  `:140`), not a 404/blocked status, so pages cannot detect the block and the "blocked" counter the
  UI implies does not exist.
- **The default-deny path is real for the VPN** (`PrivateDnsVpnService.kt:369-374` returns NXDOMAIN
  when `HostBlocklist.shouldBlock` matches) but the VPN is usually not running (§10), and only
  IPv4/port-53 is covered (§14).
- **`SettingItem` "Blocklist" category is reachable from Settings only**, not from the browser.

**Bugs.**

| # | Location | Failure | Why |
|---|---|---|---|
| 3.1 | `HostBlocklist.kt:116-136` + `BlocklistActivity.kt:84,127` | Adding/removing a domain on the UI thread re-parses the entire downloaded hosts file (StevenBlack: ~100k lines) synchronously | `addCustomDomain`/`removeCustomDomain`/`mergeCustomDomains` all end in `reloadFromDisk()`, which re-reads and re-parses; `BlocklistActivity` calls them from click handlers. Import is the only path that uses `Dispatchers.IO` (`:32`); typing a domain is not. ANR risk on a low-end device. |
| 3.2 | `HostBlocklist.kt:70-83` | Removing a built-in domain **whitelists** it forever with no UI to undo | `removeCustomDomain` falls through to `KEY_WHITELIST_DOMAINS`; `snapshotAllDomains` then omits it (`applyMerged` skips whitelisted entries), so the row disappears and there is no list of whitelisted hosts anywhere. Deterministic. |
| 3.3 | `HostBlocklist.kt:28-35, 138-162` | Every construction fires a 100k-line network download and a full re-parse | The `init` block starts `tryDownloadStevenBlack` unconditionally; `PrismBlocklist.get()` is first called from `BrowserPageView`'s constructor (`BrowserPageView.kt:38`), i.e. on first browser open. `mergeCustomDomains` from the importer parses on IO. Contained to a daemon thread, but a large unrequested download on first browser use. |
| 3.4 | `PrismWebViewClient.kt:139` | Normal-tab ads are never blocked; users will read this as "the blocklist is broken" | Private-only predicate (see above). |

**App whitelist (`vpn/WhitelistActivity.kt`) — SCAFFOLD.** Status: **BROKEN claim.** The screen lists
installed apps with checkboxes and stores `PrismSettings.setAppWhitelist` (:44). No
`addDisallowedApplication`/`addAllowedApplication` exists anywhere in the app (repo-wide grep: zero
hits), so the VPN does not exclude anything. The only consumer is `AppUsageMonitor.kt:42-54`, which
pauses the *entire* engine when a whitelisted app comes to the foreground — it stops the hosting and
proxy listeners (`pauseTunnel`) instead of excluding one app, and it only reacts to the *foreground*
app, so a background whitelisted app stays fully covered. The row's subtitle
"(N bypassing VPN)" (`WhitelistActivity.kt:60`) is therefore false. Also: the search box is inert —
the hint is set to "Search apps…" (:40) but no `TextWatcher` is registered, unlike
`BlocklistActivity.kt:95-101`.

---

## 4. Downloads (files)

**What it does.** A `DownloadListener` per WebView guesses the filename and enqueues an Android
`DownloadManager` request into the public Downloads folder with the system notification suppressed;
Prism posts its own polling progress notification and records the entry for its own Downloads list.

**User path.** Browser page → any download link. Then browser menu (⋮) → **Downloads** →
`PrismDownloadsSheet`. Long-press a row → × → confirm to delete.

**Status: PARTIAL — enqueue works, "open" never works, deletes work.**

- **Opening a downloaded file always fails.** `openDownloadedFile` builds
  `Uri.parse(file.localPath)` (`BrowserPageView.kt:509-511`). `localPath` was stored as
  `Uri.fromFile(local).toString()` (:416) — a `file://` URI. Passing a `file://` URI to
  `startActivity` throws `FileUriExposedException` for `targetSdk >= 24`; the surrounding
  `catch (e: Exception)` (:514) swallows it and shows "No app can open <name>". So the Downloads list
  can open *sites* but never *files* (`PrismDownloadsSheet.kt:70` → `onOpenFile`). Deterministic.
- **Recorded path can be wrong even before that.** The path is guessed as
  `Downloads/<guessFileName>` (:407-412), but `DownloadManager` de-duplicates by renaming
  (`report(1).pdf`), so the recorded local path may point at a different (or a previous) file. The
  `downloadId` is recorded too (:415-417), but only the *remove* path uses it
  (`PrismDownloadsSheet.kt:184-190`).
- **Private tabs record their downloads persistently** (see §2).
- **`setNotificationVisibility(VISIBILITY_HIDDEN)`** (:392-394) relies on
  `DOWNLOAD_WITHOUT_NOTIFICATION` (declared, manifest :54) — that permission is signature-level
  and is **not grantable to a normal app**; the request will simply be ignored by
  `DownloadManager`, so both Prism's notification and the system's may appear. *Unverified* at
  runtime, but the permission is `signature` in AOSP.
- **Progress notification can spin forever.** `trackFile` polls every second
  (`PrismDownloadNotifications.kt:56-91`). If no row matches the id, `c.moveToFirst()` is false →
  `return@use` only exits the `cursor.use` block, and the `while (true)` loop continues at 1 Hz for
  the process lifetime with a pinned indeterminate notification. Also no upper bound on iterations
  for a download that never leaves `STATUS_PENDING`.
- Deleting is correct: `DownloadManager.remove(downloadId)` first, then the recorded path
  (`PrismDownloadsSheet.kt:181-207`); "Clear file list" only clears history, as documented (:99-104).

---

## 5. Whole-site download / mirroring

Two different implementations are reached from one button.

**What it does.**
- Mesh mirror: fetch `https://<domain>.remote/manifest.json` through the tunnel, download every file
  to `Mirrors/<domain>`, verify SHA-256 per file, then register mirror + hosted site + DNS record
  `127.0.0.1` and gossip it (`PrismMirrorManager.kt:28-68,116-144`).
- Open-web crawl: `PrismSiteDownloader` (`core/.../PrismSiteDownloader.kt:69-135`) crawls same-host
  links (150 pages / 80 MB / 8 MB per file) into `Mirrors/<host>` and calls
  `onSiteSaved` → `PrismMirrorManager.registerDownloadedSite` → the same four-step registration
  (`PrismApp.kt:121-124`).

**User path.** Browser page → the globe/handshake "download site" toolbar button
(`BrowserPageView.kt:70`, only visible when `PrismMeshService.isOnMesh()`, :438-439) → for a `.p2p`
host the mesh mirror path, otherwise the crawler (:547-556). Or ⋮ → **Mirror** (enabled only for
`.p2p` hosts, :583).

**Status: PARTIAL.** Integrity checking is genuinely implemented (`PrismMirrorManager.kt:96-101`) and
the crawler's path handling is bounded. Defects:

- **The `.remote` "fetch from a peer" hint is defeated before DNS sees it.**
  `PrismMirrorManager` requests `https://$domain.remote/...` (:34, :79). OkHttp's interceptor rewrites
  the host to `$domain` and sets `Host:` **before** the socket is created
  (`PrismTunnelEngine.kt:116-130`). DNS lookup then runs inside `chain.proceed` on the rewritten URL
  (`:92-115`), so `P2pDnsManager.resolve` never sees the `.remote` suffix and its `excludeLocal`
  branch (`P2pDnsManager.kt:168-185`) never fires. If this device already mirrors the domain, the
  "remote" re-mirror reads from `127.0.0.1` (itself) instead of from a peer.
- **Arbitrary file write from a peer-controlled manifest path.**
  `val targetFile = java.io.File(targetDir, path)` (`PrismMirrorManager.kt:83`) where `path` is
  `fileObj.getString("path")` from the *remote* JSON (:49). No normalisation, no `..` rejection, no
  canonical-prefix check — unlike every other file-writing path in this stack
  (`PrismWebCache.kt:342`, `PrismSiteDownloader.kt:182`, `PrismWebHost.kt:145`). A peer answering
  `/manifest.json` with `"path":"../../../../Documents/x.html"` writes outside
  `Mirrors/<domain>`; with `MANAGE_EXTERNAL_STORAGE` (manifest :53) that is arbitrary external-storage
  write, and each file is created via `parentFile.mkdirs()` first (:84).
- **No in-flight guard on the mesh path.** `mirrorSite` has no `isDownloading`-style set (the crawler
  does, `PrismSiteDownloader.kt:60,76`). With Auto-Mirror on, every navigation calls it again
  (`BrowserPageView.kt:263-270`) — the `.p2p` test is true for the site you are *browsing*, so every
  page load starts another full crawl writing into the same directory.
- Failure is only a log line + notification (`PrismMirrorManager.kt:63-66`); the in-app UI has no
  error state, and `syncProgress` never returns to empty on success (only sets 100).
- The crawler's `fetch` returns `null` on any exception with **no logging at all**
  (`PrismSiteDownloader.kt:153-154`), so a crawl that saves nothing reports only the generic
  "nothing could be downloaded from <host>" (:123).
- **Privacy default:** crawling/hosting a normal website publishes that domain into the P2P DNS
  ledger pointing at this device and gossips it (`finalizeMirror` → `updateRecord`, :140-143), so
  every mesh peer resolves the real domain to this phone.

---

## 6. P2P site hosting (and virtual host mapping)

**What it does.** `PrismWebHost` is a minimal HTTP/1.1 server (object, not a service) that serves a
hosted site's directory for a domain: path resolution over a raw `File` or `DocumentFile` tree,
correct-ish MIME by extension, `Range`/206 support for video, `manifest.json` generation with
SHA-256 per file, negative caching, an AI template endpoint for `.prism` files, and a cache fallback
served **only to loopback**. It is reached from `PrismProxyServer`'s listener on 8080, keyed by the
`PRISM_CONNECT <domain>` handshake or a `Host:` header. `P2pHostingActivity` manages the list and
publishes a DNS record via `MeshUtils.getLocalMeshIp()`.

**User path.** Settings > Privacy & VPN → **"P2P Web Hosting"** (`SettingsActivity.kt:1401-1407`) →
`P2pHostingActivity`: "Host New Website" → domain dialog (suggests `blog.p2p` or `site.com`) →
`OpenDocumentTree` folder picker → "Website Live!". Toggle per row = publish/unpublish;
long-press/× = stop hosting. Tap a row = animated health check.

**Status: PARTIAL.**

- Serving over the `PRISM_CONNECT` handshake works, including the careful plain-HTTP head fix
  (`PrismProxyServer.kt:197-209`).
- **The plain-HTTP/`Host:`-header hosting path is dead code.** `PrismProxyServer.handleClient:237`
  reads the rest of the head only when the first line does **not** contain `" HTTP/"`. Every real
  HTTP/1.1 request contains it, so `header` is just the request line; the `Host:` test at :272 then
  fails and the socket is closed at :294 with **no response**. Consequence: an ordinary browser or app
  on the device cannot read a hosted site (contrary to the comment at :231-236), and plain-HTTP
  proxying through the VPN proxy is dead (§12).
- **Domain collision protection is local-knowledge only**, so any user can claim `google.com`
  (the hint invites it) and publish it mesh-wide; the only check is "is it already in my ledger"
  (`P2pHostingActivity.kt:208-213`).
- **Deleting a hosted site does not drop the file cache.** `onDelete` (:63-71) removes the list entry
  and the DNS record but, unlike `PrismMirrorManager.removeSite` and `PrismWebCache.unpublish`, never
  calls `PrismWebHost.clearCache(domain)`. For the rest of the process lifetime the removed site keeps
  being served from `siteCache` — *unverified* end-to-end (the listener would also need to be up), but
  the cache is only cleared by `trimCaches`/`clearCache`, neither of which this path calls.
- **`takePersistableUriPermission` with WRITE on a read-only tree throws.** `:35-38` always requests
  `READ|WRITE`; a provider that grants read only makes this throw `SecurityException` outside any
  try/catch → crash on "Host New Website" with such a folder.
- `resolveFile` swallows every SAF failure with an empty `catch` (`PrismWebHost.kt:292`), so a
  broken/revoked tree URI looks identical to "file not found".
- `getFileHash` swallows exceptions and returns `""` (`:561`), so a manifest can advertise an empty
  hash; peers then always fail integrity verification for that file.
- `collectFiles` returns nothing for SAF roots (`:524-542`: the `DocumentFile` branch is `null` with
  the comment "This is simplified"), so `manifest.json` is **empty** for any site hosted from a
  `content://` tree — which is what the folder picker produces. Mirrors of SAF-hosted sites download
  nothing, then `mirrorSite` fails at `finalizeMirror`? No: `getJSONArray("files")` is an empty array,
  the loop body never runs, and the mirror is registered as complete with zero files. So a mirrored
  SAF-hosted site is registered, DNS-advertised, and serves nothing.
- Path traversal: the literal check is `if (path.contains(".."))` before any decoding (`:145`), which
  is bypassable in principle but ineffective in practice because the path is never URL-decoded and is
  used as a literal filename; `File(baseDir, candidate)` (:273) cannot escape without a literal `..`.
  Marked **not exploitable as written**, unlike §5.
- `sendManifest` walks and hashes the whole tree synchronously inside the request handler
  (`:486-522`); a large mirror makes `manifest.json` block the serving coroutine for seconds.
- `sendFile` uses `input.skip(range.first)` without checking the return (`:435`) — harmless for
  `FileInputStream`, silently wrong for a short skip on other streams.

---

## 7. Bookmarks

**What it does.** `PrismSettings.addBookmark/removeBookmark/isBookmarked/getBookmarks`, toggled from
the toolbar star, listed in an `AlertDialog` with a "Remove…" multi-choice step
(`BrowserPageView.kt:443-487`).

**User path.** Browser page → star icon (`binding.bookmarkButton`, `:69`) to bookmark/unbookmark the
active tab → ⋮ → **Bookmarks** → tap to open, "Remove…" to delete several.

**Status: WORKING.** Star state is refreshed on every navigation via `updateToolbarState`
(`:430-439`), open uses `loadUrl` with the tab's private headers (:519-524), and removal is a real
multi-select (`:475-487`). Minor: bookmarks are keyed by exact URL including query, so the star shows
unfilled for the same page reached with a different query; no folders/tags; `showBookmarks` is only
reachable from the bottom sheet, not from any Settings screen. Nothing broken found.

---

## 8. P2P DNS resolution + seeding

**What it does.** `P2pDnsManager` keeps a domain → `DnsRecord(ip, alternates, timestamp, isVerified,
source)` map, persists it as JSON, picks a candidate by TCP latency probe on port 8888 with
`127.0.0.1` short-circuited first, excludes local providers for `.remote` lookups, gossips updates
with mesh opcode `0x05`, ingests peer records by merging alternates, and seeds GLOBAL records from
system/VPN resolutions. `DiscoveryEngine` implements a separate UDP signalling protocol including
`0x04` DNS sync and `0x05` DNS write.

**User path.** Settings > Privacy & VPN → **"P2P DNS Records"** (`SettingsActivity.kt:1394-1400`) →
`P2pDnsActivity`: add domain+IP, long-press to delete, "Resync Mesh". Hosting/mirroring write records
automatically.

**Status: PARTIAL, and the write path is a security hole.**

- **Remote DNS writes are completely unauthenticated.**
  `DiscoveryEngine.handleDnsWrite` (`vpn/DiscoveryEngine.kt:105-129`) takes `{"action":"ADD",
  "domain":…,"ip":…}` from **any** sender and calls `P2pDnsManager.updateRecord(context, domain, ip)`
  (:118), or `deleteRecord` for `"DEL"` (:120). There is no `vpnSecret` comparison here — note that
  `handleRegistration` *does* check the secret (:49-52), so the omission is not a design decision.
  `updateRecord` then persists the record, sets the resolution source to P2P, and re-broadcasts it to
  the whole mesh (`P2pDnsManager.kt:74-88`). Any host that can reach the multiplexer's UDP port can
  therefore repoint **any** domain (e.g. `bank.com`) at itself, for every app on the device via the
  VPN's DNS interception, and propagate it. Whether `DiscoveryEngine` is currently instantiated is
  **unverified** (grep finds no constructor call; `VpnMultiplexer`'s comment says discovery moved to
  `PrismMeshService`), so treat reachability as unverified but the missing check as fact.
- **Mesh DNS is unauthenticated by construction.** `updateRecord` broadcasts `{domain, ip, ts, alts}`
  with no signature (`P2pDnsManager.kt:78-87`); `ingestFromPeer` (:243-278) accepts any peer's record,
  trusts its `isVerified` flag, and overwrites a GLOBAL record whenever the peer's timestamp is newer
  (:268-273). A malicious/fast-clock peer can hijack any name, including ones the user never visited.
- **No TTL is ever enforced.** `resolve` (:164-202) ignores `timestamp` entirely; records persist
  across restarts because `save`/`initBlocking` reload them. The class comment in `PrismWebCache.kt`
  acknowledges "no expiry"; the ledger therefore accumulates stale mappings forever.
- **`dnsIndex` is an unsynchronised `mutableMapOf`** (:32) mutated from the UI thread, mesh threads,
  `DiscoveryEngine`, the VPN io thread and the DNS proxy thread; `save()` iterates it while holding no
  lock (:295) → `ConcurrentModificationException` / lost updates possible. `save`'s `catch {}` (:306)
  hides both the CME and any write failure.
- **The whole browsing-DNS ledger is written to world-readable external storage**
  (`/storage/emulated/0/Prism/p2p_dns_index.json`, :109-115, :289-305). Every domain any app resolves
  is recorded there by `seedFromGlobal` (called from `PrivateDnsVpnService.kt:378` and
  `PrismTunnelEngine.kt:107`), so the file is effectively a cross-app browsing history readable by
  any app with storage access. `initBlocking`'s parse failure is also swallowed (:155).
- `P2pDnsActivity.sendRemoteCommand` sends `{domain, ip}` for ADD with no `"action"` field (:128-131)
  while `handleDnsWrite` requires `"action"` (:113) — the two message shapes do not match.
- **No IP validation anywhere.** `updateRecord` accepts `"not-an-ip"`; the DNS builder then throws,
  and in the VPN path that exception is uncaught (§10, bug 10.3).
- `probeLatencyAsync` hardcodes TCP port 8888 (:215) and treats failure as 5000 ms (:221); a host
  whose 8888 is closed is simply deprioritised, never excluded.
- UI: `refreshList` replaces the whole adapter on every emission (`P2pDnsActivity.kt:88-92`), so the
  list jumps to the top on each DNS change (the activity collects `records`, :48-52).

---

## 9. DNS proxy (Access Point mode)

**What it does.** `DnsProxyService` is a plain `Service` that binds UDP 53 (falling back to 5353) and
answers DNS queries from the P2P ledger, NXDOMAIN otherwise.

**User path.** Settings > Privacy & VPN → **"Enable DNS Proxy"** (`SettingsActivity.kt:1367-1382`) and
**"DNS Proxy Mode"** (:1383-1393). Started from `PrismApp.onCreate` when the preference is on
(`PrismApp.kt:413-415`) and from the toggle (:1374).

**Status: BROKEN — the service cannot run at all.**

1. **It is declared disabled.** `AndroidManifest.xml:753-759`:
   `<service android:name=".browser.DnsProxyService" android:enabled="false" …>`. Android refuses to
   start a disabled component; `startService` returns `null` rather than throwing, so
   `SettingsActivity.kt:1374`'s `Toast "DNS Proxy started on port 53"` is shown for a service that
   was never created. Nothing anywhere calls `setComponentEnabledSetting` for it (the only such call
   in the repo is `ExeLaunchActivity.kt:58`, for a different component). *Unverified*: the exact
   return value / log line, but not that the component is disabled.
2. **Privileged port.** `tryBindDnsPort` (`DnsProxyService.kt:130-137`) tries `DatagramSocket(53)`;
   an unprivileged Android app cannot bind < 1024, so it falls back to `5353` — and no DHCP/DNS
   client can be pointed at a non-53 port. The feature is unusable without root, as the log message
   itself admits (:134). The Settings subtitle still promises "Listen on 0.0.0.0:53" (:1369).
3. **The mode picker is dead.** `PrismSettings.getDnsProxyMode()` is read only by
   `SettingsActivity.kt:1387` to render the picker; `DnsProxyService` never reads it and always
   returns NXDOMAIN on a miss (`:98-102`). "Fallback to Global DNS" does nothing. Repo-wide grep:
   one hit, in Settings.
4. **Unbounded thread creation.** One `thread(isDaemon = true)` per received datagram (`:56-58`), no
   pool, no rate limit — a trivial local DoS, and each thread copies the whole 512-byte buffer.
5. `isRunning` is set before binding (`:35-37`), so a failed bind still blocks a retry.
6. No `foregroundServiceType`/`startForeground`; if it ever ran, `START_STICKY` (:32) would be
   restarted into background restrictions. **Unverified** (moot while disabled).
7. It answers each question of a multi-question query with a separate packet (:83-113), each carrying
   the original transaction id — acceptable in practice, but not RFC-correct.

---

## 10. Private DNS VPN / tunnel

**What it does.** `PrivateDnsVpnService` is a real `VpnService` (manifest :712-722, correctly declared
with `BIND_VPN_SERVICE` + the `android.net.VpnService` intent filter). It establishes a tun with
address `10.7.0.2/30`, MTU 1500, the configured resolvers as DNS servers, `/32` routes for those
resolvers, `10.8.0.0/24`, and (on a 5-minute refresh) `/32` routes for blocklisted host IPs; it then
reads raw IPv4 packets and answers UDP/53 itself from the cache, the P2P ledger, the blocklist, or a
forwarded upstream query. `PrismTunnelEngine` is the "backbone" it starts.

**User path.** Settings > Privacy & VPN → "Enable VPN Tunneling" (:1030), then the connect row
(`startPrismVpnTunnel`, :369-384); or "Persistent VPN Server" (:1072-1086); or automatically on the
first **private** tab (`BrowserPageView.kt:677-687`, `getVpnAutoStart()` defaults true).

**Status: PARTIAL — DNS filtering works when it runs; the "tunnel" does not tunnel, and the primary
connect action is a no-op.**

- **It does not capture general traffic.** `buildMinimalTunnel` (`PrivateDnsVpnService.kt:195-205`)
  and `establishLocked` (:236-251) add only `dnsA/32`, `dnsB/32` and `10.8.0.0/24` — never
  `0.0.0.0/0`. So TCP/UDP to the internet bypasses the VPN completely. `PrismTunnelEngine.routeOutboundPacket`
  (:318-335) returns `null` for everything except `10.8.0.x`, and `null` means the packet is dropped,
  so even the captured traffic is not forwarded anywhere. The notification "Mesh network and privacy
  tunnel active" (:424-428) and the code comment "Establish Full Privacy Tunnel"
  (`BrowserPageView.kt:686`) overstate what exists: this is a DNS interceptor plus a WireGuard
  passthrough for one /24.
- **Settings' connect action does nothing.** `startPrismVpnTunnel` calls
  `PrivateDnsVpnService.start(this)` (:375) → `establishTunnel=false` (:571-573). `onStartCommand`
  then hits `if (!tunnelRequested && !alwaysOn) { shutdown; stopForeground; stopSelf }`
  (:62-70) because `getVpnServerAlwaysOn()` defaults false. Net effect: a notification flashes and
  the service stops; no tunnel, no error, and the "Connect" row's own status stays disconnected. The
  same is true of `LauncherActivity.kt:59` and `:326`. Only a private tab
  (`start(context, true)`) or "Persistent VPN Server" actually establishes anything.
- **Switching back to a normal tab tears down the VPN.**
  `applyVpnForTab` (`BrowserPageView.kt:672-697`): for a non-private tab, unless `alwaysOn`,
  it calls `PrivateDnsVpnService.stop(context)` (:694) — which is the same service Settings started
  for the user's VPN. Leaving a private tab silently disables a VPN the user enabled elsewhere.
  `stop()` also uses `startService` with `ACTION_STOP` (:576) from what may be a background context
  → `IllegalStateException` on API 26+ is possible (uncaught).
- **The uncaught-exception path can crash the process.** The io thread calls
  `handlePacket(packet) ?: continue` with no try/catch (:262); `handleDnsPacket` →
  `buildAStaticResponse` does `ip.split(".").map { it.toInt() }` (:484) on whatever the ledger holds.
  A non-numeric IP (trivially inserted via `P2pDnsActivity` or the unauthenticated `DiscoveryEngine`
  write) throws `NumberFormatException` on that thread → uncaught → process death. Deterministic from
  the source; reachable by any app's DNS query for that domain.
- **Query type is parsed at a fixed offset.** `val qtype = if (dnsPayload.size >= 16) u16(dnsPayload, 14) else 1`
  (:341) assumes a one-label name; for `www.example.com` offset 14 is inside the first label, so the
  value is garbage and never 28. AAAA handling (`:356-357, :364`) is therefore effectively
  unreachable: AAAA queries get A answers.
- **Static answers are malformed for EDNS0 queries.** `buildAStaticResponse` copies the 12-byte
  header from the query — including `ARCOUNT` — then emits only the question and one answer
  (:474-486). A standard Android stub query carries an OPT record (ARCOUNT=1), so the response
  claims an additional record that is not there. Same in `buildNoDataResponse` (:578-585). Resolvers
  that validate counts will discard these; *unverified* whether Android's does, but this is the most
  likely reason `.p2p`/cache names fail to resolve in other apps.
- **DNS from all apps is written to the ledger** (`seedFromGlobal`, :378) — see §8's external-storage
  leak.
- `forwardDnsUdp` sends to `getPrimaryDns()` only, with one shared socket and a 5 s timeout (:401-414);
  the secondary is unused. Failures return `null` → the packet is dropped, not rejected (`:381`).
- **`1.1.1.1` is captured and dropped.** `addRoute(dnsA,32)` routes the default resolver into the tun;
  `handlePacket` only handles port 53, so all other traffic to 1.1.1.1 (including DoH at
  `https://1.1.1.1`) is swallowed. `BlockedIpv4RouteTable.isReservedDnsResolverIp` excludes
  `1.1.1.1/1.0.0.1` from the *block* set (:65-67) but the tunnel route still eats them.
- Protection of reflection-based sockets is best-effort: `protectSocket(ServerSocket)` (:529-551)
  reflects on `fd`/`impl`, both non-SDK; failures are logged and the socket stays **inside** the VPN.
  With no default route this is currently harmless; with a real tunnel it would break the listeners.
- `updateStatusNotification` is posted but the service returns `START_STICKY` (:73) with no
  `onStartCommand` intent on restart → `intent == null` → `tunnelRequested=false` → if `alwaysOn` is
  false the sticky restart immediately stops itself.

---

## 11. TLS / mesh certificates

**What it does.** `PrismCertificateManager` generates a device-unique EC P-256 root CA, persists key
and cert as DER in `filesDir`, and mints 30-day leaf certs with a SAN for a requested domain;
`PrismSslManager` builds a per-domain `SSLContext` (leaf + issuer chain) whose trust manager accepts
everything, used by `PrismProxyServer` to upgrade a mesh connection to TLS. `exportRootCA` writes the
CA as PEM into Downloads so the user can install it.

**User path.** Settings > Privacy & VPN → **"Establish Mesh Trust"** (`SettingsActivity.kt:1504-1516`)
→ CA file in Downloads → Android Settings → Security → Install from storage → CA certificate. The
per-domain TLS itself is automatic (mesh peer ↔ hosting listener).

**Status: PARTIAL, with a verification bypass.**

- **No peer authentication anywhere.** `PrismTrustManager` implements both `checkClientTrusted` and
  `checkServerTrusted` as empty methods (`PrismSslManager.kt:64-68`), and the mesh HTTP client uses a
  trust-all `X509TrustManager` **and** `hostnameVerifier { _, _ -> true }`
  (`PrismTunnelEngine.kt:46-53, 131-132`). Combined with unauthenticated DNS (§8), any peer that can
  answer for a name gets a TLS session that will never be rejected. Encryption without authentication
  is not a defence against the peer you are talking to.
- **SNI is deliberately suppressed** (`PrismTunnelEngine.kt:79-83`) — `serverNames = emptyList()`.
  That removes the only remaining hint tying a session to a hostname and makes the per-domain leaf
  you generate mostly decorative on the client side.
- **Export fails on a fresh install.** `exportRootCA` returns `null` unless `rootCert != null`
  (`PrismCertificateManager.kt:140`), and the CA is generated only by `init(context)`, which is called
  **only** from `PrismSslManager.getSslContextForDomain` (:31) — i.e. the first time this device
  serves a mesh TLS connection. Nothing calls `PrismCertificateManager.init` at startup (repo-wide
  grep: one caller). So "Establish Mesh Trust" typically shows "Failed to export Root CA"
  (`SettingsActivity.kt:1512`) until a peer happens to connect. Deterministic.
- **The CA private key is unencrypted in `filesDir` and is included in Android Auto Backup.**
  Manifest sets `android:allowBackup="true"` (manifest :79) with no `dataExtractionRules` /
  `fullBackupContent` excluding `prism_ca_key.der`, so the key can leave the device via cloud backup
  and be restored elsewhere. Anyone who can read that file can mint a cert for any domain the mesh
  trusts — including, for users who installed the CA system-wide, real HTTPS sites.
- **Leaf certs lack `keyUsage`/`extendedKeyUsage=serverAuth`** (`:104-134` only adds SAN and
  `basicConstraints`). Chrome/user-installed-root validation paths generally require EKU serverAuth;
  so the "green lock" the Settings row promises is likely not achieved. *Unverified.*
- `getSslContextForDomain` silently falls back to `SSLContext.getDefault()` when leaf generation
  fails (:32) — the connection then fails with a CA error that looks like a peer problem.
- Serial numbers are `System.currentTimeMillis()` (:81, :115): two leaves minted in the same
  millisecond share a serial.
- The CA is 10 years (`:83`) with no revocation and no way to rotate from the UI.

---

## 12. Proxy server / client

**What it does.** `PrismProxyServer` is a `ServerSocketChannel` listener used two ways: a hosting
listener on 8080 (`isProxyMode=false`, no auth, serves `PrismWebHost`/reserved internal hosts) and a
VPN proxy on an arbitrary port (`isProxyMode=true`, optional Basic auth, `CONNECT` tunnelling with a
32 KB relay). `PrismProxyClient` installs a WebView-only proxy override pointing at the Prism server
(or at this device's own listener).

**User path.** Implicit: enabling VPN Tunneling + role Server/Client (`SettingsActivity.kt:1088-1095`,
"Prism VPN Role") starts them through `PrismTunnelEngine.start()`; `PrismApp.onCreate` starts the
engine at every cold start (`PrismApp.kt:367-368`).

**Status: BROKEN for plain HTTP; PARTIAL for CONNECT.**

- **Every non-CONNECT HTTP request is dropped without a response.** As in §6:
  `PrismProxyServer.kt:237` sets `header = firstLine` whenever the request line contains `" HTTP/"`,
  which is always, so the `Host:` branch at :272 never runs for a real request and the socket is
  closed at :294. Absolute-form `GET http://host/` (what a proxied browser sends) and origin-form
  `GET /` (what a browser sends to the hosting listener) both die. Only `CONNECT` (:254-269) works.
  This is the most user-visible breakage in the stack: with the Prism proxy installed, `http://`
  pages cannot load at all.
- **Open proxy with no authentication by default.** `setCredentials` disables auth whenever both
  strings are empty (`:34-42`), and `PrismTunnelEngine` passes `getPrismVpnUsername()/Password()`
  straight through (:359-360, :376). When unset, the listener is bound to `0.0.0.0` (:79) and
  `connectToTarget` will `CONNECT` to **any** host:port, including `127.0.0.1:*` (loopback SSRF into
  every local service; the `127.0.0.1:1` special case at :257 confirms loopback targets are accepted).
  Any LAN or mesh peer can use the device as a proxy.
- **No read timeout on accepted sockets.** `accept()` → `handleClient` → `readLine` blocks forever;
  one coroutine per connection with no cap → trivial Slowloris/resource exhaustion by a peer.
- **The local-mesh `CONNECT` path writes HTTP into a TLS tunnel and then hangs.**
  `connectToTarget:336-347` decides "domestic mesh request" and calls `PrismWebHost.serve(...)` with
  no pre-read header; the client has already received `200 Connection Established` only later
  (:360-362 — note: for this branch the 200 is never sent at all) and is waiting to start TLS.
  `PrismWebHost.serveContent` then calls `readLine(input)` (`PrismWebHost.kt:126`) on a socket with
  nothing to read, with no timeout → the connection hangs indefinitely. Any `https://…p2p` or
  `https://` request resolved to loopback takes this path.
- **`addDirect()` in both client methods** (`PrismProxyClient.kt:12,33`) makes the proxy advisory:
  if the Prism listener is unreachable, WebView falls back to connecting directly, silently
  bypassing the mesh. The failure callback is an empty lambda (`:13,36`), so a proxy that never
  installed is indistinguishable from one that works — a silent catch in callback form.
- The proxy affects **WebView only**. `ProxyController` is `androidx.webkit`, so "route traffic
  through Prism" is true for Prism's own browser and nothing else.
- `start()`'s port-jump loop (`:75-77`) picks a random port for the proxy while
  `PrismSettings.getPrismVpnPort()` remains the advertised one (set earlier in
  `PrismTunnelEngine.startP2pServerMode:351-356`), so remote clients can be told the wrong port.
- `relay` swallows all exceptions with a comment (`:401-403`); `stop()` does not interrupt in-flight
  `handleClient` coroutines (they are children of the cancelled scope, so this is *probably* fine —
  unverified).

---

## 13. Blocked-IP route table (part of "does the VPN block")

**What it does.** Resolves up to 100 blocklisted hostnames on the active network, keeps up to 100
unique IPv4s, and hands them to `VpnService.Builder.addRoute(ip, 32)`; `handlePacket` drops any
packet whose destination is in the set, which `destinationPackedIpv4` extracts from the IPv4 header.

**Status: PARTIAL.** The mechanics are sound (`destinationPackedIpv4` bounds-checks version and IHL,
`BlockedIpv4RouteTable.kt:77-83`). Limits: only IPv4; only the first 100 hosts alphabetically
(`:27-34` sorts then takes); only hostnames that resolve at refresh time (every 5 minutes,
`PrivateDnsVpnService.kt:513`); blocklist host resolution **leaks every blocked domain to the system
resolver** at each refresh (`:42`); shared-CDN false positives (ACKed in the comment); and because the
tunnel has no default route, anything that is *not* in this set is entirely unfiltered. The set is a
`companion` static (`PrivateDnsVpnService.kt:508`), so it survives service restarts and can be stale
until the first refresh completes.

---

## 14. Native VPN server multiplexer (IKEv2 / L2TP / WireGuard)

**What it does.** `VpnMultiplexer` binds UDP 500/4500/1701/51820, sniffs the first bytes to classify
IKEv1/IKEv2/L2TP, and dispatches to `IkeEngine`/`L2tpEngine`/`WireguardServerEngine`.

**Status: PARTIAL/BROKEN for IKE+L2TP; WireGuard only.** `:41-57` launches a bind per port; 500, 4500
and 1701 are privileged (`< 1024`) and an unprivileged Android app cannot bind them, so the catch at
:97-103 logs "Could not bind to system port" and those engines never receive a packet. Only 51820
(also configurable via Settings > Native VPN Server) can work. `identifyProtocol` is bounds-safe
(`:109` guards `data.size < 18` before reading `data[17]`).

---

## 15. WebView response handling

**What it does.** `shouldInterceptRequest` serves, in order: this device's cache of `<host>.cache.p2p`,
the cached copy of the real host when the device is offline, mesh content for any P2P-resolved host
(with per-frame timeouts, an unhealthy-host marker, and a 503 error page for the main frame), the
blocklist (private tabs only), then the network. `onReceivedError` redirects to the cached
`.cache.p2p` copy; `onPageFinished` records history and triggers the web-cache capture and font CSS
injection.

**User path.** Implicit for every page load in the browser page.

**Status: PARTIAL.** The 6-argument constructor claim is **valid**, the cache fallbacks work, and the
503 page is well-formed. Defects:

- **The 6-argument `WebResourceResponse` claim — verdict: the API is fine, the arguments are not.**
  `WebResourceResponse(mimeType, encoding, statusCode, reasonPhrase, responseHeaders, data)` is public
  since API 21 and `minSdk` is 26, so no version guard is needed (call sites:
  `PrismWebViewClient.kt:92-96, 132-135, 200-209, 250-252, 257-259`). What is wrong is what goes in:
  (a) :120-123 copies **every** peer header into `responseHeaders`, including `Content-Length`,
  `Content-Encoding` and `Transfer-Encoding`. If a response body is transparently decompressed by
  OkHttp while a peer-supplied `Content-Length`/`Content-Encoding` survives, WebView truncates or
  mis-decodes the body. (b) `statusCode`/`reasonPhrase` are peer-controlled (`:103, :125-127`) and a
  3xx returned from an intercepted response is not followed, so a peer redirect renders blank.
  (c) `mimeType` can be the empty string if a peer sends `Content-Type: ; charset=x`
  (`:109-110`) — no validation. (d) The `Response` itself is never closed; the byte stream's lifetime
  is handed to WebView, which leaks a connection when it ignores a subresource.
- **Ad-blocking is private-only** (`:139`) and returns `200` with an empty body (`:140`) — see §3.
- **The offline-copy fallback drops the query string.** `onReceivedError` loads
  `"http://$host${MESH_SUFFIX}${url.path}"` (`:236`) — query and fragment are lost, so a cached page
  that differed only by query is opened at the wrong cache key (or misses). It also forces `http://`
  even for a site that was `https://`.
- **`hostHealthMap` is an unsynchronised `mutableMapOf`** written from `shouldInterceptRequest`,
  which WebView calls concurrently on background threads (`:27, :81`) → lost updates or a
  `ConcurrentModificationException` under a page with many subresources. No eviction either.
- **Every subresource re-asks the DNS ledger.** `P2pDnsManager.resolve(host, onlyP2p = true)`
  (`:61`) walks a map that is rebuilt from a `StateFlow` on each change, per request, per frame.
- **Serving from cache asserts `200 OK` regardless of the stored resource** (`:200-209`) and sets
  `Cache-Control: no-store` — correct intent, but the cache is keyed by path only, so two pages at
  different hosts can collide if the host directory is wrong (not reachable as written).
- History is recorded for non-private pages only (`BrowserPageView.kt:284-291`) — correct — but the
  *search* record happens in `navigate` (`:632-640`) and checks `activeCategoryIsPrivate` (the tab
  **category filter**) rather than the active tab's `isPrivate`. With the Public category selected
  and a private tab active (possible: `selectTab` does not sync `activeCategoryIsPrivate`), a search
  typed into a private tab is written to persistent history.

---

## Special-attention summary

### Silent `catch {}` / swallowed failures

| Location | What it hides |
|---|---|
| `P2pDnsManager.kt:306` | **Every** DNS-ledger write failure (empty catch). The ledger silently stops persisting; `resolve` keeps answering from memory. |
| `P2pDnsManager.kt:155` | Any parse failure of the ledger on startup → an empty DNS index with no diagnostic. |
| `PrismWebHost.kt:292` | Every SAF resolution error → indistinguishable from "file not found". |
| `PrismWebHost.kt:313, 338, 561` | Icon generation, response writes, and per-file hashing: `getFileHash` returns `""`, which poisons manifests. |
| `PrismWebCache.kt:588-592, 698-699` | Media-scanner and Lyke-ledger failures are warnings only. |
| `PrismSiteDownloader.kt:153-154` | Every crawl fetch failure, with no log at all. |
| `DiscoveryEngine.kt:100-102, 126-128` | DNS sync/write failures are logged at `Log.e` only, never surfaced. |
| `BlockedIpv4RouteTable.kt:57-59` | Blocked-host resolution failures (debug log). |
| `PrivateDnsVpnService.kt:247, 259, 263, 411-413` | Route-add failures, io-read/write failures (thread exits), and every DNS forward failure → packets silently dropped. |
| `PrivateDnsVpnService.kt:537, 548-550, 559-561` | Socket `protect()` reflection failures — the socket silently stays inside the VPN. |
| `PrismProxyServer.kt:401-403` | All relay errors. |
| `PrismProxyClient.kt:13, 36` | Proxy-override failure callback is an empty lambda. |
| `PrismSslManager.kt:50-53` (`→ getDefault()`), `PrismCertificateManager.kt:96-98, 130-133` | CA/leaf generation failures degrade to the platform default context or a null leaf. |
| `PrismDownloadsSheet.kt:185-188` | `DownloadManager.remove` failure (falls through to a path delete). |

### The 6-argument `WebResourceResponse` claim

Verified valid: the constructor exists since API 21 and `minSdk 26` makes all five call sites legal.
The real defects are argument hygiene (unsanitised peer headers, peer status/reason, possibly empty
MIME, unclosed `Response`), documented in §15.

### DNS packet parsing bounds

- `DnsPacketParser.parseQuery` is otherwise well-bounded: 12-byte minimum, `remaining() < 4` before
  type/class, `remaining() < length` before each label, pointer offset range-checked
  (`DnsPacketParser.kt:23-49, 58-88`).
- **Unbounded recursion on compression pointers** — `parseName` follows a pointer by recursing with no
  visited-offset set and no depth cap (`:68-77`). A pointer that points at itself (or a two-pointer
  cycle) recurses until `StackOverflowError`. That is an `Error`, not an `Exception`, so
  `DnsProxyService.handleDnsQuery`'s `catch (e: Exception)` (:114) does not catch it, and the listener
  is on `0.0.0.0:53` — one 20-byte packet from any LAN host crashes the process. (Reachability depends
  on the service running, which §9 says it cannot; the parser is also the shared entry point for
  anything else that feeds it.)
- `DnsPacketBuilder` is bounds-safe but **does not echo RD** and hardcodes opcode/RA
  (`DnsPacketBuilder.kt:22`: `0x8480` sets RA but RD=0, contradicting the comment above it, and the
  parsed `recursionDesired` is discarded). `encodeName` does not reject an empty label or total length
  > 255, and `buildResponseA` throws on a non-IPv4 address (:44-45) — in `DnsProxyService` that is
  caught, in the VPN it is not (§10).
- `PrivateDnsVpnService`'s private copies of the same logic duplicate the parser
  (`parseDnsQueryName`:591-603 is a second, non-pointer-aware implementation) and use the fixed
  qtype offset bug (:341).

### Whether the VPN actually blocks what it claims

It blocks **DNS names** for apps whose DNS goes to the configured resolver, and **IPv4 traffic to
≤100 blocklist-host IPs**, while the service happens to be running. It does **not** carry general
traffic (no default route), does not cover IPv6, does not cover apps using their own resolver/DoH, and
is not enforced at all when the service is not up — which includes the default state and the
Settings "Connect" action. The whitelist does not exclude apps. The blocklist IP set is refreshed
only every 5 minutes and only for 100 hosts.

### Services declared / started

| Component | Declared | Started | Verdict |
|---|---|---|---|
| `.browser.DnsProxyService` | manifest :753-759, **`android:enabled="false"`**, no FGS type | `PrismApp.kt:414`, `SettingsActivity.kt:1374` | Cannot start; toasts lie. Port 53 would fail anyway. |
| `.browser.PrivateDnsVpnService` | manifest :712-722 — `BIND_VPN_SERVICE`, `VpnService` intent filter, `specialUse` FGS | many call sites | Declared correctly; started correctly **only** with `establishTunnel=true` or `alwaysOn`. Bare `start(context)` is a no-op (:62-70). |
| `.BlocklistActivity` | manifest :143-148, not exported | Settings :1886 | OK |
| `.vpn.WhitelistActivity` | manifest :151-156, not exported | Settings :1214 | Declared/opened; the setting it writes is not applied by the VPN. |
| `.browser.P2pDnsActivity` | manifest :158-163 | Settings :1398, :1897 | OK |
| `.browser.P2pHostingActivity` | manifest :165-170 | Settings :1405, :1904, `FileExplorerPageView.kt:494` | OK |
| `PrismWebHost` / `PrismProxyServer` | not manifest components | `PrismTunnelEngine.startHostingServer` :337-341 | In-process listeners on 8080; no auth. |

---

## Ranked bug list (worst first)

1. **Unauthenticated remote DNS write/delete** — `vpn/DiscoveryEngine.kt:105-129` (no `vpnSecret`
   check, unlike :49) → `P2pDnsManager.updateRecord` (:118) → persisted + re-broadcast
   (`P2pDnsManager.kt:74-88`). Any reachable host repoints any domain for every app on the device and
   propagates it across the mesh.
2. **The VPN does not tunnel, and the main connect button is a no-op** —
   `PrivateDnsVpnService.kt:195-205, 236-251` (no default route) + `PrismTunnelEngine.kt:318-335`
   (everything not `10.8.0.x` dropped) + `SettingsActivity.kt:375` → `PrivateDnsVpnService.kt:62-70`.
   The "privacy tunnel" claim is false in both code and UI.
3. **TLS peer verification fully disabled** — `PrismTunnelEngine.kt:46-53, 79-83, 131-132`;
   `PrismSslManager.kt:64-68`. Trust-all + accept-any-hostname + no SNI, over an unauthenticated DNS
   layer, means a mesh peer can be anyone.
4. **Peer-controlled path traversal → arbitrary file write** — `PrismMirrorManager.kt:83` writes
   `File(mirrorsDir/domain, manifest.path)` with `manifest.path` from the remote JSON (:49), no
   `..`/canonical check. With `MANAGE_EXTERNAL_STORAGE` this overwrites arbitrary external storage.
5. **Unbounded compression-pointer recursion → remote StackOverflowError** —
   `DnsPacketParser.kt:68-77`; an `Error`, so the `catch (Exception)` at
   `DnsProxyService.kt:114` cannot stop it.
6. **The proxy drops every plain-HTTP request** — `PrismProxyServer.kt:237` + `:272` + `:294`
   (headers only read when the request line lacks `" HTTP/"`). `http://` browsing through the Prism
   proxy cannot work, and the "hosting listener answers plain HTTP" comment is false.
7. **`DnsProxyService` is dead on arrival** — manifest :753-759 `enabled="false"`, plus privileged
   port 53 (`DnsProxyService.kt:130-137`) and a mode setting nobody reads
   (`SettingsActivity.kt:1387`; zero consumers of `getDnsProxyMode`).
8. **Uncaught `NumberFormatException` kills the process from a DNS query** —
   `PrivateDnsVpnService.kt:484` reached from the unguarded io-thread call at :262.
9. **Private mode leaks** — shared process cookie jar (`BrowserPageView.kt:216`), DNT lost on
   navigation (`PrismWebViewClient.kt:34`), private downloads persisted (`:415`).
10. **Downloads can never be opened** — `file://` into `startActivity` (`BrowserPageView.kt:509-514`),
    `FileUriExposedException` caught and reported as "no app".
11. **`Connect`/`stop` races and teardown** — leaving a private tab stops the user's VPN
    (`BrowserPageView.kt:690-695`); `stop()` uses `startService` from background
    (`PrivateDnsVpnService.kt:576`).
12. **`Establish Mesh Trust` fails until a peer connects** — `PrismCertificateManager.kt:140` with
    `init` only called from `PrismSslManager.kt:31`; plus the CA key is unencrypted and included in
    Auto Backup (manifest :79).
13. **Open proxy on `0.0.0.0` with no auth by default** — `PrismProxyServer.kt:34-42, 79,
    254-269` allows `CONNECT 127.0.0.1:*` (loopback SSRF) from any peer.
14. **Thread-safety in the DNS/serving core** — `P2pDnsManager.dnsIndex` unsynchronised
    (`P2pDnsManager.kt:32, 295`), `hostHealthMap` (`PrismWebViewClient.kt:27`).
15. **Unbounded resource growth** — one thread per DNS packet (`DnsProxyService.kt:56`), one coroutine
    per proxy connection with no read timeout (`PrismProxyServer.kt:103, 145`), 1 Hz permanent
    download poll (`PrismDownloadNotifications.kt:56-91`).
16. **Dead settings**: "Enable JavaScript" (`SettingsActivity.kt:1016-1021`; `BrowserPageView.kt:220`
    hardcodes `javaScriptEnabled = true`), "DNS Proxy Mode" (:1383), app whitelist (§3).
17. **Manifest generation is empty for SAF-hosted sites** — `PrismWebHost.kt:524-542`; the mirror then
    registers a zero-file site.
18. `takePersistableUriPermission(READ|WRITE)` uncaught on read-only trees —
    `P2pHostingActivity.kt:35-38`.
19. Stale host cache after unpublishing from the hosting list — `P2pHostingActivity.kt:63-71` vs
    `PrismMirrorManager.kt:207`.
20. Duplicated/parallel DNS-response builders (`DnsPacketBuilder` vs `PrivateDnsVpnService`'s private
    copies) with divergent bugs (RD echo, ARCOUNT, qtype offset).
