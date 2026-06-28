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

## TRUE root cause of the "payload never arrives" — FRAGMENTATION (supersedes the "link-reliability" guess)

The warm-up fixes got routing onto the mesh (`session=true`, "Routing payment-broadcast REQUEST via mesh"), but
the helper's `didReceivePaymentBroadcastRequest` still never fired. A 4-investigator adversarial code audit
found the real reason — and it is **our payload size**, not the link per se:

- The mesh send primitive is **identical** for QR-verification and payment-broadcast
  (`BluetoothMeshService.sendNoisePayloadToPeer`, `:1128`). Verification, announce (type 1), keepalive
  (type 33), the Noise handshake, and the favorite-DM all deliver — and all are **single sub-512-byte packets**.
- `PaymentBroadcastRequest.encode` carried the raw tx as a **hex string (2× bytes)**. A 1-in/2-out ~226-byte
  signed tx → NoisePayload.data ≈ 565 B → encrypted+framed ≈ **680 B**, which **exceeds the 512-byte
  fragmentation threshold** (`FragmentManager`, `MAX_FRAGMENT_SIZE=469`) → **2 fragments**. Payment-broadcast
  is the **only** thing in the whole working set that fragments, and the **first time fragmentation was ever
  exercised on this link**.
- The mesh fragment-send path (`FragmentingPacketSender.send`, `:54-92`) is **fire-and-forget**: no GATT
  write-completion await (the client GATT callback has no `onCharacteristicWrite`, the server no
  `onNotificationSent`), just a blind 20 ms gap between fragments, and it **aborts the whole set on the first
  `sendSingle`→false** (`:68-71`, log `"Stopping fragmented send … after 1/2 fragments"`). Android allows only
  one outstanding GATT op per connection, so on a slow airplane-mode link the 2nd fragment's write is rejected
  and the set is abandoned at 1/2; the helper stores fragment 0, never reaches `expectedTotal`, and silently
  GCs the set after ~30 s. There is **no fragment-level ACK/retransmission anywhere**. (Confidence ~75% on the
  exact abort mechanism vs. plain air-loss of one fragment; the fix below resolves the failure either way.)
- Receive/dispatch and all helper gates were **cleared**: dispatch is fully wired
  (`MessageHandler.kt:173-176` → `MeshCore.kt:344` → `ChatViewModel.kt:1529`), reassembly re-dispatches a
  `ttl=0` packet correctly, `SecurityManager` does not drop FRAGMENT/NOISE_ENCRYPTED, the two fragments get
  distinct dedup ids, and every helper gate sits **downstream** of the unconditional `💸 Payment broadcast
  request received` log (`MessageHandler.kt:174`) and returns a result on decline. So "helper totally silent"
  can only mean the payload never reassembled — i.e. transport, not app-layer.

## Fix shipped (Option A — shrink below the fragmentation threshold)

`PaymentBroadcastRequest` now carries the **raw tx and the expected txid as raw BYTES** in the TLV codec
instead of ASCII hex (`PaymentBroadcastPacket.kt`: enum `RAW_TX(0x02)`, `encode` uses `hexToBytesUnchecked`,
`decode` re-hexes via `toLowerHex`). The public `rawTransactionHex`/`expectedTxid` **String** fields are
unchanged, so every consumer is byte-identical (sender coordinator, `BroadcastHelperService` decode +
`sendrawtransaction` + txid cross-check, and the Nostr path which forwards the same opaque encoded bytes). For
a 1-in/2-out tx the frame drops **680 → ~420 B ≤ 512 → SINGLE PACKET**, so payment-broadcast now behaves
exactly like the already-reliable verification flow.

- **Money-safe:** the tx is already signed before `encode()`; bitcoinj never signs. Pure framing change; the
  hex handed to the node and the anti-substitution txid cross-check are byte-identical to before.
- **Cross-version:** both phones must run the **same** new build — a stale build decodes the new binary TLV as
  non-hex and `decode()` returns null (fail-closed, no malformed tx broadcast). Fine for a synchronized flash.
- **Tests:** `PaymentBroadcastPacketTest` round-trips + a new "carried as binary so a typical send does not
  fragment" assertion (encoded < raw-hex length, < 380 B). `:app:testDebugUnitTest :app:assembleDebug` green;
  signer + SPV key-import canaries unchanged.
- **Scope limit (honest):** this makes single-input (≤~4-output) sends single-packet — the demo's 5-DOGE
  1-in/2-out qualifies. A **multi-input** tx (raw >~284 B) still fragments; for those, Option B (below) is
  the safety net.

## Fix shipped (Option B — make fragmented directed sends reliable, transport-only)

Even with Option A, multi-input txs (and any large PM) still fragment, so the fragment transport itself was
hardened. The real drop mechanism (refined from the diagnosis): payment-broadcast takes the **broadcast**
path (`broadcastPacket`), whose `FragmentingPacketSender` lambda always returns `true` — so the abort-at-1/2
never fires there; instead, inside `broadcastSinglePacketInternal`, fragment 2's
`notifyCharacteristicChanged`/`writeCharacteristic` returns `false` ("busy", because a GATT connection allows
ONE outstanding op and fragment 1 is still draining) and was **silently dropped with no retry**.

- **Broadcast/directed arm** (`BluetoothPacketBroadcaster`): new `notifyDeviceWithRetry` /
  `writeToDeviceConnWithRetry` suspend wrappers retry a busy write up to 8× with a 50 ms backoff (~350 ms
  max) before giving up; swapped in at the 4 DIRECTED/source-routed call sites only. The broadcast-to-all
  loop (single-packet announces) is left untouched so announces are never slowed.
- **Relay/targeted arm** (`FragmentingPacketSender`): the targeted path (`sendSinglePacketToPeer` via
  `sendToPeer`/`sendPacketToPeer`, used by `PacketRelayManager` for the next hop) propagates the real write
  result, so the fragment loop now **retries the same fragment** (8× / 50 ms) instead of aborting the whole
  set on the first `false`. This covers a 2-hop offline relay (sender→relay→helper), not just the 1-hop repro.
- Adversarially reviewed (verdict: ship-with-nits): bounded (no deadlock; retries run in the serialized
  broadcaster actor / the send coroutine), the receiver reassembles duplicate fragments idempotently
  (`FragmentManager` keys by index), transport-only (no signer/money impact). `:app:testDebugUnitTest
  :app:assembleDebug` green.
- **Deeper follow-up (still deferred):** await `onCharacteristicWrite`/`onNotificationSent` for true
  write-completion flow control (neither callback is wired today — the 50 ms×8 window is a heuristic),
  fragment-level ACK/retransmit, and key `MAX_FRAGMENT_SIZE` to the negotiated MTU. Do that only if on-device
  shows residual loss under the retry window.

## Root cause #2 (found during the on-device proof) — the bridge dropped the payment-broadcast callbacks

On-device, with Option A delivering the request as a single packet, the helper logged
`💸 Payment broadcast request received` (decrypted, in `BluetoothMeshService.MessageHandler`) but then did
**nothing** — no `handleRequest`, no node broadcast, no result. Cause: the app's mesh delegate chain is
`BluetoothMeshService → UnifiedMeshService (bridge) → ChatViewModel`. `UnifiedMeshService` forwards
`didReceiveVerifyChallenge/Response` to its delegate but was **missing the forwards for
`didReceivePaymentBroadcastRequest`/`Result`** — so they fell through to the empty `MeshDelegate` defaults and
were silently dropped at the bridge, before ever reaching `ChatViewModel`. This dead-ended the BLE relay in
**both** directions (the request at the helper, and the result at the sender → sender times out with "No
connected peer accepted"). It is why verification works over mesh but payment-broadcast didn't, independent of
fragmentation. Fix: add the two missing overrides to `UnifiedMeshService`, mirroring the verify forwards.

## ✅ PROVEN END-TO-END ON-DEVICE (2026-06-28) — offline Bluetooth-only send works

Two phones, sender (S24) **offline** (Wi-Fi off, BLE on), helper (Pixel 3) with a testnet node via USB
reverse tunnel. With Option A + Option B + the UnifiedMeshService forward, and a warmed symmetric Noise
session, `doge-spv-peer-broadcast nceDC… 5`:

- Sender: `Routing payment-broadcast REQUEST via mesh`, `ANDROID ENCRYPT 308 → 328 bytes` — **single packet,
  no `Fragmenting…` line.**
- Helper: `💸 Payment broadcast request received (307 bytes)` → `handleRequest` → node `sendrawtransaction`
  → `Routing payment-broadcast RESULT via mesh`.
- Node: tx `3e1f64af1159c29a0aa04915d1fa8d9d8c036b73286251590518907a253136b9` accepted (5.00000000 DOGE to
  the node address) — **in the mempool**.
- Sender: `TERMINAL=Claimed(txid=3e1f64af…)` (Claimed = the correct single-helper terminal state).

Operational notes from the proof: both phones must run the **same** new build; a fresh **symmetric** Noise
session is required (restarting one app desyncs it — sender `session=true` while helper `session=false` →
helper can't decrypt; restart **both** so the warm-up does one clean handshake); the helper's `ChatViewModel`
must be a **live** instance (a stale/old VM held by the mesh service no-ops the delegate — force-stop +
relaunch); set `stay_on_while_plugged_in` so the phones don't sleep/relock during the adb-driven test.

### On-device re-test (decisive measurement still pending)
Flash the **new build to BOTH phones** (they currently run `6c7222d`/`71e19d5`). Bootstrap a Noise session
(favorite-DM), S24 → Airplane ON + Bluetooth ON (NOT `svc wifi disable`), helper opted-in for testnet + mutual
favorite, sender Connection = "Built-in" (SPV). Console: `doge-spv-peer-broadcast <addr> <amt>`.
- **SUCCESS:** sender logs "Routing payment-broadcast REQUEST via mesh" and **NO** "Fragmenting packet type …";
  helper logs `💸 Payment broadcast request received from … (N bytes)` → broadcasts → `ACCEPTED` txid returns.
- If it still fragments (e.g. a multi-input tx) you'll see "Fragmenting … into 2 fragments" then "Stopping
  fragmented send … after 1/2" on the sender + a lone "Received fragment 0/2" on the helper = the Option-B defect.

## ⚡ Quick retry runbook (BLE offline send) — copy/paste

Distilled from the 2026-06-28 re-test. **The whole thing hinges on the Noise SESSION establishing over BLE.**
On a congested/slow link (other BLE devices nearby, phones far apart) the handshake **does not complete** and the
relay silently falls back to **Nostr** (`Routing … REQUEST via Nostr`) — which needs internet, so it is NOT the
air-gapped proof. Best success conditions: **phones within ~1 m, quiet RF (close other BLE apps), same build.**

Serials/PINs here: S24 `RFCX81GNBRE` (PIN 5555, the SENDER), Pixel 3 `89VX0HPX1` (the HELPER). ADB at
`C:\Users\rober\AppData\Local\Android\Sdk\platform-tools\adb`. Testnet node = `dogecoin-qt -testnet`
(RPC 127.0.0.1:44555, user `apstats`, pw in `%APPDATA%\Dogecoin\dogecoin.conf` — never echo it).

```bash
ADB=".../platform-tools/adb.exe"; S24=RFCX81GNBRE; PIX=89VX0HPX1
sj(){ "$ADB" -s "$1" shell "am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver --es cmd '$2'" >/dev/null 2>&1; }

# 0) node up?  ("...\dogecoin-cli.exe" -testnet getblockcount)   helper tunnel:
"$ADB" -s $PIX reverse tcp:44555 tcp:44555
"$ADB" -s $S24 shell settings put global stay_on_while_plugged_in 7   # don't sleep/relock mid-test

# 1) HELPER (Pixel): testnet + node RPC + helper on
sj $PIX "doge-network testnet"
PW=$(grep -i '^rpcpassword=' "$APPDATA/Dogecoin/dogecoin.conf"|cut -d= -f2-|tr -d '\r')
"$ADB" -s $PIX shell "am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver --es cmd 'doge-rpc-set http://127.0.0.1:44555 apstats $PW'"
sj $PIX "doge-helper-enable 1"

# 2) SENDER (S24): unlock if Samsung killed it behind the keyguard, then testnet + SPV
"$ADB" -s $S24 shell input keyevent KEYCODE_WAKEUP; "$ADB" -s $S24 shell input swipe 540 1600 540 600
"$ADB" -s $S24 shell input text 5555; "$ADB" -s $S24 shell input keyevent KEYCODE_ENTER
"$ADB" -s $S24 shell monkey -p com.bitchat.droid.debug -c android.intent.category.LAUNCHER 1
sj $S24 "doge-network testnet"; sj $S24 "doge-spv-start"
sj $S24 "myid"; sj $PIX "myid"   # BOTH must show connectedPeers=1 (BLE linked). favorites must be mutual=true.

# 3) BOOTSTRAP THE SESSION, then VERIFY it (the make-or-break step)
sj $S24 "candidates"                                  # note the helper's noise prefix from `favorites`
sj $S24 "sendfav <helperNoisePrefix> 1"               # e.g. a3cefc1e — triggers initiateNoiseHandshake
sleep 40
"$ADB" -s $S24 logcat -d | grep -a "hasEstablishedSession(<helperPeerID>)"   # MUST read true
#   false → handshake didn't complete → move the phones closer / kill other BLE / restart BOTH apps and retry.
#   Do NOT proceed to step 4 until it reads true, or the send will go over Nostr.

# 4) (only after session=true) GO OFFLINE on the S24, then send.
"$ADB" -s $S24 shell cmd connectivity airplane-mode enable    # then make sure Bluetooth is ON (toggle in UI;
#   `svc wifi disable` is WRONG on the S24 — it drops BLE. Airplane + BT-on preserves the mesh.)
sj $S24 "doge-spv-peer-broadcast nceDCkWAP9tSktE5TJ5X6LVmD2r3HwiAXN 1"
```

**PASS:** S24 logs `Routing payment-broadcast REQUEST via mesh` (NOT "via Nostr") + NO `Fragmenting…`; Pixel logs
`💸 Payment broadcast request received (N bytes)` → node `sendrawtransaction`; `TERMINAL=Claimed(txid=…)`; the node
has the tx (`dogecoin-cli -testnet getrawtransaction <txid>`). **FALL-BACK seen as Nostr** ⇒ the session wasn't up
(step 3 lied or regressed) — that path still relays the tx but over the internet, so it does NOT prove air-gapped BLE.

**Restore after:** `"$ADB" -s $S24 shell cmd connectivity airplane-mode disable`; `sj $PIX "doge-helper-enable 0"`.
