# Spec: Family Chat Identity + Payment Receipts + Home-Node Assist

**Status:** proposed — **v2 after design review** (approve-with-changes folded in)  
**Branch context:** `simple-family-profile` + merged Dogecoin wallet (SPV/RPC/mesh helper)  
**Audience:** implementers  
**Review:** 10-agent adversarial review vs code on `simple-family-profile` (32/34 claims confirmed; 2 refuted and discounted)  
**Not in scope of this doc:** regenerating SPV checkpoints (ops/tooling; already practiced).

### Changelog (v1 → v2)

| # | Change |
|---|--------|
| 1 | PR1 includes **rename UI** (did not exist; only writers were peer-declared QR nicknames) |
| 2 | Closed **`dogepaid:`** wire format; **hard ban** on receipt under `dogecoin:` (double-pay hazard) |
| 3 | Receipts: **claim-until-corroborated**, Pay-tap **convKey capture**, persisted txid idempotency |
| 4 | Home-node: **non-persistence**, mainnet **pin-before-read**, provisioner-only, QR import replaces LAN scan |
| 5 | **EN + JA** localization requirement for every new user-facing string |
| 6 | Normative **surface table** (not “1:1 bubble labels” — those don’t render in Simple 1:1) |
| 7 | Explicit **do-not-build** list; closed open decisions |

---

## 1. Motivation (what we learned on hardware)

Three friction points showed up while running **S24 + Pixel Tablet** on Dogecoin **testnet / SPV**, Simple/family profile:

| # | Pain | Observed symptom |
|---|------|------------------|
| **A** | SPV can lag far behind tip on testnet | Wallet unusable for minutes–hours even when a full node sits on the same LAN |
| **B** | Contact display names don’t stick | User intends tablet as **“googlepad”** on S24; UI shows **“anon”** (geohash fallback). *Note: v1 assumed a rename UI; code only stores the peer’s self-declared QR nickname — rename is a PR1 deliverable.* |
| **C** | Payment-request loop is incomplete | Requester posts `dogecoin:` card; payer pays in-app. Manual “Share receipt to chat” already exists (`DogecoinWalletSheet` → `onShareToChat`); what is **missing** is **automatic, machine-parseable, bubble-rendered** receipt with live status |

Theme: **family devices should feel reliable without node babysitting or crypto jargon.**

---

## 2. Goals & non-goals

### Goals

1. **Local pet-names win** on every Simple surface a human reads; never show bare `"anon"` / `"Unknown"` when a relationship name exists; **user can set** that name (rename UI).
2. **Close the pay loop in chat:** after broadcast success from a payment-request Pay path, auto-post a structured **`dogepaid:`** receipt; receiver treats it as a **claim** until local chain view corroborates.
3. **Optional home-node assist** when SPV is not ready: use a **provisioner-pinned** Core RPC node for reads/broadcast **without** permanently switching backend; SPV remains default trust base.

### Non-goals / do-not-build

See **§9 Do-not-build**. Summarized:

- LAN subnet probe / mDNS in v1 (QR import instead)
- Any receipt under the `dogecoin:` scheme
- Pin-time best-block-hash “fingerprint”
- Auto-retry/auto-broadcast of expired signed txs when a node appears
- Mainnet use of any discovered/unpinned node
- Storage rewrite of `message.sender` for rename
- Auto explorer lookups on receipt receive; no status field on the wire
- Full Electrum; redesign of the Coin wallet sheet

### Global requirement G0 — Localization

Every user-facing string introduced by this work ships in **`values/strings.xml` and `values-ja/strings.xml` in the same PR**.

- Today: `simple_*` has 78/78 EN/JA parity; wallet/`dogecoin_*` has ~271 EN strings and **zero** JA.
- Add a **key-set parity** unit/CI test (EN keys used by new features ⊆ JA).
- Each workstream’s test plan includes a **Japanese-locale smoke** on new surfaces.
- Do **not** copy the Connection card’s hardcoded English literals (`DogecoinWalletSheet` ~1887–1921).

### Success metrics

- Saved/renamed favorite never shows `"anon"` on title, notification, or recreation restore.
- Pay-from-request produces ≤1 durable `dogepaid:` artifact per txid on both sides; receiver never shows “paid” until local corroboration.
- Assist: with SPV `!synced` and pinned node, balance + send work; after kill + relaunch with node down, backend resolves **SPV** (not sticky RPC).

---

## 3. Workstream A — Display name resolution + rename (P0 / PR1)

### 3.1 Problem (code-confirmed)

Names come from **uncoordinated sources**:

| Source | Role today |
|--------|------------|
| `FavoritesPersistenceService.peerNickname` | Home list; written only at favorite/QR provision with **peer-declared** nick (`ChatViewModel.toggleFavorite`, `FamilyProvisioning`) — **user cannot type “googlepad”** |
| Message `sender` | Often remote nick or **`"anon"` baked at receive** (`NostrDirectMessageHandler`) |
| `displayNameForNostrPubkeyUI` / geohash cache | Falls back to **`"anon"`** / `anon#XXXX` |
| Mesh path | `"Unknown"` / announced nick |

**Bug site:** `SimpleModeScreen.openContactByConvKey` titles via `displayNameForNostrPubkeyUI(hex)` while looking up the favorite Noise key next and **never reading `peerNickname`**. History under `nostr_<pub16>` **persists**; the **label** is wrong. Activity recreation re-derives the title → a correct title can **silently degrade to “anon”** on dark-mode toggle / rotation.

Simple **1:1 bubbles do not show sender labels** (`showSender = isGroup`). Fixing “bubble sender” alone does nothing.

### 3.2 Requirements

#### R-A0 — Rename UI (normative PR1 deliverable)

Without this, priority-1 of the resolver is never user-settable.

| Rule | Detail |
|------|--------|
| Entry | Long-press (or overflow) on Simple home **contact row** → rename dialog |
| Favorite | `updateFavoriteStatus` / equivalent keyed by **Noise key** |
| npub-only | `KnownNpubStore` label |
| Input | trim; max **24** chars; strip Cc/Cf including U+202A–U+202E; reject empty after sanitize |
| Live title | Open conversation title is **derived state**, not an open-time snapshot — rename updates title **without** leaving the thread |
| Pet-name privacy | Local pet-names **must not** serialize into outbound group `bgm` tags. Populate `bgm` from the member’s **self-asserted** name only. Corrects false “local-only” claim. Test required. |

#### R-A1 — Resolution order (display-time only)

For any human-visible label for a peer:

1. **Local relationship name** (favorite `peerNickname`, else KnownNpub label), if non-blank after sanitize  
2. Else **remote asserted / message sender**, if non-blank after sanitize and **not** a banned token  
3. Else **short id**: localized family fallback string + **first 4 hex** of the identity material available (from full pubkey, or from `nostr_<pub16>` convKey’s embedded prefix). Collision-breaker only — **not** a verifier (16 bits is grindable).

**Banned tokens** (case-insensitive, also with `#` suffix form): `anon`, `anon#*`, `unknown`.

**Never** emit the string `"anon"` on Simple surfaces.

**R-A1a — Spoofing:** Remote name **never** overrides a local label. Sanitize remote (strip Cc/Cf). If remote name equals any local relationship label (NFKC, case-insensitive), render remote with disambiguator + “unverified” subtitle. Note: pure account-DM favorites often have **no** remote name on the wire (`NostrTransport` 1:1) — priority 2 mainly fires where spoof risk is highest (mesh/group).

#### R-A1 surface table (normative)

| Surface | File / path notes |
|---------|-------------------|
| Conversation title | Both `openContactByConvKey` paths: notification deep-link **and** Activity-recreation restore |
| Cold-start title fallback | Must not trust raw stored `sender` if banned token — re-resolve |
| System notification | `NotificationManager` `contentTitle` + `Person.name` |
| Mesh FG notification | `BluetoothMeshService` notification titles (same-house BLE path) |
| Group / payment bubbles | When `showSender`; map via authenticated identity, not display-name equality |
| Tap-to-add dialog | Sanitizer + collision warning + **≥16 hex or npub** identity display (4-hex insufficient) |
| KnownNpub home rows | Use stored label / resolver |
| Power profile (optional parity) | Prefer same resolver where contact relationship exists; geohash public lists may keep anon |

#### R-A2 — Resolver API shape

Must accept **all four key shapes** used by one contact thread:

| Shape | Notes |
|-------|--------|
| `nostr_<pub16>` convKey | Prefix = first 16 hex of Nostr pubkey |
| 64-hex Noise static key | Favorite primary key |
| 16-hex mesh peerID | **SHA-256(noiseKey).take(16)** — **not** a Noise-key prefix (see memorialized bug `FavoritesPersistenceService` ~127–133) |
| npub / 64-hex Nostr pubkey | |

**Required unit test name:** `fingerprint_not_noise_prefix` — mesh peerID lookup must use SHA-256 fingerprint, not `noiseKey.take(16)`.

#### R-A3 — Display-time resolve (DECIDED)

- **Do not rewrite** on-disk `message.sender` for rename retroactivity.  
- Storage stays raw forever (incl. baked `"anon"`).  
- Resolve boundary = **anything a human sees**, including notification construction at receive time.  
- Search/export may keep raw senders (document; don’t fix in v1).

#### R-A4 — Groups

- Map to favorite by message **`senderNostrPubkey`** (rumor author — authenticated) via favorites npub attribute.  
- Never by display-name equality.  
- Non-author `bgm` names stay unverified treatment.

#### R-A5 — Multi-device same npub

- `findNoiseKey` is `firstOrNull` today — arbitrary. Specify **deterministic** pick (e.g. lowest Noise key hex).  
- Document: `nostr_<pub16>` **merges** devices into one thread by construction — do not split in v1.  
- Group self-copy oddity (own second device) is known; not PR1 scope.

#### R-A6 — No protocol change for names

Resolver is a **pure read layer**: never mutate stored `sender` (protects `isMine` legacy name-equality fallback). Preserve `openContactByConvKey` side effects (`startGeohashDM` + `GeohashAliasRegistry.put`). Do not feed resolver fallback strings into outbound `bgm`.

### 3.3 Design sketch

```text
ContactDisplayName.resolve(
  convKey?: String,
  noiseKeyHex?: String,
  meshPeerId?: String,
  nostrPubkeyHex?: String,
  messageSenderFallback?: String,
  context: Resources
): ResolvedName  // display, verified/local, shortId
```

Open title: `SimpleTarget.Contact` holds identity keys; **display name derived** each composition (or StateFlow from favorites), not a frozen `name` string alone.

### 3.4 Tests (A)

- Resolver priority matrix + banned tokens + `anon#XXXX` / `Unknown`.  
- `fingerprint_not_noise_prefix`.  
- Rename updates open title without navigation; delete favorite → downgrade, never `"anon"`.  
- **Activity-recreation matrix:** favorite-titled thread → dark-mode / rotation / split-screen → title stays.  
- **BLE notification path:** backgrounded mesh DM from favorite → notification shows favorite name.  
- Pet-name never appears in outbound `bgm` payload (unit).  
- JA smoke + key parity for new strings.

---

## 4. Workstream B — Payment-sent chat receipt (P1 / PR2)

### 4.1 Problem (corrected)

- Payment **requests** = whole-message `dogecoin:` URI → rich Pay bubble (proven).  
- Manual **“Share receipt to chat”** already posts free-text txid via `onShareToChat` → `sendMessage`.  
- Missing: **automatic**, **machine-parseable**, **rich bubble**, **claim-until-corroborated** status, **idempotent** with manual share.

### 4.2 Wire format (DECIDED — closes former §7.1)

```text
dogepaid:<network>:<txid>?v=1&amount=<amount>&to=<address>[&req=<reqref>]
```

| Field | Rule |
|-------|------|
| `network` | Literal `mainnet` \| `testnet` \| `regtest`, lowercase, **mandatory** (unlike `dogecoin:`, which infers from address version) |
| `txid` | Exactly **64** lowercase hex |
| `v` | Mandatory `1`; other versions → plain fallback bubble (copyable txid, no rich status) |
| `amount` | Decimal DOGE via `DogecoinAmount.isValidAmount`, locale-invariant `.` |
| `to` | Base58 address; version byte **must match** network token or parse fails |
| `req` | Optional 16 lowercase hex = first 8 bytes of SHA-256 of originating request’s **exact** URI string |
| Length | Reject entire message if **>256** chars |
| Params | No free-text; **no URLDecoder**; reject any `req-*` key (BIP-21 convention); **ignore** other unknown keys (forward-compat — `v` governs breaking changes) |
| Status | **No status field on wire** — always computed live by receiver |

**Detection:** identical **trim-then-exact whole-message** rule as `DogecoinUri.wholeMessagePaymentUri`.

#### Hard ban — `dogecoin:` for receipts

**Critical.** `DogecoinPaymentRequest.parse` accepts amount/label/message and only rejects `req-*`. A receipt forged as `dogecoin:…?amount=50` renders as a **payable request with a live Pay button** on shipped clients → realistic **double-pay**.  

- Never encode receipts under `dogecoin:`.  
- Do **not** extend `DogecoinUri.findPaymentUris` / `MessageSpecialParser.findUrls` to match `dogepaid:` (would route taps into Pay path).

**Degradation:** old clients see inert plain text. Verified: `MessageSpecialParser` matchers do not fire on this grammar if free-text params are forbidden (bare-domain fallback would linkify `label=pay.me`-style junk).

### 4.3 Requirements

**R-B1.** After **broadcast success** on a Pay path that was opened from a chat payment-request (or explicit thread pay), auto-insert one `dogepaid:` whole-message into the **captured private conversation**.

**R-B1a — Conversation binding (high):**

- Capture **target `convKey` at Pay-tap** (not at completion).  
- Send from **ViewModel / app scope**, not sheet `rememberCoroutineScope` (dismissal must not drop or misroute).  
- **Never reroute** to whatever thread is open at completion.  
- At send time: hard-refuse non-private targets (no public geohash). Power profile can post requests publicly — auto-receipt must not follow that context.

**R-B2.** What “success” means (static **broadcast claim**, not settlement):

| Backend | Receipt may post when | Semantics |
|---------|----------------------|-----------|
| SPV | Claimed (handed to peers; timeout ≠ failure) | Claim only |
| Mesh helper | Claimed (single helper) | Claim only — not Confirmed |
| RPC / home-node | Node accepted raw tx + byte-verified txid | Claim only — not Confirmed until SPV/explorer sees it |

Receipts are **never retracted**. If the chain never shows the tx, bubble stays “not yet seen by your wallet” forever.

**R-B3.** Rich bubble (Simple **and** power UI — both branch whole-message URIs independently):

- Network badge, amount, truncated txid + copy  
- Tap → confirmation UI reusing `ConfirmationRing` patterns when corroborated  
- DeliveryStatus on **outgoing** receipt + manual re-send if outbox/TTL fails  

**R-B3a — Claim-until-corroborated (high):**

Until **this device’s** SPV/RPC observes txid `T` paying **our** address (existing bloom/snapshot paths; **no auto explorer**):

- Copy like: **“reports paying N DOGE — not yet seen by your wallet”**  
- **Never** checkmark, conf count, or the word **“paid”**  
- **Never** auto-mark the originating request bubble paid from receipt content alone  

After local observation: show conf progress; may mark request paid only via **local** corroboration.

**R-B4.** No receipt on cancel/failure.  
**R-B5.** Idempotency: **persisted per-network txid set** written **before** `sendMessage`; shared with manual share so auto+manual ≤1 post; receiver dedupes by txid to one live status.  
**R-B6.** Mainnet gates unchanged (WIF, acks, etc.). Hook is additive at existing success anchors only.  
**R-B7.** Groups: family group is NIP-17 fan-out (not public geohash). v1: if Pay was from a **group** request bubble, send receipt as **1:1 DM to requester** (not group-wide disclosure of amount/txid/address to all members). Identify the requester by the request message’s **structural sender identity** (`senderNostrPubkey` / sender key) — **never** by display name (avoids reintroducing Workstream A’s name-spoofing surface).  
**R-B8.** Amount: payer may edit prefilled amount; display **chain-observed** amount once seen. Optional `req` binds receipt→request; multiple outstanding requests allowed.  
**R-B9.** Cross-network: mainnet receipt on testnet wallet → badge only, no live status, no wallet action.  
**R-B10.** All bubble strings EN+JA (G0). No free-text from URI rendered as human claims.

### 4.4 Integration map

| Piece | Note |
|-------|------|
| Pay-tap | Snapshot `convKey` + optional `req` hash into wallet session |
| Success hooks | SPV/RPC: inside `onSuccess` after relevance guard; mesh: after `resultTxid == transaction.txid` check |
| Send | `ChatViewModel` scope → private send to captured key |
| Parse | New `DogepaidReceipt.parse` — isolated from `DogecoinPaymentRequest` |
| Render | `PaymentSentBubble` in `SimpleModeScreen` **and** `MessageComponents` |

### 4.5 Tests (B)

- Encode/decode + reject malformed / >256 / bad network-address / v=2 / free-text.  
- Pay-tap capture survives sheet dismiss mid-broadcast.  
- Process death between success and send: ≤1 receipt after restart (persisted marker).  
- Transport matrix: mesh, Nostr, store-and-forward, helper path.  
- SPV-behind requester: never fake 0/6; catch-up updates status.  
- Adversarial: nonexistent txid forever unverified; request bubble never flips.  
- Profile matrix: both UIs both directions.  
- JA smoke + key parity.  
- Manual share + auto share same txid → one message.

---

## 5. Workstream C — Home-node assist (P2 / PR3)

### 5.1 Problem

SPV can lag largely even with checkpoints (birthdate-limited). Users often have Core on LAN. Manual RPC exists; sticky `saveBackend` + `saveRpcConfig` on send can **permanently** flip users to RPC — assist must not.

**Corrections vs v1:**

- Mainnet **checkpoint asset ships** (`dogecoin-checkpoints-mainnet.txt`); no-node mainnet soft-defaults SPV. Fix stale KDoc on `DogecoinWalletRepository` defaulting if still wrong.  
- RPC credentials at rest are **EncryptedSharedPreferences**; exposure is the **wire** (HTTP Basic, often cleartext).  
- RPC client **does not use Tor** (no proxy on its OkHttp) — disclose, don’t pretend assist inherits SPV Tor.

### 5.2 Requirements

**R-C1 — How a node is configured**

| Path | v1 |
|------|-----|
| Manual URL + auth | Yes (existing) |
| **QR import** from provisioner | Yes — primary “found my node” UX |
| LAN scan / mDNS / subnet probe | **Do not build** (see §9) |

**R-C2 — Trust / pin**

- **Mainnet:** manual entry only; **explicit pin required before any read or broadcast**; no discovery.  
- **Testnet:** user-triggered discovery optional later; still **credential-silent pre-pin**; no silent public-RPC fallback.  
- Pin dialog when Tor ON: one-time ack **“Home-node connections do not use Tor”**; banner appends “· not over Tor”.  
- Password warning: travels **unencrypted on this Wi-Fi**; recommend dedicated limited `rpcauth` user.  
- **No** pin-time best-block-hash fingerprint (theater).  
- **Per-session sanity:** chain name match; node height ≥ SPV `chainHeight` and within sane window of SPV `bestPeerHeight` when peers exist.

**R-C3 — Assist trigger and ladder**

- **Primary trigger:** `!synced` (already has Schmitt/hysteresis).  
  **Do not** use `blocksBehind > N` alone — with zero peers, `bestPeerHeight=0` ⇒ `blocksBehind=0` looks healthy when SPV is most useless.  
- Banner hysteresis; mid-session when SPV becomes synced: **revert reads to SPV**, no persisted backend change.  
- Broadcast ladder (document honestly):

  1. If assist active + pinned node reachable → RPC broadcast (existing gates: shape, testmempoolaccept, txid-vs-bytes).  
  2. Else mesh helper — **this is new UI under SPV** today (`dogecoinBackend != SPV` gates helper CTA off; only console `doge-spv-peer-broadcast` exists). Spec requires building that CTA carefully.  
  3. Else wait / fail messaging.

- **One-broadcast-path per user action** — no auto-cascade (double-submission shaped).  
- **Noise warm-up first** before mesh helper; **never** LAN reachability probe ahead of `requestPeerBroadcast`.  
- Helper CTA edit at wallet sheet is the double-submit footgun — review carefully.  
- `requestPeerBroadcast` has **no mainnet gate** inside coordinator — callers must not assume it.

**R-C4 — SPV remains default**  
**R-C4a — Non-persistence (critical):**

Assist **must not**:

- call `saveBackend(RPC)`, or  
- allow existing **saveRpcConfig-on-send/broadcast** paths to persist the assist node as the user’s permanent node when assist is temporary.

**Acceptance test:** enable assist → kill app → relaunch with node **unreachable** → `resolveBackend` is **SPV**.

**R-C5 — Retry**

- No auto-broadcast when node appears.  
- Retry only as **user action** while the **same** confirm dialog is open; expired signed tx discarded (existing review expiry).

**R-C6 — Cross-check**

Promote former open decision: when SPV reaches synced after assist was used, run existing `DogecoinSpvCrossCheck` once; on mismatch: warn, revert reads to SPV, mark pin **disputed** (suspends helper broadcasts using that node).

**R-C7 — Persona (provisioner-only)**

- Discovery / trust / pin UI: **provisioner / power profile only**.  
- Simple family devices **consume** a node pinned at provisioning (QR).  
- Simple shows status banner only — no “OK” spam on trust prompts.

**R-C8 — Mainnet fee-burn mitigation**

Legacy Dogecoin sighash doesn’t commit to input values; lying UTXO values → silent fee-burn. Mainnet: **implied-fee hard cap** and/or per-outpoint corroboration before sign. High-fee ack alone is insufficient if computed from lying data.

**R-C9 — Node-assist send semantics**

Mark **Claimed-not-Confirmed** until SPV or **opt-in** explorer sees txid. `sendrawtransaction` returning txid ≠ propagation.

**R-C10 — Helper inheritance**

Pinned node on a helper device also relays **others’** txs — disclose on helper toggle; disputed pin suspends helper broadcasts.

**R-C11 — Breakage guardrails**

- Never touch SPV `torConnectionManager` / rebuild-fail-to-STOPPED.  
- Receipt/assist status polling: existing **read-only** SPV calls only.  
- Disclose RPC Tor bypass in UI copy.

### 5.3 Tests (C)

- Non-persistence acceptance (R-C4a).  
- Mainnet: unpinned node never queried.  
- One-broadcast-path invariant with node + helper candidate both present.  
- Sanity checks reject wrong chain / absurd height.  
- Cross-check mismatch → disputed.  
- JA smoke for banner/pin/QR strings.  
- Negative: wrong password → no silent public fallback.

---

## 6. Priority & PR slices (amended)

| PR | Scope | Effort |
|----|--------|--------|
| **PR1** | Rename UI + resolver + surface table (titles, notifications, restore, mesh notif) + pet-name≠bgm + EN/JA | **M** (not S–M) |
| **PR2** | `dogepaid:` parse/bubble both UIs + auto-send on success + R-B1a/B3a/B5 + EN/JA | **M** (independent of PR1 for structure; labels nice-to-have) |
| **PR3** | Home-node assist: pin + non-persistence + banner + QR import + trigger/`!synced` + mainnet gates + EN/JA | **M–L** |
| ~~PR4~~ | ~~mDNS/subnet~~ → **cancelled**; QR covers persona |

Do not block PR1/PR2 on assist.

---

## 7. Decisions log (closed)

| ID | Decision |
|----|----------|
| D1 | Receipt wire = **`dogepaid:` v1** grammar in §4.2; **ban `dogecoin:` receipts** |
| D2 | Display-time resolve; **no** storage rewrite of `sender` |
| D3 | Open title is **derived**; rename live-updates |
| D4 | Group-request payment receipts → **1:1 to requester** in v1 |
| D5 | SPV↔node cross-check **required** after assist when SPV syncs |
| D6 | QR import replaces LAN scan in v1 |
| D7 | G0 localization EN+JA same PR |

---

## 8. Security & privacy checklist

| Topic | Stance |
|-------|--------|
| Pet-names | Local for display; **must not** leak via group `bgm` |
| Remote names | Sanitized; never override local; collision → unverified |
| Payment receipts | Unauthenticated **claims**; claim-until-corroborated; no public auto-receipt |
| Explorer | Never auto on receipt receive (txid-interest leak) |
| LAN/home RPC | Pin required; mainnet stricter; cleartext Basic auth disclosed; no Tor on RPC path disclosed |
| Fee-burn | Mainnet implied-fee / value corroboration |
| Mesh helpers | Warm-up first; one path per action; mainnet gates stay upstream |
| SPV Tor | Assist must not weaken fail-closed rebuild semantics |

---

## 9. Do-not-build list

1. R-C1 v1b LAN subnet probe / mDNS (spoofable co-tenant; OEM/permission cost; wrong persona).  
2. Any receipt encoding under **`dogecoin:`** (double-pay on shipped clients).  
3. Pin-time best-block-hash fingerprint.  
4. Auto-retry/auto-broadcast when node appears outside open confirm dialog.  
5. Mainnet reads or broadcast to discovered/unpinned nodes.  
6. Storage rewrite of `message.sender` for rename.  
7. Automatic explorer lookups on receipt receive; status field on receipt wire.  
8. Putting LAN probe ahead of `requestPeerBroadcast` / Noise warm-up.  
9. Extending payment-URI finders to treat `dogepaid:` as payable.

---

## 10. Consolidated two-device ship test matrix

1. Activity recreation: favorite title survives dark-mode / rotation / split-screen.  
2. Process death: cold start with stored `"anon"` sender; kill between broadcast success and receipt send; dismiss sheet mid-broadcast.  
3. Receipt transports: mesh, Nostr, store-and-forward, helper.  
4. SPV-behind requester status honesty.  
5. JA locale smoke + EN/JA key parity CI.  
6. `dogepaid:` both profiles both directions.  
7. Rename mid-thread; delete favorite.  
8. Adversarial names + malformed receipts.  
9. Cross-network receipt badge-only.  
10. Assist non-persistence after kill.  
11. One-broadcast-path invariant.  
12. Same-house BLE notification shows favorite name.

---

## 11. References (code)

- Simple UI: `profile/ui/SimpleModeScreen.kt` (`openContactByConvKey` ~153, restore ~185, `showSender` ~601)  
- Favorites: `favorites/FavoritesPersistenceService.kt` (fingerprint note ~127–133)  
- `"anon"`: `nostr/GeohashRepository.kt`; bake-in: `NostrDirectMessageHandler`  
- Notifications: `NotificationManager`; mesh: `BluetoothMeshService`  
- Payment request: `DogecoinPaymentRequest.kt`, `DogecoinUri.kt`  
- Manual share: `DogecoinWalletSheet` share-to-chat  
- SPV: `DogecoinSpvService`; mesh pay: `PaymentBroadcastCoordinator`  
- Backend sticky: `DogecoinWalletRepository.resolveBackend` / `saveBackend`  
- Prior docs: `dogecoin-frictionless-plan.md`, `dogecoin-offline-mesh-relay-findings.md`, `simple-profile-tightening-plan.md`

---

## Appendix A — User stories

1. As a parent on S24, I **rename** the tablet contact to **googlepad** and always see that name on the thread title and notifications — never **anon** — including after rotation.  
2. As googlepad, when I request DOGE and S24 pays, I get a **`dogepaid:`** bubble that says they **report** payment until **my** wallet sees the txid, then confirmations climb.  
3. As provisioner, I pin home Core via **QR** onto family devices; when SPV lags they still balance/send; if I kill the app and the node is down, they fall back to **SPV**, not a sticky RPC forever.

---

## Appendix B — Review provenance

External design review (Claude Fable / multi-agent workflow) verdict: **approve-with-changes**. All five blocking findings addressed in this v2. Mesh offline broadcast and SPV Tor fail-closed remain intact if implementers honor R-C3 warm-up order, R-C4a non-persistence, and R-C11 / R-B success-hook constraints.
