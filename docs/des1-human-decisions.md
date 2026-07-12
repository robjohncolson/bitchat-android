# DES-1 human decisions — mainnet TRUSTED_PERSONAL_NODE

**Status:** DECIDED (2026-07-12)  
**Design:** `docs/dogecoin-trusted-personal-node-mainnet-design.md`  
**Queue:** Wave 4 — decisions locked; next **DES-1-GUARDRAIL**, then phased **DES-1-IMPL**

---

## Product notes from human (locked)

- **Fee rates:** Network/app minimum remains **0.01 DOGE/kB**. Node UI “such expensive” max (~5.2 DOGE/kB) is a ceiling warning, not the default rate.  
- **Home server:** User-entered / shareable profile (URL + auth). **Never** baked into the APK. Easy one-person → another share of connection details is desired (export later; not a secret in the store build).  
- **Host:** Personal laptop 24/7 behind Tailscale Serve is the intended v1 shape.

---

## Decisions (numbered)

| # | Topic | Decision |
|---|--------|----------|
| 1 | **Fee hard blocks** | **Keep** absolute **≥ 1 DOGE** total fee **or** **≥ 10%** of send amount as hard blocks (no ack override). App min/default rate stays **0.01 DOGE/kB**. |
| 2 | **Node readiness** | **Defaults OK:** peers ≥ 4; header/block lag ≤ 2; readiness/snapshot TTL ≤ 2 min; min input confs 6; rescan complete = operator-confirmed at bind + re-check watch flags on activation. |
| 3 | **Prev-tx source** | **Prefer wallet-scoped `gettransaction` hex** when it works on the watch-only wallet. **Mainnet host SHOULD run `txindex=1`** so `getrawtransaction` is a reliable fallback for full prev-tx bytes. If neither yields verified prev-tx for a selected input → **read-only** (balance/activity may show as node-reported; **no spend**). No scalar-amount-only signing. |
| 4 | **Opt-in cadence** | **Defaults OK:** durable profile authorization + session activation + **per-spend acknowledgement** on Review. Full oracle warning on first authorize and session activation; short reminder each spend. |
| 5 | **Legacy mainnet My node** | **Guardrail first:** generic mainnet “My node” / non-TPN RPC **spend path fails closed** until a valid TPN profile + active session exist. Ship **DES-1-GUARDRAIL** before full TPN send path. |
| 6 | **Rescan evidence** | **Defaults OK:** host runbook + bind-time operator attestation in app (UX gate only, not honesty proof). |
| 7 | **Raw Core vs gateway** | **Defaults OK:** raw watch-only Core behind Tailscale Serve for v1; typed gateway later. |
| 8 | **Tailscale posture** | **Defaults OK (manual Serve setup).** App does not auto-configure Tailscale. Connection details are **user-entered profile**, shareable later via export/QR — **not** compiled into APK. |
| 9 | **Dispute recovery** | **Defaults OK:** clear DISPUTED only after **2** fresh fully-synced SPV-vs-node agreements, ≥ 6 confs context, no conflicting spends; profile not auto-deleted. |
| 10 | **Settlement** | **Defaults OK:** SPV depth ≥ **6** → CONFIRMED. No explorer as observer in v1. |
| 11 | **Ambiguous broadcast** | **Defaults OK:** same-byte/same-route retry; release reservations only on SPV evidence of exact conf or conflicting spend; no timed auto-release; no destructive override in v1. |
| 12 | **Release proof** | **Defaults OK:** multi-day testnet soak + fault matrix; mainnet canary needs separate explicit go. |
| 13 | **Debug mainnet path** | **Defaults OK:** keep only if isolated from TPN profile/proofs/reservations; else remove before TPN release. |

---

## Non-negotiables (unchanged)

- Phone remains the only signer — **never** WIF-to-Core.  
- No public RPC, Funnel, `rpcbind=0.0.0.0`.  
- Loopback Core + Tailscale **Serve** only.  
- Watch-only is **not** an honest oracle — claim-until-SPV-observed.  
- TPN is **not** a flip of testnet home-node assist.

---

## Implementation order (after this lock)

```text
DES-1-GUARDRAIL   DONE
DES-1-A … F       See docs/des1-implementation-plan.md (start with A — profile, no spend)
```

Share/export of connection profiles is DES-1-F; must never include WIF.
