# Prism audit — wallet/chain, virtualization, cloud, files, notifications, trusted devices, misc.

Scope: read-only audit of `F:\AndroidStudioProjects\Prism` (no source edited). All findings below are
from reading the code; nothing was built or run. Line numbers are from the files as they exist in the
working tree.

Method note: the `pwsh` tool could not provision the workspace ACL in this session
(`SetNamedSecurityInfoW failed (Win32 5)`), so no Gradle/Kotlin build or test was executed and no
grep-through-shell was possible; every claim is grounded in file:line citations that a reader can
re-check. Comments were not trusted — each status is backed by code that either runs, returns an
error, or returns silently.

Legend: **WORKING** = the path a user can reach does what it says. **PARTIAL** = the mechanism exists
but a real user hits a dead end or an unverified assumption. **BROKEN** = the reachable path cannot
succeed. **SCAFFOLD-ONLY** = data structure / UI / protocol exists but nothing ever completes.

---

## 0. Cross-cutting findings

### 0.1 Android APIs in `:core` — clean
`grep '^import android\.'` over `core/src/main/kotlin` returns **no matches**, and a search for
fully-qualified `android.(content|os|app|…)` references finds only comments, plus the deliberate
fake-framework `core/.../virtualapp/AndroidSurface.kt` (which *parses* android class names rather than
importing them) and `Platform.kt`-style hooks. Platform services are reached through
`PrismPlatform.host` / injected lambdas. This part of the brief checks out.

### 0.2 Comment-vs-code drift is systematic
Several load-bearing claims in doc comments are contradicted by the code in the same file. The ones
that matter are called out per feature (e.g. `NotificationHistory` "never records excluded apps",
`WalletNetwork` endpoints "kept here, not in :core" while the file *is* in `:core`,
`VirtualizedAppVault` "a virtualized app runs inside the guest VM").

### 0.3 Money paths that never move money
Three features present a success affordance and move nothing:
`CoinConvertDialog` (offer posted, nothing can take it), app routing into PrismOS (tap swallowed,
app never starts), and `WalletNetwork.send` for PSC (always returns an error string).

---

## 1. PrismCoin — chain, node, wallet, mining

**What it does.** PrismCoin (PSC) is Prism's own account-model chain (balances + nonces, SHA-256d PoW,
no UTXO, no scripts). Blocks and transactions gossip over the mesh (`OPCODE_BLOCK/TX/HEAD`), only
devices that have the Wallet page on a desktop slot participate, the chain is persisted as JSON at
`filesDir/psc/chain.json`, and the block reward is derived from the difficulty it was mined at so one
PSC is nominally one reference-hour of work (`PrismCoinConsensus.blockReward`).

**Exact user path.** Launcher → place the *Wallet* page on a slot → open it → the PSC card
(`WalletPageView.renderPrismCoin`, WalletPageView.kt:874) → "Start mining" from the mining menu
(:995, :1522) → `MiningService` → `PrismCoinNode.mineOnce`. Balance appears on the same card; Send is
the card's detail → "Send" (`promptSend`, :1268).

**Status: PARTIAL.** The chain, validation, persistence, mining and receipt notification are all real
code with tests (`PrismCoinTest`, `PscMinedBalanceTest`, `MiningTest`). The wallet-level *send* is not.

**Bugs.**

1. **PSC cannot be sent at all.** `WalletPageView.promptSend` (:1268) builds a form, renders a PSC-only
   note field (:1285–1299), and on confirm calls `WalletNetwork.send(...)` (:1334). `send()` looks the
   coin up in `endpoints` (WalletNetwork.kt:442) and there is **no "PSC" key** in that map
   (WalletNetwork.kt:59–85 — BTC, LTC, DOGE, BCH, DASH, ZEC, DGB, VTC, RVN, ETH, USDC, ETC, BNB,
   MATIC, AVAX, ARB, OP). Every PSC send therefore returns
   `"Prism has no network endpoint for PSC."` Why: PSC has no explorer by design, and nobody wrote a
   local-chain branch into `send()` even though `PscTransaction.sign` + `PscChain.submit` +
   `PrismCoinNode.broadcastTransaction` exist and are used by `ModelPayments` and
   `AndroidMeshBridges`. The flagship coin's Send button is dead while its Receive, balance, mining and
   receipts all work.
2. **Mining "runs" with nothing running.** `MiningService.beginMining` sets `State.running = true`
   and `State.status = "Connecting"` before dispatch (MiningService.kt:174–181). `beginPrismCoin`
   then returns early when the device is not PSC-eligible (MiningService.kt:320–324) without clearing
   either. The wallet page and the ongoing notification then show a live miner with status text and a
   hash rate of "starting…" forever. Same shape in `beginMonero` when RandomX did not load
   (:364–371) and in `beginSolo` when the coin is unknown (:241–244).
3. **Difficulty retarget is off by one block.** `PscChain.targetForNext` walks
   `walked < RETARGET_INTERVAL - 1` (PscChain.kt:130) and then measures
   `parent.timestamp - start.timestamp` (:135). That span covers 29 block intervals, not the 30 the
   divisor assumes (`PrismCoinConsensus.nextTarget`, expected = 120 s × 30). Every retarget is scaled
   by 29/30, so block times drift ~3 % slow per retarget, and because the reward is derived from the
   target the issuance peg drifts with it. Consensus-consistent (every node is wrong the same way), so
   it will not fork — it silently mis-prices every block.
4. **A reorg silently swallows pending payments.** `considerBlock` removes included transactions from
   the mempool (PscChain.kt:251) but nothing re-adds them when a branch is undone
   (`Reorganised`/`forkDepth`). A payment confirmed in a block that a heavier branch replaces
   disappears from both the chain and the mempool, so it never confirms and the user is never told.
5. **Genesis does not satisfy its own PoW rule.** `PscBlock.genesis()` names the miner
   `"PrismCoin genesis: one PSC is one hour of work"` (PrismCoin.kt:332) — not a valid PSC address —
   and its fixed nonce-0 hash is not checked against `GENESIS_TARGET`. It only works because
   `PscChain`'s `init` inserts genesis without validating it (PscChain.kt:62–66). Any peer that ever
   gossips genesis as a block gets `Rejected` (height-0 address check at PscChain.kt:201). Harmless
   today, but it means "genesis is the same everywhere" rests on construction order rather than on
   validation.

---

## 2. Other coins (BTC/LTC/DOGE/BCH/DASH/ZEC/ETH/USDC/…)

**What it does.** One BIP-39 phrase, BIP-32 per-coin derivation, addresses rendered locally
(`AddressCodecs`), balances/price/fee stats from public explorers & CoinGecko, and locally signed
transactions broadcast through Blockchair (UTXO) or a public EVM RPC (`WalletNetwork`).

**Exact user path.** Wallet page → coin list (`walletCoins`, :115) → coin card → "Receive"
(:1227) / "Send" (:1268) → `WalletNetwork.send`.

**Status: PARTIAL.** Receive/address/balance are real. Send is implemented but unverified against a
live chain, and the fee/UTXO arithmetic is the kind of code that needs one.

**Bugs.**

1. `WalletNetwork.sendUtxo` (WalletNetwork.kt:485–535) fetches UTXOs only from
   `api.blockchair.com/<chain>/dashboards/address/...` (…:538) while balances prefer mempool.space
   (:191–208). On a chain where the two disagree the wallet can show a spendable balance and then
   refuse with "No spendable outputs at this address."
2. `sendUtxo` broadcasts to `https://api.blockchair.com/<chain>/push/transaction` (:526) and treats any
   non-empty `data.transaction_hash` as success. There is no verification the node accepted it and no
   test covers the path — `WalletSigningTest` covers only the EIP-155 reference transaction, which the
   file's own comment admits (:424–430).
3. `DGB`, `VTC`, `RVN` have only a `coingeckoId` and no explorer/EVM endpoint (:74–76), so
   `isQueryable` is false: they are listed in the wallet, derive addresses, and can never show or move
   a balance. The UI silently skips them (`if (!WalletNetwork.isQueryable(coin)) continue`,
   WalletPageView.kt:1389) so the coin looks like an empty wallet rather than an unsupported one.

---

## 3. Solo mining against the user's own full node

**What it does.** `SoloMiner` speaks `getblocktemplate`/`submitblock` to a node, builds its own
coinbase (BIP-34 height push, optional BIP-141 witness commitment), folds the merkle root, walks the
nonce range for 30 s and submits a whole block.

**Exact user path.** Settings → mining mode "Solo" → Wallet → mining menu → a coin → "Start". If
"self-hosted node" is on, `PrismNodeManager` is meant to run the node.

**Status: BROKEN.** The node binary is not shipped, so nothing can be mined.

**Bugs.**

1. `PrismNodeManager.readiness` returns `Readiness.BinaryMissing` unconditionally in this build:
   `binaryFor()` resolves `nativeLibraryDir/libbitcoind.so` (PrismNodeManager.kt:223–224) and
   `jniLibs/arm64-v8a` contains only `libqemu-system-aarch64.so`, `libproot.so`,
   `libproot-loader.so`. `readiness` checks it at :169 and returns `BinaryMissing` before any start.
   The code and the comment are honest about this (PrismNodeManager.kt:33–42); it is still a BROKEN
   user path because the UI offers the mode.
2. **`MiningService` never starts the node it points at.** `beginSolo` only computes
   `PrismNodeManager.rpcUrl(coin)` and passes it to `SoloMiner` (MiningService.kt:249–255). The only
   caller of `PrismNodeManager.start` is `WalletPageView.confirmStartMining` (:1724). So a miner
   started from any other entry point (sticky restart, mesh bridge `AndroidMeshBridges.installMining`
   → `MiningService.start`, notification "stop then resume") gets
   `lastError = "Could not reach the node at http://127.0.0.1:8332"` every 20 s.
3. `MiningService.stopMining` calls `PrismNodeManager.stop()` unconditionally (MiningService.kt:714) —
   including after a *pool* session, and including a node the wallet page started for a different coin.
4. `SoloMiner.fetchTemplate` defaults the target to `"f".repeat(64)` when the node's template has no
   `target` field (SoloMiner.kt:105). `mining` mode then compares every hash `<= 2^256-1`, so the very
   first hash "wins" and a garbage block is submitted, counted as a found block, and rejected by the
   node. A missing field should abort the template, not produce the most permissive target possible.

---

## 4. Pool mining (BTC/LTC/DOGE/DGB/BCH), Stratum V1

**What it does.** Line-delimited JSON-RPC over a raw socket, `mining.subscribe`/`authorize`, job
dispatch, per-thread nonce striding, hash-to-target test, share submission, endpoint rotation, mesh
work distribution.

**Exact user path.** Wallet → mining menu → BTC/LTC/DOGE/BCH/DGB → "Start".

**Status: PARTIAL.** The protocol and hashing are genuinely implemented (endianness handled carefully
in `StratumWork`: version/prevhash/ntime/nbits reversed, merkle fold correct), but a share counter that
over-reports makes the one number the user watches wrong.

**Bugs.**

1. **`mining.authorize`'s `true` is counted as an accepted share.** `StratumClient.handle` treats any
   boolean `result` as a share result (StratumClient.kt:138–140) and `mining.authorize` replies
   `{"result": true, "id": …}`. `MiningService.attachStratumHandlers` increments `State.shares` and
   calls `PrismSettings.recordMinedShare(cfg.coin, …)` on every `onAccepted(true)`
   (MiningService.kt:496–505). Every connect therefore adds a phantom share and a phantom yield
   record, before a single hash has been accepted. There is no request-id correlation to distinguish
   the authorize reply from a submit reply, so this cannot be fixed without adding one.
2. `StratumClient.listen` sets `soTimeout = 120_000` (:73) and `readLine` throwing
   `SocketTimeoutException` is caught as "Pool connection dropped" (:99–102). A pool that is quiet for
   two minutes (common at phone hash rates, and normal on `solo.ckpool.org`) is torn down and
   reconnected — which then re-awards the phantom share from bug 1.
3. Share submission is fire-and-forget: `submit(...)` returns whether the *write* succeeded
   (:148–157) and the return value is ignored at every call site except a log line
   (MiningService.kt:659–671). A submission the pool rejects is only visible if the pool answers with
   `error`, which is *not* wired to `onAccepted(false)` in the Bitcoin client (only the Monero client
   does that, MoneroStratumClient.kt:163–169) — so a rejected share leaves `State.shares` incremented.
4. `MiningService.rotationFor` ignores the user's configured host/port whenever the coin has a
   built-in pool list (`MineableCoins.all()...pools().orEmpty()`), so a custom pool typed into Settings
   is silently ignored for every built-in coin (MiningService.kt:130–136).

---

## 5. Monero (XMR) mining, RandomX

**What it does.** Monero's own pool protocol (`login`/`submit`, finished hashing blob, 8-byte
little-endian target on the hash tail, rotating seed hash), RandomX via JNI
(`nativeCreate/nativeHash/nativeDestroy`), one VM per mining thread, seed-rotation by generation
counter, JIT opt-in with a crash-recovery flag.

**Exact user path.** Wallet → mining menu → XMR → pool address prompt (`promptMoneroAddress`,
WalletPageView.kt:1558) → Start.

**Status: PARTIAL.** This is the most credible miner in the app: `randomx_jni` really is built
(`app/src/main/cpp/CMakeLists.txt:297–299`, `add_library(randomx_jni SHARED randomx_jni.cpp)`), the
JNI symbols match (`randomx_jni.cpp:136–247`), the nonce offset 39 is right for a v1 blob, the compact
target expansion is the standard `2^64-1 / (2^32-1 / t)`, and the share test reads the last 8 bytes
little-endian. What keeps it at PARTIAL is availability and pool liveness, not arithmetic.

**Bugs.**

1. Same "running but doing nothing" early return as PSC: no RandomX → `State.status` set, `return`
   with `State.running` still true (MiningService.kt:367–371).
2. `MoneroStratumClient.handle` treats **any** top-level `error` object as a rejected share
   (:163–169). A login rejection, an unknown-job error or a keepalive error increments
   `State.rejected` and prints a rejection message, so the miner reports a bad share rate that is not
   one.
3. `moneroLoop` restarts each thread's nonce at `index` for every new job (:450) and strides by thread
   count into an `Int`; after ~2^32/threads hashes the nonce wraps and re-tests already-submitted
   nonces for the same job (a duplicate-share source pools penalise).
4. `Thread.sleep(250)` in `moneroLoop` (:447) throws `InterruptedException` on
   `stopWorkers()`/`restartMoneroWorkers`, which skips `RandomXNative.releaseThreadVm()` in the
   `finally`-less tail (:484) — the per-thread VM leaks on every seed rotation.

---

## 6. Coin conversion / swap

**What it does.** Posts a peer-to-peer offer for a coin pair to the mesh order book
(`P2pCoinOffers`, opcode `0x0E`), shows any matching offer, enforces PSC↔USDC-only pairing.

**Exact user path.** Wallet page → "Convert" button (`installConvertButton`, WalletPageView.kt:831–855)
→ `CoinConvertDialog.show` → pick from/to, amount → "Convert".

**Status: SCAFFOLD-ONLY.** There is no way to take an offer, so no conversion can ever happen.

**Bugs.**

1. **The order book has no accept path.** `P2pCoinOffers` (full file, 146 lines) exposes `post`,
   `cancel`, `broadcast`, `ingestFromPeer`, `getAll`, `matching` — and nothing that accepts an offer,
   pays the maker, or records a settlement. `CoinConvertDialog.submit` posts the offer and toasts
   *"Offer posted: X for Y. It settles when a peer takes it."* (CoinConvertDialog.kt:167–172). No code
   anywhere takes one, so the sentence is false and the user's coins never move in either direction.
2. `matching()` includes the device's own offers (`post` writes `byPeer[myIp] = mine`, then `getAll`
   flattens every map value — P2pCoinOffers.kt:76–82, :134–145), so "Best open offer" can quote the
   user back to themselves.
3. With an empty book the price is silently 1:1 (`val wanted = open?.let { … } ?: value`,
   CoinConvertDialog.kt:153–154) — an offer to swap BTC for DOGE one-for-one, posted with a toast that
   does not mention the rate.

---

## 7. Compute market and its payments

**What it does.** Lists mesh devices that are selling compute (`MeshComputeRegistry`), lets the user
pick one or several (split inference across devices), records what is owed in a debt ledger
(`ComputeDebtLedger`), and pays PSC from the local chain when the balance allows
(`MeshBridges.pay` → `AndroidMeshBridges.installCoin.pay`).

**Exact user path.** Wallet page → "Compute market" (`showComputeMarket`, WalletPageView.kt:234) →
tap a device / long-press for multi-select → footer shows "Owed to the mesh: N".

**Status: PARTIAL.** Selection, pricing, ledger persistence, gossip of debts and PSC settlement are all
real. What is not real is the "settled" state.

**Bugs.**

1. **A payment counts as settled when it only reached the local mempool.**
   `ComputeDebtLedger.settleAll` marks a debt settled when `pay(...)` returns true
   (ComputeDebtLedger.kt:141–157), and `AndroidMeshBridges.pay` returns true immediately after
   `chain.submit(tx)` accepts it into the local mempool (:114–121). On PSC nothing is final until a
   block is mined and gossiped; if no device mines, every debt is marked paid and vanishes from
   "Owed" while the creditor's chain shows nothing. `charge` has the same behaviour (:100–132).
2. **Storage is billed as compute tokens.** `CloudStorage.place` calls
   `ComputeDebtLedger.charge(peer, (chunk.bytes / 1024).coerceAtLeast(1))` — the second parameter is
   `tokens` and is priced with `ComputePricing.costOf(peer.score, tokens)`
   (CloudStorage.kt:236–238, ComputeDebtLedger.kt:104). A megabyte of storage is charged as 1024
   inference tokens.
3. A device that never loaded the PSC chain (`PrismCoinNode.chain` at genesis, e.g. wallet page not on
   a desktop slot) has `balanceOf(address) == 0`, so `pay` always returns false and the ledger grows
   without bound while the UI keeps promising "paid the moment there is enough".

---

## 8. Native compilation (on-device toolchain)

**What it does.** A declared build plan per library (RandomX buildable, Bitcoin Core declared
infeasible with a reason), source download, SHA-256 verification, tar.gz extraction, explicit
clang/clang++ invocations through a JNI "compiler driver" library, progress/log streams, cancellation,
and `PrismSettings.setCompiledLibrary` on success.

**Exact user path.** Wallet → mining menu → a coin with no shipped algorithm → "Compile"
(`offerCompile`, WalletPageView.kt:1602) → `NativeCompilerActivity`.

**Status: SCAFFOLD-ONLY — and honestly labelled as such.** `toolchainState` returns
`Unavailable` while `PrismSettings.getToolchainUrl()` is blank, which is the default, with a message
saying no clang-as-a-shared-library pack exists (NativeBuildEngine.kt:105–118). `build()` fails at
`Phase.RESOLVING_TOOLCHAIN` before downloading anything (:149–163). The comment at :29–38 states the
gap plainly. This is the correct way to ship an unfinished feature.

**Bugs.**

1. **The one plan Prism ships is unpinned.** `NativeBuildPlan.RANDOMX.sourceSha256 = ""`
   (NativeBuildPlan.kt:117) while the class comment says archives are "pinned by hash and a mismatch
   aborts before a single file is compiled". `build()` downgrades an empty pin to a log warning and
   continues (NativeBuildEngine.kt:183–197). So the moment a toolchain URL is configured, Prism will
   download whatever `github.com/tevador/RandomX/archive/refs/tags/v1.2.1.tar.gz` serves that day,
   compile it, `dlopen` it into the app process and load it — the exact scenario the comment says is
   guarded. A blank hash should be a hard failure for a build that ends in `dlopen`.
2. `plan.steps` emits bare `clang`/`clang++` argv (:65, :81) with no `--target=aarch64-linux-android`,
   no sysroot and no NDK include paths, and links `-llog` (:133). Even with a driver library, those
   invocations cannot produce a loadable Android `.so`; the plan would need the sysroot threaded
   through, which nothing does.
3. `NativeCompilerActivity` is declared in the manifest (AndroidManifest.xml:375–379) so the entry
   point at least resolves — unlike §12.

---

## 9. PrismOS VM, VNC display, and app routing into the guest

### 9.1 VM boot

**What it does.** `VmController` runs a guest either through AVF (`VirtualMachineManager`, API 34+,
reflection) or by spawning a bundled `qemu-system-aarch64` with UEFI firmware, `virt` machine, VNC on
127.0.0.1:5900, `virtio-gpu/keyboard/tablet`, and a VNC→Surface renderer. A process-wide singleton,
because QEMU outlives the view.

**Exact user path.** Add the "Virtualization" page to a slot → swipe to it. `VirtualizationPageView`'s
`SurfaceHolder` callback calls `bootFromSettings()` (VirtualizationPageView.kt:170, :443–494), which
boots automatically with no button press.

**Status: BROKEN for PrismOS; PARTIAL for a custom ISO.**

The QEMU half is real and unusually well-diagnosed: binary, UEFI firmware (`assets/qemu/QEMU_EFI.fd`)
and the VNC keymap are all present, `-L` points at an unpacked data dir, `HOME`/`TMPDIR`/
`LD_LIBRARY_PATH` are set, stdout is drained, the process is liveness-checked 1.5 s in, and a watchdog
turns a later crash into `State.ERROR` (VmController.kt:412–590). With a user-supplied ISO it can
boot.

**Bugs.**

1. **The PrismOS image is never created or downloaded.** `defaultPrismOsQemuImage()` is
   `filesDir/prism_os/prism_os.img` (VmController.kt:760–761) and a repo-wide search for
   `prism_os.img` finds only that line — no downloader, no asset, no first-run writer. With the
   default `VIRT_MODE_PRISM_OS` the first swipe to the page runs
   `qemu -drive if=virtio,format=raw,file=…/prism_os.img`, QEMU exits with
   `Could not open '…/prism_os.img': No such file or directory`, and the page shows
   `ERROR: QEMU exited immediately (code 1): …` (:551–555). The AVF half is equally empty:
   `startAvf` demands `filesDir/prism_os/vm_config.json` (:239–241) which nothing writes either.
2. **AVF's `vm.run()` blocks the only executor thread.** `startAvf` calls
   `vmClass.getMethod("run").invoke(vm)` (:265–266) on `executor`, which is
   `Executors.newSingleThreadExecutor` (:96–98). `VirtualMachine.run()` runs the payload and returns
   when the VM exits, so once AVF is in use, `stop()` (:185–198), `pause()` (:154–167) and
   `sendAppIntent` (:205–214) are all queued behind a call that never returns. Stop can never stop the
   VM.
3. **The AVF display is fiction.** `setSurface` is not a method on `VirtualMachine`; the call is
   wrapped in `catch (_: NoSuchMethodException) { /* older AVF build without display API */ }`
   (:260–262), so on every API level the guest renders nowhere and the failure is invisible.
4. **Pause is a no-op that lies.** `pauseQemu()` is literally `// Send QEMU monitor command via stdin
   (if monitor pipe is wired) — no-op for now` (:662–664), yet `pause()` sets `State.PAUSED`
   (:158–160) and is called on every page detach and every surface destruction
   (VirtualizationPageView.kt:175, :204). The control bar then says "Paused" while the guest keeps
   running and keeps burning a core. `resume()` "resumes" by reconnecting VNC (:666–669).
5. An orphaned QEMU from a previous app process can never be stopped: `stop()` only destroys the
   handle this process holds (:191–193), while `start()` deliberately adopts the orphan when
   `isVncPortLive()` (:126–133).

### 9.2 VNC display

**What it does.** A real RFB client: `RFB 003.008` handshake, forced BGRX pixel format, SetEncodings,
Raw rectangles, DesktopSize resize handling, every message type consumed, one writer thread so
keystrokes cannot interleave with update requests.

**Status: WORKING** (the parts needed by QEMU/Wine/x11vnc).

**Bugs.**

1. The security handshake is hardcoded: it reads the offered type list and writes `1` (None) without
   checking that None was offered, then **discards `SecurityResult`** (`input.readInt()`,
   VncSurfaceRenderer.kt:95–102). A server that requires `VncAuth` (QEMU with `-vnc …,password`) or
   rejects the selection leaves the client reading the failure code as the start of `ServerInit`, so
   the stream desynchronises and the renderer reports itself usable while drawing garbage.
2. Unknown encodings close the connection rather than skipping (:194–199) — correct, since
   rectangle length is not in the header, but it means any server that ignores SetEncodings yields a
   black screen with only a logcat line.

### 9.3 App routing into PrismOS ("launch Android apps inside PrismOS")

**What it does.** When "virtualize Android apps" and PrismOS mode are both on,
`LauncherActivity.launchComponent` diverts a tapped app to the Virtualization page and asks the VM to
start that component via `am start`.

**Exact user path.** Settings → OS Virtualization → virtualization on, mode PrismOS → tap any app icon.

**Status: BROKEN, and it breaks app launching generally.**

**Bugs.**

1. **The route always swallows the tap.** `LauncherActivity.launchComponent` checks the PrismOS
   condition and `return`s after paging to the virtualization view (LauncherActivity.kt:754–767)
   before the normal `startActivity` at :769–778. So with the setting on, *every* app tap stops
   launching the app and instead swipes the pager.
2. **The delivery is a stub.** `VirtualizationPageView.launchApp` → `VmController.sendAppIntent` →
   `sendViaAdb` (:729–738) → `sendVsock` builds an address and a socket object and does nothing with
   them (`@Suppress("UNUSED_VARIABLE") val unused = listOf(addr, socket, cmd)`, :740–752) or
   `sendAdbTcp` writes `"am start -n …\n"` raw to `127.0.0.1:5555` (:754–758). Nothing listens there:
   QEMU is started with `-net none` (:497) and no adb port forward, and adb over TCP is not a raw
   line protocol anyway (it needs `CNXN`/`OKAY` framing). The guest never receives the intent. The
   comment "queued and replayed once RUNNING" describes a queue into a function that cannot deliver.

---

## 10. Wine / Windows executables

**What it does.** `WineInstaller` assembles a Windows compatibility layer from upstream artefacts:
Ubuntu 22.04 base rootfs (tar.gz, unpacked by a hand-written `untar`), box64 and wine `amd64` `.deb`s
resolved out of Debian `Packages` indexes with version grouping and soft pins, `dpkg-deb -x` inside the
rootfs under PRoot, `apt-get install xvfb x11vnc …`, then `finalizeRootfs`
(`/opt/wine` symlink, `home/prism`, exec bits). `WineContainer` builds the PRoot+box64+wine command
line; `WineSession` starts Xvfb on `:0`, x11vnc on 5901, waits for the port, then launches the program
and hands the port to the VNC renderer.

**Exact user path.** Settings → turn Windows mode on (this also enables the `.exe` handler via
`ExeLaunchActivity.syncHandlerRegistration`) → either open a `.exe` from a file manager, or
Virtualization page → run an executable → "Install the Windows layer?" dialog → ~160 MB download →
program runs.

**Status: PARTIAL.** This is real, substantial code — PRoot ships (`jniLibs/…/libproot.so`,
`libproot-loader.so`), the tar/zip extractors are path-traversal guarded, apt runs with
`APT::Sandbox::User=root` inside PRoot, and the failure messages are specific. It is unverified on a
device and has one structural bug that will bite the second time it is used.

**Bugs.**

1. **The Windows session belongs to a recyclable view.** `wineSession` is a field of
   `VirtualizationPageView` (VirtualizationPageView.kt:216), while `VmController` is a process-wide
   singleton *explicitly because the pager throws the page away* (VmController.kt:59–75). Swipe away
   and back, then run an exe: the new view has `wineSession == null`, so `startWineSession` creates a
   second `WineSession` and `startDisplay` launches a second `Xvfb :0` and `x11vnc -rfbport 5901`
   (WineSession.kt:87–104). Xvfb dies immediately ("Server is already active for display 0"), the
   `display?.isAlive` check in `awaitDisplay` (:144) returns false, and the page reports "The X server
   did not come up" — while the first session's Xvfb/x11vnc/wine keep running with no owner and no
   way to stop them.
2. `WineSession.start` sets `State.RUNNING` immediately after spawning wine (:66) without a liveness
   check, so a wine that exits at once (missing DLL, 32-bit binary — deliberately unsupported, see
   WineInstaller's class comment) is reported as running.
3. `WineContainer.createContainer` is called with a hardcoded name `"default"`
   (VirtualizationPageView.kt:299–300, ExeLaunchActivity.kt:117–118), and `Container.screenSize` is
   always the default `1280x720` because `containers()` reconstructs containers from directory names
   only (WineContainer.kt:122–126). There is no UI to create a second container and no persistence of
   its settings, so "containers" (plural) is structural but unreachable.
4. `ExeLaunchActivity.displayNameOf` only short-circuits for `.exe`; anything else falls through to a
   `DISPLAY_NAME` query and finally to the literal `"program.exe"` (ExeLaunchActivity.kt:162–171).
   A `content://` URI whose provider returns no display name is therefore staged and launched as a
   Windows executable even if it is a `.msi` (accepted at :98) or not a Windows file at all.
5. `runWindowsExecutable` calls `vmController.pause()` first (VirtualizationPageView.kt:297) — which
   by §9.1/4 does nothing but change the state label, so both halves think they own the surface if a
   VM was actually running.

---

## 11. Android app virtualization + vault

**What it does.** Per-app backup of APKs into `filesDir/virtualized/<pkg>/apk`, a sealed data archive
(`data.enc`) unsealed into `…/live` only while the app runs, and an in-process host: `DexClassLoader`
over the backed-up APKs parented to the boot loader, `AssetManager.addAssetPath` resources, a
`PhoneWindow` over a `VirtualContext`, reflective `Activity.attach` essentials
(`mWindow/mMainThread/mInstrumentation/mFragments/attachHost`), all running in a separate
`:virtualapp` process and embedded into the launcher page through
`SurfaceControlViewHost`/`SurfacePackage`.

**Exact user path.** Settings → "Virtualize Android apps" on → tap an app
(`VirtualizedAppLauncher.launch`) → the app appears on the Virtualization page via
`VirtualAppEmbedder`.

**Status: PARTIAL.** Genuinely impressive reflection work (`VirtualAppRunner`/`VirtualPackage` handle
`<activity-alias>`, split APKs, per-ABI native extraction, API 14's writable-dex rule, and boot-loader
parenting for the Kotlin-stdlib clash). The lifecycle is one method deep and the security model does
not hold.

**Bugs.**

1. **The vault's stated guarantee is void in the shipped design.** `VirtualizedAppVault`'s class
   comment says the promise is kept "by WHERE the code runs… a virtualized app runs inside the guest
   VM", and that "an in-process virtualizer (the VirtualApp approach) would run the app under Prism's
   own uid, and then nothing in this file would mean anything at all — the app could simply read the
   key out of the process it was sharing" (VirtualizedAppVault.kt:29–37). The Android path *is* the
   in-process VirtualApp approach: `VirtualAppActivity`/`VirtualAppHostService` run in `:virtualapp`
   (AndroidManifest.xml:543–563), a separate **process** but the same **UID**, and `VirtualAppRunner`
   loads the hosted APK's dex into it. App-private storage and the Keystore are scoped per UID, not per
   process, so hosted code can read `filesDir/virtualized/vault.key` and use alias
   `prism_virtualized_app_vault_v1`, and therefore `dataKey()` for any other virtualized package. The
   "the app never sees the key, and therefore never any OTHER app's data" claim in `VaultService`
   (:36–39) is not true on Android.
2. **A Keystore failure silently destroys every virtualized app's data.**
   `VirtualizedAppVault.passphrase()` unseals `vault.key`; if that fails for any reason (screen lock
   removed, Keystore reset, app restored to another device, backup restore) it **generates a new
   passphrase and overwrites the file** (:113–123) with only a `logWarning`. The per-package key is
   `PBKDF2(passphrase, "prism-vault-$pkg", 120_000)` (:214–228), so the new passphrase makes every
   existing `data.enc` undecryptable forever. Recovering the archive is impossible; the code even
   reports it as success to the caller.
3. **Only `onCreate` runs on a hosted activity.** `VirtualAppRunner.createActivity` ends at
   `ActivityAccess.callOnCreate(instance, null)` (:172) and `destroy()` only calls `onDestroy`
   (:179–182). No `onStart`/`onResume`/`onPause`, no `onSaveInstanceState`, no `onNewIntent`, no
   result plumbing, no `onBackPressed` (a `WHAT_BACK` message exists but nothing calls
   `Activity.onBackPressed`). Apps that start work or build their view in `onResume` — which is most
   of them — show a blank window and look broken.
4. `VaultService.unseal` deletes the working directory *before* decrypting
   (`working.deleteRecursively()` then decrypt, VaultService.kt:143–150). A decrypt that fails leaves
   no plaintext (good) but the intermediate `unseal.tmp` is left behind, and the caller is told only
   "could not be unsealed".
5. The passphrase is described as "what makes it portable, because a Keystore key cannot leave the
   device and a user who wants their data on a new phone needs something that can"
   (VirtualizedAppVault.kt:47–50). Nothing exports the passphrase — it is sealed to a non-exportable
   device key and there is no export path in the file. The portability rationale is aspirational.
6. The desktop has a *third*, unrelated implementation: `desktop/…/vm/DesktopVirtualApp.kt` runs
   Android APKs through a hand-written Dalvik interpreter (`DalvikInterpreter`, `DexFile`) against a
   fake framework (`AndroidSurface`, ~1,500 lines answering `android.*` calls). That cannot run
   arbitrary real apps — any opcode or framework call outside the implemented surface is an
   `UnsupportedOpcode` or a zero. It is out of this audit's depth to grade, but it must not be read as
   "Android apps run on the desktop".

---

## 12. Cloud storage (mesh)

**What it does.** Content-addressed chunk store on peers: 1 MiB chunks, AES-GCM with the chunk index as
AAD, ciphertext-hash ids, an encrypted manifest, N-replica placement with per-peer billing, download
that tries recorded holders then any peer, a repair pass, and a host route that enforces a storage
quota and verifies each chunk against its id.

**Exact user path.** Cloud page → "storage" tab → upload a file / download / "Check every chunk"
(repair) / delete. Requires a seeded wallet (the key is HKDF-derived from the BIP-39 seed), cloud
enabled, and the mesh up.

**Status: PARTIAL.** The crypto is careful and the code is coherent: HKDF with its own labels
(`CloudVault.kt:122–126`), random 96-bit nonce per chunk, AAD binding the chunk to its index, and the
uploader re-hashes every fetched chunk before decrypting (`CloudStorage.fetch`, :295–308). What keeps
it from WORKING is that placement is almost always unsatisfiable, and that the stated namespace does
not exist.

**Bugs.**

1. **There is no namespace on disk.** `CloudVault.namespace()` is derived and documented as "what a
   peer stores chunks *under*" (:129–135), but neither `CloudStorage` nor `CloudHost` ever calls it:
   hosted chunks are bare files named by ciphertext hash in one shared
   `filesDir/cloud-hosted` directory (CloudHost.kt:97, CloudStorage.hostedRoot). Consequence: no
   ownership, no per-user accounting, and — by design, :148–155 — **an unauthenticated `drop`**: any
   peer who learns a chunk id can delete another user's copy, and the only remedy is the owner's
   manual repair pass.
2. **Placement silently degrades to nothing.** `CloudStorage.candidates()` is
   `MeshComputeRegistry.all()` (:91–92) — every node that advertises compute, whether or not it sells
   storage. `place()` tries them in order and calls `CloudWire.putChunk` (:220–244); a peer with
   `getCloudSellStorage() == false` answers `refuse()` (400), so `putChunk` returns false from
   `runCatching { JSONObject(String(reply)) }` and the chunk is recorded with `holders = []`. An upload
   whose every chunk is unplaced still writes a manifest entry and returns
   `UploadResult(entry, 0, total)`; the UI then offers "repair" for a file that was never stored
   anywhere. `readiness()` does filter for offering peers, but only to decide whether to start — it is
   not consulted per chunk and `place` ignores the offer probe the class comment promises
   (:88–90 "the `CloudWire.offer` probe happens per peer once per placement" — nothing calls
   `offer()` there).
3. `CloudWire.getChunk` distinguishes a refusal from a chunk by *size and substring*
   (`reply.size < 64 && String(reply).contains("\"ok\":false")`, :77–85). `CloudHost.refuse` writes a
   **400 with `text/plain`** (:226–228), which matches neither test, so a refused `GET` (unknown
   route, exception) is returned as "ciphertext", fails the hash check at `CloudStorage.fetch:304`, and
   the peer is logged as returning wrong bytes. A real error is indistinguishable from a bad peer.
4. `CloudStorage.upload` calls `PayLaterPolicy.check` but never `PayLaterPolicy.afterWork()` on the
   early-return paths (:137–140, :166–168), and `repair` calls `afterWork` once at the end even when it
   transfers hundreds of megabytes (:379).

---

## 13. Cloud gaming

**What it does.** Client half only: find desktop peers on the mesh with Steam, ask one to start a
title (`POST /cloud` `game-start`), wait for the display port, then attach the existing
`VncSurfaceRenderer` to a full-screen `CloudGamingActivity` with touch-as-mouse and X11 keysyms.

**Exact user path.** Cloud page → "gaming" tab → "Look for PCs" → tap a game title → (intended)
full-screen session.

**Status: BROKEN — twice over, for independent reasons.**

**Bugs.**

1. **`CloudGamingActivity` is not declared in `AndroidManifest.xml`.** The manifest declares
   `.cloud.CloudFileActivity` (line 203) and nothing else from the `cloud` package; the merged debug
   manifest at `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml`
   contains only `CloudFileActivity` too. Yet `CloudPageView.kt:629` calls
   `CloudGamingActivity.launch(context, …)`, which does
   `context.startActivity(Intent(context, CloudGamingActivity::class.java)…)`
   (CloudGamingActivity.kt:196–203). Tapping any game therefore throws
   `ActivityNotFoundException: Unable to find explicit activity class …` from inside a click listener
   with no try/catch — a crash of the launcher process, not even a "not available" message.
2. **No host exists anywhere.** `CloudGaming.hostingEnabled()` returns `false` and
   `startHosting()` returns `null` unconditionally on Android (:251–267, with an honest explanation
   that a phone under Wine is unplayable), and the class comment says the host half "runs in Prism
   desktop, which does not have it yet" (:34–37). The Android `CloudHost.control` would answer
   `OP_GAME_START` with `CloudGaming.startHosting(...)` → `null` → `{"ok":false}` (:190–204). So even
   with the manifest fixed, every session is refused: the feature is a client for a server nobody
   wrote.

---

## 14. File explorer

**What it does.** A launcher page listing the filesystem with a tree panel and a chooser
(`FileExplorerPageView`, `editor/FileChooser`, `editor/FileTreePanel`), plus a one-slot "cut" holder
(`FileExplorerClipboard`).

**Exact user path.** Add the "Files" page to a slot → browse; the editor's FileChooser/FileTreePanel
open the same tree.

**Status: PARTIAL (file operations themselves were not exercised).** The clipboard is the clearest
gap.

**Bugs.**

1. `FileExplorerClipboard` (23 lines) holds a single `File?` for "cut" and nothing else — no copy
   payload, no copy/move intent, no multi-select, no persistence across app death. A user who copies a
   file and then force-stops Prism loses the pending cut with no warning; a "copy" of a large file
   would have to be re-read anyway because no content is captured.
2. The explorer depends on `MANAGE_EXTERNAL_STORAGE` (AndroidManifest.xml:53), which on Android 11+
   requires a special all-files access grant. Nothing in this audit's scope showed the explorer gating
   its UI on `Environment.isExternalStorageManager()`; if the grant is refused, `File.listFiles()`
   returns null/empty rather than an error, which presents as empty folders.

---

## 15. Notification history

**What it does.** A `NotificationListenerService` records every posted notification (title, big-text
body, app label, key, ongoing flag, dedupe-count) into an append-only JSONL file in `:core`
(`NotificationHistory`), collapsing repeats and expiring nothing. The Notifications page and the global
search read and clear it. The desktop has its own capture path (`NotificationCapture`).

**Exact user path.** Settings → notification access → grant Prism → Notifications page (or global
search) → list/search/clear.

**Status: WORKING.** The listener is declared and permission-guarded correctly
(AndroidManifest.xml:253–261), `isEnabled` reads the secure setting rather than `connected`, the
backlog is captured on connect, the file caps at 100 000 entries with oldest-first eviction, writes go
through a temp file, and `clear()` deletes rather than flags.

**Bugs.**

1. **The file is rewritten wholesale on the caller's thread when an entry lands out of order.**
   `record` inserts in time order and, when the insert is not at the tail, calls `rewrite(list)` while
   holding the lock (NotificationHistory.kt:169–185). The listener's backlog loop
   (`activeNotifications.forEach { record(it) }`, PrismNotificationListener.kt:44) delivers the shade
   in platform order, which is frequently not time order. On a device with a large history that is up
   to 100 000 lines serialised and fsynced per out-of-order notification, on the notification
   listener's main thread — an ANR-shaped hazard exactly when the user first grants access.
2. **No per-app exclusion exists, though the doc promises it.** "the listener never records
   notifications from apps the user has excluded" (NotificationHistory.kt:42). `record` checks only
   `getNotificationHistoryEnabled()` and a blank package (:116–117); `PrismNotificationListener.record`
   checks only the group-summary flag (:65). A repo-wide grep for an exclusion setting returns nothing.
   Notification bodies (2FA codes, banking, medical) are the most sensitive data this app stores, and
   the only control the user actually has is the global on/off.
3. `MAX_ENTRIES = 100_000` is enforced only on insert; nothing prunes by age, so the file can reach
   ~100 k × ~2.3 KB ≈ 230 MB of plaintext message bodies before the first eviction, and every
   `load()` reads all of it synchronously (`load()` at :247–261 is called from `search`, which the
   global search bar calls).

---

## 16. Trusted devices — pairing, wallet handoff, SMS, app transfer

**What it does.** Mutual, per-kind trust between the user's own devices: an offer carrying the offered
kinds plus a random salt; the offerer displays a code (`PairingCode.generate`) that is *never* sent;
the acceptor derives a key from the typed code + salt and returns a sealed proof; the offerer verifies
the proof and only then confirms. Once confirmed, four kinds can flow, sealed with
`CloudVault.Purpose.MESSAGING` (a wallet-seed-derived key): MESSAGES (SMS relay), BROWSER_HISTORY,
VIRTUAL_APPS (APK transfer over HTTP), CLIPBOARD. A separate opcode carries a one-time wallet handoff
to a device that has no wallet, sealed with the code-derived key.

**Exact user path.** Trusted Devices page → "Pair" → pick a device on the mesh → choose kinds → read the
code to the other device; on the other side the offer prompt appears at launcher `onResume`
(`TrustedDeviceOffers.promptIfPending`), the user types the code, and a wallet offer follows if that
device has none.

**Status: PARTIAL — implemented, but consent can be forged.**

The design is genuinely careful: `MeshMembership.mayPairWith` is checked on offer *and* on reply, the
lock gate (`PrismLockScope.Gate.TRUSTED`) refuses offers while locked, the code is compared by being
used as a key rather than by equality, replies can only narrow what was offered
(`accepted.intersect(previous.outgoing)`, TrustedDevices.kt:660–664), a wallet is only ever sent when
the far side asked, and the receiver re-checks `accepts(peerIp, kind)` on every share.

**Bugs.**

1. **An unsolicited `TRUST_REPLY` creates a confirmed trusted device with no offer and no code.**
   `onReply` looks for a challenge in `pendingCodes[provisionalFingerprint(peerIp)]` and, **only if one
   exists**, verifies the proof (:616–644). When it does not exist — which is the normal case for a
   device that never offered anything, and also the case after any app restart because `pendingCodes`
   is in-memory only (:427) — the code block is skipped entirely and execution falls straight through
   to
   `upsert(Trust(fingerprint, …, outgoing = accepted.intersect(previous?.outgoing ?: accepted),
   incoming = accepted…, confirmed = accepted.isNotEmpty()))` (:648–667). With no `previous` row, the
   intersect collapses to the attacker's own `accepted` set. Any device on the meshnet can therefore
   send opcode `0x2A` with its own fingerprint and a kind list and appear in the trusted list as
   confirmed, with the user never shown a prompt. The payload is sealed with the wallet key so the
   attacker cannot read what it is sent, but `recipientsFor(kind)` (`:799–800`) now includes it, so
   Prism will transmit clipboard/SMS/history to an unpaired device — and the row is indistinguishable
   from a real pairing in the UI. This is the security-relevant bug in this feature.
2. `Trust.online` calls `peerCheck(lastIp)`, i.e. a mesh liveness probe, from a property read on the UI
   thread (`TrustedDevices.kt:158`; rendered per row in `TrustedDevicesActivity.deviceCard`, :203).
   A stale IP on a dead peer is a network round trip per list render.
3. `localFingerprint()` mixes the machine hostname into the identity
   (`InetAddress.getLocalHost().hostName`, :185–193, :196–199). Two devices with the same default
   hostname (very common: "localhost", "android") and the same wallet namespace produce the **same**
   fingerprint, so a peer can overwrite its own trust row with another device's.
4. The wallet handoff is only sent when `Kind.WALLET in accepted` (:640) *and* the acceptor asked
   (`needsWallet = !WalletVault.isInitialized()`, :547). Because `Kind.WALLET` is offered by the
   sending device, the accepting user can untick it in the kinds dialog and then never receive a
   wallet — while the pairing still reports success and every share will silently fail to decrypt
   (`onShare` returns false with "different recovery phrase", :890–895). `Trust.canDecrypt` is
   referenced as the way the UI tells the two apart (class comment, :45–46) but no such function
   exists in the file.

---

## 17. Diagnostics

**What it does.** `PrismLogger` mirrors every message to logcat, a `SharedFlow` for the live
Diagnostics page, and a rotating file at `/sdcard/Prism/diagnostics.log`; it installs a global
uncaught-exception handler that flushes before the process dies. `DiagnosticsActivity` renders the live
flow grouped by day with per-level colours, and `PrismDiagnostics`/`PrismLogger` provide the plumbing
the QEMU and Wine paths (which shell out to `logcat -d -b crash`) read.

**Exact user path.** Settings → Diagnostics (`DiagnosticsActivity`, declared at
AndroidManifest.xml:209–214); QEMU/Wine failures also surface their own tail in the page that failed.

**Status: WORKING, with a real chance the file log the user is asked to send does not exist.**

**Bugs.**

1. **`SimpleDateFormat` (not thread-safe) is shared and used from a coroutine pool.** `sdf` is a single
   field (PrismLogger.kt:45) and `getTimestamp()` (:226) is called from `append`, which runs inside
   `scope.launch { … }` on `Dispatchers.IO` (:24, :127–130) — a pool. Concurrent logging (training
   runs, mesh traffic) can interleave inside `SimpleDateFormat` and produce spliced or wrong
   timestamps, or throw `ArrayIndexOutOfBoundsException`/`NumberFormatException` inside the formatter.
   Timestamps are the thing a crash report is read for.
2. **File logging is silently disabled after five failures.** The log lives at
   `Environment.getExternalStorageDirectory()/Prism` (:57–61), which on API 29+ needs
   all-files access; if it is not granted, `FileWriter` throws, `consecutiveFailures` climbs, and
   `fileLoggingDisabled = true` (:170–180) permanently turns off file logging with only a logcat line.
   The Diagnostics page then shows the in-memory replay buffer (last 50 entries, empty after a
   restart) while the user believes a file is being written.
3. `rotateIfNeeded` deletes the previous generation then renames, inside a `try` that logs to logcat
   only (:191–203). If the rename fails on a full disk the current log is deleted and the old
   generation is already gone — the worst case for a diagnostics file.

---

## 18. Settings inventory (feature surface, not every row)

`SettingsActivity` is the only entry point to a large part of the surface audited here. Spot-checked
groups (line numbers from `SettingsActivity.kt`): mining mode + solo node URL/credentials + discovery
URL + toolchain URL + RandomX JIT; wallet (fiat, node binary dir); OS virtualization (on/off, mode,
ISO path, Windows mode, Windows layer URL, install/uninstall, experimental compiler) — the Windows
toggle at :2291–2298 also drives `ExeLaunchActivity.syncHandlerRegistration`; app virtualization;
notification history (:1617 area); cloud (enabled, sell compute/storage, replicas, quota); trusted
devices; diagnostics. The settings file is ~2 900 lines and nothing in this audit suggests a feature
listed in Settings is missing a backing implementation — the failures are inside the implementations
(§1, §6, §9, §10, §12, §13), not the wiring.

---

## 19. Desktop (`:desktop`) — spot-checked, not exhaustively audited

* **Wallet.** `desktop/wallet/DesktopWalletCipher.kt` (the desktop `WalletCipher`),
  `desktop/wallet/DesktopNodeHost.kt`: a real node manager that *finds* `bitcoind` on PATH or in a
  user-named directory rather than shipping it (`binaryFor`, :88–90), forces `prune=2048`, keeps RPC
  on loopback on purpose, and relays RPC over mesh opcodes instead of exposing the port
  (comment :40–46). This is the mirror image of §3 and is the more honest design of the two.
* **VM.** `desktop/vm/QemuVm.kt` + `VncClient.kt` + `ui/VirtualizationPage.kt`: real QEMU spawn and a
  shared RFB client; navigating away deliberately does not stop the guest
  (VirtualizationPage.kt:78–88). No app-routing agent exists; the page sends keystrokes from a command
  box instead and says so (:55–62).
* **Other pages present and wired:** `ui/WalletPage.kt`, `ui/WalletExtrasPage.kt`,
  `ui/FileExplorerPage.kt`, `ui/NotificationsPage.kt` (reads the same `NotificationHistory`),
  `ui/TrustedDevicesPage.kt`, `ui/AccessPointPage.kt`, `NotificationCapture.kt` (desktop notification
  capture into the same history).
* **`desktop/vm/DesktopVirtualApp.kt`** runs Android APKs through a from-scratch Dalvik interpreter
  against `AndroidSurface`'s emulated framework. Treat it as SCAFFOLD-ONLY: it can execute the subset
  of opcodes and framework calls that are implemented, and real apps will hit `UnsupportedOpcode` or
  receive zeroed answers.

---

## 20. Status table

| # | Feature | Status | One-line reason |
|---|---|---|---|
| 1 | PrismCoin chain/node/mining | PARTIAL | Chain, PoW, gossip and receipts are real; PSC **send** is impossible (§1.1) |
| 2 | Other coins (BTC/ETH/…) | PARTIAL | Real derivation + UTXO/EVM signing; broadcast path unverified, 3 coins unqueryable |
| 3 | Solo mining (own node) | BROKEN | `bitcoind` is not shipped (`BinaryMissing`), and the service never starts the node |
| 4 | Pool mining (Stratum V1) | PARTIAL | Protocol correct; every connect books a phantom share from `mining.authorize` |
| 5 | Monero / RandomX | PARTIAL | Native RandomX genuinely ships and is correct; liveness/UX bugs remain |
| 6 | Coin conversion | SCAFFOLD-ONLY | Order book has no accept path; every "conversion" is an unreadable offer |
| 7 | Compute market payments | PARTIAL | PSC settlement is real but counts mempool acceptance as payment |
| 8 | Native compilation | SCAFFOLD-ONLY | No clang-as-a-library toolchain exists; labelled honestly; plan ships unpinned |
| 9 | PrismOS VM | BROKEN | `prism_os.img` / `vm_config.json` are never created or downloaded; `pause()` is a no-op |
| 10 | VNC display | WORKING | Full RFB client; security handshake hardcoded to None and unchecked |
| 11 | App routing into guest | BROKEN | Hijacks every app tap, then delivers `am start` to a port nothing listens on |
| 12 | Wine / Windows apps | PARTIAL | Real PRoot+box64+Wine installer; session owned by a recycled view, fails on second use |
| 13 | Android app virtualization | PARTIAL | Sophisticated in-process host; vault threat model invalid, key loss is silent |
| 14 | Cloud storage | PARTIAL | Good crypto, no namespace, placement silently records zero holders |
| 15 | Cloud gaming | BROKEN | Activity missing from the manifest **and** no host implementation exists |
| 16 | File explorer | PARTIAL | Works as a browser; clipboard is a single in-memory cut slot |
| 17 | Notification history | WORKING | Correct listener, persistence, search; no per-app exclusion, O(file) rewrite on insert |
| 18 | Trusted devices | PARTIAL | Strong design; an unsolicited reply creates a confirmed device with no code |
| 19 | Diagnostics | WORKING | Real log pipeline; shared `SimpleDateFormat`, file log can silently stop |
| 20 | Settings inventory | WORKING | All audited features are reachable; failures are in the features, not the wiring |

---

## 21. The five worst

1. **PrismCoin cannot be sent.** `WalletNetwork.send` has no PSC branch and no PSC endpoint
   (WalletNetwork.kt:59–85, :442–443) while `WalletPageView.confirmSend` (:1334) calls it for every
   coin. The app's own chain — the thing the whole wallet page is built around — has a Send button that
   always answers "Prism has no network endpoint for PSC." *Why:* the send path was written against
   explorer/EVM endpoints and PSC deliberately has neither; the local-chain branch that
   `ModelPayments`/`AndroidMeshBridges` already use was never added.
2. **The virtualized-app vault protects nothing on Android, and loses data on a Keystore hiccup.**
   The documented model ("the app runs in a guest VM; an in-process virtualizer would mean nothing in
   this file means anything", VirtualizedAppVault.kt:29–37) is exactly the model that shipped:
   same-UID `:virtualapp` process, so hosted code can read `vault.key` and use the Keystore alias. On
   top of that, `passphrase()` regenerates and overwrites the vault passphrase whenever unsealing fails
   (:113–123), which permanently destroys every `data.enc` under the new PBKDF2 key, reported only as a
   warning.
3. **Turning on PrismOS app routing breaks launching every app.** `LauncherActivity.launchComponent`
   returns after paging to the virtualization view (LauncherActivity.kt:754–767) before its real
   `startActivity`; the delivery is either a VSock placeholder that discards its arguments
   (VmController.kt:740–752) or raw `am start` text to `127.0.0.1:5555` on a QEMU started with
   `-net none` (:497, :754–758). The user taps an app, the launcher swipes away, and nothing opens —
   and the normal launch path is skipped, so *nothing* opens.
4. **PrismOS can never boot, and `pause` only relabels a running guest.** `prism_os.img` and
   `vm_config.json` are referenced (VmController.kt:241, :760–761) and written by nothing, so the
   default first swipe to the page ends in `ERROR: QEMU exited immediately (code 1)`; on AVF,
   `vm.run()` blocks the single executor thread (:265–266 vs :96–98) so Stop can never run; and
   `pauseQemu()` is an explicit no-op (:662–664) behind a state change to `PAUSED` (:158–160) that is
   triggered on every page detach.
5. **Cloud gaming is one missing manifest line away from working, and several features away from
   existing.** `CloudGamingActivity` is absent from `AndroidManifest.xml` (and from the merged
   manifest), yet `CloudPageView.kt:629` calls `CloudGamingActivity.launch(...)` → an
   `ActivityNotFoundException` thrown inside a click listener. Even declared, `startHosting()` returns
   `null` unconditionally (CloudGaming.kt:262) and the desktop host "does not have it yet" (:34–37),
   so every session would be refused.

*Runner-up worth fixing in the same pass:* an unsolicited `TRUST_REPLY` creates a confirmed trusted
device with no offer and no code (TrustedDevices.kt:616–667), because the code check is skipped
whenever no in-memory challenge exists — which is always the case for a device that never offered.
