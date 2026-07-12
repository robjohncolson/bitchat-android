# DES-1 implementation plan — TRUSTED_PERSONAL_NODE

**Status:** ACTIVE — **DES-1-A/B DONE**; next **DES-1-C**  
**Decisions:** `docs/des1-human-decisions.md` (DECIDED 2026-07-12)  
**Design:** `docs/dogecoin-trusted-personal-node-mainnet-design.md`  
**Guardrail:** DONE — `DogecoinSpendRoutePolicy` (mainnet generic RPC spend fail-closed)

Do **not** ship one mega-PR. Each card below is one Codex/Fable unit with its own tests.

---

## Already shipped

| Card | What landed |
|------|-------------|
| **DES-1-GUARDRAIL** | Mainnet spend only via SPV until TPN profile+session exist. Generic RPC read-only; helpers suppressed. |

---

## Phase map (design §17 → cards)

```text
DES-1-A   Profile + state machine + ceremony UI (no spend, no balance from TPN)
DES-1-B   Activation + readiness + read-only node balance/activity (provenance)
DES-1-C   Proof-backed UTXO snapshot (prev-tx gettransaction / txindex fallback)
DES-1-D   Send coordinator (hard fees, per-spend ack, preflight, Claimed)
DES-1-E   Settlement + dispute + SPV-6 confirm + reservations recovery
DES-1-F   Ops: host runbook, shareable profile export (no WIF), testnet soak gate
```

**Hard rule:** Each card must leave mainnet **no less safe** than after GUARDRAIL.  
Until **DES-1-D** lands, `dogecoinSpendRouteAllowed` still treats TPN as absent for spend (or only unlocks when a typed TPN session is ACTIVE and proofs exist).

---

## DES-1-A — Profile, state, trust ceremony (DONE)

### Goal

Durable **authorization profile** for one exact Tailscale origin + mainnet + bound Android address + Core wallet name, with explicit trust ceremony. **No** TPN balance/UTXO reads for spending. **No** send unlock.

### In scope

1. **Data model** (pure + unit tests):
   - Profile: exact origin, network=MAINNET, Android address, Core wallet id/name, policy version, revision, authorized-at, rescan operator attestation flag/timestamp.
   - Session: in-memory only; process death → inactive.
   - States: at least `UNAUTHORIZED`, `PROVISIONING`, `AUTHORIZED_INACTIVE`, `REVOKED` (other states may be stubs until B–E).
2. **Exact-origin validation** pure helpers (design §6.2 vectors).
3. **Persistence:** encrypted credentials separate from trust record; not in plaintext backups if app already has patterns; never log password.
4. **UI (mainnet settings):**
   - Draft fields: origin, user, password, wallet name — **zero I/O on edit**.
   - Disclosure + “Test this exact origin” → one-shot PROVISIONING probe (TLS/auth/chain/watch-only checks only; fixed method allow-list).
   - Oracle warning + multi-checkbox confirm → persist AUTHORIZED_INACTIVE.
   - SPV remains default backend; no “use TPN” activation yet (or activation UI disabled until B).
5. **Policy seam:** introduce a typed `DogecoinTrustedPersonalNodeAuthorization` / session holder that GUARDRAIL can later consult — but **A does not unlock spend**.
6. EN+JA strings.

### Must not

- Enable mainnet RPC spend or flip assist.
- Call `importaddress` / rescan / wallet-create from app.
- Call balance/listunspent for TPN spend path (provisioning probe only).
- Bake host URL into APK.
- Share/export QR (optional later in F).
- Weaken SPV gates.

### Done means

- Unit tests: origin vectors, state transitions, process-death session inactive, revision bump invalidates session.
- Zero network I/O on draft edit (test or documented spy).
- Suite green; assembleDebug.
- Manual: can authorize a draft against testnet **only if** design allows test path — **prefer pure tests + mock**; live Tailscale mainnet optional, no spend.

### Preferred agent

Codex (trust/policy).

---

## DES-1-B — Activation + readiness + read-only display (DONE)

### Goal

Explicit “Use trusted personal node” for this session → readiness check → show **node-reported** balance/activity with provenance. Still **no spend** unless proofs exist (spend stays closed until D).

### In scope

- Session ACTIVE after readiness (peers≥4, lag≤2, watch-only, etc. per decisions).
- Non-mutating RPC allow-list for reads.
- Provenance copy EN+JA.
- DEGRADED / AUTH_REQUIRED basic handling.
- Snapshot TTL ≤ 2 min for display freshness.

### Must not

- Sign/broadcast via TPN.
- Import/rescan from app.
- Auto-activate on app start.

---

## DES-1-C — Proof-backed UTXOs

### Goal

Every selectable mainnet UTXO carries verified full previous-tx (`gettransaction` hex preferred; `getrawtransaction` if `txindex=1`; else no spend candidates).

### In scope

- Bounded prev-tx parser + `VerifiedPrevout`.
- Snapshot binding + tip race rules (design §9).
- Malicious-vector unit tests.
- If proofs incomplete → stay read-only.

### Must not

- Sign with scalar-only node amounts.
- Unlock broadcast without C+D gates.

---

## DES-1-D — Send coordinator

### Goal

Proof-backed build/sign on phone → hard fee blocks (1 DOGE / 10%) → per-spend ack → `testmempoolaccept` + `sendrawtransaction` on bound route only → **Claimed**.

### In scope

- Extend `dogecoinSpendRouteAllowed` / route policy for **typed TPN session + ACTIVE + proofs**.
- Frozen review, no ack override on hard fees.
- Same-route only; no helper/SPV cascade.
- Attempt + input reservation persistence (encrypted, backup-excluded).

### Must not

- Generic mainnet My node spend without TPN.
- Fee ack bypassing hard caps.
- WIF to Core.

---

## DES-1-E — Settlement + dispute

### Goal

Claimed → Observed (SPV sees tx) → Confirmed (SPV ≥6). DISPUTED recovery per decisions (2 agreeing comparisons). Reservation release only on SPV evidence.

---

## DES-1-F — Ops + share

### Goal

- Host runbook: Serve, loopback, `txindex=1`, watch-only, no Funnel.
- Optional **connection profile export/import** (URL+auth only, no WIF).
- Testnet soak checklist before any mainnet canary.

---

## Suggested Codex order

```text
1. DES-1-A   ← start here
2. DES-1-B
3. DES-1-C
4. DES-1-D   (first spend unlock)
5. DES-1-E
6. DES-1-F
```

Do not parallelize A–D without orchestrator file splits (WalletSheet / policy thrash).

---

## Relationship to GUARDRAIL

```kotlin
// Today (after GUARDRAIL):
mainnet spend ⇔ backend == SPV

// After D (conceptual):
mainnet spend ⇔ backend == SPV
            || (TPN session ACTIVE && proofs && hard fee gates && per-spend ack)
// never: generic RPC / explorer / helper on mainnet
```

A–C must not widen that OR until D is complete and reviewed.
