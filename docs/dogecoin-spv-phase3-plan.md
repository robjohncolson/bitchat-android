# Dogecoin SPV — Phase 3 implementation plan (testnet broadcast, fail-closed)

> Detailed, reviewable implementation plan for **Phase 3** of the SPV integration. Supersedes/expands
> the Phase 3 section of [`dogecoin-spv-integration-plan.md`](./dogecoin-spv-integration-plan.md).
> Produced by a grounded design workflow (7 parallel code-mappers → synthesis → adversarial money-path
> critique, verdict **sound-with-fixes**) and then **verified against the live code** (every code anchor
> below was read, not assumed). This is the money path — review before implementing.

## Scope

Turn the existing read-only SPV backend into a real **testnet broadcast sink**, keeping **Option B**:
bitcoinj/libdohj `0.14.7` is ONLY a UTXO source + broadcast sink; `DogecoinTransactionBuilder`
(`DogecoinTransaction.kt`) stays the **sole signer** and its signed bytes are canonical. **bitcoinj
never signs.** MAINNET stays **hard-blocked** (Phase 4). REGTEST stays unsupported (no peers/params).

### Decisions locked for Phase 3 (from the sign-off)

- **Privacy: clearnet + in-app disclosure.** SPV reaches Dogecoin peers over clearnet (Arti↔bitcoinj
  SOCKS routing is UNVERIFIED — the spike was clearnet-only and Arti couldn't build circuits). The
  Connection selector copy MUST state that the address may be linkable to the device IP and that Tor
  routing is not yet available. The code MUST **never silently fall back** to clearnet — it is
  clearnet-only today, stated plainly. (`DogecoinSpvService.kt:109` TODO documents the future Arti path.)
- **Confirmation threshold:** `depthInBlocks >= 1` → Confirmed (sender view; acceptable on testnet).
- **No foreground service** in Phase 3 — sync-on-demand; "relaunch if Samsung kills it" is acceptable
  for a testnet soak. Revisit for mainnet (Phase 4).
- **Old imported keys:** birthdate floor stays `DEFAULT_SPV_IMPORT_BIRTHDATE_MILLIS` (2021-01-01) —
  **warn-only** that pre-2021 funds may not appear under Built-in (SPV); no user-supplied-date control yet.
- **Mesh-helper vs SPV:** when `backend == SPV`, SPV self-broadcast takes precedence; gate the mesh
  peer-broadcast CTA off (prevents double sends / double receipts).
- **Thin testnet peer sets:** pin a known-good testnet node via `addAddress` so broadcast/sync don't
  hang waiting for peers (langerhans pattern), in addition to DNS discovery.

## Architecture: the fail-closed chokepoint

All byte/txid safety is concentrated in ONE new pure object, `DogecoinSpvBroadcastVerifier`, that runs
with **no PeerGroup** so every fail-closed branch is unit-testable on the JVM:

1. `normalize(signedHex)` (existing `DogecoinRawTxValidator.normalize`, line 27) runs FIRST — rejects
   malformed / non-standard / trailing-byte input before bitcoinj sees anything.
2. Decode the normalized bytes; build a libdohj `org.bitcoinj.core.Transaction(params, bytes)`.
3. **FC: no witness data** — `require(!tx.hasWitnesses())` (Dogecoin is legacy-only).
4. **FC (load-bearing): byte-for-byte re-encode** — `require(tx.bitcoinSerialize().contentEquals(bytes))`.
   This is a *genuine* re-serialization: `Transaction(params, bytes)` uses `params.getDefaultSerializer()`,
   which is **non-parse-retaining**, so bitcoinj re-encodes rather than echoing the cached payload. This
   guarantees the bytes `peerGroup.broadcastTransaction` will hand peers are EXACTLY the signer's bytes.
5. **FC (redundant cross-check): txid agreement** — `require(tx.hashAsString.equals(expectedTxid, true))`
   where `expectedTxid = DogecoinTransactionBuilder.transactionId(normalized)` (line 294). NOTE: this is
   *not* an independent implementation — bitcoinj computes the id by double-SHA256 over the same
   serialization, so once FC byte-equality holds this is largely redundant. Kept as a cheap guard.

Any failure **throws → never broadcasts.** A test asserts the serializer in use is non-retaining so a
future refactor can't silently weaken the byte check to a tautology.

## Fail-closed invariants (must all hold)

| # | Invariant |
|---|-----------|
| FC1 | `normalize()` throws on empty/odd/non-hex/short/non-canonical-varint/non-standard-script/trailing input — *before* bitcoinj sees bytes. |
| FC2 | `bitcoinSerialize().contentEquals(inputBytes)` byte-for-byte (load-bearing; serializer is non-retaining). |
| FC3 | bitcoinj `hashAsString == DogecoinTransactionBuilder.transactionId(normalized)` (redundant cross-guard). |
| FC4 | `!tx.hasWitnesses()` — reject any segwit/witness serialization. |
| FC5 | Service refuses (returns `null`) unless `status.synced && peerCount >= MIN_PEERS`; pins `minBroadcastConnections = MIN_PEERS` to avoid the single-peer `TransactionBroadcast` deadlock. |
| FC6 | REGTEST: `paramsFor(REGTEST) == null` → service `null` → datasource throws; sheet offers SPV only for non-regtest. |
| FC7 | MAINNET blocked at **4 layers**: (a) `service.broadcast` returns `null` for MAINNET; (b) datasource `require(network != MAINNET)`; (c) sheet `spvReady`/`canBroadcastViaSpv` exclude MAINNET + existing mainnet ack gates; (d) console `doge-spv-broadcast` refuses MAINNET. |
| FC8 | SPV broadcast renders **Claimed only** (`viaSpvClaimedOnly = true`), never Confirmed, until on-chain `confirmationDepth >= 1`. No corroboration within budget ⇒ stays Claimed (safe default). |
| FC9 | All existing send gates still run on the SPV branch: `requireConsistentRawTransactionId`, `requireSelectedInputsStillSpendable` (against freshly re-listed SPV UTXOs), 10-min expiry, high-fee ack. |
| FC10 | Only consume a corroboration/receipt whose `txid == transaction.txid` (mirrors the mesh guard) so a stale txid can't be paired with this tx's amounts. |
| FC11 | `null` vs throw discipline: `service.broadcast` returns `null` ONLY for "SPV can't broadcast here" (inactive/unsynced/refused network); the verifier THROWS only for "bytes are wrong". Neither path ever broadcasts unverified bytes. |

## Implementation steps

### P3-1 — `DogecoinSpvBroadcastVerifier` (new, pure, unit-testable)

`app/src/main/java/com/bitchat/android/features/dogecoin/DogecoinSpvBroadcastVerifier.kt`

```kotlin
object DogecoinSpvBroadcastVerifier {
    /** FAIL CLOSED: returns the parsed tx ONLY if bitcoinj round-trips [normalizedHex] byte-for-byte
     *  AND agrees on [expectedTxid]. Caller must have Context.propagate'd and pass libdohj params.
     *  FC2 (byte-equality) is the real guard: Transaction(params, bytes) uses the default NON-retaining
     *  serializer, so bitcoinSerialize() genuinely re-encodes. FC3 (txid) is a cheap redundant check. */
    fun verifiedTransaction(
        params: org.bitcoinj.core.NetworkParameters,
        normalizedHex: String,
        expectedTxid: String,
    ): org.bitcoinj.core.Transaction {
        val inputBytes = DogecoinHex.decode(normalizedHex)
        val tx = org.bitcoinj.core.Transaction(params, inputBytes)
        require(!tx.hasWitnesses()) { "SPV broadcast aborted: witness/segwit serialization (Dogecoin is legacy)." }
        require(tx.bitcoinSerialize().contentEquals(inputBytes)) {
            "SPV broadcast aborted: bitcoinj re-serialization diverged from the signed bytes."
        }
        require(tx.hashAsString.equals(expectedTxid.trim(), ignoreCase = true)) {
            "SPV broadcast aborted: bitcoinj txid did not match the signed transaction."
        }
        return tx
    }
}
```

**Risk/verify:** confirm `hasWitnesses()` + `hashAsString` compile on the pinned libdohj/bitcoinj
`0.14.7` (present since 0.14.4 segwit-awareness, but pin-verify). The single audit chokepoint for FC2–FC4.

### P3-2 — `DogecoinSpvService`: add `broadcast()` + `confirmationDepth()`

`DogecoinSpvService.kt` (add after `snapshotBalance`, ~line 192; add `BROADCAST_TIMEOUT_SECS` to the
companion ~line 277). Touches the private `peerGroup` (line 60) and `wallet` (line 57) under `lock`.

```kotlin
/** Phase 3: FAIL-CLOSED SPV broadcast. Returns the txid as CLAIMED (inputs reserved, tx in flight) or
 *  null if SPV can't broadcast here (mainnet/regtest/unsynced/too-few-peers). THROWS only via the
 *  verifier when bytes are wrong. The blocking future await is best-effort propagation confirmation —
 *  a timeout is NOT a failure (testnet peers often don't re-announce); on-chain depth is the real proof. */
fun broadcast(network: DogecoinNetwork, normalizedHex: String, expectedTxid: String): String? {
    val broadcast: org.bitcoinj.core.TransactionBroadcast
    val tx: org.bitcoinj.core.Transaction
    synchronized(lock) {
        if (network == DogecoinNetwork.MAINNET) return null          // FC7(a): Phase-3 hard block (Phase 4 lifts)
        paramsFor(network) ?: return null                            // FC6: REGTEST
        val pg = peerGroup?.takeIf { activeNetwork == network } ?: return null
        val w = wallet ?: return null
        val st = _status.value
        if (!st.synced || st.peerCount < MIN_PEERS) return null       // FC5
        org.bitcoinj.core.Context.propagate(bitcoinjContext)
        tx = DogecoinSpvBroadcastVerifier.verifiedTransaction(        // FC2/FC3/FC4: throws = never broadcast
            paramsFor(network)!!, normalizedHex, expectedTxid)
        w.receivePending(tx, null)                                    // reserve inputs NOW (anti double-spend)
        pg.minBroadcastConnections = MIN_PEERS                        // FC5: avoid single-peer deadlock
        broadcast = pg.broadcastTransaction(tx)                       // sends to peers immediately; returns now
    }
    // Best-effort propagation wait OFF-lock. Completion = N peers re-announced; timeout = still in flight.
    // Either way the tx is CLAIMED (inputs reserved). Do NOT throw on timeout (would invite a double-spend retry).
    runCatching {
        broadcast.future().get(BROADCAST_TIMEOUT_SECS, java.util.concurrent.TimeUnit.SECONDS)
    }
    return tx.hashAsString
}

/** Phase 3 corroboration: depth of OUR broadcast tx as the SPV chain catches up. No third party.
 *  null = SPV not active for [network]; 0 = seen but unconfirmed; >=1 = in a block. */
fun confirmationDepth(network: DogecoinNetwork, txid: String): Int? = synchronized(lock) {
    val w = wallet?.takeIf { activeNetwork == network } ?: return null
    org.bitcoinj.core.Context.propagate(bitcoinjContext)
    val h = runCatching { org.bitcoinj.core.Sha256Hash.wrap(txid) }.getOrNull() ?: return null
    val t = w.getTransaction(h) ?: return 0
    runCatching { t.confidence?.depthInBlocks }.getOrNull()
}
// companion: const val BROADCAST_TIMEOUT_SECS = 25L
```

**Why this shape (critique fixes folded in):**
- **`receivePending` (HIGH fix)** — without it `snapshotUnspents`/`snapshotBalance` keep showing the
  just-spent UTXOs as spendable until a block confirms, so a quick second send / retry builds a
  conflicting same-input tx. The proven Langerhans reference (`AbstractWalletActivityViewModel.java:62-78`)
  calls `wallet.receivePending(tx, null)` before broadcast. We do the same, under the lock.
- **Timeout ≠ failure (MEDIUM fix)** — `pg.broadcastTransaction` puts the tx on the wire immediately;
  the future only completes when `minBroadcastConnections` peers re-announce, which thin testnet peers
  often won't. Throwing on timeout would present a *successful* broadcast as a failure → user retries →
  double-spend. Inputs are already reserved and the txid is known, so we return it as **Claimed**; the
  `confirmationDepth` poll determines real confirmation.
- **All fail-closed checks happen BEFORE `broadcastTransaction`** (sync, peer floor, verifier). After
  the send there is no "rejection" signal from SPV peers, so post-send is always Claimed.
- **Await off-lock** so `snapshotBalance`/`start`/`stop` aren't frozen. `broadcast`+`tx` are captured
  under the lock; `stopLocked()` nulling fields afterward is safe (the future + `tx` are independent).

**Risk:** definite-assignment of the two `val`s across the inline `synchronized` block — every refusal
path `return`s, so they're assigned on every fall-through; if the compiler complains, hoist to nullable
locals and null-check after the block. Do NOT call `dropAllPeers()` (the reference does, but our
PeerGroup also serves reads — dropping forces a resync).

### P3-3 — `DogecoinSpvDataSource.broadcast()`: un-throw (IO-dispatched, mainnet defense-in-depth)

`DogecoinSpvDataSource.kt` — replace the `UnsupportedOperationException` body (lines 23-26).

```kotlin
override suspend fun broadcast(rawTransactionHex: String, network: DogecoinNetwork): String {
    require(network != DogecoinNetwork.MAINNET) {                       // FC7(b) defense-in-depth
        "Dogecoin SPV broadcast is testnet-only in this version."
    }
    val normalized = DogecoinRawTxValidator.normalize(rawTransactionHex)  // FC1: throws on malformed
    val expectedTxid = DogecoinTransactionBuilder.transactionId(normalized) // canonical on-device id (Option B)
    return withContext(Dispatchers.IO) {                                 // ANR fix: blocking await off main
        service.broadcast(network, normalized, expectedTxid)
            ?: throw IllegalStateException(
                "Dogecoin SPV is not synced/active for ${network.displayName}; cannot broadcast."
            )
    }
}
```

**ANR fix (HIGH):** the sheet calls broadcast from `rememberCoroutineScope().launch{}` (Main). The RPC
path is only safe because every `rpcClient` suspend fn does its own `withContext(Dispatchers.IO)`;
`service.broadcast` is a plain blocking call, so the datasource MUST move it (and the verifier
round-trip) to `Dispatchers.IO` or the up-to-25s await ANRs the UI. Also update the class KDoc (lines
6-9) to say broadcast is LIVE on testnet, fail-closed, mainnet-blocked.

### P3-4 — Sheet `reviewSend()`: SPV sets `mempoolAcceptance(checked=false)` instead of RPC `testmempoolaccept`

`DogecoinWalletSheet.kt` (the `rpcClient.testMempoolAcceptance(...)` call, ~lines 765-772).

```kotlin
val mempoolAcceptance = if (dogecoinBackend == DogecoinBackend.SPV) {
    // No testmempoolaccept under SPV. checked=false records "no node policy check ran" (honest). On
    // TESTNET this pops NO ack (requiresPolicyUnavailableAcknowledgement is MAINNET-gated, see
    // DogecoinTransaction.kt:66-68); it satisfies the existing check() below and auto-engages the
    // MAINNET ack in Phase 4. Do NOT relax the mainnet gating to "make it fire" on testnet.
    DogecoinMempoolAcceptance(checked = false, allowed = null)
} else {
    rpcClient.testMempoolAcceptance(config = config, rawTransactionHex = signed.rawTransactionHex, network = network)
}
check(!mempoolAcceptance.checked || mempoolAcceptance.allowed == true) {
    mempoolAcceptance.error ?: context.getString(R.string.dogecoin_send_policy_rejected)
}
```

**Correction baked in:** the continuation prompt's "set checked=false so the gate fires" only literally
holds on MAINNET. On testnet it's correctly low-friction (no ack). This is intended — do not "fix" it.

### P3-5 — Sheet `broadcastSignedTransaction()`: route SPV, render Claimed-only, poll Claimed→Confirmed

`DogecoinWalletSheet.kt` (broadcast call ~line 948; `onSuccess` ~959-969). Keep ALL pre-broadcast gates
(`requireConsistentRawTransactionId` ~944, `requireSelectedInputsStillSpendable` against freshly
re-listed SPV UTXOs via `walletReadSource` ~945-946, the `canExportOrBroadcast…` ack gates ~915-933).

```kotlin
val txid = if (dogecoinBackend == DogecoinBackend.SPV) {
    spvDataSource.broadcast(transaction.rawTransactionHex, network)   // peers relay; success != accepted
} else {
    refreshAddressWatchStatusFromNode(config, address, network, configRevision)  // RPC only
    rpcClient.sendRawTransaction(config, transaction.rawTransactionHex, network)
}
// onSuccess { txid -> ... }
sentReceipt = DogecoinBroadcastReceipt(
    txid = txid, /* …existing fields… */,
    viaSpvClaimedOnly = (dogecoinBackend == DogecoinBackend.SPV),     // FC8: Claimed until corroborated
)
if (dogecoinBackend == DogecoinBackend.SPV) coroutineScope.launch {
    repeat(SPV_CORROBORATION_POLLS) {
        val depth = withContext(Dispatchers.IO) { spvService.confirmationDepth(network, txid) }
        if (depth != null && depth >= 1 && txid == transaction.txid) { // FC10 txid-match
            sentReceipt = sentReceipt?.copy(viaSpvClaimedOnly = false)  // → Confirmed
            return@launch
        }
        kotlinx.coroutines.delay(SPV_CORROBORATION_INTERVAL_MS)
    }
    // budget exhausted → leave Claimed (FC8 safe default)
}
```

**Critique fixes:** corroboration runs `confirmationDepth` on `Dispatchers.IO`; never marks Confirmed
off a bare peer relay; the `txid == transaction.txid` guard prevents a stale corroboration confirming
the wrong amounts. **Also skip the RPC-only refreshes on the SPV path** (`refreshAddressWatchStatusFromNode`
~755 & ~986, `rpcClient.getWalletActivity` ~988) — they hit a blank/unreachable node and set spurious
errors in a no-node session; guard them on `dogecoinBackend != SPV`.

### P3-6 — Re-enable the send + confirm buttons for a synced SPV backend; selector copy + disclosure

`DogecoinWalletSheet.kt`. (a) Primary send Button (~2573-2596): replace the
`&& dogecoinBackend != DogecoinBackend.SPV` disable clause and DELETE the inline "sending disabled
under SPV" note (~2589-2596), replacing it with a sync hint shown only when SPV is selected but not yet
synced. (b) Confirm-dialog broadcast Button (~3310-3324): widen `enabled`. (c) Connection-selector
description (~1469-1476): drop "Read-only for now"; add the clearnet/privacy disclosure.

```kotlin
val spvStatus by spvService.status.collectAsState()
val spvReady = dogecoinBackend == DogecoinBackend.SPV && spvStatus.synced &&
    selectedNetwork != DogecoinNetwork.MAINNET                          // FC7(c)
Button(onClick = { reviewSend() },
    enabled = !sending && !rescanning && ((dogecoinBackend != DogecoinBackend.SPV && nodeReady) || spvReady)) { /* … */ }
if (dogecoinBackend == DogecoinBackend.SPV && !spvStatus.synced) {
    Text("Syncing the built-in light client… sending unlocks once it catches up " +
         "(peers=${spvStatus.peerCount}).", style = MaterialTheme.typography.bodySmall)
}
// confirm dialog:
val canBroadcastViaSpv = dogecoinBackend == DogecoinBackend.SPV && spvStatus.synced &&
    transaction.network == selectedNetwork && transaction.network != DogecoinNetwork.MAINNET
Button(onClick = { broadcastSignedTransaction(transaction) },
    enabled = !sending && canExportOrBroadcastAfterAcknowledgements &&
        (canBroadcastThroughConfiguredNode || canBroadcastViaSpv)) { /* … */ }
```

New string resources (commit strings before referencing — workspace deploy checklist): the sync hint
and the selector disclosure ("Built-in connects to Dogecoin peers over the internet; your address may
be linkable to your IP. Tor routing is not yet available.").

### P3-7 — Receipt model: `viaSpvClaimedOnly` flag + rendering

`DogecoinWalletSheet.kt`. Add `val viaSpvClaimedOnly: Boolean = false` to the private
`DogecoinBroadcastReceipt` data class (~4231-4245, alongside `viaPeer`/`peerCorroborated`). In the
receipt UI (~2491) add a branch reusing the existing "uncorroborated claim" visual language:
Claimed → "Claimed — broadcast to the Dogecoin network; not yet chain-confirmed"; cleared flag →
normal confirmed copy. Default `false` keeps RPC/mesh receipts unchanged.

### P3-8 — `doge-spv-broadcast` console driver (mainnet-refused soak surface)

`ui/ChatViewModel.kt` — add to the `debugConsoleHost` when-block near the other `doge-spv-*` commands
(~309-346) and to the help string (~162-167). Builds a real signed tx from **SPV** UTXOs (no node),
signs via `DogecoinTransactionBuilder`, broadcasts via `DogecoinSpvDataSource(...).broadcast(...)`.
Mainnet refused exactly like `doge-self-broadcast`. **Run the whole block on `Dispatchers.IO`** (the
launch scope is Main.immediate → same ANR concern). Never logs keys/WIFs. Logs the txid as CLAIMED and
hints to poll `doge-spv-status` / `confirmationDepth`.

### P3-9 — Tests: fail-closed verifier matrix + datasource mainnet refusal; keep canaries green

- New `DogecoinSpvBroadcastVerifierTest` (pure JVM, libdohj params, no network — pattern of
  `DogecoinSpvKeyImportTest`). Set up a bitcoinj `Context` defensively
  (`Context.propagate(Context(DogecoinTestNet3Params.get()))`) so the test is robust to internal
  `Context.get()` checks. Cases:
  1. **Round-trip happy path over ALL `DogecoinSignerRegressionTest` goldens** (mainnet/testnet,
     compressed/uncompressed, multi-input/output) — `verifiedTransaction` returns a tx whose
     `hashAsString` == `transactionId(normalized)` and `bitcoinSerialize()` == normalized bytes. (This
     proves bitcoinj doesn't legitimately re-encode any tx shape bitchat emits differently — which would
     wrongly trip fail-closed and DoS legit sends.)
  2. **txid divergence** → `assertThrows`.
  3. **byte/trailing/structure divergence** → `normalize()` or verifier throws (never returns).
  4. **wrong params** (parse mainnet bytes with testnet params / vice versa) → rejected.
  5. **serializer is non-retaining** — assert the serializer in use re-encodes (so FC2 can't degrade to
     a tautology).
- `DogecoinWalletDataSourceTest`: SPV datasource broadcast on MAINNET throws (the `require` fires before
  any service touch). If constructing the singleton in pure JVM is infeasible, keep the mainnet/regtest
  assertion at the verifier/validator level and cover the datasource path in the on-device soak (note it).
- **Canaries MUST stay green unchanged:** `DogecoinSignerRegressionTest` (golden hex+txid) and
  `DogecoinSpvKeyImportTest` (bitcoinj address == app address) — if either fails after this work, the
  Option-B signer or address derivation moved; **reject the change, do not re-baseline.**

## Money-path risk summary (post-fix)

| Risk | Level | Mitigation |
|------|-------|-----------|
| Wrong bytes to wire | LOW | FC2 byte-equality over non-retaining serializer; tx immutable post-verify; `!hasWitnesses()`. No path found where bitcoinj's re-encoding reaches peers. |
| Fail open | LOW | Verifier throw + service null-refusals gate the only broadcast call; sheet `runCatching`→onFailure (no false success); `normalize` pre-validates. |
| Mainnet leak | LOW | 4-layer block (service/datasource/sheet/console). (SPV may still START+READ on mainnet; under SPV+mainnet the mesh-helper CTA — with its own mainnet ack — remains the only enabled broadcast; not a Phase 3 regression.) |
| Double-spend of own inputs | LOW (was MED-HIGH) | `receivePending` reserves inputs immediately; timeout returns Claimed (no retry-invite). |
| Claimed shown as Confirmed | LOW | `viaSpvClaimedOnly` until on-chain `depth>=1`; never off a bare relay. |
| ANR / hang | LOW (was MED) | Blocking await on `Dispatchers.IO`; timeout never blocks the lock; `minBroadcastConnections=MIN_PEERS<=peerCount`. |
| Money invisible (can't spend) | LOW-MED | Birthdate floor warned; transient `depth==0` post-sync surfaces a "still settling, retry" message, not a generic error. |

## Open items to resolve during/after on-device testnet soak

- **Peer floor reachability on testnet:** is `MIN_PEERS=4` reliably reachable? If not, pin a known-good
  testnet node via `addAddress` and/or lower the broadcast-only floor. Measure.
- **`BROADCAST_TIMEOUT_SECS` (25s):** confirm against real testnet re-announce behavior; it's only a
  best-effort propagation wait now (timeout = Claimed, not failure), so the exact value is non-critical.
- **`depthInBlocks==0` flakiness** for genuinely-confirmed inputs immediately post-sync (could make
  `requireSelectedInputsStillSpendable` intermittently throw — safe/fail-closed, but verify UX).
- **Arti Tor routing** for `PeerGroup` (clearnet-only ships now, disclosed; never silent fallback).

## On-device soak (the no-second-node surface)

```
doge-network testnet
doge-import-wif <funded testnet WIF>      # or doge-address + fund from the node
doge-spv-start
# poll until synced=true, peers>=4:
doge-spv-status
doge-spv-balance ; doge-spv-unspents      # confirm funded UTXOs visible
doge-spv-broadcast <addr> <amtDoge> [feePerKbKoinu]   # expect "CLAIMED txid=…"
# poll for on-chain depth (confirmationDepth / an explorer) until depth>=1
doge-spv-status
# negatives: broadcast before sync → ERR; regtest → refused/not offered; mainnet → refused at every layer
```

Then the sheet check: Connection → Built-in (testnet); send disabled while syncing (hint shown), enabled
once synced; run a send; receipt shows "Claimed — not yet chain-confirmed", then flips to confirmed.

## Out of scope (Phase 4)

Mainnet enablement (remove the 4-layer block), mainnet read-only soak, WIF-backup requirement, mainnet/
high-fee/policy acks for the SPV path, possible foreground service, user-supplied earlier birthdate for
old imported keys, Arti Tor routing. MAINNET is the last switch, per-spend authorized.
