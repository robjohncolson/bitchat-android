# Bitchat Android — Orchestrator Improvement Queue

**Audience:** human router + coding agents (Fable, Codex, Grok, etc.)  
**Status:** living queue — execute one card at a time unless the orchestrator says otherwise  
**Repo:** `C:\Users\rober\Downloads\Projects\bitchat-android`  
**Related:** `CONTINUATION_PROMPT.md`, `docs/family-wallet-ux-learnings-spec.md`, `docs/simple-profile-tightening-plan.md`, `docs/dogecoin-remote-node-access-spec.md` (deferred)

Agents: **read this whole file** when a short prompt says `Read docs/orchestrator-improvement-queue.md` and names a card id (e.g. `P0-1`).

---

## 1. Shared product context

| Item | Fact |
|------|------|
| App | bitchat Android — Kotlin, Compose, BLE mesh + Nostr + Dogecoin |
| Profiles | **Power** (advanced) · **Simple/Family** (curated people-first) |
| Money | Android holds WIF; **never** export/import WIF to Dogecoin Core |
| Personal node (testnet proven) | Tailscale Serve `https://athena.tail3f5172.ts.net` → `127.0.0.1:44555`; watch-only; phone signs; node broadcasts |
| SPV | Default Built-in backend; home-node assist is session/explicit |
| Mainnet home-node | **`TRUSTED_PERSONAL_NODE` not built** — design-only until orchestrator opens it |

### Hard rules (all cards)

1. **Testnet** for experiments unless the card says mainnet design-only.  
2. **No** public RPC, Funnel, `rpcbind=0.0.0.0`, broad `rpcallowip`.  
3. **No** silent mainnet gate relaxation; no WIF-to-Core.  
4. **Money path + signed mesh + group trust:** fail closed; minimal diff.  
5. **Never** forge `FavoriteRelationship` / Noise keys for tap-add or rename.  
6. Do not commit secrets; do not force-push/rewrite history unless human asks.  
7. Preserve unrelated user changes. Verify with focused Gradle when touching money/mesh.  
8. Follow `Agents.md` / project conventions. Inspect before edit.

### Agent routing

| Agent | Strength | When |
|-------|----------|------|
| **Fable** | Dependable delivery, more built-in guardrails | UI, nav, localization, structural refactors without gate changes |
| **Codex** | Precise, needs explicit rules in prompt | Money path, SPV/RPC, protocol, trust gates, receipt state machines |

**Default:** money-adjacent or trust-adjacent → Codex. Pure UX → Fable.

### After each card (human → orchestrator)

```text
ORCHESTRATOR UPDATE
Agent: Fable | Codex
Card: <id>
Status: DONE | BLOCKED | PARTIAL
Report:
(paste agent summary)
```

---

## 2. Queue order

```text
P0-1  SPV teardown ANR (doge-spv-stop / DebugCommandReceiver)
P0-2  SPV far-behind header stall
P0-3  Home-node UX honesty (draft vs saved, provenance)
P1-1  Simple contact rename (local pet names)
P1-2  Simple chat list (unread / preview / time / sort)
P1-3  Payment receipt loop (dogepaid, claim-until-corroborated)
P1-4  Simple Money entry (open wallet without App Info dig)
P2-1  Split DogecoinWalletSheet (behavior-preserving)
P2-2  Extract doge-* console from ChatViewModel
P2-3  Wallet EN/JA localization + parity test
DES-1 Design-only: mainnet TRUSTED_PERSONAL_NODE (no code)
```

**Dependencies:** Do not parallelize P0-1 and P0-2 (same SPV service). P1 cards may parallel after P0 if different agents and no file thrash. DES-1 anytime as design-only.

---

## 3. Card definitions

### P0-1 — SPV stop ANR

**Preferred agent:** Codex (or Fable)  
**Problem:** `doge-spv-stop` blocked `DebugCommandReceiver` ~60s → ANR. Long `DogecoinSpvService` lock contention with snapshots.  
**Goal:** `doge-spv-start|stop|status` never ANR; blocking SPV work off main and off `BroadcastReceiver` thread; DbgConsole logs preserved.  
**Must not:** change signer, broadcast verifier, WIF, RPC trust classifier.  
**Done means:** root cause + fix; `:app:testDebugUnitTest` + `:app:assembleDebug` (or document env block); manual verify steps for start/stop without ANR dialog.  
**Out of scope:** SPV sync stall (P0-2), wallet UI, Tailscale, mainnet trusted node.

---

### P0-2 — SPV far-behind stall

**Preferred agent:** Codex  
**Problem:** Height frozen far behind tip with `peers>0` (e.g. ~150k behind); `catchUpJob` insufficient.  
**Goal:** When `running && !synced && peers≥1`, height advances in bounded time **or** clear stalled status. Prefer `DogecoinSpvService` fix.  
**Must not:** relax `broadcast` synced/peer gates; no silent clearnet under Tor; no automatic store wipe.  
**Done means:** hypothesis with code refs + fix; tests if pure helpers; verify via `doge-spv-status`.  
**Out of scope:** ANR (P0-1), home-node UX, RPC.

---

### P0-3 — Home-node UX honesty

**Preferred agent:** Fable (Codex OK if presentation-only)  
**Problem:** Test connection = draft only; users mistook draft auth fail for broken wallet. Node balance needs provenance.  
**Goal:** Dirty draft vs saved clarity; Test CTA/result labeled draft; node/assist reads show short “node-reported…” provenance; empty watch set vs RPC error distinguishable.  
**Must not:** change trust allow-lists, reintroduce auto-probe, change signer/broadcast gates. New strings EN+JA together.  
**Done means:** UI-only (or pure formatters); assembleDebug; describe surfaces.  
**Out of scope:** TRUSTED_PERSONAL_NODE, SPV engine, Simple nav (P1-4).

---

### P1-1 — Simple contact rename

**Preferred agent:** Fable or Codex  
**Spec:** `docs/family-wallet-ux-learnings-spec.md` Workstream A.  
**Goal:** User sets local pet name; wins on title, list, notifications where applicable; survives process death.  
**Must not:** forge favorites/Noise keys; alter group trust gate; rewrite stored `message.sender`. EN+JA.  
**Done means:** rename UI + persistence + pure resolution unit test.  
**Out of scope:** receipts, chat list unread, wallet.

---

### P1-2 — Simple chat list quality

**Preferred agent:** Fable or Codex  
**Goal:** Unread counts, last preview, timestamp, activity ordering on Simple home list.  
**Must not:** reintroduce per-message `markRead` on main thread (use bulk/IO); expose Power channels. EN+JA.  
**Done means:** messenger-like list; tests for pure sort/unread if extracted.  
**Out of scope:** OS-killed Nostr push; Power list.

---

### P1-3 — Payment receipt loop

**Preferred agent:** Codex  
**Spec:** family-wallet-ux-learnings (receipts) + `docs/payment-request-status-spec.md` if present.  
**Goal:** After successful pay-from-request broadcast, auto-post structured **`dogepaid:`** (or spec final scheme) receipt; receiver **claim-until-corroborated**; idempotent per txid; convKey captured at Pay tap.  
**Must not:** put receipts under `dogecoin:` (double-pay); confirm paid from receipt alone; weaken broadcast gates. Hook after Claimed only. EN+JA. Parser/state unit tests.  
**Out of scope:** TRUSTED_PERSONAL_NODE, RPC classifier rewrite, rename.

---

### P1-4 — Simple Money entry

**Preferred agent:** Fable  
**Goal:** ≤2 taps from Simple home to Dogecoin wallet sheet; sane back stack.  
**Must not:** change money gates; don’t dump RPC setup into default Simple path. EN+JA.  
**Done means:** nav only; assembleDebug.  
**Out of scope:** Coin redesign; mainnet mode.

---

### P2-1 — Split DogecoinWalletSheet

**Preferred agent:** Codex (strict no-behavior-change) or Fable  
**Goal:** Split ~5k-line sheet into focal/send/receive/settings/assist/activity files.  
**Must not:** any behavior/gate/UX change.  
**Done means:** compile + doge unit tests + assembleDebug; file map.  
**Fail if:** money-path logic must change to split — stop and report.

---

### P2-2 — Extract doge console from ChatViewModel

**Preferred agent:** Codex or Fable  
**Goal:** Move `doge-*` DebugConsole commands out of `ChatViewModel`.  
**Must not:** change command names, log formats, or mainnet console refusals.  
**Done means:** same console behavior; assembleDebug.  
**Out of scope:** new commands (unless seam requires).

---

### P2-3 — Wallet JA localization

**Preferred agent:** Fable  
**Goal:** Dogecoin wallet user strings EN+JA parity; move hardcoded literals as needed.  
**Must not:** translate protocol constants; no logic changes. Optional parity unit test.  
**Done means:** resources + wiring; assembleDebug.  
**Out of scope:** full-app JA audit.

---

### DES-1 — Mainnet TRUSTED_PERSONAL_NODE (design only)

**Preferred agent:** Codex  
**Goal:** Design doc under `docs/` only — opt-in, Tailscale origin bind, what SPV gates remain, fee caps, claim-until-observed, failure modes, test plan, non-goals.  
**Must not:** implement code; recommend WIF-to-Core; claim watch-only = honest oracle.  
**Done means:** markdown path + open questions for human.

---

## 4. Proven baseline (do not re-litigate)

- Tailscale Serve + loopback Core RPC works on testnet.  
- Watch-only + full rescan → non-zero UTXOs for phone address.  
- `doge-self-broadcast` phone-sign + node-broadcast E2E PASS (example txid era 2026-07-11).  
- Home-node balance works after rescan.  
- Draft vs saved RPC is intentional architecture; UX was the bug, not Tailscale.

---

## 5. Explicitly not in this queue

- Custom Tor onion gateway / Arti rebuild program  
- Public RPC / Funnel helpers  
- Large media over BLE  
- Electrum-style wallet redesign  
- Implementing mainnet TRUSTED_PERSONAL_NODE without DES-1 + human go-ahead  

---

## 6. Verify defaults

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

On-device (when relevant): debug package `com.bitchat.droid.debug`, console via `DebugCommandReceiver` + logcat tag `DbgConsole`. Money-path: testnet only unless card says otherwise.

---

*Orchestrator: keep short prompts pointing at card ids in this file; update card status in a future revision when the human marks DONE.*
