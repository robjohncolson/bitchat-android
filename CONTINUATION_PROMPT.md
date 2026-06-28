# Continuation Prompt: Bitchat Android Dogecoin Wallet

Continue work in:

```text
C:\Users\rober\Downloads\Projects\bitchat-android
```

Goal: continue the Dogecoin wallet integration in Bitchat Android. Work autonomously, inspect the
relevant files first, keep changes focused, do not revert unrelated user changes, and verify with
focused Gradle + on-device checks. **Money path + signed mesh protocol — review carefully.**

## ✅ DONE (2026-06-28, HEAD `487ee90`) — offline Bluetooth send PROVEN end-to-end; NEXT = Phase 4 mainnet

The "send testdoge phone-to-phone with the SENDER fully offline (Bluetooth-only)" thread is **COMPLETE and
PROVEN ON-DEVICE** (tx mined, 1 conf). It took THREE transport-only fixes (tx still SPV-built+signed on-device,
bitcoinj never signs):
- **`f5780e3` (Option A)** — `PaymentBroadcastPacket` carries the raw tx + txid as **bytes, not ASCII hex**, so
  a typical send is ~420 B ≤ 512 → **single packet, no BLE fragmentation** (was ~680 B → 2 fragments → the 2nd
  was silently dropped, so the helper never reassembled).
- **`38735de` (Option B)** — retry busy GATT writes in `BluetoothPacketBroadcaster`
  (`notifyDeviceWithRetry`/`writeToDeviceConnWithRetry`, 8×50 ms, DIRECTED sends only) + retry-the-fragment
  (not abort-the-set) in `FragmentingPacketSender`. Hardens multi-input/larger fragmented sends.
- **`487ee90` (the actual final blocker)** — `UnifiedMeshService` (the `BluetoothMeshService → ChatViewModel`
  delegate bridge) forwarded `didReceiveVerifyChallenge/Response` but was **MISSING the forwards for
  `didReceivePaymentBroadcastRequest/Result`** → a decrypted request was silently dropped at the bridge (empty
  `MeshDelegate` defaults) in BOTH directions. That's why verification works over mesh but payment-broadcast
  never completed — independent of fragmentation. The `💸 received` log fired but `handleRequest` never ran
  until this was added.
- **PROOF:** sender (S24) Wi-Fi OFF + BLE ON → `Routing REQUEST via mesh` + `ENCRYPT 308→328` (single packet,
  NO `Fragmenting…`) → helper `💸 received (307 bytes)` → node `sendrawtransaction` → `Routing RESULT via mesh`
  → node mempool tx `3e1f64af1159c29a0aa04915d1fa8d9d8c036b73286251590518907a253136b9` (5.0 DOGE) → **mined (1
  conf)** → sender `TERMINAL=Claimed(txid=3e1f64af…)`. Claimed = correct single-helper terminal state.
- **On-device gotchas (also in `docs/dogecoin-offline-mesh-relay-findings.md`):** both phones MUST run the same
  build (binary TLV fails-closed cross-version); a FRESH SYMMETRIC Noise session is required — restarting ONE
  app desyncs it (sender `session=true`, helper `session=false` → helper can't decrypt → no `💸`), so restart
  BOTH for one clean warm-up handshake; the helper's `ChatViewModel` must be a LIVE instance (a stale VM held by
  the mesh service no-ops the delegate → force-stop+relaunch); `adb shell settings put global
  stay_on_while_plugged_in 7` keeps the phones awake/unlocked over adb (PINs: Pixel 5555, S24 5555); the warm-up
  awaits only 3.5 s so the FIRST send may fall to Nostr while the handshake finishes in bg — a 2nd send rides
  mesh; `broadcast-test` does NOT warm up (calls the coordinator directly) — only `doge-spv-peer-broadcast` does.
- **Optional deeper Option-B follow-up (DEFERRED, not needed now):** true `onCharacteristicWrite`/
  `onNotificationSent` write-completion flow control + fragment-level ACK/retransmit + MTU-keyed fragment size.
- **DEVICE STATE after the proof:** S24 `RFCX81GNBRE` = sender, Wi-Fi OFF + BT ON (offline), SPV ~3.97 TESTDOGE
  at `nUEBj7Wi…` (was 8.97, sent 5). Pixel 3 `89VX0HPX1` = helper, node via USB tunnel `adb reverse tcp:44555`
  → `127.0.0.1:44555`, helper-enabled, mutual favorites. Both run HEAD `487ee90`. Node = testnet. **Restore for
  normal use:** re-enable S24 Wi-Fi; Pixel `doge-rpc-set http://10.0.0.24:44555 apstats <pw>` if you drop the
  USB tunnel; `doge-helper-enable 0` to stop helping.

**✅ PHASE 4 COMPLETE (2026-06-28) — REAL MAINNET SPV SEND PROVEN.** Steps 1-3 all done on real mainnet:
(1) mainnet checkpoint asset + feasibility (chainwork fits 12 bytes), (2) read-only soak PASSED (3 clean
`doge-spv-crosscheck` agreements vs the mainnet node across mempool→confirmed for a 48.82-DOGE funding), (3) a
real user-authorized mainnet send — `doge-spv-mainnet-send D7Svjsok… 10 CONFIRM` → built+signed on-device,
BROADCAST CLAIMED, the mainnet node received 10 DOGE (tx `829d219b…`), SPV tracked the 38.81254 change and the
change cross-check PASSED. The node-less on-device SPV money path works end-to-end on MAINNET. Along the way
fixed TWO real SPV bugs (checkpoint asset loaded the wrong chain — see below) + added `doge-reset-mainnet`
(acknowledgment-gated fresh mainnet key) and `doge-spv-mainnet-send` (the ONE confirmation-gated mainnet
broadcast channel; `mainnetAuthorized` flag, default false; DataSource/UI 4-layer block UNTOUCHED). See the
"NEXT STEP: PHASE 4" section below + memory for the full trail.
**✅ FULL UI MAINNET ENABLEMENT DONE (`b5730c1`)** — mainnet sending now works from the WALLET UI, behind the
already-wired gates: `DogecoinSpvDataSource` 3-param `broadcast(…, mainnetAuthorized)` (2-param interface stays
false); `broadcastSignedTransaction` passes true ONLY after WIF-backup (re-checked there for defense-in-depth)
+ mainnet/high-fee/policy-unavailable acks (`canExportOrBroadcastSignedDogecoinTransaction`); `spvSendReady` +
`canBroadcastViaSpv` no longer exclude mainnet; DataSource `require` + under-lock service check kept as
defense-in-depth. Adversarially reviewed (subagent): SHIP, no gate-bypass, all 4 gates enforced. NOT tapped
through on-device (the send button is LIVE → blind adb taps risk a real send; user does the UI send). **⏩
REMAINING (optional, non-blocking):** confirm the console 10-DOGE send `829d219b…` mined (in-mempool at handoff,
valid + ~44× relay fee, awaiting a pool — mainnet block production was slow); WIF-backup/Tor-for-SPV polish.

## CURRENT FOCUS: self-contained SPV wallet (no node, no paid key)

The active work is making the Dogecoin wallet self-contained via an **SPV light client** (bitcoinj +
libdohj), added ALONGSIDE the existing RPC + explorer backends, sharing the same on-device key. The
agreed end state: free, no user-run node, no paid explorer key, keys on-device. Branch
`dogecoin-m2-pay-nickname`. **Last green commit: `d81c5d5`** (wallet UI "Coin" redesign — see the dated UI
note below; on top of `933d26b` Tor-for-SPV routing + WIF-backup UX polish —
see the dated note below; on top of `b5730c1` Phase 4 FULL UI mainnet enablement, gated +
adversarially reviewed; on top of `f622330` gated `doge-spv-mainnet-send` console send PROVEN on mainnet, the
SPV checkpoint-network fixes `ec73ea9`/`c5526b2`, `doge-reset-mainnet`,
`9229dc7` mainnet checkpoint validated, `6a9f7df` mainnet checkpoint asset, `2348972` doge-spv-crosscheck,
`487ee90`/`38735de`/`f5780e3` offline Bluetooth send; `:app:testDebugUnitTest` + `:app:assembleDebug` BUILD
SUCCESSFUL; `git diff --check` clean). Working tree clean.

**✅ WALLET UI "COIN" REDESIGN — LAYOUT COMPLETE (2026-06-28, commits `034ca7c`→`d81c5d5`).** A Dieter-Rams ×
Dogecoin redesign of `DogecoinWalletSheet`, picked by the user from a 3-direction design workflow ("Coin"
direction). Verified on-device via adb-driven screenshots on the Pixel 3 (the funded/syncing S24 was away).
**New files:** `features/dogecoin/ui/DogecoinWalletTheme.kt` (a wallet-scoped palette: warm ink-on-paper +
two grays, gold `#C2A633` the SINGLE accent — ring + active state ONLY; light+dark; provided via a
CompositionLocal AND a nested MaterialTheme so the rest of the app keeps its terminal-green theme; follows the
app's effective dark/light via ambient background luminance; wraps content in a paper Surface so bare text
defaults to ink not the app green — both were real bugs the first screenshot caught) and
`features/dogecoin/ui/ConfirmationRing.kt` (the one ornament: a gold ring, IDLE solid circle / SYNCING
continuous arc / CONFIRMING N-of-target discrete segments, with a contentDescription a11y fallback).
**The redesign (all in `DogecoinWalletSheet.kt`):** the whole sheet is wrapped in `DogecoinWalletTheme`; a
`DogeWalletAction` view-state (NONE/SEND/RECEIVE/SETTINGS) drives a "one thing at a time" focal-swap — DEFAULT
shows the balance inside the gold ring + a "backend·sync·Tor" status strip + `[Send] [Receive]` + a persistent
"! Back up your key" mainnet chip; SEND/RECEIVE/SETTINGS each REPLACE the focal area with a "‹ back" row;
a header GEAR opens SETTINGS (connection/backend, network, node RPC, SPV status, balance+coins+activity, helper,
corroboration, reset/import-WIF — everything advanced). Selected segmented buttons read GOLD (set
`secondaryContainer` to a gold tint, was Material purple). Balance font adapts to length so a big balance fits
the ring. **CRUCIAL: this is a presentation/view-state refactor ONLY — every send/receive money-path handler +
gate (reviewSend, broadcastSignedTransaction, the WIF/mainnet/high-fee/policy gates, the fail-closed verifier)
is REUSED UNTOUCHED; only VISIBILITY is gated by view-state.** Commits: `034ca7c` palette+ring, `b3e741a`
palette adoption (+the 2 theme bugs), `2946eb8` focal ring hero, `4b96906` balance/sync cards behind settings,
`ec73ad3` focal-swap + gold segments, `d81c5d5` Settings gear view. `:app:assembleDebug` green throughout.
**REMAINING (data-dependent polish — deferred, needs the FUNDED/SYNCING S24 to verify, not the Pixel):** the
ring's live balance number, the SYNCING arc with real `blocksBehind`, an estimated-time-to-sync line, and the
CONFIRMING 0→N fill wired to `confirmationDepth` on a live send. The component + view-state plumbing are ready;
only the live-data wiring + its on-device check remain. **On-device nav gotcha for screenshotting the wallet:
app TITLE → App Info → scroll → "Dogecoin wallet" row; the wallet is a bottom sheet so a DOWNWARD swipe
DISMISSES it (scroll content UP only); Samsung kills the backgrounded app behind the keyguard — PIN-unlock
(5555) + relaunch to get the console host back.**

**✅ TOR-FOR-SPV + WIF-BACKUP POLISH SHIPPED (`933d26b`, 2026-06-28).** SPV peer connections now route over
the embedded Arti SOCKS proxy when Tor is ON. `DogecoinSpvService.start()` decides transport on Tor INTENT
(`ArtiTorManager.currentSocksAddress() != null`, set the instant the mode flips, BEFORE bootstrap): non-null ⇒
`PeerGroup(params, chain, BlockingClientManager(SocksProxySocketFactory))` so every peer socket is a SOCKS5
CONNECT through Arti; null ⇒ the default clearnet `NioClientManager` path, **byte-for-byte unchanged**. A
clearnet socket is NEVER opened while Tor is on; can't-reach-peers fails closed (no balance/broadcast), never a
clearnet retry. `DnsDiscovery` in BOTH modes (numeric `PeerAddress` only — a hostname-only `PeerAddress` crashes
`PeerAddress.equals`, verified in the 0.14.7 bytecode at offset 29-37); the one-time seed DNS lookup stays on the
local resolver (honestly disclosed). A `statusFlow` observer rebuilds the transport on the SOCKS **address**
(`distinctUntilChanged`, so OFF⇄ON + Arti port-bump 9060→9061 are handled, not just on/off); it never tears down
mid-broadcast (`broadcasting` guard, reset throw-safe) and the rebuild is **non-throwing** (`runCatching`) so it
can't mask an already-CLAIMED txid in `broadcast()`'s finally or kill the observer coroutine. New `overTor`
status field surfaces the ACTUAL transport. **Money-path UNTOUCHED:** bitcoinj never signs (Option B); the
fail-closed verifier, 4-layer mainnet block, MIN_PEERS=4 floor, `mainnetAuthorized` gate, and all send/WIF gates
are intact (confirmed by a two-lens adversarial diff review). Disclosure at `DogecoinWalletSheet.kt:~1527`
replaced the "Tor routing isn't available yet" string with Tor-state-aware text — "your IP is hidden" is claimed
ONLY when `overTor && torReady` (actual transport, not mere intent). **WIF-backup polish (strictly additive, no
gate weakened):** a blocked mainnet send now auto-opens the backup dialog inline (was a dead-end) in both
`reviewSend` + `broadcastSignedTransaction`; the send-card warning reuses the gate string; the testnet
Receive-card uses non-urgent wording (testnet sends aren't gated, new `dogecoin_wif_copy_missing_testnet`). New
`DogecoinSpvTorTransportTest` pins the no-silent-fallback decision (`torConnectionManager`), the load-bearing
no-arg `createSocket` override, and `overTor`. Built via two workflows (grounded design→adversary; two-lens diff
review); all REVISE findings fixed; `:app:testDebugUnitTest` + `:app:assembleDebug` green. **PROVEN ON-DEVICE
(S24 `RFCX81GNBRE`, MAINNET read-only, 2026-06-28; `doge-spv-status` now prints `overTor`, commit `e0d8b60`):**
baseline Tor OFF → `overTor=false`, clearnet peer + balance 38.81254 (DRjrQ6); `tor-set on` → log
`Tor SOCKS endpoint changed (null → /127.0.0.1:9060); rebuilding SPV transport for MAINNET` → `overTor=true`,
**7-8 mainnet peers connected OVER TOR** (real reachability, NOT fail-closed), balance still reads 38.81254 over
Tor; `tor-set off` → reverse rebuild log → `overTor=false` → `synced=true` in ~30s on clearnet. **HONEST CAVEAT:
full header CATCH-UP over Tor stalled** (height frozen 165 behind for ~3.5 min; Arti `END cell MISC` circuit
flakiness) — connection + small reads work over Tor, but the sustained getheaders bulk download is slow/unreliable
on these circuits; the SAME backlog synced instantly on clearnet, isolating it as Tor-network quality, NOT a code
bug. (`RESOLVEFAILED` "remote hostname lookup" errors are OTHER app traffic — Nostr relays rerouted through Tor;
SPV connects numeric peer IPs so it's unaffected.) Info-level deferred: `ArtiTorManager.applyMode(ON)` sets
`mode=ON` before `socksAddr` (the SPV observer can lag a toggle by one self-healing emission; never a leak — left
alone as shared Tor infra).

**WARM-UP FIX SHIPPED (`6c7222d`):** `requestPeerBroadcast` now `warmUpMeshHelperSessions` (initiate
`initiateNoiseHandshake` for connected session-less helpers + bounded await 3.5s) before dispatch. VERIFIED
on-device: warm-up establishes the BLE session (`session=true`) and the relay routes **via mesh** not Nostr
(`MessageRouter: Routing payment-broadcast REQUEST via mesh`). BUT end-to-end BLE delivery did NOT complete in
this env — the Pixel never received the payload; the BLE link here is very slow (handshake alone ~30s, payload
not delivered within the coordinator's ~30s window). **Remaining limiter is BLE payload delivery between these
phones (env: airplane mode, S24↔Pixel-3, a 3rd "doge-bobby" device), NOT a logic bug.** Follow-ups: PROACTIVE
warm-up DONE (`71e19d5`, fired from `ChatScreen.onShowDogecoinWallet` so the ~30s handshake finishes before
send); still optional: lengthen the coordinator mesh attempt window (`ATTEMPT_TIMEOUT_MS=30s`); re-test on a
clean BLE link (phones close, no 3rd device, not airplane-throttled).

**Offline phone-to-phone send test (2026-06-27) — see `docs/dogecoin-offline-mesh-relay-findings.md`.** PROVEN:
SPV builds+signs OFFLINE; node-less sender → helper → chain mined twice (txids `7b1be7ae`,`638c253e`) **but over
NOSTR (sender had internet)**. FAILED: truly air-gapped Bluetooth-only relay. ROOT CAUSE (high-confidence
workflow): the payment-broadcast relay picks transport purely on Noise-SESSION state (3 gates:
`listBroadcastHelperCandidates` filter `ChatViewModel:1631`, `resolveConnectedMeshPeerId :1674`,
`MessageRouter.isReady :231`), and the broadcast send path (`BluetoothMeshService.sendNoisePayloadToPeer`) never
calls `initiateNoiseHandshake` (unlike `sendPrivateMessage`), so two announce-only peers stay `session=false`
and the relay diverts to Nostr (needs internet). Recommended fix: warm up Noise sessions in the doge broadcast
flow (initiate handshake for connected session-less helper candidates, bounded await, concurrent with Nostr) —
transport-only, no money-safety impact. No-code workaround to demo offline-BLE: bootstrap a session via a DM
(`sendfav`) first, then airplane-mode+BT send. **S24 quirk: `svc wifi disable` drops its BLE mesh; airplane+BT
preserves it.**

**Two on-device UX fixes after the soak (`0383ae5`):** (1) the debug build (`com.bitchat.droid.debug`) was
indistinguishable from a side-by-side Play-store `com.bitchat.droid` on the home screen — easy to open the wrong
app (no Dogecoin wallet); the debug manifest now relabels the launcher to **"bitchat dev"** (`tools:replace`).
(2) the wallet defaulted to "My node" (RPC) and showed nothing for a no-node user; `DogecoinWalletRepository.resolveBackend`
now defaults to **Built-in (SPV)** when no node is configured AND a checkpoint asset ships (testnet today),
explicit choice always wins, configuring a node flips it back. **Wallet UI access: tap the app title → App Info
sheet → "Dogecoin Wallet" row (below Tor Network); the green wallet icon in the message bar is "request DOGE",
NOT the wallet.** Android tears down the Activity/ViewModel for a backgrounded app (process may survive) → the
wallet "disappears"; just relaunch (data persists).

> **CRITICAL: the app now uses bitcoinj/libdohj `0.14.7` (spongycastle), NOT `0.15.9`.** bitcoinj 0.15+
> uses `org.bouncycastle` + `ECPoint.isCompressed()`, REMOVED in bcprov 1.70 — the bcprov the app uses for
> `EncryptionService`/`NostrCrypto`/`NoiseEncryptionService`. 0.14.7 uses `com.madgag.spongycastle`
> (`org.spongycastle.*`, separate namespace), so the app keeps bcprov 1.70 untouched and bitcoinj's crypto
> is isolated + never signs (Option B). The feasibility-spike numbers below were on 0.15.9; re-validated on
> 0.14.7 (199,710 testnet headers through AuxPoW, zero errors).

### The SPV arc so far (newest first) — Phases 0–3 SHIPPED; Phase 3 testnet broadcast PROVEN on-device

- **`2d4271b` — SPV wallet PERSISTENCE (balance survives stop/start).** The soak exposed that the service
  created a fresh in-memory bitcoinj `Wallet` each `start()` and never saved it → balance showed 0 after ANY
  stop/start (sheet close, app background, backend switch) because the header store had advanced past the
  funding block (funds always safe on-chain; only the cache was lost). Fix: `loadOrCreateSpvWallet` +
  `autosaveToFile` per network; `stopLocked` flushes via `shutdownAutosaveAndWait`. **bitcoinj quirk:
  `Wallet.loadFromFile` FAILS for Dogecoin (`NetworkParameters.fromID` doesn't know libdohj nets) — load via
  the EXPLICIT-params `WalletProtobufSerializer().readWallet(params, null, parseToProto(stream))`.** Added
  `clearPersistedState`/`doge-spv-rescan` (force re-scan, keeps key); `doge-reset` now clears store+wallet.
  Validated on-device: 14.99 survived a stop→start ("Loaded persisted SPV wallet", instant, no rescan).
  **UI note: the sheet defaults to "My node" (RPC); switch Connection → "Built-in" on testnet to SEE the SPV
  balance (persists per-network via saveBackend).**
- **`a6fba67` — SPV Phase 3 soak: fast checkpoint seed + `doge-reset` — testnet broadcast PROVEN ON-DEVICE (S24).**
  The on-device soak first exposed a seed-speed bug: the service seeded via the static
  `CheckpointManager.checkpoint(...,time)` which applies a ~7-day margin (~1M extra testnet headers), and the S24's
  IMPORTED key (2021 birthdate floor) seeded from GENESIS → ~57h. Fix: seed via
  `CheckpointManager.getCheckpointBefore(birthdate)` (NO margin, spike-proven), FORWARD-ONLY (never regress a
  more-synced store; safe because a generated key's birthdate is its creation time). Added `doge-reset`
  (mainnet-refused) to regenerate a FRESH key (birthdate=now) + `DogecoinSpvCheckpointSeedTest`. **Soak (testnet):
  fresh key seeded at cp 65,648,620 → synced ~51k headers to tip in ~4.5 min (was 57h); SPV read the 20-DOGE funding
  UTXO; broadcast 5 DOGE (txid `5e8b4163…`) → propagated to the local node's mempool → mined (1 conf) → SPV change
  UTXO confirmed (depth 2), spent UTXO gone.** Fail-closed verifier + receivePending + Claimed→Confirmed all
  validated on real hardware.
- **`b34ca4c` — SPV Phase 3: testnet broadcast, FAIL-CLOSED, mainnet hard-blocked (Option B).** New
  `DogecoinSpvBroadcastVerifier` is the fail-closed chokepoint: decode the normalized signed hex → reject
  segwit framing via the BIP144 marker byte (`Transaction.hasWitnesses()` is ABSENT on the libdohj 0.14.7
  fork) → require `tx.bitcoinSerialize().contentEquals(inputBytes)` byte-for-byte (default serializer is
  NON-retaining ⇒ a real re-encode; a test pins `isParseRetainMode==false`) → require `tx.hashAsString ==
  DogecoinTransactionBuilder.transactionId(normalized)`; ANY divergence THROWS, so bitcoinj's own bytes never
  reach the wire. `DogecoinSpvService.broadcast()` is verifier-gated + peer-floor-pinned
  (`minBroadcastConnections=MIN_PEERS`), calls `wallet.receivePending` to reserve inputs (anti same-input
  double-spend), and awaits `future.get(25s)` OFF-lock — a TIMEOUT returns Claimed, NOT failure (thin testnet
  peers rarely re-announce). `confirmationDepth()` drives Claimed→Confirmed from our own synced chain.
  `DogecoinSpvDataSource.broadcast()` un-thrown + `withContext(Dispatchers.IO)` (the ANR fix — the sheet scope
  is Main). Sheet: SPV send routes via the datasource; `mempoolAcceptance(checked=false)` (note:
  `requiresPolicyUnavailableAcknowledgement()` is MAINNET-gated, so no ack pops on testnet — intended);
  Claimed-only receipt; send+confirm buttons enabled for a synced TESTNET SPV backend (route-mirroring);
  mesh-helper CTA gated OFF under SPV; clearnet/testnet-only disclosure. Console `doge-spv-broadcast`
  (node-free, mainnet-refused, IO). Tests: verifier fail-closed matrix + datasource mainnet refusal; signer
  canaries green. **MAINNET blocked at 4 layers.** Built by a plan workflow (`8720092` =
  `docs/dogecoin-spv-phase3-plan.md`) + a 5-lens adversarial code review (15 confirmed findings, none
  critical/high; fixes folded in). **Now PROVEN on-device — see `a6fba67` above.**
- **`3256ea2` — SPV Phase 2 (FINAL): wallet-sheet backend selector + sync status — PHASE 2 COMPLETE.**
  `DogecoinSpvService` is now a process SINGLETON (`getInstance`) so the sheet + console share ONE
  PeerGroup/SPVBlockStore. `DogecoinWalletSheet`: a "Connection" selector under Advanced settings ("My node"
  RPC default / "Built-in" SPV; hidden for REGTEST); `walletReadSource()` branches on backend (SPV→
  `DogecoinSpvDataSource`, RPC byte-identical); SPV start-on-select / stop-on-dispose; a visible "Built-in
  node" card shows sync progress (`StateFlow`); balance fills in reactively. **Sending DISABLED under SPV**
  (broadcast is Phase 3) with an inline note. Behavior-preserving for default RPC. The read-only SPV backend
  is now FULLY USABLE in the app.
- **`101fb0b` — SPV Phase 2: DbgConsole drivers.** ChatViewModel owns a lazy `DogecoinSpvService` (stopped
  in onCleared); DEBUG console commands `doge-spv-start/-stop/-status/-balance/-unspents` (REGTEST refused;
  broadcast still throws). The on-device driver surface for SPV.
- **`a2d7bae` — SPV Phase 2: LIVE READ VALIDATION (SUCCEEDED).** Spike `balance mode`
  (`-PwatchKeyHex=<hex> [-Pcheckpoint=<path>]`) mirrors `DogecoinSpvService`. Funded a fresh testnet addr
  `nZjD…j6w1` with 12.34 TESTDOGE from the node (txid `fada3b6a…`); SPV seeded at cp 65,648,620, synced
  ~50k headers to the tip in ~4 min, **bloom-matched the funding tx**, reported AVAILABLE=ESTIMATED=12.34
  with the exact UTXO (`fada3b6a…:0`=12.34, depth=2). Proves checkpoint-seed + AuxPoW/Scrypt validation +
  bloom matching + balance accounting end-to-end for Dogecoin. (`CheckpointManager.checkpoint(...,time)`
  subtracts a 7-DAY margin before selecting — never seeds past birthdate; testnet 7d≈1M blocks, mainnet
  7d≈10k. `getCheckpointBefore(now)` directly = the recent cp, for fast validation seeding.)
- **`f94262a` — SPV Phase 2: smart RPC checkpoint generator + validated testnet checkpoints asset.**
  `tools/spv-checkpoints/gen-dogecoin-checkpoint.ps1` builds bitcoinj `StoredBlock` compact records DIRECTLY
  from RPC (no full sync): `getblockheader` gives the raw 80-byte header + cumulative `chainwork`, packed as
  12-byte chainwork(BE) + 4-byte height(BE) + 80-byte header, emitted as `TXT CHECKPOINTS 1`. Testnet
  chainwork fits 0.14.7's 12-byte limit (mainnet may not eventually — Phase 4 risk). Asset
  `app/src/main/assets/dogecoin-checkpoints-testnet.txt` (3 cps). **VALIDATED**: spike `verifyCheckpoint`
  mode loads it via `CheckpointManager` + seeds the store; head hash == node `getblockhash 64698620` exactly.
  (Quirk: `& script.ps1` makes dogecoin-cli intermittently EOF on this box — run the body inline/dot-sourced;
  pass `true`/`false` literally; a `Cli` function name collides with the `Clear-Item` alias.)
- **`0459bd6` — SPV Phase 2: `DogecoinSpvService` + `DogecoinSpvDataSource` (read-only, no money path).**
  The on-device light client (bitcoinj 0.14.7). Compiled CLEANLY on 0.14.7 first try. Service: imports the
  EXISTING key via `ECKey.fromPrivate` (key creation time = `loadSpvBirthdateMillis`); `SPVBlockStore` in
  filesDir seeded by `CheckpointManager.checkpoint` from a `dogecoin-checkpoints-<net>.txt` asset IF present
  (else slow sync); `BlockChain`+`PeerGroup` (bloom on); `HighestHeightDownloadPeerGroup.selectDownloadPeer`
  override (no-SegWit); `DownloadProgressTracker`+peer listeners → `StateFlow<DogecoinSpvStatus>` (`synced` =
  caught-up-within-2-blocks AND ≥4 peers); `snapshotBalance`/`snapshotUnspents` propagate the bitcoinj
  Context. DataSource `broadcast()` THROWS this phase. REGTEST disabled. **0.14 read APIs that worked:
  `wallet.unspents`, `out.parentTransactionHash`/`parentTransaction.confidence.depthInBlocks`/`value.value`/
  `scriptBytes`, `Wallet.BalanceType.AVAILABLE`/`ESTIMATED` (NOT `AVAILABLE_SPENDABLE`).** NOT yet wired into
  UI/console (backbone only, like Phase 1's adapters).
- **`5e776b5` — SPV Phase 2: conservative `spv_birthdate` policy.** Separate per-network pref
  (`"${id}_spv_birthdate"`): generate → set to generation time (exact, fast); import → lower to a
  conservative floor (`DEFAULT_SPV_IMPORT_BIRTHDATE_MILLIS` = 2021-01-01, TUNABLE), NEVER the import
  timestamp (which would let `CheckpointManager` skip funding txs); reset → removed. `lowerSpvBirthdateMillis`
  only ever DECREASES. `loadSpvBirthdateMillis` falls back to the floor. Test in `DogecoinWalletRepositoryTest`.
- **`d8b3f25` — SPV Phase 2 foundation: switch to bitcoinj 0.14.7 (spongycastle) + key-import gate.**
  See the CRITICAL note above. `libdohj 0.14.7` + `guava 18.0` + `exclude com.google.guava:listenablefuture`
  (guava-18 bundles `ListenableFuture` → dup-class with the standalone artifact gms pulls); dropped the
  now-moot bcprov-jdk15to18 exclude. **`DogecoinSpvKeyImportTest` (the Phase-2 canary):** bitcoinj+libdohj
  `ECKey.toAddress(params)` MUST equal `DogecoinKeyGenerator`'s address — else SPV watches the wrong address
  (silent zero balance). **0.14 API diffs from 0.15:** address = `ecKey.toAddress(params)` (NOT
  `LegacyAddress.fromKey`); `VersionMessage.NODE_BLOOM` constant is gone (use literal `1L<<2`); on
  Windows+JDK17 the spike's `SPVBlockStore.close()` hits `WindowsMMapHack`/`sun.nio.ch` (added `--add-opens`)
  — **WindowsMMapHack NEVER runs on Android, so the app is unaffected.**
- **`e10012a` — SPV Phase 1: `DogecoinWalletDataSource` read abstraction (behavior-preserving).**
  Narrow interface (`getBalance`/`listUnspent`/`broadcast` only — node-specific status/watch/mempool/
  rescan + rich activity stay RPC-specific, per the design critique's leaky-interface warning).
  `DogecoinRpcDataSource` (thin delegation using the caller's CAPTURED config → byte-identical),
  `DogecoinExplorerDataSource` (READ-only; `broadcast()` HARD-THROWS so the refactor doesn't re-expose
  explorer broadcast). `DogecoinBackend{RPC,EXPLORER,SPV}` enum + repo `loadBackend`/`saveBackend`
  (per-network, default RPC, key `"${network.id}_backend"`). Routed the 7 read sites in
  `DogecoinWalletSheet` (3 getBalance + 4 listUnspent) through a local `walletReadSource(config)`
  helper (currently always RPC; Phase 2 makes it branch on backend). Default RPC ⇒ zero behavior change.
- **`faf9f6f` — SPV Phase 0: deps + bcprov isolation + signing regression gate (no feature code).**
  JitPack repo in `settings.gradle.kts`; `libdohj v0.15.9` (→ `bitcoinj-core:0.15.9`) + `guava
  28.2-android` pin in `libs.versions.toml`/`app/build.gradle.kts`. **EXCLUDE bitcoinj's bundled
  `org.bouncycastle:bcprov-jdk15to18`** so the app's audited `bcprov-jdk15on:1.70` stays the SOLE
  `org.bouncycastle` (same package, different module → would otherwise dup-class). R8 keep/dontwarn for
  bitcoinj/libdohj/protobuf/spongycastle. **`DogecoinSignerRegressionTest`** pins byte-for-byte signed
  hex+txid (captured pre-bitcoinj) ⇒ proves the money-path signer is UNCHANGED with bitcoinj present.
  **APK delta = +2.0 MB/ABI** (arm64 16.2→18.2; R8 shrinks the ~6-8MB stack to ~2MB). User: size not a concern.
- **`827060f` — SPV integration plan: `docs/dogecoin-spv-integration-plan.md`.** Produced by a grounded
  design workflow + adversarial money-path critique. **UTXO fork RESOLVED → Option B**: bitcoinj is ONLY
  a UTXO source + broadcast sink; `DogecoinTransactionBuilder` stays the SOLE signer (every safety gate
  preserved, additive). 5 phases (0 deps → 1 read abstraction → 2 read-only SPV → 3 testnet broadcast →
  4 mainnet). READ THIS DOC before continuing.
- **`f9d13c1` — dev-only feasibility spike: `tools/spv-spike/` (standalone pure-JVM Gradle, JDK 17).**
  PROVED: libdohj v0.15.9 resolves from JitPack + compiles; **synced 275,487 testnet headers in 8 min,
  ZERO `VerificationException`**, through the testnet DigiShield (~157.5k) + AuxPoW (~158.1k) transitions
  (libdohj issue #15 rejected nothing); connected to LOCAL + PUBLIC testnet peers, all `NODE_BLOOM`.
  Doubles as a re-validation tool + basis for a `BuildCheckpoints` generator. Run: `gradlew -p
  tools/spv-spike run` (default local node, or `-PnoLocalhost`, or `-PpeerHost=127.0.0.1`).

### NEXT STEP: PHASE 4 — mainnet broadcast (user-gated, per-spend authorized)

**Phase 4 progress (2026-06-28):** first SAFE prep step DONE — the **SPV-vs-node cross-check** (the
read-only soak validation surface) is shipped (`2348972`) + validated on-device. User decisions locked in:
oracle = **local node (RPC)**; proceed with the cross-check tool first (no mainnet node restart yet). New
`DogecoinRpcClient.getTxOut` (gettxout, read-only — Dogecoin Core 1.14.9 has NO `scantxoutset`, and a full
address rescan on the ~65.7M-block chain is impractical, so per-outpoint gettxout is the oracle) +
`DogecoinSpvCrossCheck` (pure, unit-tested) + console `doge-spv-crosscheck`. It checks the safety-critical
direction (no spent/forged UTXO is ever presented as spendable). **Proven both ways on testnet:** an OFFLINE
S24 SPV still showing the already-spent `638c253e…:1` → `result=FAIL SPENT_OR_MISSING`; after wifi-on resync
to the `3e1f64af…:1` change UTXO → `result=PASS nodeConfirmed=396000000k`. Oracle reachability on the S24 =
`adb reverse tcp:44555` + `doge-rpc-set http://127.0.0.1:44555 apstats <pw>` (the S24's stored RPC pointed at
the unreachable LAN IP). **Step 1 DONE (`6a9f7df`): mainnet checkpoint asset shipped + feasibility CONFIRMED.**
Mainnet node was up+synced (block 6.27M); chainwork = `…30b699a16d67c3d04c2a` = ~78 bits, 22 leading zero
bytes → fits bitcoinj 0.14.7's 12-byte `CHAIN_WORK_BYTES` with ~2^18 headroom (the "may eventually exceed"
risk is decades off). Generator fix: Dogecoin **mainnet is merge-mined (AuxPoW)** so `getblockheader false`
returns 80-byte base header **+ AuxPoW tail** (the testnet checkpoints only worked because that testnet was
CPU-mined = plain 80-byte headers) → take the first 80 bytes (block hash = SHA256d of exactly those). Asset =
4 cps (heights 4267104/5767104/6217104/6265104). **Step 2 partial DONE (`9229dc7`): mainnet checkpoint VALIDATED
to load in libdohj 0.14.7** (unit test: `CheckpointManager` parses the AuxPoW base headers without throwing;
seeded head hash == node `getblockhash 6265104` = `c5e6395c…c1c5e4a4`). The cross-check read path is already
proven (network-agnostic code). **Step 2 REMAINING (needs real money + a fresh key):** the LIVE mainnet soak —
SPV syncs mainnet forward from the checkpoint + `doge-spv-crosscheck` vs the mainnet node over the user's window
— needs (a) a FRESH mainnet key (the test device's PRE-EXISTING mainnet key `DTtoTrY3qjBiMkxaJG4gBdN1jmy8kNQGhh`
has the 2021 floor birthdate → seeds from GENESIS, stuck; **doge-reset is mainnet-REFUSED** so there's no clean
way to regenerate it today — a Phase 4 gap to resolve), and (b) the user funding that address with real mainnet
DOGE. **Step 3 (NOT started — gated):** lift the 4-layer block + WIF-backup gate, then a real per-spend-
authorized mainnet broadcast. Soak duration/agreement criteria are a USER decision (not set). Mainnet node RPC =
port 22555 (tunnel `adb reverse tcp:22555`); device switched back to testnet after the probe.

PHASE 3 (testnet broadcast, fail-closed) is SHIPPED + **PROVEN ON-DEVICE** (`a6fba67`): a fresh testnet key
synced cp→tip in ~4.5 min and broadcast 5 DOGE via SPV peers (`5e8b4163…`) → local node mempool → mined →
SPV change UTXO confirmed (depth 2), spent UTXO gone. The remaining work is Phase 4 (mainnet) — the LAST
switch, requiring explicit per-spend user authorization (irreversible real money). Per
docs/dogecoin-spv-phase3-plan.md + the integration plan, Phase 4 should:
- Remove the 4-layer MAINNET block ONLY behind: a mainnet read-only soak (SPV balance/UTXOs cross-checked vs
  an explorer/node on mainnet), a WIF-backup requirement, and the existing mainnet + high-fee + policy ack
  gates (these already fire on mainnet — `requiresPolicyUnavailableAcknowledgement` is mainnet-gated, so
  SPV's `mempoolAcceptance.checked=false` auto-engages the policy ack there).
- Watch mainnet chainwork vs bitcoinj 0.14.7's 12-byte `CHAIN_WORK_BYTES` limit (known Phase-4 risk; testnet
  fit, mainnet may eventually exceed) when generating the mainnet checkpoint asset.
- Use a FRESH generated key for fast mainnet SPV seeding (imported keys seed from genesis — see the soak
  learning below).

**Soak learnings (testnet, baked into the code at `a6fba67`):** the service now seeds via
`CheckpointManager.getCheckpointBefore(birthdate)` (NO margin) — the static `checkpoint(...,time)` applies a
~7-day margin = ~1M extra testnet headers (~50 min). IMPORTED keys (2021 birthdate floor) have no checkpoint
before them → seed from GENESIS (~57h on testnet) → use `doge-reset` (new console cmd, mainnet-refused) to
generate a FRESH key (birthdate=now) for SPV. testnet mines ~1 block/30-40s so funding/confirmation are fast.

Remaining TESTNET polish (optional, non-blocking): pin a known-good testnet peer via `addAddress` if
MIN_PEERS=4 is ever hard to reach; deferred review nits (reorg one-way receipt, interruptible 25s await,
"Use Max" under SPV). **Tor-over-SPV IMPLEMENTED (`933d26b`) + PROVEN ON-DEVICE (mainnet read-only, S24)** —
routes peer connections over the embedded Arti SOCKS when Tor is ON, 7-8 mainnet peers reached over Tor +
balance read over Tor, fail-closed, no silent clearnet fallback (full proof trail in the dated note up top).
Only caveat: bulk header catch-up over Tor is slow on flaky circuits (a Tor-network limit, not a code bug).

### Node state + deferred balance-match spike (Task 3)
**The testnet node is now HEALTHY** (user closed the mainnet instance 2026-06-27; only testnet runs —
synced to block ~65.7M, verifyprogress 1.0, RPC responds; P2P 44556 also fine). This unblocks the smart
RPC checkpoint generation AND the balance-match validation. Balance-match spike: point the spike at the
local node (`-PpeerHost=127.0.0.1`), watch a funded address, assert SPV balance == node oracle — but it
needs a checkpoint near that address's funding height first (testnet from genesis is infeasible), so it
follows the smart-checkpoint step.

## Older shipped milestones (background — all committed pre-`f9d13c1`)
- **M0/M1** — UX hardening (fee presets, address book, payment requests). **M2 — Pay @nickname**
  (Ed25519-signed receive-address TLVs). **M3b — Broadcast-over-mesh** (node-less sender relays a SIGNED
  tx to an opt-in helper; `NoisePayloadType 0x30/0x31`, `BroadcastHelperService`,
  `PaymentBroadcastCoordinator`, two-helper corroboration ⇒ Confirmed; signed `NODE_HELPER` TLV 0x06).
- **3b.1 — Nostr off-mesh fallback + explorer corroboration** (corroboration counts by the helper's
  Noise static key, never a free-to-mint Nostr pubkey). Proven on hardware: Nostr off-mesh broadcast
  round-trip vs LIVE relays (Tor OFF); on-chain peer broadcast mined (testnet txid `d3be593c…`, 36 conf).
- **Wallet UX redesign (P0+P1)** — one `LazyColumn` of `item(key=…)`; node/dev settings collapsed;
  balance hero; de-jargoned. **Explorer "no-node" mode (`b939eca`)** — but free public explorers gate
  anonymous access (Blockchair → paid key; Trezor/Dogechain → 403). THIS is why SPV is the direction.

## Debug console (on-device driver — `DebugConsole`, BuildConfig.DEBUG only)
Drive the app textually over adb (no UI tapping). **Quote the WHOLE am command** so a space-arg survives:
```powershell
adb -s <serial> shell "am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver --es cmd 'doge-peer-broadcast nceDC… 5'"
adb -s <serial> logcat -d -s DbgConsole
```
Host registers from `ChatViewModel.init` (chat screen must be alive — if "no host registered", relaunch).
Commands incl.: `doge-network/-rpc-set/-address/-import-wif/-balance/-self-broadcast/-peer-broadcast/
-helper-enable/-explorer-config/-explorer-balance/-utxos/-broadcast/-send`; `nostr tor tor-set peers
reannounce`. **Mainnet money-path commands HARD-REFUSE; WIF/keys never logged.** (Phase 2: add SPV
sync/balance + SPV-vs-node cross-check commands here as the soak surface.)

## Local Dogecoin Node (testnet)
```text
"C:\Program Files\Dogecoin\dogecoin-qt.exe" -testnet            # RPC 127.0.0.1:44555 (P2P 44556)
"C:\Program Files\Dogecoin\daemon\dogecoin-cli.exe" -testnet <cmd>
```
- Config (do NOT print rpcpassword): `C:\Users\rober\AppData\Roaming\Dogecoin\dogecoin.conf` (`server=1`,
  RPC user `apstats`). For a phone over LAN: `dogecoin-qt -testnet -rpcbind=10.0.0.24 -rpcbind=127.0.0.1
  -rpcallowip=10.0.0.0/24 -rpcallowip=127.0.0.1` (was reachable at `http://10.0.0.24:44555`). For SPV the
  phone uses P2P (44556) directly, not RPC. For `BuildCheckpoints`, keep the node synced + RPC up.
- Funded node-owned address `nceDCkWAP9tSktE5TJ5X6LVmD2r3HwiAXN` — ~1.72M TESTDOGE (WIF imported into the
  Pixel's wallet). The S24's own testnet key is `ni5e6MFNimwAZfFXGbdxWjzC7FBWfwM7iS` (~0; it's the helper).
- Coinbase maturity 240 blocks. Faucets dead → CPU-mine with pooler minerd. See [[dogecoin-testnet-ops]].

## Android / Device State
- SDK `C:\Users\rober\AppData\Local\Android\Sdk`; `adb` at `…\Sdk\platform-tools\adb`.
- **Galaxy S24** (Android 16) serial `RFCX81GNBRE` = helper; **Pixel 3** (Android 12, arm64) serial
  `89VX0HPX1` = sender. Both arm64; debug app id `com.bitchat.droid.debug` (coexists with Play
  `com.bitchat.droid`). REGTEST selector is DEBUG-only.
```powershell
.\gradlew.bat assembleDebug      # SPLIT per-ABI; both phones arm64:
adb -s 89VX0HPX1 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s 89VX0HPX1 shell monkey -p com.bitchat.droid.debug -c android.intent.category.LAUNCHER 1
```
Gotchas (hard-won):
- **Windows: ALWAYS pass `gradlew -p "<repo>"`** — a stale shell CWD makes Gradle resolve the wrong
  project dir ("Project directory … is not part of the build"). Never pipe gradlew to `tail`/`grep` and
  trust the exit code (capture to a file, check `$LASTEXITCODE`).
- **gitnexus MCP tools were NOT connected this session** (despite CLAUDE.md) — do impact analysis manually
  via grep, or check if they're back.
- **bitcoinj 0.15.9 predates new JDKs**: the spike pins a JDK-17 Gradle toolchain. The app's AGP build
  runs on JAVA_HOME (JDK 22) with source/target 8 — fine; the signing regression test guards the signer.
- **Samsung (S24) aggressively kills backgrounded bitchat** → "wallet doesn't show" = app was killed;
  force-stop + relaunch. The wallet is **Tor-independent** (own HTTP client). **Nostr is forced through
  Arti Tor** (default ON, SOCKS `127.0.0.1:9060`, fail-closed) — toggle OFF (`tor-set off`) for Nostr
  tests; Dogecoin RPC bypasses Tor. **SPV P2P over Arti is UNVERIFIED.**
- Both phones have a secure lock that re-engages on idle; `adb` can't unlock, but the console works
  regardless (it's a BroadcastReceiver). `keyevent 4` (BACK) drops the keyboard; never `keyevent 111`
  (ESC) inside the sheet (resets network to mainnet). `adb shell input text` mangles long random strings.
- PowerShell has NO heredocs — commit multi-line messages via the Bash tool. `adb pull` binary files
  (don't `>`-redirect — UTF-16 mangles).

## Key Files
SPV (new): `features/dogecoin/{DogecoinWalletDataSource,DogecoinRpcDataSource,DogecoinExplorerDataSource,
DogecoinSpvService,DogecoinSpvDataSource}.kt` + `spv_birthdate`/`DogecoinBackend` in `DogecoinWalletRepository.kt`;
tests `DogecoinSpvKeyImportTest`/`DogecoinSignerRegressionTest`/`DogecoinWalletDataSourceTest`;
checkpoints `tools/spv-checkpoints/gen-dogecoin-checkpoint.ps1` + `app/src/main/assets/dogecoin-checkpoints-testnet.txt`;
plan `docs/dogecoin-spv-integration-plan.md`; spike `tools/spv-spike/` (+ its README; `-PverifyCheckpoint=<path>`
mode). Reference SPV
wallet: `C:\Users\rober\Downloads\Projects\dogecoin-wallet-new` (Langerhans, bitcoinj+libdohj) —
`wallet/src/de/schildbach/wallet/service/{BlockchainService,NonWitnessPeerGroup}.java` + `Constants.java`
(checkpoints asset `checkpoints.txt`, `CheckpointManager.checkpoint(...)`). Wallet core:
`features/dogecoin/{DogecoinWalletRepository,DogecoinWallet,DogecoinTransaction(Builder),DogecoinRpcClient,
DogecoinExplorerClient,DogecoinRawTxValidator,BroadcastHelperService,PaymentBroadcastCoordinator}.kt`. UI:
`features/dogecoin/DogecoinWalletSheet.kt` (4200+ lines; `walletReadSource()` helper near `rpcClient`).
Debug console: `debug/DebugConsole.kt` + `app/src/debug/AndroidManifest.xml` + `debugConsoleHost` in
`ui/ChatViewModel.kt`. Mesh/Nostr/Tor: `mesh/*`, `services/MessageRouter.kt`, `nostr/*`, `net/*`.

## Verification
```powershell
# IMPORTANT: pass -p; never pipe gradlew to tail/grep and trust the exit code.
.\gradlew.bat -p "C:\Users\rober\Downloads\Projects\bitchat-android" :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
```
Last green at `3256ea2`. The `DogecoinSignerRegressionTest` (app signer) AND `DogecoinSpvKeyImportTest`
(bitcoinj derives the app's address) are the money-path canaries — if either ever fails
after a dep change, the audited ECDSA signer changed; REJECT the change, do not re-baseline.

## Constraints
- No mainnet wallet broadcast without explicit per-spend user authorization (irreversible real money).
- No custodial signing, remote key storage, or seed export without explicit approval.
- Never print private keys or RPC passwords in user-facing output.
- No destructive git commands. Keep changes narrowly scoped; follow existing Kotlin/Compose style.
- SPV must NOT be the sole money-path source of truth until mainnet-validated; keep RPC/explorer as the
  cross-check anchor. bitcoinj NEVER signs (Option B) — `DogecoinTransactionBuilder` is the only signer.
