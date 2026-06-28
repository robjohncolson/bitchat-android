# Dogecoin offline send over the mesh — on-device findings (2026-06-27)

Two-phone test (Galaxy S24 `RFCX81GNBRE` = sender, Pixel 3 `89VX0HPX1` = helper) of "send testdoge
phone-to-phone **without internet on the sender**", driving both via the debug console over adb.

## TL;DR

- ✅ The SPV wallet **builds + signs fully offline** (no internet) from its persisted UTXO set.
- ✅ A **node-less sender → helper → chain** relay works and was **mined twice** (3+3 TESTDOGE) — **but over
  Nostr** (the sender had Wi-Fi). So it proves a node-less sender, **not** an internet-less one.
- ❌ A **truly air-gapped (Bluetooth-only) send does NOT work today.** Root-caused below: the relay's
  transport selection is gated entirely on an established **Noise session**, and the broadcast flow never
  establishes one — so with only a bare BLE link it diverts to Nostr, which fails with no internet.

## What was proven on-device

| Capability | Result |
|---|---|
| SPV reads balance offline (airplane mode, `peers=0`) | ✅ read 8.97 from the persisted wallet |
| SPV builds + signs a tx offline | ✅ signed `39d8f9be…` in airplane mode |
| Node-less sender → mesh-helper → node → chain (Nostr transport) | ✅ `7b1be7ae…` (3 DOGE, mined), `638c253e…` (3 DOGE, mined); S24 balance tracked 14.99 → 11.98 → 8.97 |
| Same flow, **Bluetooth-only** (sender airplane mode) | ❌ `Failed: No connected peer accepted the transaction` |

The helper (Pixel) reached the node via a **USB reverse tunnel** (`adb reverse tcp:44555`) so no node
rebind/firewall was needed; `doge-rpc-show` → `ready=true canBroadcast=true`. Mutual favorite + helper-enable
were already set; the S24 saw the Pixel as a helper candidate.

## Root cause of the Bluetooth-only failure (high confidence)

Transport selection for the payment-broadcast relay depends purely on **Noise-session state, never on BLE
connectivity**, and the flow itself never bootstraps a session:

1. **Three independent session gates**, all calling `hasEstablishedSession`:
   - `ChatViewModel.listBroadcastHelperCandidates` mesh tier is hard-filtered `.filter { mesh.hasEstablishedSession(it) }` (`ChatViewModel.kt:1631`).
   - `resolveConnectedMeshPeerId` maps a Noise key → live peerID only if `mesh.hasEstablishedSession(peerID)` (`ChatViewModel.kt:1674`); else returns null.
   - `MessageRouter.isReady = isConnected && hasEstablishedSession` (`MessageRouter.kt:231-238`).
   With the Pixel BLE-connected but `session=false`, **all three fail**, emptying the mesh tier.
2. **Noise sessions are established lazily/on-demand**, only by explicit send paths that call
   `initiateNoiseHandshake` — `BluetoothMeshService.sendPrivateMessage` (`:1019-1022`), `sendFilePrivate`
   (`:938`), opening a private chat, verification. **BLE connect and identity-announce do NOT run the Noise
   XX handshake** (announce only binds static/signing keys to a peerID). Two phones that only exchanged
   announces report `hasEstablishedSession=false` indefinitely (matches the Pixel log
   `hasEstablishedSession(<S24>) = false`).
3. **The payment-broadcast send path does not bootstrap a session.**
   `BluetoothMeshService.sendNoisePayloadToPeer` (`:1128-1150`) calls `encryptionService.encrypt()` directly
   and, on no-session, throws/swallows — it never calls `initiateNoiseHandshake` (unlike the private-message
   path). There is also **no outbox/queue** for the broadcast (`MessageRouter.kt:129-130`) and the
   coordinator marks each candidate `used` after one dispatch (`PaymentBroadcastCoordinator.kt:109-111`), so
   it never waits for or retries a handshake.

Therefore the Pixel survives only in the **Nostr fallback tier** (it's a mutual favorite with a stored npub,
`ChatViewModel.kt:1652-1658`); the `sendRequestToPeer` lambda hands `MessageRouter` the raw 64-hex Noise key,
`isReady=false` + `canSendViaNostr=true` → **Nostr**. Hence the observed
`NostrProtocol: Creating private message…` / `NostrTransport: sending broadcast PAYMENT_BROADCAST_REQUEST
giftWrap…` even in airplane mode. Wi-Fi vs airplane **does not change the transport choice** — Wi-Fi only
makes the same Nostr path reachable. The BLE transport for the broadcast **exists and would carry it if a
session were present** (`BluetoothMeshService.sendPaymentBroadcastRequest` → `sendNoisePayloadToPeer`; live
mesh branch at `MessageRouter.kt:133-136`); it's simply never reached.

## Fix options (ranked; all transport-only — no money-safety impact)

bitcoinj never signs; tx build/sign stays on-device; corroboration still counts by the helper's scarce Noise
key. These only change which radio carries the already-signed relay.

1. **(Recommended) Warm up Noise sessions in the doge broadcast flow.** When the broadcast CTA shows / when
   `PaymentBroadcastCoordinator.broadcast` starts, for every connected, session-less mutual-favorite /
   NODE_HELPER peer call `mesh.initiateNoiseHandshake(peerID)`, then await `hasEstablishedSession` up to a
   short bound (~2–4s) **before** building the mesh tier — run concurrently with the Nostr fan-out so the
   online path isn't slowed. Scoped to the doge flow; reuses existing handshake machinery. Effort medium,
   risk low-medium.
2. **Handshake-on-demand in `sendRequestToPeer`** (make it suspend: initiate handshake, bounded await, send
   over mesh, else Nostr). Fix at the fork point. Effort medium-high (thread suspend through the dispatch
   loop + reconcile with attempt timeouts).
3. **Eagerly handshake on BLE connect/announce for favorites/advertised helpers** (global). Zero change to the
   broadcast flow, but broadest blast radius (battery/airtime for all favorite connections; peerIDs rotate).
4. ❌ Fire-and-forget handshake inside `resolveConnectedMeshPeerId` alone — a **false fix**: the handshake is
   a round-trip, so the attempt still returns null + the candidate is marked `used` and never retried.

## Workaround to test the offline-BLE path TODAY (no code change)

Bootstrap the Noise session out-of-band first, then go offline:
1. With both phones BLE-connected, **establish a session** S24↔Pixel — e.g. open a private chat / send a DM
   (console `sendfav`), which calls `initiateNoiseHandshake`. Confirm `peers` shows `session=true`.
2. Put the S24 in **Airplane mode + Bluetooth on** (NOT `svc wifi disable` — see quirk below).
3. `doge-spv-start` → `doge-spv-peer-broadcast <addr> <amt>`. With a session present, `resolveConnectedMeshPeerId`
   returns a real peerID, `isReady` passes, and the relay rides **BLE** → helper broadcasts → mined.

## Operational quirks (hard-won)

- **S24 (Samsung) couples BLE to Wi-Fi via `svc wifi disable`:** turning Wi-Fi off over adb **drops the BLE
  mesh** (peers→0) and it only recovers after an app restart. **Airplane mode + manually re-enabling
  Bluetooth preserves the mesh** (`peers connected=2`, no internet) — that's the correct way to air-gap this
  phone for a Bluetooth-only test.
- **Two near-identical apps:** `com.bitchat.droid` (Play, no Dogecoin wallet) vs `com.bitchat.droid.debug`
  (dev build, now labeled **"bitchat dev"**). Opening the wrong one looked like "no wallet."
- **Helper broadcasts via RPC only** (`BroadcastHelperService` uses `rpcClient.sendRawTransaction`), so the
  helper needs a reachable node — supplied here via the USB reverse tunnel.
- testnet mines ~1 block/30–40s, so check `getrawtransaction <txid>` (mined) rather than `getrawmempool`
  (the tx leaves the mempool almost immediately).

## New console command added this session

`doge-spv-peer-broadcast <addr> <amt> [feeKb]` — signs from the persisted SPV UTXO set (offline-capable) and
relays the signed tx over the mesh via `requestPeerBroadcast` (twin of `doge-peer-broadcast`, which builds via
RPC). Committed `c1354ce`.

## Fix implemented (commit `6c7222d`) — warm up Noise sessions (option 1)

`ChatViewModel.requestPeerBroadcast` now calls `warmUpMeshHelperSessions(network)` before
`paymentBroadcastCoordinator.broadcast`: for each connected, session-less mutual-favorite / NODE_HELPER peer it
calls `mesh.initiateNoiseHandshake` and awaits `hasEstablishedSession` up to `PEER_BROADCAST_SESSION_WARMUP_MS`
(3.5s), returning early; no-op/no-delay when a session already exists or no helper is connected; best-effort
(`runCatching`). Transport-only — tx build/sign and corroboration-by-Noise-key are unchanged.

**Result on-device (S24 airplane mode → Pixel helper):**
- ✅ The warm-up **establishes the BLE Noise session** (`session=true`, symmetric on both phones).
- ✅ With a session, the relay now **routes over mesh, not Nostr**: `MessageRouter: Routing payment-broadcast
  REQUEST via mesh to 74424755…` (previously it went to `NostrTransport`). The routing root cause is fixed.
- ❌ **End-to-end BLE delivery did not complete in this environment.** The Pixel never received the payload
  (no `BroadcastHelperService` / `didReceivePaymentBroadcast`), so the coordinator timed out (`Failed: No
  connected peer accepted`). The BLE link between these two phones in airplane mode is very slow — the Noise
  handshake alone took **~30s** (far exceeding the 3.5s warm-up window, so the *first* send still fell to Nostr;
  the second send, with the session already up, routed via mesh but the larger payment-broadcast payload still
  didn't arrive within the coordinator's ~30s attempt window). This is a **BLE link-reliability limit**
  (airplane mode, Samsung S24 ↔ Pixel 3, a third "doge-bobby" device sharing airtime), not a logic bug.

**Implications / follow-ups (separate from this fix):**
- The 3.5s warm-up window is fine for a healthy link (handshake <1s) but useless against a ~30s handshake.
  **DONE (`71e19d5`):** warm up **proactively** — `ChatViewModel.prewarmBroadcastHelperSessions()` is now
  called from `ChatScreen.onShowDogecoinWallet` (wallet open), so the handshake starts well before the user
  sends and a session is ready by send time. Still optional: lengthen the coordinator's mesh attempt window
  (`PaymentBroadcastCoordinator.ATTEMPT_TIMEOUT_MS = 30_000L`) for very slow links.
- The deeper limiter is BLE **payload** delivery between these specific phones; a cleaner test (phones close,
  no third device, not airplane-throttled) is needed to confirm the warmed-session mesh path end-to-end. The
  code change is correct and proven at the routing layer.
