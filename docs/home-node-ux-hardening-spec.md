# Spec: Home-node confirmation UX + wallet-open hardening

**Status:** ready for implementation  
**Branch context:** `simple-family-profile`  
**Related:** `docs/orchestrator-improvement-queue.md` cards **CONF-RPC-PROGRESS**, **WALLET-OPEN-HARDEN**  
**Related shipped:** home-node assist send unlock (`HOME-NODE-SEND`), verificationprogress clamp  

**Audience:** Codex (money-adjacent) / implementers  

---

## Product thesis

Home-node assist exists so the user can **send and track payments** when Built-in SPV is behind.  
Shipping send without confirmation progress and with freezes on wallet open is not “bulletproof.”

**Normative:**

1. Phone remains the only signer (never WIF-to-Core).  
2. Effective backend route-mirrors **reads, send, and confirmation progress**.  
3. Node-reported data stays labeled **not independently verified by Built-in** until SPV agrees.  
4. SPV lag must not blank confirmation UI for a node-path send.  
5. Opening the wallet must never ANR / freeze the messenger.

---

## Observed failures (hardware)

| Symptom | Likely cause |
|---------|----------------|
| Tx later “Confirmed” but never saw 0→6 | Focal confirm ring + depth poll are **SPV-only**; under assist `dogecoinBackend == RPC` so ring poll clears. RPC activity poll (~15s) can jump 0 → ≥6 between ticks. |
| App freeze when tapping wallet from Simple home | Heavy sheet first composition + SPV start/stop thrash (`start` on open, `stop` on every dismiss) + concurrent RPC status/balance + SPV lock contention. |

---

## Card CONF-RPC-PROGRESS

### Goal

While effective backend is **RPC** (persisted My node **or** session home-node assist), drive post-send confirmation progress from the **node**, not SPV `confirmationDepth`.

### Current code anchors (as of queue)

- Ring fill: `DogecoinWalletSheet` `LaunchedEffect(sentReceipt…)` — **returns early if `dogecoinBackend != SPV`**.  
- Depth source: `spvService.confirmationDepth` only.  
- Pending list under RPC: `walletActivity` from RPC (OK in principle) but slow/coarse refresh.  
- Focal `confirming` mode requires `spv && confDepth != null`.

### Required behavior

#### A. Progress source by effective backend

| Effective backend | Confirmation progress source |
|-------------------|------------------------------|
| SPV | Unchanged: SPV `confirmationDepth` / `snapshotTransactions` |
| RPC (incl. assist) | Node: `listtransactions` / activity rows and/or `gettransaction` confs for tracked txids |

#### B. Active send tracking

After successful broadcast that sets `sentReceipt`:

1. Always track `sentReceipt.txid` for progress UI regardless of backend.  
2. **RPC path:** poll node confirmations for that txid on a **fast interval** (recommend **3–5s**) until `confirmations >= DOGECOIN_SPV_CONFIRM_TARGET` (6) or budget expires.  
3. Show intermediate values **0,1,2,…** when the node reports them — do not only show terminal Confirmed.  
4. If node still says 0 (mempool), UI may show **0/6** honestly for as long as that is true (testnet block gaps OK).  
5. Provenance caption when RPC-driven: e.g. “Confirming on home node · N/6 — not independently verified by Built-in.” EN+JA.

#### C. Surfaces to update (minimum)

1. **Focal ring** (default NONE view): allow CONFIRMING mode when RPC backend + active tracked send depth known.  
2. **Pending cards** under balance: refresh often enough that confs climb visibly for the active send.  
3. **Tx detail dialog** (tap pending): same depth source as list.  
4. **Post-send receipt** copy: do not imply SPV saw the tx if only node did.

#### D. SPV path unchanged

- Built-in only: keep existing SPV ring + SPV pending snapshot behavior.  
- Do **not** require SPV synced to show node-path progress.  
- Optional later (out of scope for this card): when SPV depth ≥1 for same txid, badge “also seen by Built-in.”

#### E. Money path

- **No** change to: signer, raw tx verifier, WIF gates, mainnet acks, fee/policy gates, RPC trust classifier, assist session-only rules.  
- Confirmation polling is **read-only** presentation (like existing `confirmationDepth` poll).  
- Never feed node confs into signing input selection without existing RPC UTXO path.

### Non-goals (this card)

- TRUSTED_PERSONAL_NODE mainnet mode.  
- Fixing testnet SPV header sync speed.  
- Full WalletSheet state hoist (WALLET-SESSION).  
- Changing dogepaid claim rules (still claim-until-local-corroboration per existing receipt design).

### Acceptance

**Automated:**

- Pure helper: `confirmationProgressSource(backend) → SPV|RPC` and/or `shouldShowConfirmingRing(...)`.  
- Unit tests: RPC backend + sentReceipt → uses node depth path; SPV backend → SPV path; null receipt → no confirming.  
- Mock/fixture: conf sequence 0,1,2 does not skip if polled (helper state machine optional).

**Manual (testnet):**

1. Built-in lagging, Use home node, small self-send.  
2. After broadcast, focal or pending UI shows **0/6** (or climbing N/6) **before** final Confirmed.  
3. User can watch at least one intermediate step under normal block times (or forced by watching node confs).  
4. Stop assist / back to SPV: SPV path still works when synced.  
5. No gate regression on mainnet (code review + existing tests).

### Likely files

- `DogecoinWalletSheet.kt` (effects + ring conditions)  
- `DogecoinWalletSupport.kt` (constants, pure helpers)  
- `DogecoinRpcClient.kt` only if a thin `getTransactionConfirmations` is cleaner than listtransactions  
- `DogecoinWalletComponents.kt` if pending row needs provenance  
- `values` / `values-ja` strings  
- unit tests under `app/src/test/.../dogecoin/`

---

## Card WALLET-OPEN-HARDEN

### Goal

Opening the Dogecoin wallet from Simple (or Power) never freezes/ANRs the app. First interactive chrome appears quickly; heavy work is deferred, cancellable, and off the main thread.

### Current code anchors

- Simple: `showWallet = true` → compose full `DogecoinWalletSheet`.  
- Sheet: `LaunchedEffect(persistedBackend)` → `spvService.start` on IO.  
- Sheet: `DisposableEffect` → **always** `spvService.stop()` on dispose (open/close thrash).  
- Assist: concurrent `refreshNodeStatus` + `refreshWalletBalance`.  
- Sheet still very large (~4.7k lines after helper extract) — first composition is expensive.

### Required behavior

#### A. Progressive open

1. First frame: sheet chrome (title, close, skeleton balance or last-known cached display if safe).  
2. Then schedule IO: SPV start (if needed), RPC status/balance (if RPC/assist).  
3. Never block first composition on network or SPV mmap start beyond already-off-Main patterns — ensure **no** main-thread `start`/`stop`/`snapshot*`/`RPC`.

#### B. SPV lifecycle thrash

1. **Do not** stop SPV on every sheet dismiss if persisted backend is still SPV and process is alive. Prefer:
   - keep running after dismiss, **or**
   - delayed stop (e.g. 30–60s idle) cancelled if sheet reopens.  
2. `start` must stay idempotent and off Main (already partly true).  
3. Rapid open/close 20× must not deadlock or ANR.

#### C. Work serialization

1. One supervisor/scope for wallet sheet IO (status, balance, activity, SPV start/stop).  
2. Dismiss cancels in-flight RPC; no `nodeStatus` / balance updates applied after dispose (generation token — pattern already used for rpcConfigRevision).  
3. Avoid holding SPV lock on Main under any path.

#### D. UX under load

1. If SPV starting: show syncing skeleton, not frozen blank.  
2. If node status pending: “Checking home node…” (existing patterns OK).  
3. Never infinite spinner without timeout + error string.

#### E. Money path

- No change to sign/broadcast gates.  
- No weakening of trust classifier.  
- Assist remains session-only.

### Non-goals (this card)

- Full section split / `DogecoinWalletState` hoist (follow-up WALLET-SESSION).  
- SPV tip catch-up performance.  
- CONF-RPC-PROGRESS (may land first or in parallel if no file thrash — prefer CONF first).

### Acceptance

**Automated:**

- Hard to unit-test ANR; prefer: no Main dispatcher in `DogecoinSpvService.start/stop` call chain from sheet without `withContext(IO)`; regression tests for idle-stop timer pure logic if extracted.

**Manual:**

1. From Simple home, open wallet 20× rapidly — no freeze, no force-stop.  
2. Cold open: interactive close button + title within ~1s on S24.  
3. With SPV backend: open → dismiss → reopen does not multi-second freeze.  
4. With assist: open still responsive while status/balance load.  
5. Existing money canaries / unit suite green.

### Likely files

- `DogecoinWalletSheet.kt` (lifecycle effects)  
- `DogecoinSpvService.kt` only if stop policy needs a refcount/idle timer (keep minimal)  
- Optional small `DogecoinWalletLifecycle` helper + tests  
- EN+JA only if new user-visible loading copy

---

## Dependencies

```text
CONF-RPC-PROGRESS   (Codex first — product correctness of send UX)
WALLET-OPEN-HARDEN  (Codex second — or after CONF if same files)
```

Do not parallelize both on `DogecoinWalletSheet.kt` without explicit orchestrator split.

---

## Explicitly out of this document’s implement scope

- Mainnet TRUSTED_PERSONAL_NODE code (see `docs/dogecoin-trusted-personal-node-mainnet-design.md`)  
- Public RPC / Funnel / WIF-to-Core  
- Encrypt chat history  
- OS-killed Nostr notifications  

---

## Verify defaults

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

On-device: `com.bitchat.droid.debug`, S24 `RFCX81GNBRE`, testnet + Tailscale home node as needed.
