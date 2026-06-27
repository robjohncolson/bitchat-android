# Dogecoin Wallet — SPV (bitcoinj + libdohj) Integration Plan

> Status: proposed roadmap (no app code written yet). Grounded in a code audit of the
> current wallet (`app/src/main/java/com/bitchat/android/features/dogecoin/`), the
> Langerhans/libdohj reference wallet (`dogecoin-wallet-new/`), and a **proven** pure-JVM
> feasibility spike (`tools/spv-spike/`, commit `f9d13c1`). Sibling of
> [`dogecoin-frictionless-plan.md`](dogecoin-frictionless-plan.md) — this is the "self-contained,
> no-node, no-paid-key" endgame for that plan's friction diagnosis.

## Why SPV, and what's already proven

The wallet's two existing backends each hit a wall: **RPC** needs a node the user runs/reaches,
and the **explorer** "no-node" mode works but every free public Dogecoin explorer gates anonymous
access (Blockchair → paid key; Trezor/Dogechain → Cloudflare 403). SPV removes both: talk straight
to the Dogecoin P2P network, keys on-device, free.

The feasibility spike settled the make-or-break questions (see `tools/spv-spike/README.md`):

- **libdohj is obtainable** — `com.github.dogecoin:libdohj:v0.15.9` resolves from JitPack and pulls
  `org.bitcoinj:bitcoinj-core:0.15.9` (+ `guava:28.2-android`) from Maven Central; compiles on JDK 17.
- **The engine is correct** — synced **275,487 testnet headers, zero `VerificationException`**, through
  the testnet DigiShield + AuxPoW transitions (libdohj issue #15 rejected nothing real).
- **The no-node path works** — connected to local *and* public testnet peers; Dogecoin Core still
  defaults `peerbloomfilters=1`, so BIP37 SPV is alive on Dogecoin (unlike post-0.19 Bitcoin).

Two facts that shape the build: testnet is ~65.7M blocks (mainnet ~5.4M), so a **generated
checkpoints file is essential** — and a fresh on-device key (recent birthdate) then syncs near-instantly.

## The core decision: SPV is a peripheral, never a second signer (UTXO fork → Option B)

bitcoinj/libdohj is used **only as a UTXO source** (`wallet.getUnspents() → DogecoinUtxo`) and a
**broadcast sink** (`peerGroup.broadcastTransaction` over bitchat's already-signed raw hex).
`DogecoinTransactionBuilder.createSignedTransaction` stays the **single builder/signer**.

Rationale: 100% of the money-safety logic (largest-first input selection, `MIN_TX_FEE` floor, low-S
RFC6979 ECDSA, SIGHASH_ALL, overflow-safe math, per-network separation, 10-min expiry, txid
consistency, mainnet/high-fee/policy acknowledgements, `requireSelectedInputsStillSpendable`
re-check at `DogecoinTransaction.kt:143`, `DogecoinRawTxValidator` standardness) already lives
**outside** the backend. Option B is purely additive and preserves every gate. Option A
(`Wallet.sendCoinsOffline`/`completeTx`) would add a second coin selector, fee engine, signer, and a
persisted `.wallet` keychain for the same real funds — a divergent money path with none of bitchat's
gates — and would widen key custody into bitcoinj's keychain. Rejected.

## Staging (lowest-risk, money path touched last)

| Phase | What | Money path? |
|---|---|---|
| **0** | Dependency wiring + **bcprov isolation + signing regression test** | No (no feature code) |
| **1** | `WalletDataSource` read abstraction (EXPLORER broadcast stays blocked) | No (behavior-preserving) |
| **2** | SPV **read-only** service (sync-on-demand) + birthdate policy + checkpoints | No (broadcast disabled) |
| **3** | SPV as **broadcast sink, testnet only**, fail-closed, Claimed-not-Confirmed | Testnet only |
| **4** | **Mainnet** enablement, after a mainnet read-only soak | Mainnet, per-spend user-authorized |

### Phase 0 — Dependency wiring + signer regression gate (no feature code)
- Add JitPack to `settings.gradle.kts` `dependencyResolutionManagement.repositories` (NOT
  `app/build.gradle.kts` — `FAIL_ON_PROJECT_REPOS` rejects project repos).
- Add `libdohj v0.15.9` + an explicit `guava:28.2-android` pin to `gradle/libs.versions.toml`.
- **Resolve the BouncyCastle split (hard gate):** bitcoinj 0.15.9 drags its own bcprov; force/exclude
  it to the app's audited **bcprov 1.70** so `DogecoinKeyGenerator`/`DogecoinTransactionBuilder` keep
  the exact provider. This is the *one* place SPV could silently reach the money path under Option B.
- **Signing regression test** (`DogecoinSignerRegressionTest.kt`): assert `createSignedTransaction`
  produces **identical signed hex + txid** vs fixed baseline fixtures on the new classpath. Must pass
  before any feature code.
- Measure `assembleRelease` APK delta; add R8 keep rules for `org.bitcoinj.**`/`org.libdohj.**`/
  `com.google.protobuf.**`.

### Phase 1 — `WalletDataSource` abstraction (reads)
- Define `DogecoinWalletDataSource` (`getBalance`/`getActivity`/`listUnspent`/`broadcast`). SPV's
  push-model status is **not** forced through it (thin sealed status type; SPV via `StateFlow`).
- Adapters: `DogecoinRpcDataSource` (no logic change), `DogecoinExplorerDataSource` (**reads only** —
  `broadcast()` stays hard-blocked so the refactor does NOT re-open the console-only mainnet path).
- `DogecoinBackend{RPC,EXPLORER,SPV}` enum + `loadBackend`/`saveBackend` (mirror `helperEnabledKey`),
  default **RPC**. Route the ~7 hardcoded `rpcClient.*` read sites in `DogecoinWalletSheet.kt`
  through the interface. (Run `gitnexus_impact` on the send sites first.)

### Phase 2 — SPV read-only service + birthdate policy + checkpoints (no money path)
- `DogecoinSpvService` (**sync-on-demand while the sheet is open — NOT a foreground service in v1**):
  libdohj params per network, `new Wallet(params)` + `wallet.importKey(ECKey.fromPrivate(...))` —
  **never** `Wallet.createDeterministic`; share the one existing key (identical address, version
  bytes match). REGTEST → SPV disabled.
- **Birthdate policy (must-fix):** `created_at` is overwritten with *import time* by
  `importWalletFromWif` (`Repository:403`), and a re-import of a funded generated key rewrites it
  forward — feeding it to `CheckpointManager` would fast-forward **past** funding txs (forward-scan
  only → silent money-missing). Persist a **separate, conservative `spv_birthdate` floor** a later
  backup can never push forward.
- **Checkpoints:** ship per-network Dogecoin assets (generated against libdohj params or vendored
  from `dogecoin-wallet-new`; a Bitcoin checkpoints file won't validate AuxPoW/Scrypt), reconciled
  against the conservative birthdate. The `SPVBlockStore` is **disposable** (keys live in
  EncryptedSharedPreferences) — a corrupt store can always be deleted and re-synced.
- `listUnspent` maps `wallet.getUnspents()` → `DogecoinUtxo` (own P2PKH only), **depth→confirmations
  honestly**, balance separates **AVAILABLE vs ESTIMATED**. `broadcast()` throws this phase.
- Status via `DownloadProgressTracker` → `StateFlow {height, headers, peerCount, verificationProgress,
  syncedAsOfBlockN+date}`; the sheet **observes** it. `nodeReady` = caught up within N blocks AND
  `peerCount ≥ floor`; `broadcastReady` = `peerCount ≥ floor`.
- **Cross-check is a DbgConsole/soak tool, not production sheet UI** in this phase. Oracle is the
  **RPC node on testnet** (there is no public testnet explorer — `ExplorerClient.kt:142/129`);
  explorer agreement is reserved for the mainnet soak.

### Phase 3 — SPV broadcast sink (testnet only, fail-closed)
- `broadcast()`: run `DogecoinRawTxValidator.normalize`, deserialize into a libdohj `Transaction`,
  **re-serialize and recompute the txid via `DogecoinTransactionBuilder.transactionId`** — **FAIL
  CLOSED** on any byte/txid divergence; only on exact match call `peerGroup.broadcastTransaction`.
- Pin bitcoinj `minBroadcastConnections` to the peer floor (single-peer `TransactionBroadcast` can
  deadlock waiting for re-announcement).
- Set `mempoolAcceptance.checked=false` on the SPV path so the **existing**
  `requiresPolicyUnavailableAcknowledgement` gate fires (no `testmempoolaccept` for SPV).
- Render peer-receipt as **"Claimed" only** — peers silently drop non-standard/invalid txs and return
  no reason. Require the existing **Claimed→Confirmed** on-chain corroboration before any success.
- MAINNET stays hard-blocked here.

### Phase 4 — Mainnet enablement (user-gated, last)
- Require a **mainnet read-only soak** (SPV agrees with the explorer over a defined window) — this is
  where WIF-import birthdate correctness is finally validated (testnet genesis sync is impractical).
- Remove the SPV mainnet block, routing every send through the **existing** production gates verbatim
  (WIF-backup requirement, mainnet-ack, high-fee-ack, policy-ack, `requireSelectedInputsStillSpendable`,
  `hasConsistentRawTransactionId`, 10-min expiry). No console-style bypass. Fail-closed txid re-verify
  still applies. RPC/explorer stay selectable as the cross-check anchor.

## Money-path guardrails (non-negotiable)
1. `DogecoinTransactionBuilder` is the ONLY builder/signer; bitcoinj never builds/signs/completes.
2. Phase 0 hard gate: bcprov pinned to 1.70 + signing regression test green **before** any feature code.
3. SPV broadcast **fails closed** on any re-serialization/txid mismatch; never hands bitcoinj's bytes
   to the network unless they exactly match bitchat's signed hex.
4. SPV broadcast renders as **Claimed only**, never Confirmed, until on-chain corroboration.
5. Birthdate is **never** an import/backup timestamp — conservative `spv_birthdate` floor.
6. Peer floor **> 1** (target 3–4) for `nodeReady`, `broadcastReady`, and `minBroadcastConnections`.
7. `mempoolAcceptance.checked=false` engages the existing policy-ack gate; invent no bypass.
8. Honest `depth→confirmations`; AVAILABLE vs ESTIMATED; never build against a mid-sync/pending set;
   reorg can revert a displayed coin → gate spends on `nodeReady` and re-list before send.
9. Read-only first; mainnet broadcast is the last switch, per-spend user-authorized.
10. Per-network isolation; REGTEST has no SPV. EXPLORER broadcast stays blocked through this refactor.

## File change list
- `settings.gradle.kts` — JitPack repo.
- `gradle/libs.versions.toml` — libdohj + guava pin + bcprov 1.70 force/constraint.
- `app/build.gradle.kts` — SPV deps, bcprov force/exclude, R8 keep rules.
- `app/src/test/.../DogecoinSignerRegressionTest.kt` — **NEW** Phase-0 gate.
- `DogecoinWalletRepository.kt` — `DogecoinBackend` enum + load/save; separate `spv_birthdate` pref;
  compressed-flag getter; persist last SPV synced height/date.
- `DogecoinWalletDataSource.kt` / `DogecoinRpcDataSource.kt` / `DogecoinExplorerDataSource.kt` — **NEW**.
- `DogecoinSpvService.kt` / `DogecoinSpvDataSource.kt` — **NEW**.
- `DogecoinWalletSheet.kt` — route reads through the interface; backend selector; observe SPV flow;
  `mempoolAcceptance.checked=false` on SPV; Claimed-only render; no in-sheet cross-check in read phase.
- `ChatViewModel.kt` — own `DogecoinSpvService` at process scope, start/stop on-demand.
- `DbgConsole.kt` — SPV-vs-oracle cross-check commands (soak validation surface).
- `app/src/main/assets/dogecoin-checkpoints-{mainnet,testnet}.txt` — **NEW**, committed before referenced.

## Open questions (need a user decision or a live test)
- **Arti-SOCKS ↔ bitcoinj P2P (live test):** does `PeerGroup` over the local Arti SOCKS port reach
  live Dogecoin peers? The spike was **clearnet-only**, and Arti couldn't build circuits last session.
  Until proven, the privacy mitigation doesn't exist and clearnet address↔IP exposure must ship in the
  selector copy. **Never silently fall back to clearnet.**
- **Conservative `spv_birthdate` floor (user + known-good date):** what per-network pre-wallet epoch is
  safe; do we also let the user supply an earlier date for an old imported key?
- **Checkpoint generation (user/infra):** self-generate via `BuildCheckpoints` (needs a fully-synced
  libdohj node — the testnet node qualifies) vs vendor `dogecoin-wallet-new`'s asset; regeneration cadence.
- **Peer floor value (live tuning):** is 3–4 right for Dogecoin testnet/mainnet peer sets?
- **`nodeReady` "within N blocks" threshold + reorg surfacing (live tuning).**
- **Mainnet soak criteria (user):** duration / number of SPV-vs-explorer agreements to unblock Phase 4.
- **On-demand sync acceptability (live test):** is sheet-scoped sync fast enough on real devices, or
  does Samsung's aggressive kill force a later foreground-service upgrade?
- **APK size budget (user, pending Phase 0 number):** acceptable bitcoinj+guava delta; ABI split?
- **Final funded-mainnet SPV broadcast run (user):** mirror the deferred funded-mainnet RPC/explorer step.
