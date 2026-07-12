# DES-1 human decisions — mainnet TRUSTED_PERSONAL_NODE

**Status:** AWAITING HUMAN  
**Design:** `docs/dogecoin-trusted-personal-node-mainnet-design.md`  
**Queue:** Wave 4 — `DES-1-DECISIONS` before any implementation  

Reply with **“defaults OK”** or list overrides by number. Implementation stays closed until this is answered.

---

## Recommended defaults (v1)

| # | Topic | Recommended default |
|---|--------|---------------------|
| 1 | **Fee hard blocks** | Keep `>= 1 DOGE` absolute **or** `>= 10%` of send amount as hard block (whichever triggers first). Accept that the existing fee floor can block very small mainnet sends. |
| 2 | **Node readiness** | Peers **≥ 4**; headers/blocks lag **≤ 2**; readiness/snapshot TTL **≤ 2 min**; min input confs **6**; “rescan complete” = operator-confirmed once at bind + app re-checks `listunspent`/watch flags on each activation (no silent re-import). |
| 3 | **Prev-tx source** | Prefer **wallet-scoped `gettransaction` hex** for watch-only inputs on Core 1.14.9; if unavailable, require **`txindex=1`** + `getrawtransaction`. If neither works on host → **read-only mode** (no spend). |
| 4 | **Opt-in cadence** | Durable profile authorization **once** + **session activation** each process/session + **per-spend acknowledgement** on Review. Full oracle warning on first authorize and on session activation; short reminder on each spend. |
| 5 | **Legacy mainnet My node** | **Yes — guardrail first:** generic mainnet “My node” / assist-like RPC spend path **fails closed** unless TPN profile+session active. Balance/read may still be blocked or labeled unavailable until TPN. Ship as **DES-1-GUARDRAIL** before full send path if desired. |
| 6 | **Rescan evidence** | Operator checklist in host runbook + app stores **bind-time rescan attestation** (timestamp + address + “operator confirmed”) — not trusted as honesty proof; only as UX gate. |
| 7 | **Raw Core vs gateway** | **Raw watch-only Core behind Tailscale Serve** OK for v1 with method-unscoped `rpcauth` **only** on loopback + Serve (never Funnel / public). Typed gateway = later. |
| 8 | **Tailscale posture** | **Manual** Serve/Funnel/ACL/Tailnet Lock checklist for v1; no LocalAPI automation required for first ship. |
| 9 | **Dispute recovery** | Clear `DISPUTED` only after **2** fresh fully-synced SPV-vs-node comparisons agreeing, **≥ 6** confs deep, with no conflicting spends in that window. Profile is **not** auto-deleted. |
| 10 | **Settlement** | SPV depth **≥ 6** moves OBSERVED → CONFIRMED. **No** explorer as observer in v1. |
| 11 | **Ambiguous broadcast** | Same-byte/same-route retry while signed review eligible; release reservations only on **SPV evidence** of exact conf or conflicting spend. **No** timed auto-release. No destructive override in v1. |
| 12 | **Release proof** | Testnet soak **≥ 7 days** with fault matrix from design § ops; mainnet canary is a **separate** explicit human go after dry runs. |
| 13 | **Debug mainnet path** | Keep confirmation-gated SPV mainnet debug commands **only if** proven isolated from TPN profile/proofs/reservations; otherwise remove before TPN release. |

---

## Non-negotiables (already decided by design)

- Phone remains the only signer — **never** WIF-to-Core.  
- No public RPC, Funnel, `rpcbind=0.0.0.0`.  
- Loopback Core + Tailscale **Serve** only.  
- Watch-only is **not** an honest oracle — claim-until-SPV-observed.  
- TPN is **not** a flip of testnet home-node assist.

---

## After you answer

1. Orchestrator locks answers into this file as **DECIDED**.  
2. Optional card **DES-1-GUARDRAIL** (item 5) can ship alone.  
3. Then phased **DES-1-IMPL** PRs per design § boundaries (not one mega PR).

---

## Your reply templates

```text
DES-1 decisions: defaults OK
```

or

```text
DES-1 decisions:
1 keep
2 peers=3 lag=3 ...
5 skip guardrail-first, wait for full TPN
...
```
