# Prism — meshnet, VPN protocols, access point audit

Read-only audit of `F:\AndroidStudioProjects\Prism` (no source was modified). Scope as briefed.

**Environment note.** Every shell command in this session failed before it ran:

```
pwsh: Error: SetNamedSecurityInfoW failed (Win32 5): grantWrite(F:\AndroidStudioProjects\Prism)
```

The DSH sandbox cannot provision its write grant on the workspace root, so `pwsh` was unusable and the
diagnose-and-repair script could not be run (approvals are disabled in this session). Everything below
comes from reading the files with the read/grep/glob tools. `glob` on the repo root also times out
(30 s) because of the build tree, so paths were discovered by grepping narrower roots. No claim in this
report rests on a build, a run, or a log — only on code.

---

## 0. The thing that explains most of the findings: there are two mesh transports

| | Android | Desktop |
|---|---|---|
| Transport | `PrismMeshService` (`app/.../mesh/PrismMeshService.kt`) | `MeshCore` (`core/.../MeshCore.kt`) |
| Dispatch | one giant `when (command)` (line 331–530) | opcode registry (`register`, line 112) |
| Socket | `DatagramSocket(8081)` (line 98) | `DatagramSocket(null).bind(8081)` (line 248) |
| Bind port source | `PrismSettings.getMeshBootstrapPort()` (line 94) | `portProvider()` hook, **never installed** → hardcoded 8081 (line 246) |
| Receive buffer | 16 KB (line 112) | 16 KB (line 286) |
| Built-in opcodes | inlined | `0x01–0x0B` hardcoded in `handlePacket` |
| Feature opcodes | inlined, ~35 features | only what something calls `register` for |

`MeshTransport` (`core/.../MeshTransport.kt`) is the seam features are supposed to use;
`AndroidMeshBridges.installTransport()` (line 60) points it at `PrismMeshService`; on desktop the default
lambdas point at `MeshCore` (lines 58–89). Two features bypass that seam and call `MeshCore` directly —
`MeshDns` (line 217, 228) and `MeshModels` (line 128) — which makes them **desktop-only in practice**
even though they live in `:core` and claim to be portable.

---

## 1. Opcode map (the collision table)

Built-ins, both platforms, in `MeshCore.handlePacket` / `PrismMeshService.handlePacket`:

| Op | Meaning | Android | Desktop |
|---|---|---|---|
| 0x01 | heartbeat | ✅ 332 | ✅ 414 |
| 0x02 | peer-list request | ✅ 333 | ✅ 431 |
| 0x03 | peer-list response | ✅ 334 | ✅ 440 |
| 0x05 | DNS write | ✅ 335 | ✅ (branch 452, needs `dnsConsumer`) |
| 0x08 | DNS sync request | ✅ 336 | ✅ 442 |
| 0x09 | DNS sync response | ✅ 337 | ✅ 452 |
| 0x0A | discovery request | ✅ 338 | ✅ 422 |
| 0x0B | heartbeat response | ✅ 339 | ✅ 418 |

Feature opcodes and where they are claimed:

| Op | Constant | Android dispatch | Desktop registration |
|---|---|---|---|
| 0x0C | `P2pModelRegistry.OPCODE_MODEL_ANNOUNCE` **and** `MeshModels.OPCODE_ANNOUNCE` | ✅ 340 → `P2pModelRegistry` | ✅ `MeshModels.registerWithMesh` (Main.kt:324) |
| 0x0D | `P2pModelListings.OPCODE_LISTINGS` | ✅ 341 | ✅ MeshMarket:52 |
| 0x0E | `P2pCoinOffers.OPCODE_OFFERS` | ✅ 342 | ✅ MeshMarket:58 |
| 0x0F | `ModelListingScanner.OPCODE_VERIFY_NOW` | ✅ 372 | ✅ MeshMarket:102 |
| 0x11 | `MeshPool.OPCODE_WORK` | ✅ 350 | ❌ |
| 0x12 | `MeshPool.OPCODE_SHARE` | ✅ 352 | ❌ |
| 0x14 | `ModelRefunds.OPCODE_REFUND_REQUEST` | ✅ 358 | ✅ MeshMarket:114 |
| 0x15 | `ModelListingScanner.OPCODE_SALE_APPROVED` | ✅ 366 | ✅ MeshMarket:91 |
| 0x20 | `MeshComputeRegistry.OPCODE_COMPUTE_ANNOUNCE` | ✅ 379 | ✅ MeshMarket:64 |
| 0x21 | `…OPCODE_RPC_REQUEST` | ✅ 384 | ✅ MeshMarket:70 |
| 0x22 | `…OPCODE_RPC_READY` | ✅ 389 | ✅ MeshMarket:76 |
| 0x23 | `…OPCODE_DEBT_ANNOUNCE` | ✅ 391 | ✅ MeshMarket:149 |
| 0x24–0x27 | `MeshScience` cosmic/witness/rf | ✅ 450–465 | ❌ |
| 0x28 | `SmsRelay.OPCODE_SMS` | ✅ 433 | ❌ |
| 0x29–0x2C | `TrustedDevices` trust/share/wallet | ✅ 407–431 | ✅ TrustedDevices:934–937 |
| 0x30 | `AetherMeshSync.OPCODE_AETHER_ANNOUNCE` | ✅ 502 | ❌ |
| 0x31 | `NebulaMeshSync.OPCODE_NEBULA_ANNOUNCE` | ✅ 503 | ❌ |
| 0x32–0x34 | `SocialMeshSync` announce/request/deliver | ✅ 514–521 | ✅ SocialMeshSync:105–107 |
| 0x40–0x42 | `PrismCoinNode` block/tx/head | ✅ 526–529 | ✅ MeshMarket:127–147 |
| 0x50–0x57 | `MinigameMesh` | ✅ 469–500 | ❌ |
| 0x58 | `KokoroMeshShare.OPCODE_KOKORO_ANNOUNCE` | ✅ 508 | ✅ MeshMarket:157 |

**Collisions / conflicts actually present**

* **0x0C is defined twice for the same wire number** — `P2pModelRegistry.kt:28` and
  `MeshModels.kt:40`. Same payload shape, deliberately duplicated (each side keeps its own registry),
  so it *works*, but it is two constants for one number and only one of them is registered on desktop.
* **0x0C is at the boundary of the "features start at 0x0C" rule** (`MeshCore.register` refuses `< 0x0C`,
  `MeshCore.kt:113`). `MeshDns.OPCODE_DNS_WRITE = 0x05` (`MeshDns.kt:288`) is therefore unregisterable
  by design; `MeshDns` never tries, it broadcasts through `MeshCore` directly (see F1/DNS below).
* **`MeshCore.register` replaces silently** (`handlers[opcode] = handler`, line 120) even though the
  whole `NebulaMeshSync` KDoc (line 75) explains that a clash "would be caught by a registry". The
  registry does not catch it. This is exactly how Nebula's 0x0E/0x0D clash happened before
  (fixed to 0x31 on 2026-09-24, `NebulaMeshSync.kt:64–78`).
* **`MeshComputeRegistry.kt:37` says "0x20 onwards is unused by every other subsystem"** — false:
  0x24–0x27 (science), 0x28 (SMS), 0x29–0x2C (trust), 0x30/0x31 (Aether/Nebula), 0x32–0x34 (social),
  0x40–0x42 (PrismCoin), 0x50–0x57 (minigames), 0x58 (speech) all exist.

**Opcode claimed on one platform only** (a real parity gap — a desktop never receives these and drops
them as "unclaimed", `MeshCore.kt:457–464`): 0x11, 0x12 (mesh pool mining), 0x24–0x27 (science),
0x28 (SMS relay), 0x30 (Aether), 0x31 (Nebula), 0x50–0x57 (minigames). Conversely **nothing is
Android-only-broken** in the other direction: every opcode the desktop claims is also in
`PrismMeshService`'s `when`.

---

## 2. Feature-by-feature

### F1. Mesh discovery / heartbeat / peer list / DNS gossip

**What it does.** UDP `"PRISM"` + one opcode byte + UTF-8 payload on port 8081. Broadcast discovery,
heartbeats for RTT, peer-list exchange for transitive discovery, and DNS-sync packets carrying the
`.p2p` registry.

**User path.** Android: launched unconditionally at startup (`PrismApp.kt:410`), so it is on by default
(`getMeshEnabled()` defaults `true`, `PrismSettings.kt:1245`); the bootstrap address/port fields are in
Settings (`SettingsActivity.kt:1191`) and the nearby-node list in the server fleet manager
(`SettingsActivity.kt:2852`). Desktop: `MeshCore.start()` + `MeshConnect.startServer()` at
`Main.kt:364–365`; the peer list is the "peers" section of the Mesh page (`MeshPage.kt:134–166`).

**Status: PARTIAL.** Discovery/heartbeat/peer list work on both transports and are wire-compatible for
the fields they send. DNS gossip does not work on the desktop **at all**; and the two transports
disagree on the port and on timeouts.

**Bugs.**

1. **DNS gossip is dead on desktop.** `MeshCore` handles 0x05/0x08/0x09 by calling
   `dnsConsumer`/`dnsProvider` (`MeshCore.kt:442–455`), which are only assigned in
   `MeshDns.registerWithMesh()` (`MeshDns.kt:227–231`). **That function is never called anywhere** —
   `grep registerWithMesh` finds only `TrustedDevices` (Main.kt:258) and `MeshModels` (Main.kt:324).
   Consequence: `requestDnsSync` returns early (`MeshCore.kt:502`, `?: return`), and an inbound
   `OP_DNS_SYNC_REQ` sends no reply (`if (mine != null)`, line 447). A desktop neither asks for nor
   answers mesh names; the Mesh page's "names" card (`MeshPage.kt:280–289`) shows only records
   registered in-process.
2. **`MeshDns.install()` is never called either.** `storage` stays null (`MeshDns.kt:69`), so
   `save()` is a no-op, and `.p2p` records on a desktop vanish on restart. The class KDoc
   ("Called once at startup. Loads whatever was saved", line 71) is not true of the shipped build.
3. **Names are announced before the socket exists.** `MeshSearchHost.install()` (Main.kt:323) and
   `MeshWebHost.install()` (Main.kt:316) both call `MeshDns.announce` → `MeshCore.broadcastToOthers`
   (`MeshDns.kt:217`), at which point `activePeers` is empty (the socket is opened at Main.kt:364).
   `prism.com` and every hosted site is therefore announced to nobody, once, at startup.
4. **The desktop ignores the configured mesh port and bootstrap nodes.** `MeshCore.portProvider` and
   `MeshCore.bootstrapNodes` are declared (`MeshCore.kt:156, 163`) and never assigned; the desktop binds
   8081 no matter what `getMeshBootstrapPort()` says, and never dials `getMeshBootstrapAddress()` /
   `getPrismServers()` (`getAllMeshNodes()` is read only by `PrismMeshService.kt:138`). The Mesh page
   then prints `PrismSettings.getMeshBootstrapPort()` (`MeshPage.kt:117`) — a number the listener is
   **not** bound to. A phone on a non-default mesh port and a desktop can never see each other.
5. **The desktop mesh cannot be switched off.** `Main.kt:364` calls `MeshCore.start()`
   unconditionally; `getMeshEnabled()` is checked by `PrismMeshService.start()` (`:87`) and by
   `PrismMeshService.isOnMesh()` (`:68`) but nothing gates `MeshCore`.
6. **Peer expiry differs by 3.3×**: 300 s on Android (`PrismMeshService.kt:148`) vs 90 s on
   desktop (`MeshCore.kt:52`). A phone keeps a dead peer in its list (and in the market UI) for two
   minutes longer than a PC does.
7. **The Android gossip loop is O(peers) per round, not one random peer.** Lines 161–165 heartbeat
   every peer older than 30 s, plus a random peer at line 155, contradicting `MeshCore`'s stated design
   ("ONE RANDOM PEER PER ROUND… twenty devices is four hundred packets a round", `MeshCore.kt:303–310`).
8. **`reuseAddress` is set after the socket is already bound.** `DatagramSocket(meshPort).apply {
   reuseAddress = true }` (`PrismMeshService.kt:98–101`) — on Android `reuseAddress` only affects
   `bind()`, which the constructor already did. The desktop path gets this right
   (`DatagramSocket(null)` then `reuseAddress` then `bind`, `MeshCore.kt:248–252`). The practical
   effect is a mesh that fails to restart while the old socket is in `TIME_WAIT`.
9. **16 KB receive buffer can be overrun, silently.** `getLocalDnsJson()` (`PrismMeshService.kt:711`)
   serialises *every* record plus every hosted site into one payload, and it is attached to every
   discovery reply (`:576`), every sync request (`:754`) and every sync response (`:651`). At roughly
   110 bytes per record that is ~140 names before the datagram exceeds 16384 bytes; the receiver's
   `receive()` truncates and neither side checks `packet.length == buffer.size`
   (`PrismMeshService.kt:114–116`, `MeshCore.kt:288–290`). The truncated JSON then fails to parse
   inside an empty `catch` (`PrismMeshService.kt:664`) — a large mesh silently stops syncing names and
   logs nothing. Same exposure for `getPeerListString()` concatenated at `:653`.
10. **Latency is measured against the wrong request.** `pendingRequests["$ip:$command"]` is written
    from a coroutine per send (`PrismMeshService.kt:783–785`) with no sequencing; two concurrent
    heartbeats to one peer overwrite the timestamp, so the reported RTT can be a fraction of the real
    one. `MeshCore` has the same map but sends synchronously.
11. **The desktop announces the wrong mesh address.** `MeshUtils.getLocalMeshIp()` prefers an interface
    whose name *contains* `tun` or `wg` (`MeshUtils.kt:30`). The desktop WireGuard interface is named
    `prism0` (`WireGuardInterface.kt:46`), so it never matches and the desktop advertises its LAN
    address as its mesh address (`MeshDns.exportJson`, `P2pModelListings.announce`,
    `MeshComputeRegistry.announce` all use it).

---

### F2. Mesh roles (server vs client)

**What it does.** `VPN_MODE_PRISM` + `PRISM_ROLE_SERVER` starts the local hosting listener and the
Prism proxy server (and the UDP VPN multiplexer); `PRISM_ROLE_CLIENT` starts only `PrismProxyClient`.

**User path.** Settings → VPN Tunnelling (on) → Mode = Prism → Role = Server/Client
(`PrismSettings.VPN_MODE_PRISM`/`PRISM_ROLE_*`, defaults `prism`/`client`, `PrismSettings.kt:1252, 1266`;
tunnelling itself defaults **off**, `:1232`). Applied by `PrismTunnelEngine.start()`
(`PrismTunnelEngine.kt:187–234`), which is called from `PrismApp`/`SettingsActivity:1176`.

**Status: PARTIAL.** The role split is real and the code paths differ, but the *announcement* of the
server role is mostly empty and the client role has no server-selection logic beyond a settings list.

**Bugs.**

12. **A phone acting as a VPN server advertises no key and no WireGuard port.**
    `PrismMeshService.localAnnouncement()` (`:592–596`) passes only `meshPort`, `serverPort` and
    `serverProtocol`. `PrismNodeWire.announcement` supports `serverPublicKey` and `wireGuardPort`
    (`PrismNodeWire.kt:38–39`), `PrismNodeWire.parse` reads them back (`:53–54`), the desktop fills
    them (`Main.kt:347–363`) — and `PeerInfo.serverPublicKey` / `wireGuardPort`
    (`PrismMeshService.kt:53–54`) are therefore **never populated on Android**. Nothing can auto-configure
    a WireGuard client from a phone's announcement.
13. **The advertised server port is conditional on a listener that may not be the one serving.**
    `advertisesProxy` requires `tunnelEngine.isPrismServerRunning()` (`:584`), which is
    `proxyServer.isRunning() || vpnMultiplexer.isRunning()` (`PrismTunnelEngine.kt:313–316`) — so a
    device that only has the *UDP multiplexer* up advertises `"proxy"` with the TCP proxy port
    (`getPrismVpnPort()`), which nothing is listening on.
14. **`VpnMultiplexer`'s `port` parameter is dead.** `PrismTunnelEngine.kt:398–400` passes the *mesh*
    port, and `VpnMultiplexer.start()` ignores it and binds `listOf(500, 4500, 1701, 51820)`
    (`VpnMultiplexer.kt:39`). Consequently `PrismSettings.getWgServerPort()` (default 51820,
    `PrismSettings.kt:2828`) is advertised but never honoured — see F4.
15. **Arguments swapped into the multiplexer.** `VpnMultiplexer(context, udpPort, pass, user, pass)`
    (`PrismTunnelEngine.kt:399`), signature `(context, port, psk, user, pass)`: the PSK is the
    password. Harmless today only because `IkeEngine`/`L2tpEngine` ignore `psk` entirely.

---

### F3. Mesh kick / ban

**What it does.** Kick evicts a peer from the local peer table for 5 minutes; ban additionally records
the peer's `nodeId` in `KEY_BANNED_PRISM_NODE_IDS` and drops its packets from then on.

**User path.** Android: Settings → server fleet manager → tap a node → kick/ban icons
(`SettingsActivity.kt:2782–2850`). Desktop: `PrismNodesDialog.kt:119` (kick) and `:134` (ban). Both go
through `MeshTransport.kickNode`/`banNode` → the platform transport.

**Status: PARTIAL.** The local eviction and the persisted ban work, in the gossip path only.

**Bugs.**

16. **Ban does not stop anything except UDP gossip.** The check is `knownNodeId in
    getBannedPrismNodeIds()` inside `handlePacket` (`PrismMeshService.kt:293`, `MeshCore.kt:382`).
    `PrismProxyServer` (Android `:8080`) and `MeshAiHost`/`PrismAiHost` have no ban check at all, so a
    banned node can still open PRISM_CONNECT, pull models, and run inference on the device that banned
    it. The desktop proxy checks only its own, differently-namespaced ids
    (`DesktopPrismProxyServer.kt:69, 84`).
17. **Kick and ban both require a non-empty `nodeId`.** `evictNode` matches `it.value.nodeId == nodeId`
    (`PrismMeshService.kt:235`); a peer that has only ever sent `"{}"` heartbeats has a blank id, the
    UI disables the buttons (`SettingsActivity.kt:2799`) and the operation is unreachable — the peer
    remains, unkickable, for the whole 300 s expiry.
18. **A kicked peer can undo the kick by omitting its id.** `knownNodeId` falls back to the stored id
    (`:290`), but the entry is removed from `activePeers` by the kick, so a subsequent packet with no
    `nodeId` finds nothing in `kickedUntil` and the peer is re-added at line 312.
19. **Ban is a local deny-list per device, not a mesh-wide revocation** — stated nowhere in the UI,
    whose copy says "blocks its Prism app identity from rejoining this server"
    (`SettingsActivity.kt:2838`). That copy is accurate; the *Kick* tooltip "Node disconnected"
    (`:2809`) is not, since the node was never connected to anything but the gossip table.

---

### F4. WireGuard — client and server

**What it does.** Two entirely different things share the name:
* Android *client*: `WireguardController` wraps `com.wireguard.android:tunnel`'s `GoBackend`.
* Android *server*: `WireguardServerEngine`, a hand-rolled userspace "WireGuard" driven by
  `VpnMultiplexer` on UDP 51820.
* Desktop *server*: `WireGuardInterface` writes a real `wg-quick`/`wireguard.exe` config for
  `prism0` on 10.8.0.1/24 and shells out.

**User path.** Client: Settings → VPN → Mode = External → paste a WireGuard profile into "External VPN
profile" → the engine calls `WireguardController.init/loadProfile/start`
(`PrismTunnelEngine.kt:408–415`). The profile itself is produced by
Settings → "Generate WireGuard client config" (`SettingsActivity.kt:1494`). Server (phone): automatic
whenever protocol mode is not `proxy` (`PrismTunnelEngine.kt:397–401`). Desktop: Tunnel page → bring
`prism0` up (`desktop/.../ui/TunnelPage.kt`), peer invitations via
`WireGuardInterface.invite()`.

**Status: BROKEN (Android server), PARTIAL (Android client), WORKING by construction (desktop server —
delegates the protocol to `wg-quick`/`wireguard.exe` and includes a real handshake self-test at
`WireGuardInterface.upAndConnectToSelf()`; not executed during this audit).**

**Bugs.**

20. **The Android "WireGuard server" performs no cryptography and injects plaintext into the VPN.**
    `handleHandshakeInitiation` returns a 92-byte buffer whose only non-zero byte is `response[0] = 2`
    (`WireguardServerEngine.kt:48–50`) — no responder index, no ephemeral key, no MAC, no Noise
    handshake. `handleTransportPacket` then *strips 16 bytes and calls
    `PrivateDnsVpnService.injectPacket(payload)`* (`:71–77`), i.e. **any UDP datagram to port 51820
    beginning with byte 4 is treated as an IP packet and injected into the device's VPN interface**,
    with no key, no session and no source-address check. This is a LAN-reachable packet-injection hole,
    not a VPN.
21. **Every peer is assigned the same tunnel address.** `sessions["10.8.0.2"] = PeerSession("CLIENT_PUB_KEY",
    …)` (`:59`) runs on every handshake, so the second device overwrites the first.
    `encapsulateAndSend` looks up `sessions[peerIp]` (`:81`), which is keyed by *virtual* IP while callers
    pass a *virtual* IP (`PrismTunnelEngine.kt:327–329`) — so it can only ever reach 10.8.0.2.
22. **Encapsulation emits a zero receiver index and a plaintext payload** (`:85–89`), which no real
    WireGuard peer will accept.
23. **`WireguardController` discards the backend result.** `backend?.setState(tunnel, UP, c)` (line 27)
    returns a `BackendException`-carrying `Result` that is thrown away; `start()` cannot report failure
    and there is no `isRunning()`/state hook, so `PrismTunnelEngine` cannot tell a failed external tunnel
    from a working one. `loadProfile` lets `Config.parse` throw straight out of the engine thread.
    There is also no `VpnService.prepare()` for this path anywhere in the engine.
24. **The generated client config is unshippable.** `PrismSettings.generateWgClientConfig()` still
    contains `val serverIp = "YOUR_PHONE_IP_HERE"` (`PrismSettings.kt:2834`) and hardcodes
    `Address = 10.8.0.2/24`, so the button at `SettingsActivity.kt:1494` emits a config that cannot
    connect.
25. **The configured WireGuard port is never bound.** Server-side port binding is
    `VpnMultiplexer.kt:39`'s fixed 51820 while the setting (`PrismSettings.kt:2828`) is editable and the
    desktop honours it (`WireGuardInterface.kt:190`). Change the port on a phone and the server silently
    stays on 51820.

---

### F5. L2TP

**What it does.** `L2tpEngine` claims to be a "Minimal L2TP responder for identifying and starting L2TP
control tunnels": it should answer SCCRQ with SCCRP.

**User path.** Server mode with protocol mode `l2tp` (`PrismSettings.VPN_PROTOCOL_L2TP`) →
`VpnMultiplexer` binds UDP 1701 (`PrismTunnelEngine.kt:397` → `VpnMultiplexer.kt:39`) → a Windows
client's L2TP dial.

**Status: BROKEN — it can never reply to a real SCCRQ.**

**Bugs.**

26. **AVP parsing is off by two, so the message type is never read.** The first AVP begins at offset 12
    with a 6-byte header (flags+length, vendor id, attribute type) followed by the value, so an
    IETF Message-Type AVP reads type at bytes 16–17 and value at 18–19. The code reads
    `avpType = data[18..19]` and then `msgType = data[21] if avpType == 0`
    (`L2tpEngine.kt:34–37`). For a genuine SCCRQ, `data[18..19]` is the *value* (1), so `avpType == 0`
    is false, `msgType` becomes −1, and `sendSccrp` is never called. The responder is unreachable dead
    code. (`sendSccrp` itself writes the AVP in the *correct* layout at `:60–67`, which is what makes
    the asymmetry obvious.)
27. **The SCCRP it would send is malformed anyway.** It allocates 40 bytes, sets length = 40
    (`:51`), writes one 8-byte AVP, and leaves bytes 20–39 as zeros — a trailing "AVP" of length 0,
    which a real L2TP stack rejects. The length should be 20.
28. **No L2TP beyond SCCRP exists.** No SCCCN, no ZLB ack, no ICRQ/ICRP/ICCN, no PPP/LCP/CHAP, no
    session, no data path. `psk`, `user` and `pass` (`:10–14`) are never read. Even a perfect SCCRP
    would leave the client hanging at "no PPP".
29. **L2TPv3 is mis-detected as L2TPv2.** `identifyProtocol` accepts version bytes 0x02 *or* 0x03
    (`VpnMultiplexer.kt:120–123`), then the engine assumes the 12-byte v2 control header
    (`L2tpEngine.kt:17, 28`).

---

### F6. IKEv2

**What it does.** `IkeEngine` claims to respond to Windows VPN IKE handshakes, including a full
IKE_SA_INIT handler.

**User path.** Server mode with protocol mode `ikev2` → `VpnMultiplexer` binds UDP 500 and 4500
(`VpnMultiplexer.kt:39`) → a Windows IKEv2 client.

**Status: BROKEN / SCAFFOLD.** It sends *a* packet and no IKE SA can ever be established.

**Bugs.**

30. **The SA payload is not a valid proposal.** Bytes 38–50 encode a 44-byte SA whose proposal header
    ends with `… ,0,0,0,36` — the last byte of a proposal header is the transform count, and 36 is not
    a count (it is the proposal length written into the wrong field); each transform likewise has its
    length and its ID values in the wrong positions (`IkeEngine.kt:38–50`). Any conformant stack drops
    the message as a malformed payload.
31. **The KE payload is random bytes, not a Diffie-Hellman public value.**
    `java.security.SecureRandom.getSeed(256)` (`:54`) returns entropy, not `g^priv mod p`. The client
    computes a shared secret against garbage, so even a parser that accepted the SA would derive a key
    the server cannot possibly share — the server has no private key either.
32. **The Ni nonce payload is malformed and there is no IKE_AUTH.** `niPayloadHead = [0,0,0,36]`
    (`:57`) declares 36 bytes of payload whose next-payload type is 0 (No Next Payload) while the
    buffer carries 32 bytes. Every exchange other than IKE_SA_INIT logs "coming soon"
    (`:25–27`), so no CHILD_SA, no authentication, no ESP.
33. **IKEv1 is named in the KDoc and detected by the multiplexer but never handled.** `IkeEngine`
    only branches on version `0x20` (`:22`); `VpnMultiplexer.identifyProtocol` returns `IKEV1` for
    `0x10` (`:115`) and hands it to the same engine, which does nothing.
34. **`extractNonce` is a stub returning null** (`:94`) — unused, but it is the function a real
    implementation would need.
35. **The NAT-T marker is computed twice and inconsistently.** `VpnMultiplexer` strips the 4-byte
    non-ESP marker and passes `hasMarker` (`:75–81`); `IkeEngine` then *re-adds* one based on
    `receivingPort == 4500 || hasMarker` (`:61–68`) while writing the IKE length field as
    `totalIkeLen` (excluding the marker) in a buffer that *includes* it — the header's length field and
    the datagram length disagree by 4.

---

### F7. Proxy tunnel (Prism VPN / PRISM_CONNECT)

**What it does.** The working half of the "Prism VPN": a per-app proxy (Android `PrismProxyServer` +
`PrismProxyClient`) and a TCP `PRISM_CONNECT <domain>` handshake on 8080 that multiplexes hosted sites,
model transfer, AI generation, protein work, cloud routes, SSH-less app transfer and search over one
port. The desktop equivalent is `MeshConnect` (server, `MeshConnect.kt:139`) plus
`DesktopPrismProxyServer` (authenticated HTTP CONNECT, `DesktopPrismProxyServer.kt:28`).

**User path.** Settings → VPN Tunnelling → Mode Prism → Role Server → protocol `proxy`/`auto`
(`PrismTunnelEngine.kt:373–395`); clients get `PrismProxyClient` (`:404–406`). Desktop: the device's
own WireGuard client config points at `DesktopPrismProxyServer`, started by
`WireGuardInterface.upAndConnectToSelf()` (`:313`).

**Status: WORKING** (the most complete part of the VPN scope). Both proxy servers enforce Basic auth,
both handle CONNECT, both are bounded and threaded correctly.

**Bugs.**

36. **Open CONNECT relay on an unprivileged, non-loopback port.** `DesktopPrismProxyServer.start()`
    binds `0.0.0.0` on `getPrismVpnPort()` (`:35`) and forwards CONNECT to *any* `host:port`
    (`:122–136`) after Basic auth. The credentials are auto-generated (`PrismSettings.kt:1337, 1350`)
    and there is no allow-list, so anyone on the LAN who learns them has a general-purpose TCP relay.
    Android's `PrismProxyServer` has the same shape (`PrismProxyServer.kt:330`).
37. **Dead and contradictory timeout code in `fetchMeshContent`.** Two nested `newBuilder()` blocks;
    the first sets `readTimeout(… ?: 10, TimeUnit.MILLISECONDS)` with the author's own note
    `// Oops, careful with units`, and its result is discarded in favour of the second
    (`PrismTunnelEngine.kt:426–445`).
38. **The mesh fetch bypasses `PrismSocket`.** `fetchMeshContent` uses the plain OkHttp client with a
    `Host:` header (`:417–445`) rather than the handshake-aware socket, so a `.p2p` URL fetched this way
    depends on DNS having been overridden — see F12.

---

### F8. Split tunnelling / app whitelist

**What it does.** A checkbox list of installed apps; each ticked package is meant to *bypass* the VPN.
`AppUsageMonitor` polls `UsageStatsManager` for foreground changes and toggles the engine.

**User path.** Settings → VPN → "App whitelist" (`SettingsActivity.kt:1214`) → `WhitelistActivity` →
checkboxes.

**Status: BROKEN.** The toggling is global, not per-app, and the monitor usually never sees an event.

**Bugs.**

39. **Whitelisting one app tears down the entire mesh and VPN.** `AppUsageMonitor` calls
    `engine.pauseTunnel()` when a whitelisted app comes forward (`AppUsageMonitor.kt:50–51`), and
    `pauseTunnel` stops the hosting listener (port 8080!), the proxy server, the VPN multiplexer, the
    proxy client and the WireGuard client (`PrismTunnelEngine.kt:249–259`). So the moment the user opens
    a whitelisted app, the mesh site host dies for everybody, model transfers fail, and every *other*
    app loses its tunnel. Coming back to a non-whitelisted app calls `resumeTunnel()` (`:53`), which
    rebinds 8080 — a listener tear-down/rebind on every app switch.
40. **The whitelist does not bypass anything per-app.** Android per-app bypass is
    `Builder.addDisallowedApplication`; the engine never calls it, so a whitelisted app either gets the
    full tunnel or (bug 39) the whole device gets none.
41. **The feature silently does nothing without usage-access permission.** `PACKAGE_USAGE_STATS` is
    declared (`AndroidManifest.xml:55`) and nothing ever sends the user to
    `Settings.ACTION_USAGE_ACCESS_SETTINGS` — there is no such intent anywhere in the app (grep finds
    none). Without the grant `queryEvents` returns an empty cursor forever (`AppUsageMonitor.kt:29–38`),
    so the checkboxes appear to work and have no effect.
42. **`lastWhitelistedState` is stale across app switches.** It is a single global flag updated only
    when `latestEvent.packageName != currentPackage` (`:40–45`); if the monitor starts while a
    whitelisted app is already foreground and then the user goes to a *second* whitelisted app, no state
    change is detected (correct), but if the process restarts mid-session the flag re-initialises to
    `false` and the tunnel is left in whatever state it was. The UI text "N bypassing VPN"
    (`WhitelistActivity.kt:60`) is wrong whenever any of the above holds.

---

### F9. Access point creation

**What it does.** Creates a Wi-Fi group: Wi-Fi Direct (`createGroup`) on API 29+, `LocalOnlyHotspot`
below. Validates SSID length and password length, records the config, and persists it.

**User path.** Settings → "Access Points" (`SettingsActivity.kt:1364`) → `AccessPointPortalActivity` →
"Create New Access Point" (`:120`) → `CreateAccessPointActivity` form → Create.

**Status: PARTIAL.** The group does get created (given the permission dance), and the config persists.
It is not an access point in the sense the UI implies: no internet, no DNS advertisement, no address
range it claims.

**Bugs.**

43. **The advertised network range is fiction.** The portal prints "Network Range: 10.8.0.0/24"
    (`AccessPointPortalActivity.kt:249`) and `AccessPointConfig.gatewayIp` defaults to `10.8.0.1`
    (`AccessPointModel.kt:22`). Wi-Fi Direct group owners serve DHCP on `192.168.49.0/24`
    (`192.168.49.1`), and `LocalOnlyHotspot` uses `192.168.43.x`. `10.8.0.1` is the *Prism VPN* gateway.
    So `applyIsolationRules(ap.id, ap.gatewayIp, "10.8.0.1", ap.ipRange)`
    (`AccessPointPortalActivity.kt:273–275`) is handed three addresses that no client will ever use.
44. **No internet sharing and no NAT.** A Wi-Fi Direct group gives peers a link-local subnet and
    nothing else; there is no tethering, no `ip_forward`, no NAT rule that is actually executed (see
    F13). A device that joins a "Prism Access Point" gets no connectivity at all.
45. **`networkType` (WiFi / Bluetooth / Hotspot) is cosmetic.** It is written to prefs
    (`CreateAccessPointModel` → `AccessPointStore.kt:67`) and never read by `AccessPointManager`, which
    only ever does Wi-Fi Direct or LocalOnlyHotspot.
46. **The list field separator is not escaped.** Configs are serialised as
    `id::ssid::active::…::description` (`AccessPointStore.kt:67`) and parsed by `split("::")` with
    `if (p.size < 13) null` (`:26–27`). An SSID, a password or a description containing `::` shifts every
    following field; the record is then either mis-parsed (password becomes the IP range) or dropped
    entirely, silently.
47. **An "Open" access point is silently created as WPA2 with a hardcoded password.** Validation
    allows an empty password when `authType == AuthType.OPEN` (`AccessPointManager.kt:55`), but
    `startP2pGroup` always supplies a passphrase: `if (config.password.length >= 8) config.password else
    FALLBACK_PASS` (`:76`), and `WifiP2pConfig.Builder().setPassphrase(...)` (`:94–97`) cannot create an
    open group. So a user who chose OPEN gets a WPA2 network keyed `prism00000` (`:42`) and is never
    told the password.

---

### F10. Access point portal

**What it does.** Lists saved access points from prefs, with Start/Stop/Edit/Devices/Stats per row, a
summary card, and a create button.

**User path.** Settings → "Access Points".

**Status: PARTIAL.** The list and the create/edit flow work. Start works. Stop only works in the same
process instance that started it.

**Bugs.**

48. **The `AccessPointManager` is per-Activity, so Stop fails after a restart.** `onCreate` builds a
    fresh manager (`AccessPointPortalActivity.kt:56`), whose `activeAccessPoints` map
    (`AccessPointManager.kt:34`) is empty. `stopAccessPoint` looks the id up in that map and returns
    `failure("Access point $apId is not active")` (`:165–166`) — which is what the user sees after
    rotating, backgrounding until the process dies, or reopening the app, even though the Wi-Fi group is
    still up. `AccessPointStore` persists `isActive = true` (`AccessPointManager.kt:150`), so the UI goes
    on claiming ACTIVE with no way to stop it.
49. **The portal's "Stats" button is dead for the same reason.** `getAccessPointStats` reads the
    in-memory `apStats` map (`:216`) and is null after any recreation, so `viewAccessPointStats` silently
    does nothing (`AccessPointPortalActivity.kt:355–361` — no else branch, no message).
50. **`initializeAccessPointSystem` is called from `onResume` and from every action's `finally`**
    (`:287, :309, :333, :366`), each time re-reading prefs and rebuilding the whole adapter. Harmless,
    but it is also the reason the stale `isActive` flag never gets corrected: nothing reconciles prefs
    against the live manager.
51. **A denied permission leaves the portal permanently on "Initializing…".** The launcher callback
    only proceeds when every permission is granted (`AccessPointPortalActivity.kt:47`), and the failure
    branch does nothing — no message, no retry, no explanation. Denying the location prompt (requested
    on API 29–32, `:181–183`) or `NEARBY_WIFI_DEVICES` leaves `statusText` at "Initializing…"
    (`:96`) forever. The Bluetooth permissions requested at `:168–176` are also unnecessary for a
    Wi-Fi-only feature.

---

### F11. Connected devices

**What it does.** Intended to list devices joined to an AP, with block/unblock/bandwidth/disconnect.

**User path.** Portal → row → "Devices" (`AccessPointPortalActivity.kt:343`).

**Status: SCAFFOLD-ONLY.** The list is a hardcoded empty list.

**Bugs.**

52. **The device list is literally `emptyList()` with a comment saying so.**
    `ConnectedDevicesActivity.kt:92–93`:
    `// In a real implementation, fetch devices from AccessPointManager` / `val devices = emptyList<ConnectedDevice>()`.
    The screen always reports "Connected Devices: 0".
53. **Nothing ever populates the manager's map.** `addDevice` (`AccessPointManager.kt:213`) has **no
    callers** anywhere in the repo (grep). There is no `WifiP2pManager.requestGroupInfo` /
    `requestPeers` call, no `WIFI_P2P_CONNECTION_CHANGED_ACTION` receiver, and no DHCP-lease read — so
    even a correct screen would have nothing to show.
54. **The per-device actions all "succeed" against nothing.** `blockDevice`/`limitBandwidth`
    (`ConnectedDevicesActivity.kt:110–154`) call `NetworkIsolationEngine`, whose execution is a no-op
    (F13); combined with bug 52 they are unreachable UI.

---

### F12. DNS override

**What it does.** A UDP DNS proxy that answers from the P2P `.p2p` ledger and NXDOMAINs everything
else, meant to be the resolver for devices joined to a Prism access point.

**User path.** Portal → Start on an access point →
`dnsOverrideService.startDnsServer(ap.gatewayIp)` (`AccessPointPortalActivity.kt:272`). Status text then
says "DNS on port 5353 — set manually on connected devices" (`:276–279`).

**Status: PARTIAL.** The responder itself works; nothing ever sends it a query.

**Bugs.**

55. **No client is ever told to use it.** There is no DHCP option 6, no Wi-Fi Direct group-owner DNS
    configuration, no `iptables -t nat REDIRECT` that actually runs (F13). The only path is the user
    manually typing a resolver into each guest device, which the UI admits. An "override" that requires
    the user to configure the client by hand is not an override.
56. **It will almost never bind 53.** `tryBindDnsSocket` tries 53 then 5353 (`DnsOverrideService.kt:208–215`).
    Android refuses 53 to unprivileged apps, so the bound port is 5353 — and a Wi-Fi Direct group owner
    advertises itself (`192.168.49.1`) as the guest's DNS server on port 53, where nothing is listening.
57. **Everything not in the P2P ledger is NXDOMAIN, including the guest's own OS lookups.**
    `resolve(..., onlyP2p = true)` (`:128`) then `sendNxdomainResponse` (`:139`). Even with correct
    plumbing, a guest on a Prism AP cannot resolve `example.com`; combined with bug 44 (no internet) the
    AP is a network with no name service and no routes.
58. **`isBlocked` always returns false** (`:182–186`, `return false // Default: allow`) — the blocklist
    branch at `:134–136` is unreachable.
59. **512-byte receive buffer**, `DnsOverrideService.kt:78`: any EDNS0 query larger than 512 bytes is
    truncated by `receive()` and dropped by `parseQuery`.
60. **The socket is never closed on the error path.** `startDnsServer` assigns `dnsServerSocket` then
    launches the listener; if `stopDnsServer` is never called (nothing calls it — grep finds no caller
    outside the class) the port stays bound for the process lifetime, and a second "Start" on a second AP
    replaces the field while the first listener keeps running on the orphaned socket.

---

### F13. Network isolation

**What it does.** Claims to enforce, via iptables, that AP clients can only reach the VPN, with
per-device block and per-device bandwidth limiting.

**User path.** Automatic on AP Start (`AccessPointPortalActivity.kt:273–275`); per-device actions from
Connected Devices (`ConnectedDevicesActivity.kt:110–154`).

**Status: SCAFFOLD-ONLY — it reports success and executes nothing.**

**Bugs.**

61. **`executeIptablesCommand` only logs.** `NetworkIsolationEngine.kt:170–181`:
    ```
    // In production, this would execute via su/root
    // For now, we log the command for verification
    PrismLogger.logInfo(TAG, "iptables: $command")
    // Actual execution (requires root):
    // Runtime.getRuntime().exec(arrayOf("su", "-c", command))
    ```
    Nothing is executed, `applyIptablesRule` (`:124–132`) has no way to fail, and
    `applyIsolationRules` returns `Result.success("Isolation rules applied successfully")` (`:50`).
    `blockDevice` returns "Device blocked successfully" for a device that was never blocked.
62. **One of the rules it would run is invalid.** `FORCE_VPN` emits
    `iptables -t nat -A POSTROUTING -s $srcIp -d ! $clientSubnet -j MASQUERADE`
    (`NetworkIsolationEngine.kt:159`) — the negation must precede the option (`! -d …`); as written
    iptables would reject the line.
63. **If it ever were enabled, `removeIsolationRules` would flush the device's firewall.**
    `iptables -F FORWARD`, `-t nat -F PREROUTING`, `-t nat -F POSTROUTING` (`:193–195`) delete every rule
    in those chains, not just Prism's.
64. **Per-device bandwidth limiting targets `wlan0` unconditionally** (`:251–253`) — the wrong interface
    for a Wi-Fi Direct group owner (`p2p0`) and for a LocalOnlyHotspot, and `tc qdisc add … root` fails
    if one already exists.
65. **Every rule is IPv4-only string matching with no state tracking** (`:137–165`): no `-m conntrack`,
    no `-i p2p0`/`-o` interface qualifier, so the rules (in the hypothetical where they run) would apply
    to *all* forwarded traffic on the device, not just the AP's clients.

---

### F14. Mesh compute market

**What it does.** Gossips each device's capacity (RAM/VRAM/swap/NPU/cores/payout address/platform) on
0x20, derives prices locally (`ComputePricing`), lets a buyer ask a peer to stand up a ggml RPC server
(0x21/0x22) and either split a model across ≥2 peers or run the prompt on 1 peer. Debts are recorded
and announced on 0x23.

**User path.** Android: Wallet → compute market (`ComputeMarketView`, `ComputeDebtLedger.settleAll` at
`WalletPageView.kt:256`), opt-in via the compute-host setting. Desktop: Mesh page → "Announce capacity"
/ "Withdraw", market list (`MeshPage.kt:194–231`).

**Status: PARTIAL.** The gossip, the derived pricing, the registry and the debt ledger are real and
shared. The *single-peer* execution path is dead, the RPC server is exposed wider than claimed, and the
desktop announces itself even when opted out.

**Bugs.**

66. **`MeshBridges.generateOnPeer` is never installed, so single-peer offload always fails.**
    Declared at `MeshBridges.kt:145`, read at `MeshInference.kt:242` (`?: return null`), and **assigned
    nowhere** — `AndroidMeshBridges.install()` sets only `coin`, `mining` and `connect`
    (`:86, :135, :169`), and `AiManager.generateOnPeer` (`AiManager.kt:327`) is never wired to it.
    `AiManager` calls `MeshInference.runOnPeer` whenever exactly one peer is selected
    (`AiManager.kt:285–286`), which is **the common case** — automatic mode returns exactly one peer
    (`MeshInference.kt:72–74`). So the market's headline single-peer path returns null and the user
    silently falls back to local inference, while `MeshInference`'s own KDoc calls this "the cheap case"
    that "works with whatever model that peer has loaded".
67. **The ggml RPC server is not bound to the tunnel, contrary to its comment.**
    `MeshInference.startHosting` binds `MeshUtils.getLocalMeshIp()` (`:143`) and the comment claims
    "only devices inside the Prism tunnel can reach it" (`:135–142`). `getLocalMeshIp()` returns the
    **LAN** address whenever no interface is named `tun*`/`wg*` (`MeshUtils.kt:30–40`) — always true on
    the desktop (`prism0`), and true on a phone with the VPN off. An unauthenticated ggml RPC server,
    which upstream documents as memory-disclosure-unsafe, is therefore reachable by every device on the
    Wi-Fi.
68. **A desktop that never opted in still announces itself.** `MeshComputeRegistry.announce()` has no
    `getComputeHostEnabled()` gate (`:160`); Android's caller does gate it
    (`PrismMeshService.kt:175`), the desktop's does not (`MeshMarket.kt:177–185`, `Main.kt:370`). Peers
    then see a market entry whose owner will refuse work.
69. **A refusal is indistinguishable from silence, and costs the buyer 8 seconds.**
    `onRpcRequest` returns without answering when hosting is off (`MeshInference.kt:160–164`), and the
    buyer polls for `peers.size` endpoints until `timeoutMs = 8_000` (`:182–197`). With one
    non-participating peer in the selection every generation stalls 8 s before falling back.
70. **`rpcPorts` is never invalidated.** `MeshComputeRegistry.rpcPorts` (`:143`) is only written
    (`:256`) and read into `Peer.rpcEndpoint` (`:127`); nothing clears it when the peer withdraws
    (`withdraw()`, `:214`) or when `peers.remove` runs (`:225–226`), so a stale endpoint is offered
    forever.
71. **"Tokens served" is always 0.** `tokensServed` (`MeshInference.kt:56`) is only read
    (`:268`, `MeshPage.kt:174`, `DesktopDiagnostics.kt:301`); nothing increments it. The Mesh page
    displays it as fact.
72. **`withdraw()` announces an empty name, which `ingestFromPeer` treats as removal** (`:224–226`) —
    correct — but the *sender's own* row is not removed from its own `peers` map, so a device that
    withdrew still shows itself in its own market until the 15-minute expiry.

---

### F15. Model listings and transfers

**What it does.** A seller gossips a list of models for sale (id, name, kind, parameters, price in PSC
minor units, seller address, size) on 0x0D; a buyer checks out, pays (0x15 / ModelRefunds 0x14), then
pulls the weights with `GET /model/<name>` over PRISM_CONNECT to port 8080.

**User path.** Android: Model Shop → upload (`ModelUploadActivity` → `ModelListingStore.add` →
`P2pModelListings.announce`) / buy (`ModelCheckoutActivity` → `P2pModelTransfer.download`). Desktop:
Model Shop page (`ModelShopPage.kt:476–587`).

**Status: PARTIAL/BROKEN.** The listing gossip and the checkout flow work. The transfer itself fails
against a phone seller and fails for non-active models against a desktop seller.

**Bugs.**

73. **The phone serves models from a different directory than the one it stores and lists them in.**
    `PrismAiHost.serveModelFile` resolves `File(context.filesDir, "models")` (`PrismAiHost.kt:238`),
    while `P2pModelTransfer.modelsDir()` is `ModelRegistry.directory()` =
    `documentsDir()/Models` (`ModelRegistry.kt:60`) — the directory `ModelUploadActivity` copies into
    (`ModelUploadActivity.kt:187`) and the one the shop lists from. Every `GET /model/<name>` against a
    phone seller returns **404 "No such model on this peer"**. This is the single most damaging bug in
    the model-sale feature: the sale completes and the download cannot.
74. **The desktop serves only its *active* model, not its listed ones.** `MeshAiHost.serveModelFile`
    resolves the request against `PrismSettings.getLocalAiModelPath()` and 404s unless
    `active.name == name` (`MeshAiHost.kt:207–211`), while listings come from `ModelListingStore`
    (arbitrary files in the models directory). Selling any model other than the currently loaded one
    yields a 404 after payment.
75. **The download request line is not URL-encoded.** `"GET /model/${listing.name} HTTP/1.1"`
    (`P2pModelTransfer.kt:83`) against a server that URL-*decodes* the name (`PrismAiHost.kt:237`,
    `MeshAiHost.kt:201`). A listing name containing a space, `#`, `?` or `%` produces a malformed request
    line or a different name than the one stored; the destination filename is separately sanitised
    (`P2pModelTransfer.kt:56`), so buyer and seller disagree on the name.
76. **A refused transfer leaves a `.part` file.** The "no Content-Length" early return
    (`P2pModelTransfer.kt:91–98`) returns from inside the `try` without deleting `partial`; only the
    `catch` deletes it (`:112`). Repeated failures accumulate multi-GB orphans.
77. **Neither checks the transfer against the listed size.** `copy()` verifies only the HTTP
    `Content-Length` it was told (`:147–167`); `listing.sizeBytes` is gossiped, displayed, and never
    compared. A seller can advertise 4 GB and send 1 byte, and the buyer's file is accepted.
78. **There is no authorisation that the buyer paid.** `serveModelFile` checks only the path is inside
    the models dir (`PrismAiHost.kt:241`) — any peer that can reach 8080 can pull any model in that
    folder, paid or not, listed or not. On Android it does not even check
    `getP2pModelHostingEnabled()` (the desktop one does, `MeshAiHost.kt:195`), so a phone shares its
    model folder while the "Host my active model" toggle is off.

---

### F16. Coin offers

**What it does.** A gossip order book for coin swaps: 0x0E carries a peer's full offer list
(id, from/to symbol, amounts, maker address). PSC may only be quoted against USDC, enforced on both
posting and ingestion.

**User path.** Android: Wallet → Convert (`CoinConvertDialog.kt:78–160`) → post a matching offer.
Desktop: Mesh page → "coin offers" card (`MeshPage.kt:268–278`, read-only).

**Status: WORKING (discovery/quoting).** This is the most honest feature in scope: the KDoc states
plainly that it is not an atomic swap, that one side sends first, and that the UI must say so.

**Bugs.**

79. **A partial announcement deletes the peer's other offers.** `ingestFromPeer` replaces the whole
    per-peer list (`P2pCoinOffers.kt:131`), and `getAll()` expires a peer only when *all* its offers are
    stale (`:136`). A peer that cancels one offer and re-broadcasts the remainder is fine; a peer that
    broadcasts a filtered view (e.g. one pair) silently drops the rest from everyone else's book.
80. **`getAll()` does not clean up per-offer staleness, only per-peer.** A peer that keeps one offer
    fresh and lets another rot keeps the rotten one advertised indefinitely (`:135–138`).
81. **Duplicate offers from the same maker are not deduplicated across peers.** Matching sorts by rate
    (`:145`) and returns everything, so the same maker address appears once per gossiping peer.

---

### F17. Mesh AI RPC host (PRISM_CONNECT AI host)

**What it does.** Serves a peer's inference request over the reserved domain `ai-model.prism.p2p`:
Android `PrismAiHost` (dispatched by `PrismProxyServer`), desktop `MeshAiHost` (registered with
`MeshConnect`). Also handles `GET /model/<name>` (F15), protein work and cloud routes on the same
socket.

**User path.** Enable "Host my active model" (`KEY_P2P_MODEL_HOSTING_ENABLED`, off by default) →
another device selects this peer in the compute market or picks its hosted model
(`P2pModelRegistry.announce` at `SettingsActivity.kt:1418`) → `POST /generate`.

**Status: WORKING.** Streaming and non-streaming paths both exist, the pre-read-header trap is fixed
and documented, and both platforms implement the same protocol.

**Bugs.**

82. **Android serves `/model/` with the hosting toggle off** (see 78) — an authorisation gap, not a
    protocol one.
83. **`PrismAiHost` reads `Content-Length` but never bounds it.** `ByteArray(contentLength)`
    (`PrismAiHost.kt:133`) — a peer can request a multi-gigabyte allocation before sending a byte.
    The desktop's `readExactly` (`MeshAiHost.kt:245`) has the same shape.
84. **A generation has no deadline.** `AiManager.getResponse` runs with the socket open and the write
    errors swallowed (`PrismAiHost.kt:171–183`); a peer that connects and never reads keeps a
    generation thread plus the model busy. The comment says the peer "disconnected mid-stream — nothing
    more to do", but nothing stops the generation.

---

### F18. Mesh TLS

**What it does.** A per-device root CA (BouncyCastle, EC prime256v1) and a leaf minted per requested
domain with a SAN, so a mesh site can be `https://` on the mesh. `MeshConnect.serve` sniffs the first
byte and upgrades a `0x16` connection to TLS (`MeshConnect.kt:212–229`). The root is exported as PEM
for the user to install.

**User path.** Desktop `prism` CLI → TLS section (`Main.kt:2365–2389`, `tls export`) → install the PEM
(hint text at `MeshTls.installationHint()`); then browse `https://<site>.p2p` on the mesh.

**Status: WORKING on desktop; on Android the same feature exists but as a second, incompatible
implementation.** `MeshTls.install()` is called only from `Main.kt:308`, and `MeshTls` is referenced
only from `MeshConnect` (desktop) — so the core CA never exists in the phone process. The phone has its
own pair, `PrismSslManager` + `PrismCertificateManager` (`app/.../vpn/`), and `PrismProxyServer` uses it
to upgrade `0x16` connections (`PrismProxyServer.kt:173–190`). So mesh https does work on both
platforms — **with two different certificate authorities.**

**Bugs.**

85. **Two independent CAs for one mesh, so one root installation can never cover both devices.**
    `MeshTls` (core, BouncyCastle, `dataDir()/tls/prism_ca_{key,cert}.der`, `MeshTls.kt:106–157`) and
    `PrismCertificateManager` (Android, same BouncyCastle recipe, same *filenames*,
    `context.filesDir`, `PrismCertificateManager.kt:45–60`) both mint a "Prism Mesh Root CA". A browser
    or OS that trusts the desktop's root will still reject every phone-served leaf, and vice versa.
    Nothing in either codebase shares or exchanges the CA.
86. **Android silently falls back to the platform default context.** When leaf generation fails,
    `PrismSslManager` returns `SSLContext.getDefault()` (`:32`) instead of refusing — the server then
    presents no certificate for the requested domain and the peer's handshake fails with a generic TLS
    error in the *wrong* component, exactly the failure mode `MeshTls` was written to avoid
    (`MeshTls.kt:214–220`).
87. **The Android CA has no export path.** `exportRootCa`/`rootCaPem` exist only in `MeshTls`
    (`:165–180`) and are only invoked by the desktop CLI (`Main.kt:2377`); no equivalent appears in
    `PrismCertificateManager`, so a phone's CA can never be installed by the user and its leaves can
    never be trusted by a browser.
88. **The client trust manager accepts any certificate** (`MeshTrust`, `MeshTls.kt:276–280`, and
    `PrismSslManager.PrismTrustManager`, `:64–68`) — documented as deliberate, but the KDoc also says
    material "is separately encrypted to the user's own key before it is handed to a socket", which is
    not true of anything served through `MeshConnect`/`PrismProxyServer` (hosted sites, model files, AI
    answers are plaintext-under-TLS). The security claim in the comment is stronger than the code.
89. **`MeshTls.install` regenerates the CA on any read failure** (`:122–125`), silently invalidating
    every certificate any peer's browser has been taught to trust, with only a `warn` in the log.
90. **Leaf lifetime is 90 days and there is no renewal path** — `contextFor` caches per domain
    (`:94, :239`) for the process lifetime and nothing re-mints a cached leaf, so a long-running desktop
    serves an expired certificate it minted at startup.
91. **TLS is refused, not degraded, when no CA exists on desktop** (`MeshConnect.kt:214–220`) — correct
    behaviour, but there is no UI path to create the CA on Android, no diagnostics entry on the Mesh
    page, and the only way to learn this is a log line.

---

## 3. Ranked bug index — the 5 worst

1. **B20 — Android "WireGuard server" injects unauthenticated plaintext into the VPN.**
   `WireguardServerEngine.kt:42–77`. Any LAN datagram to UDP 51820 whose first byte is 4 has its bytes
   16..n handed to `PrivateDnsVpnService.injectPacket()`. No Noise handshake, no MAC, no session, no
   key. A remote attacker on the same Wi-Fi can inject arbitrary IP packets into the device's tunnel
   interface. It is also advertised as a WireGuard server to peers.
2. **B73 — model purchases cannot be downloaded.** `PrismAiHost.kt:238` vs `ModelRegistry.kt:60`. The
   phone seller serves `filesDir/models`; every model it lists and stores lives in
   `documentsDir/Models`. Every paid `GET /model/<name>` returns 404. Combined with B78 (no
   authorisation), the transfer path is simultaneously non-functional for buyers and unrestricted for
   anyone else.
3. **B61/B62 — network isolation reports success while executing nothing.**
   `NetworkIsolationEngine.kt:170–181`. `blockDevice`, `limitDeviceBandwidth` and
   `applyIsolationRules` all return `Result.success` and only write a log line; the `su -c` call is
   commented out. The access-point UI and the connected-devices UI both present these as applied
   controls.
4. **B39 — app whitelisting tears down the whole mesh.** `AppUsageMonitor.kt:50–53` →
   `PrismTunnelEngine.kt:249–259`. Opening one whitelisted app stops the port-8080 hosting listener,
   the proxy, the multiplexer and the WireGuard client for the entire device, then rebinds on the way
   back. Split tunnelling is implemented as a global tunnel kill switch.
5. **B66/B26 — the two headline "parity" paths are dead.** `MeshBridges.generateOnPeer` is never
   assigned (`MeshBridges.kt:145` / `MeshInference.kt:242`), so single-peer mesh inference — the default
   automatic-mode case — always returns null; and `L2tpEngine.kt:34–37` misparses the L2TP AVP by two
   bytes, so the L2TP responder can never answer an SCCRQ. Both are presented as working features.

Runners-up: B46 (access-point `::` separator corrupts saved configs), B48 (Stop is impossible after a
restart), B41 (usage-access permission never requested → whitelist silently inert), B9 (16 KB buffer
silently truncates DNS sync), B12 (phone never advertises its WireGuard port/key).

---

## 4. Comments that the code contradicts

The brief said not to trust comments. These are the ones that actively mislead:

| Claim | Where | Reality |
|---|---|---|
| "0x20 onwards is unused by every other subsystem" | `MeshComputeRegistry.kt:37` | 0x20–0x58 are heavily used (see §1) |
| "BOUND TO THE MESH INTERFACE, NOT 0.0.0.0 … only devices inside the Prism tunnel can reach it" | `MeshInference.kt:135–142` | `getLocalMeshIp()` returns the LAN address; the ggml RPC port is on the Wi-Fi |
| "the periodic exchange in `MeshCore` … makes it eventually consistent" | `MeshDns.kt:200–202` | `registerWithMesh()` is never called; there is no exchange |
| "Called once at startup. Loads whatever was saved." | `MeshDns.kt:71` | `MeshDns.install()` has no callers |
| "the desktop and a phone see each other's models with no change on either" | `MeshModels.kt:26` | `MeshModels.announce()` goes through `MeshCore` directly (`:128`) and is called only on desktop |
| "A REGISTRY WOULD HAVE CAUGHT IT" (opcode clashes) | `NebulaMeshSync.kt:75` | `MeshCore.register` silently replaces (`MeshCore.kt:120`) |
| "The Android server upgrades a connection whose first byte is 0x16 … it needs a certificate authority this build does not have" | `MeshConnect.kt:39–42` | `MeshTls` exists and desktop does the upgrade; Android has no `MeshConnect` server at all |
| "Minimal L2TP responder for identifying and starting L2TP control tunnels" | `L2tpEngine.kt:8` | It cannot identify an SCCRQ (off-by-two), and there is no tunnel |
| "Minimal IKEv2/IKEv1 responder" | `IkeEngine.kt:8` | IKEv1 is not handled; IKEv2 cannot complete an SA |
| "Note: Key exchange (Noise Protocol) is simplified for this implementation" | `WireguardServerEngine.kt:16` | There is no key exchange — the response is 91 zero bytes |
| "Network Range: 10.8.0.0/24" | `AccessPointPortalActivity.kt:249` | Wi-Fi Direct groups are 192.168.49.0/24 |
| "N bypassing VPN" | `WhitelistActivity.kt:60` | Nothing bypasses per-app; see B39/B40 |

---

## 5. Status roll-up

| # | Feature | Status |
|---|---|---|
| F1 | Mesh discovery / heartbeat / peer list / DNS gossip | **PARTIAL** — transport works; DNS gossip dead on desktop |
| F2 | Mesh roles (server vs client) | **PARTIAL** — split real; server announcement empty |
| F3 | Mesh kick / ban | **PARTIAL** — gossip-path only |
| F4 | WireGuard server (Android) | **BROKEN** — no crypto, packet injection |
| F4 | WireGuard client (Android) | **PARTIAL** — result discarded, config placeholder |
| F4 | WireGuard server (desktop) | **WORKING** — real `wg-quick`/`wireguard.exe`, self-tested |
| F5 | L2TP | **BROKEN** — never answers |
| F6 | IKEv2 | **BROKEN/SCAFFOLD** — malformed SA, fake KE |
| F7 | Proxy tunnel / PRISM_CONNECT | **WORKING** — with an open relay |
| F8 | Split tunnelling / whitelist | **BROKEN** — global teardown, no permission |
| F9 | Access point creation | **PARTIAL** — group created, no connectivity |
| F10 | Access point portal | **PARTIAL** — Stop fails after restart |
| F11 | Connected devices | **SCAFFOLD-ONLY** — hardcoded empty list |
| F12 | DNS override | **PARTIAL** — responder works, never reached |
| F13 | Network isolation | **SCAFFOLD-ONLY** — logs, returns success |
| F14 | Mesh compute market | **PARTIAL** — gossip/ledger real, single-peer path dead |
| F15 | Model listings / transfers | **PARTIAL/BROKEN** — transfer 404s |
| F16 | Coin offers | **WORKING** — discovery/quoting only, as documented |
| F17 | Mesh AI RPC host | **WORKING** — authorisation gap |
| F18 | Mesh TLS | **PARTIAL** — works on both, but two incompatible CAs; Android CA unexportable |

---

## 6. Twelve-line summary

1. Mesh transport: WORKING on both platforms and wire-compatible; DNS gossip is dead on desktop
   (`MeshDns.registerWithMesh` never called, `MeshDns.install` never called).
2. Desktop ignores the configured mesh port and bootstrap nodes (`MeshCore.portProvider`/
   `bootstrapNodes` never installed) and cannot be switched off (`Main.kt:364`).
3. Mesh roles: PARTIAL — a phone server announces no `serverPublicKey`/`wireGuardPort`; desktop does.
4. Kick/ban: PARTIAL — UDP gossip only; a banned node still reaches port 8080.
5. WireGuard server (Android): BROKEN — 92 zero bytes, then plaintext `injectPacket` of any byte-4
   datagram; every peer gets 10.8.0.2.
6. WireGuard client (Android): PARTIAL — `setState` result discarded, generated config still says
   `YOUR_PHONE_IP_HERE`.
7. WireGuard server (desktop): WORKING via wg-quick/wireguard.exe on `prism0` 10.8.0.1/24.
8. L2TP: BROKEN (AVP off-by-two at `L2tpEngine.kt:34`); IKEv2: BROKEN/SCAFFOLD (malformed SA, random
   "public key", no IKE_AUTH).
9. Proxy tunnel: WORKING, but `DesktopPrismProxyServer` is an authenticated open relay on 0.0.0.0.
10. Whitelist/split tunnelling: BROKEN — pausing the tunnel kills the port-8080 host and the whole VPN;
    usage-access permission is never requested.
11. Access point: PARTIAL (no internet, wrong advertised subnet); portal Stop fails after restart;
    connected devices SCAFFOLD-ONLY (`emptyList()`); DNS override PARTIAL; network isolation
    SCAFFOLD-ONLY (logs only, returns success).
12. Compute market PARTIAL (`generateOnPeer` never wired → single-peer offload always null); model
    transfers BROKEN (phone serves `filesDir/models`, models live in `documentsDir/Models`); coin offers
    WORKING; AI RPC host WORKING; mesh TLS PARTIAL — desktop `MeshTls` and Android
    `PrismSslManager`/`PrismCertificateManager` are two independent CAs, so no root trust can span both.

**Worst five:** (1) `WireguardServerEngine` unauthenticated packet injection; (2) model transfers 404
because seller and store disagree on the models directory; (3) `NetworkIsolationEngine` returns success
while executing nothing; (4) the app whitelist tears down the entire tunnel; (5) `generateOnPeer` never
installed, so single-peer compute offload silently never runs — and L2TP can never answer a handshake.
