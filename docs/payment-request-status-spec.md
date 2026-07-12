# Spec: Payment-request status view (Workstream B, slice B0)

**Status:** proposed — v1 (draft grounded by 3 code-mapper agents; adversarial critique folded, 10/10 findings addressed)
**Parent spec:** `docs/family-wallet-ux-learnings-spec.md` v2 (**binding** — esp. §4.2 wire grammar, R-B3a claim-until-corroborated, R-B8 chain-observed amounts, R-B9 cross-network, R-B10/G0 localization, R-C11 read-only polling, §9 do-not-build)
**Branch context:** `simple-family-profile` (incl. PR1 display names `3300e1f`, home-node assist `bbf7618`)
**Audience:** implementers
**Slice contract:** shippable **before** PR2 (`dogepaid:` receipts). **Zero wire changes.** PR2's `req`-hash binding must upgrade matching precision **without rework** of either render site.

---

## 1. Motivation

The requester posts a `dogecoin:` payment request and then stares at a mute bubble. Today the only way to answer *"did googlepad's 5 DOGE arrive?"* is to open the wallet sheet and eyeball the pending list. B0 closes that gap **requester-side only**: tap your own "You requested" bubble → a status sheet computed **entirely from your own local chain view** (SPV or your pinned node). No receipt from the payer is needed, so this ships before PR2 and keeps working when the payer runs an old client.

Hard truth that shapes everything below: the wallet uses **one static receive address per network** (`DogecoinWalletRepository.loadOrCreateWallet`, `DogecoinWalletRepository.kt:128-162`), and the chain only ever shows `(txid, amount, confirmations)`. A request therefore has **no on-chain identity** — B0 can honestly report *"a payment matching this amount arrived"*, never *"this request was paid."*

## 2. Goals & non-goals

### Goals
1. Own-request tap → honest status ladder: **can't check / not seen / seen (0 of 6) / confirming N of 6 / confirmed**, from local reads only.
2. Both render sites (Simple `PaymentRequestBubble`, power `DogecoinPaymentRequestBubble`), per parent R-B3's both-UIs rule.
3. Ship the PR2 seam now: a shared, unit-tested `reqRef()` and a two-stage resolver whose exact-binding stage is empty in B0.

### Non-goals (B0)
- Payer-side "you paid this" marking (outgoing SPV amounts are net-of-change **incl. fee**, `DogecoinSpvService.kt:406-410`; no destination-output read exists — PR2's outgoing `dogepaid:` bubble is the payer surface).
- Any change to Pay gating, money path, wire formats, or storage schema.
- Incoming-request status (that is receipt territory, PR2).
- Fixing the power bubble's always-rendered Pay button quirk (`DogecoinPaymentRequestBubble.kt:149-160`) — noted, separate cleanup.

## 3. Status state machine (normative)

One **pure** resolver, both render sites:

```kotlin
resolve(request: DogecoinPaymentRequest, requestTimestamp: Date,
        network: DogecoinNetwork, chainView: ChainView?): RequestStatus
```

`ChainView` is a plain data snapshot produced by the dialog's poll loop (SPV: `snapshotTransactions` + status; RPC: activity rows + read-error/window flags). The resolver performs **no I/O, holds no locks, touches no Compose** — chain reads happen ONLY in the dialog's 15s IO poll loop (R-B15/R-B18); recomposition re-runs `resolve()` on the last snapshot only.

| State | Trigger (local chain view only) | Ring (`ConfirmationRing.kt:34-44`) |
|-------|--------------------------------|------|
| **CANNOT_CHECK** | Read source unavailable or untrusted: SPV stopped and unstartable (no key / regtest, `DogecoinSpvService.kt:127-137`); SPV `!synced` (`DogecoinSpvService.kt:427` — never gate on `blocksBehind` alone, R-C3); RPC read error; RPC history window exceeded (R-B16.4); cross-network request (R-B9: badge only, **no reads started**) | none |
| **NOT_SEEN** | Trusted view, zero matching incoming txs | none |
| **SEEN** (0-conf) | Matching incoming tx at depth 0 — honest because the tx object is genuinely in the local wallet/mempool | CONFIRMING 0/6 |
| **CONFIRMING** | Depth 1..5 | CONFIRMING n/6 |
| **CONFIRMED** | Depth ≥ `DOGECOIN_SPV_CONFIRM_TARGET` (6) | IDLE, full |

Copy (EN sketches; JA ships same PR per G0):

| State | Copy |
|-------|------|
| CANNOT_CHECK (syncing) | "Can't check yet — your wallet is still catching up. Open your wallet to sync." |
| CANNOT_CHECK (cross-network) | "This request is on {network}; your wallet is on {network}." ({network} = localized resource, R-B20 — never `DogecoinNetwork.displayName`) |
| NOT_SEEN (SPV) | "No payment matching this amount has reached your wallet yet." |
| NOT_SEEN (RPC) | "No matching payment seen by your node since it started watching this wallet." (pre-import blind spot honesty, R-B16.4) |
| SEEN | "A payment of {N} DOGE reached your wallet — confirming, 0 of 6." |
| CONFIRMING | "{n} of 6 confirmations." |
| CONFIRMED | "{N} DOGE received." ({N} = **chain-observed** amount, R-B8) |
| Permanent caption | "Matched by amount only — your wallet uses one address for every request." |

**No-fake-ring rule** (parent §4.5 + `DogecoinWalletSheet.kt:1467-1468`): a null/absent chain view renders **no** ring — never a stuck "0 of 6". Trust gating: NOT_SEEN and SEEN require `status.synced` (SEEN/CONFIRMING may also show under the sheet's `synced || nearTip` flap-tolerance, `DogecoinWalletSheet.kt:1634-1642`); CONFIRMED (≥6, monotone barring reorg) may render from the persisted wallet even mid-sync. RPC counts as trusted after a successful read (user's own pinned node).

## 4. Requirements

**R-B11 — Scope & zero-wire.** Status is computed only from local SPV/RPC reads and rendered only on the requester's **own** request bubbles (structural `isMine` — `senderPeerID == myPeerID`, name fallback last, `SimpleModeScreen.kt:737-741`; power side mirrors the same structural rule per R-B7). Nothing is serialized to any transport; no message fields change.

**R-B12 — State machine + honest copy.** Exactly §3. Forbidden until local corroboration (R-B3a): the word "paid", checkmarks, flipping the bubble. Copy always claims **amount match**, never request identity. Amount-less requests (`DogecoinPaymentRequest.kt:8`, bare-address paths `:101-108`): no amount heuristic exists — dedicated copy "This request has no amount. Open your wallet to see received payments." + Open-wallet button, **no ring, no candidate listing**. (A recent-amounts mini-list was considered and cut — its "since the request" filter would rest on the same first-seen timestamps R-B13 forbids as a binding rule; revisit later only if asked for.)

**R-B13 — Matching heuristic + honesty label.** Candidates = **incoming** rows only: SPV `snapshotTransactions` (`DogecoinSpvService.kt:402-422`) or RPC `getWalletActivity` `category=="receive"` rows to our address (`DogecoinRpcClient.kt:326-360`). Match = **exact koinu equality** with the requested amount (convert once via the existing amount parser; never float). Soft time filter only: drop candidates first-seen > 10 min **before** the request's `message.timestamp`; `timeSeconds` is bitcoinj `updateTime` (first-seen by *this wallet*, `DogecoinSpvService.kt:413`) so offline catch-up stamps catch-up-era times — time must never be a binding rule. Multiple matches: display the deepest, disclose the count ("2 payments of {N} DOGE seen") — never silently pick one. The §3 caption renders in **every** non-CANNOT_CHECK state.

**R-B14 — Tap targets per render site.**
| Site | Change |
|------|--------|
| Simple `PaymentRequestBubble` (`SimpleModeScreen.kt:1102`) | Add nullable `onStatusTap: (() -> Unit)?`; call site (`:745-765`) supplies it iff `isMine && walletEnabled` (`:621`, `:762` gate pattern). `Modifier.clickable` on the Surface (`:1131` — currently zero tap targets on own bubbles, purely additive). Static hint line under "You requested" (`:1146`): "Tap to check status." No computed state on the bubble. |
| Power `DogecoinPaymentRequestBubble` (`DogecoinPaymentRequestBubble.kt:45`) | Add body tap for own messages (bubble already has the full `BitchatMessage`); must coexist with existing header-tap/long-press (`:78-94`) and leave the Pay button exactly as-is. Threaded from `MessageComponents.kt:357-371` → `ChatScreen` dialog state (mirror `openDogecoinPaymentUri` plumbing, `ChatScreen.kt:88-93`). No `walletEnabled` gate exists on power — the component must not assume one. |

**R-B15 — Status sheet content.** Mirror the wallet's tx-detail dialog (`walletTxDetailId` keyed by id, row re-looked-up **each recomposition** so the ring climbs on poll ticks; self-consistent close; `DogecoinWalletSheet.kt:1513-1515, 3886-3954`). B0 dialog is keyed by **message id** (`BitchatMessage.kt:57`; already the LazyColumn key at `SimpleModeScreen.kt:729`) and re-runs the resolver per tick on the poll loop's latest `ChainView` snapshot. Contents: 150dp `ConfirmationRing` per §3; requested amount (or "any amount"); chain-observed amount once seen (R-B8); match-count line; network badge; honesty caption; matched txid short + copy button when exactly one match; "Open wallet" button → plain wallet sheet, **no payment prefill** (never through the Pay path). Ring `contentDescription` localized — do **not** copy the sheet's hardcoded-EN a11y strings (`DogecoinWalletSheet.kt:1660-1666`). **Tor disclosure (parent R-C11):** when the resolver routes via RPC and Tor intent is ON, the dialog shows the existing one-line "not over Tor" disclosure (reuse/mirror the NodeAssistCard copy, `DogecoinWalletSheet.kt:1770`; EN+JA) — this is a new surface firing cleartext RPC while the user believes they are on Tor, exactly what R-C11's disclosure rule exists for.

**R-B16 — Data routing & SPV lifecycle (the hard part).** SPV is sync-on-demand and sheet-bound today: started/stopped by the wallet sheet (`DogecoinWalletSheet.kt:1376-1385`), singleton (`DogecoinSpvService.kt:593-601`), `stop()` also in `ChatViewModel.onCleared` (`ChatViewModel.kt:1109`) — so a bubble-launched dialog finds it STOPPED and every snapshot null. **Decision:**

1. Route by `repository.resolveBackend(network)` (`DogecoinWalletRepository.kt:233`) — the **persisted** resolution, never the sheet's session-only assist flag (R-C4a; assist state is `remember{}` inside the sheet, `DogecoinWalletSheet.kt:204-213`, and must stay unreachable here). Assist-awareness = the CANNOT_CHECK(syncing) copy deep-links to the wallet sheet, where the assist offer already lives (`:1763-1764`). The status view itself never reads via assist and never calls `saveBackend`/`saveRpcConfig`.
2. Cross-network vs `loadSelectedNetwork()` (`SimpleModeScreen.kt:631-633`) → CANNOT_CHECK badge, zero reads, zero lifecycle calls.
3. **SPV: retain/release usage-counting on the singleton**, wrapping the existing public `start()`/`stop()` (touches **no** transport/Tor/`torConnectionManager` code; rebuild-fail-to-STOPPED semantics byte-untouched). Full call-site inventory that must migrate or be defined: sheet `LaunchedEffect` start `:1380` / stop `:1382`, sheet `DisposableEffect` stop `:1385`, debug console `doge-spv-start` → `start()` (`ChatViewModel.kt:408`) and `doge-spv-stop` → `stop()` (`:412`), `onCleared` backstop (`:1109`). Semantics:
   - Count is **per network**. `retain(otherNetwork)` while `count(current) > 0` is **refused** (caller shows CANNOT_CHECK) — never a silent network switch under a live holder (`start(networkB)` tears down networkA today, `DogecoinSpvService.kt:132`).
   - The refcount wraps the **public API only** — internal `stopLocked()/start()` calls (the Tor transport rebuild, `:263-279`) never touch the count. A poll tick that finds `status.running == false` while `count > 0` re-invokes `start` (bounded retry) so a failed Tor rebuild self-heals — without weakening fail-closed (a rebuild that keeps failing keeps the service STOPPED between retries).
   - `release()` without a prior `retain` is a defined no-op (the sheet's `:1382` stop branch fires even when SPV never started).
   - `release()` schedules teardown after a **90s linger window** (one shared constant) instead of immediately — re-taps reuse the running client instead of cycling DNS discovery + 6 peer connects + mmap store open/close per tap (`:150`, `:235-254`); over Tor a short-lived open would otherwise never beat the 60s connect timeout (`TOR_CONNECT_TIMEOUT_MILLIS`, `:624`). `onCleared` remains the immediate hard stop.
   - Console semantics: `doge-spv-stop` is a **debug-only override** that force-resets the count to zero; `doge-spv-start` maps to `retain`. Document in the console help string; unit-test the override interaction.
   - **Explicit R-C11 amendment:** parent R-C11 scoped status polling to "existing read-only SPV calls"; B0's `retain()` STARTS the client from a new surface. This amends lifecycle *ownership* (sheet-only → counted holders) while leaving the transport decision and rebuild semantics untouched — recorded here so the guardrail is amended deliberately, not eroded silently.
   - `retain` runs on `Dispatchers.IO` (start opens the mmap store under the lock — `DogecoinSpvService.kt:125-155`; sheet precedent `:1379-1380`). First paint is instant from the persisted wallet (`loadOrCreateSpvWallet` `:148, 504-526` — last-known depths, §3 trust gating applies); peers refine while open. Reads: `snapshotTransactions` + `confirmationDepth` (`:387-393`) + `status` StateFlow only.
4. **RPC:** one-shot `getWalletActivity` on IO, re-poll at the existing 15s cadence while the dialog is composed. Read error → CANNOT_CHECK. **History window (R-B16.4):** `getWalletActivity` address-filters and `.take()`s rows from a bounded raw scan (`scanCount = count*5`, `DogecoinRpcClient.kt:341-342`), so the filtered result alone cannot reveal window exhaustion. The status path uses a variant that also returns **raw-scan metadata** — `rawRowsScanned` and `oldestRawScannedTimeSeconds` — and reports CANNOT_CHECK ("can't check that far back") when `rawRowsScanned == scanCap && requestTimestamp < oldestRawScannedTime`. Additionally, the status path's import is always watch-only `rescan=false` (`DogecoinRpcClient.kt:665-674`; the historical-rescan variant at `:676-691` is **never called from the status path** — unit-test this), so payments that arrived before the node first watched the address are invisible forever: the RPC NOT_SEEN copy is softened accordingly (§3), and the view never triggers a rescan.

**R-B17 — Zero storage.** No persisted request→txid match, no `StoredMsg`/`AppStateStore` change, no new `BitchatMessage` field, and **never** overload `deliveryStatus` (transport-only, with its own no-downgrade logic — `AppStateStore.kt:163-171, 298-330`). Rationale: persisting a heuristic guess freezes it into fake certainty — exactly the auto-mark R-B3a/R-B8 forbid — while live recompute self-heals across reorgs, catch-up, and later better matches. Every input is derivable: request params from message content, chain view from singleton reads.

**R-B18 — Perf.** Bubbles gain **zero** new per-recomposition work: no chain reads, no new remembered state beyond the existing `remember(m.content)` parse (`SimpleModeScreen.kt:745-747`); LazyColumn keys stay message ids (`:729-736`). All resolution and polling live inside the tapped dialog only, 15s cadence, stopped on dismiss. Hoist `DOGECOIN_SPV_CONFIRM_TARGET`/`_INTERVAL_MS` from file-private (`DogecoinWalletSheet.kt:5050-5055`) to a shared constants object — never duplicate the magic numbers.

**R-B19 — PR2 forward-compat (`req` hash).** Ship now, unused for matching:
```kotlin
// Canonical string is PINNED as the output of DogecoinUri.wholeMessagePaymentUri
// (trim-only, never re-encodes — DogecoinUri.kt:47-73). Hash the RAW string:
// createPaymentUri percent-encodes (DogecoinWallet.kt:130-132) but parse URL-decodes
// (DogecoinPaymentRequest.kt:135-137), so parse→reserialize is NOT byte-stable.
fun reqRef(messageContent: String): String? =
    DogecoinUri.wholeMessagePaymentUri(messageContent)
        ?.let { sha256(it.encodeToByteArray()).copyOf(8).toLowerHex() }   // 16 lowercase hex, spec §4.2
```
Resolver is two-stage: **stage 1** = exact binding via locally-received `dogepaid:` receipts keyed by `reqRef` (empty in B0; PR2 fills it); **stage 2** = §R-B13 amount heuristic. Stage 1 only **narrows the candidate set to the receipt's txid** — the displayed status still comes from `confirmationDepth`/snapshots (R-B3a: receipt content is never status). PR2 upgrade = swap "matching this amount" copy for "for this request" when stage-1-bound; neither render site changes.

**R-B20 — Localization (G0).** Every new string — states, caption, hint, dialog labels, ring `contentDescription` — ships in `values/strings.xml` **and** `values-ja/strings.xml` same PR. **B0 BUILDS the G0 key-parity unit test** (new-feature EN keys ⊆ values-ja keys) — no such test exists today (`values-ja` carries 5/276 dogecoin_* keys), so it is a named deliverable of this slice, not an assumed facility; every new B0 key registers in it. Network names rendered in status copy come from localized string resources (`dogecoin_network_mainnet/_testnet/_regtest`, EN+JA), **never** `DogecoinNetwork.displayName` (hardcoded English, `DogecoinWallet.kt:35/45/62`) — include these keys in the parity test and assert in the JA smoke that the cross-network sentence contains no raw `displayName`.

## 5. Integration map

| Piece | Anchor |
|-------|--------|
| Resolver + `reqRef` + state types + `ChainView` | new `features/dogecoin/RequestStatus.kt` (pure data-in/data-out; unit-testable, no Compose, no I/O) |
| Simple tap + hint | `SimpleModeScreen.kt:729-765` (call site), `:1102-1197` (bubble), gate `:621/:762` |
| Power tap | `DogecoinPaymentRequestBubble.kt:45-164`, `MessageComponents.kt:357-371`, `ChatScreen.kt` dialog state near `:88-93` |
| Status dialog (owns the 15s IO poll → `ChainView`) | mirror `DogecoinWalletSheet.kt:3886-3954`; ring `features/dogecoin/ui/ConfirmationRing.kt:34-44` |
| SPV retain/release | `DogecoinSpvService.kt` (wrap `:125`/`:246` start/stop); migrate sheet `:1376-1385`, console `ChatViewModel.kt:408/412`; backstop `:1109`; accessor exists `ChatViewModel.kt:119-125` |
| RPC read | `DogecoinRpcClient.getWalletActivity` `:326` — status-path variant returning raw-scan metadata (R-B16.4), `count` raised to 50 |
| Shared consts hoist | `DogecoinWalletSheet.kt:5050-5055` → shared object (+ the 90s linger constant) |

## 6. Decisions log

| ID | Decision |
|----|----------|
| D-B0-1 | Status is an **amount-match claim** with a permanent honesty caption — never "request paid" (one address per network makes binding a guess) |
| D-B0-2 | **Zero storage**; live recompute every open/tick; no schema headroom consumed |
| D-B0-3 | SPV lifecycle via **per-network retain/release usage count** on the existing singleton with a 90s teardown linger; sheet + console migrate; `onCleared` backstop; no transport code touched; explicit R-C11 ownership amendment |
| D-B0-4 | Status lives in the tapped dialog only; bubbles show a **static** hint, no live per-bubble status |
| D-B0-5 | `reqRef` canonical string = `wholeMessagePaymentUri` output, hashed **raw** (no decode/re-encode round trip) |
| D-B0-6 | Assist never routed inside the status view; CANNOT_CHECK deep-links to the wallet sheet where assist lives |
| D-B0-7 | Power Pay-button quirk (renders on own messages / parse failure) left untouched in B0 |
| D-B0-8 | Resolver is pure (data-in/data-out over a `ChainView` snapshot); all I/O lives in the dialog's poll loop |
| D-B0-9 | Amount-less requests get dedicated copy + Open wallet only (candidate mini-list cut) |

## 7. Security & privacy

| Topic | Stance |
|-------|--------|
| Explorer | **Never** queried by the status view — open, poll, or otherwise (parent §9 item 7: txid/address-interest leak); local SPV/RPC only |
| Tor | SPV reads inherit fail-closed Arti routing untouched (R-C11); RPC status reads bypass Tor exactly like today's node path — and because this is a NEW surface, the dialog itself shows the existing "not over Tor" disclosure when Tor is on (R-B15) |
| Money path | Read-only throughout; "Open wallet" never enters the Pay path; all existing gates (`canPay`, mainnet acks, verifier) untouched |
| Identity | Own-bubble detection structural only (R-B7); display names never key anything |
| Wire | Nothing serialized; no new schemes; URI finders not extended (parent §9 item 9) |

## 8. Do-not-build (B0)

1. Explorer lookups from the status view, automatic or on-tap.
2. Persisted request→txid match or any `AppStateStore`/`BitchatMessage` schema change.
3. Any "paid" badge / bubble flip on the request bubble itself.
4. Payer-side payment marking (PR2 owns the payer surface).
5. Extending `findPaymentUris`/`MessageSpecialParser` in any way.
6. A second `DogecoinSpvService` instance or a peer-less wallet-file reader (single-writer mmap store).
7. Assist logic, `saveBackend`, or `saveRpcConfig` calls from the status path.
8. `rescanblockchain` / `importaddress rescan=true` from the status path (heavy; pruned-node refusal).
9. Changing power Pay-button gating (tracked separately).
10. The amount-less candidate mini-list (cut per D-B0-9).

## 9. Tests

**Unit:** state-machine matrix incl. every CANNOT_CHECK trigger; exact-koinu matching (never float); amount-less → dedicated copy state; multi-match count disclosure; soft time filter + catch-up caveat; `reqRef` golden vectors (incl. percent-encoded `label`/`message`, surrounding whitespace, trailing punctuation — stable across parse/reserialize asymmetry); two-stage resolver with a fake stage-1 receipt narrowing to one txid; retain/release semantics (per-network counts, refused cross-network retain, rebuild-path non-counting, release-without-retain no-op, stopped-while-retained recovery, console force-reset override, linger-window single-cycle under rapid open/close×5); the status path never calls the historical-rescan import variant; **the new G0 key-parity test itself** (EN keys ⊆ JA, incl. `dogecoin_network_*`).

**Instrumented / device:**
1. **Two-device happy path** (Pixel requests 5 DOGE, S24 pays via SPV): requester dialog walks NOT_SEEN → SEEN 0/6 → CONFIRMING → CONFIRMED with the **chain-observed** amount, wallet sheet closed the whole time.
2. **SPV-behind requester:** fresh/behind wallet → CANNOT_CHECK, never a fake 0/6; catch-up upgrades the open dialog in place (parent §4.5 test mirrored).
3. **Payer edits the amount** (R-B8): requester stays NOT_SEEN for the requested amount; wallet still shows the real payment — copy honesty verified.
4. Two outstanding same-amount requests (incl. across conversations): both dialogs show the same match **with count disclosure**.
5. Amount-less request → dedicated copy, no ring.
6. Cross-network request → badge-only CANNOT_CHECK, verified **zero** reads/lifecycle calls (R-B9); sentence contains no raw `displayName` in JA.
7. Lifecycle interleaving: dialog open → wallet sheet open → sheet close → dialog still live; reverse order; process death mid-dialog → clean reopen.
8. RPC backend: node down → CANNOT_CHECK; request older than the raw-scan window → "can't check that far back" (not NOT_SEEN); request older than the node's first watch of the address → NOT_SEEN renders the softened RPC copy.
9. Tor ON + RPC backend → dialog shows the "not over Tor" disclosure; Tor ON + dialog opened < 60s → no clearnet socket, honest CANNOT_CHECK, no wakelock leak after dismiss.
10. Adversarial: unsolicited payment coincidentally equal to a pending request renders with the honesty caption; no "paid" wording anywhere (grep-level assert on new strings).
11. JA-locale smoke on bubble hint + dialog, both profiles; TalkBack reads the localized ring description.
12. Perf: bubble recomposition unchanged (no new state reads in rows); no polling while dialog closed; rapid open/close causes at most one SPV start/stop cycle (linger).

## 10. References

- Parent doctrine: `docs/family-wallet-ux-learnings-spec.md` — R-B3a `:261-269`, R-B8 `:275`, R-B9 `:276`, R-B10/G0 `:58-65, :277`, R-B7 `:274`, R-C3 `:334-338`, R-C4a `:351-358`, R-C11 `:387-391`, §9 `:447-458`
- Render sites: `SimpleModeScreen.kt:729-765, 1102-1197`; `DogecoinPaymentRequestBubble.kt:44-164`; `MessageComponents.kt:357-371`; `ChatScreen.kt:88-93, 587-596`
- Chain reads: `DogecoinSpvService.kt:57-63, 72-75, 125-155, 387-422, 427, 593-601`; `DogecoinRpcClient.kt:326-360, 341-342, 665-674, 676-691`
- Patterns to mirror: tx-detail dialog `DogecoinWalletSheet.kt:1513-1515, 3886-3954`; no-fake-ring `:1459-1476`; poll cadence `:1482-1493`; consts `:5050-5055`; `ConfirmationRing.kt:34-62`; not-over-Tor copy `DogecoinWalletSheet.kt:1770`
- Identity/one-address: `DogecoinWalletRepository.kt:128-162, 233`; `DogecoinPaymentRequest.kt:8, 11, 42-43, 101-108, 135-137`; `DogecoinUri.kt:47-73`; `DogecoinWallet.kt:35/45/62, 130-132`; `RequestDogeDialog.kt:56-70`
