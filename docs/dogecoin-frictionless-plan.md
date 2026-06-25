# Dogecoin Wallet — Frictionless Send/Receive Implementation Plan

> Status: proposed roadmap (no code written). Grounded in a code audit of the
> current wallet (`app/src/main/java/com/bitchat/android/features/dogecoin/`),
> the Langerhans/MyDoge wallet for reference, and bitchat's mesh primitives.

## Diagnosis

~90% of the wallet's friction is one thing: **every user must run, sync,
authenticate, and reach their own Dogecoin Core RPC node.** The Langerhans
wallet feels effortless because it removed that entirely (on-device SPV light
client). Everything else (address book, QR, fee tiers, fiat) is gravy on top.

Two strategic moves, done in order: (1) remove the cheap friction now, then
(2) play the card no normal wallet can — the **mesh**: a bitchat user shouldn't
need their *own* node, just *a peer who has one*.

## Architectural backbone (do early, unlocks Milestones 3+)

Extract a **`WalletDataSource`** interface that mirrors the 8 methods the UI
already calls on `DogecoinRpcClient` (`getBlockchainStatus`, `getWalletBalance`,
`listUnspent`, `sendRawTransaction`, `getWalletActivity`, `getAddressWatchStatus`,
`testMempoolAcceptance`, `rescanWalletHistory`). Make `DogecoinRpcClient` the
`CoreRpcWalletDataSource` implementation. This makes **Core RPC, mesh-relay
broadcast, Electrum, and SPV interchangeable backends** and keeps the UI
source-agnostic. Pull the raw-tx validation (`validateRawTransactionShape`,
`isStandardDogecoinOutputScript`, `normalizedRawTransactionHex`) into a shared
`DogecoinRawTxValidator` so every backend reuses it. (Effort: M.)

---

## Milestone 0 — Stop the bleeding (UX hardening) — effort L

The Tier-0 batch; mostly `DogecoinWalletSheet.kt` + a few model/string files.
No protocol changes. Highest value-to-effort; fixes exactly what hurt in testing.

| # | Change | Where |
|---|--------|-------|
| 0.1 | **Don't ship the emulator RPC URL on release.** Rename `DogecoinNetwork.defaultRpcUrl` → `emulatorRpcUrl` (DEBUG-only seed); `loadRpcConfig`/`DogecoinRpcConfig.url` default to `""`; blank field + placeholder/help drives the empty-state UX. | `DogecoinWallet.kt`, `DogecoinWalletRepository.kt`, `DogecoinWalletSheet.kt` |
| 0.2 | **Decouple receive from WIF backup.** Remove `mainnetReceiveBackupRequired`/`canExposeReceiveDetails` gating on the address+request cards; always allow viewing/copying the public address+QR; keep the backup nudge non-blocking. **Do NOT touch the send-side gate** (`reviewSend` still blocks mainnet spend until WIF backed up). | `DogecoinWalletSheet.kt` |
| 0.3 | **Slow/Normal/Fast fee presets** derived from node relay/incremental fee; show absolute `~X DOGE`; "Advanced" reveals manual DOGE/kB. Expose `DogecoinTransactionBuilder.estimateFeeForSelection(...)`. | `DogecoinWalletSheet.kt`, `DogecoinTransaction.kt` |
| 0.4 | **Soften Core-1.14 messages.** `testmempoolaccept`/`rescanblockchain` "unavailable" → informational tone (tertiary), not red error. Keep red only for genuine verification failures and the mainnet send-time caveat. | `DogecoinWalletSheet.kt` (`NodeStatusRow`), `strings.xml` |
| 0.5 | **Node card first + debounced auto-recheck + keep balance.** Move `item("node")` directly under `item("network")`; debounced `LaunchedEffect` re-checks after RPC edits (no manual button needed); `invalidateRpcRuntimeState` keeps last-known balance with a "revalidating" hint instead of nulling it. Add a copyable `dogecoin.conf` snippet behind a "How do I connect my node?" expander. (Recompute `DOGECOIN_SEND_ITEM_INDEX`.) | `DogecoinWalletSheet.kt` |
| 0.6 | **Default first-run to testnet** + one-time "practice before real funds?" nudge. `loadSelectedNetwork()` returns TESTNET when no selection stored; keep `DEFAULT=MAINNET` for `fromId` fallback. | `DogecoinWalletRepository.kt`, `DogecoinWalletSheet.kt` |

**Acceptance:** release build shows an empty RPC URL with help text; first launch opens on testnet with a dismissable nudge; mainnet address/QR/copy are visible without a WIF backup, but mainnet *send* still requires it; fee UI shows presets with `~DOGE` estimates; node card is step 1 and auto-rechecks; balance persists across RPC edits.
**Risks:** `DogecoinWalletSheet.kt` is one ~3.3k-line Composable — batch these to avoid repeated reflows and merge pain.
**Open Qs:** exact fee multipliers given Dogecoin's flat min-relay; auto-fill detected LAN subnet in the conf snippet?

---

## Milestone 1 — Send/receive ergonomics — effort M

Additive UI, no protocol changes. Makes the everyday flow pleasant.

- **1.1 Local address book** (per-network, in EncryptedSharedPreferences): a `DogecoinSavedAddress` store + picker in the Send flow + "Save this recipient" after a send. `DogecoinWalletRepository.kt`, `DogecoinWalletSheet.kt`.
- **1.2 WIF QR backup/restore + reveal toggle + valid-address echo.** Display/scan the 52-char WIF as a QR (reuse the existing scanner); reveal/hide on the import field; positive "✓ valid <network> address" confirmation on recipient entry. ⚠️ **WIF-QR display is a secret-exposure surface** (screenshots/recording) — gate behind a warning, consider testnet-only or an explicit "show" with FLAG_SECURE on that screen.
- **1.3 One-tap payment requests in chat.** New `RequestDogeDialog` (amount/label/message) posts a `dogecoin:` URI built via `DogecoinProtocol.createPaymentUri`; new `DogecoinPaymentRequestBubble` renders incoming/outgoing whole-message URIs as a rich bubble with a **Pay** button (reuses `onDogecoinUriClick`). Works in mesh/channel/private (store-and-forward). `MessageComponents.kt`, `InputComponents.kt`, `ChatScreen.kt`, `ChatViewModel`.

**Note — descoped:** QR *scan-to-pay* **already exists** in `DogecoinWalletSheet.kt`; reuse it, don't rebuild.
**Acceptance:** saved recipients persist per-network; composer "Request DOGE" posts a valid request that renders as a bubble and one-tap-pays; inline URIs in prose still render as links (no regression).
**Risks:** payment-request in a public/geohash channel reveals your receive address to all (privacy); bubble false-positive on a message that is exactly a URI (acceptable).

---

## Milestone 2 — Identity-bound payments ("Pay @nickname") — effort L

The product leap: send to a *person*, not a 34-char string. Touches the signed
mesh identity protocol — handle with care.

- Add optional, **Ed25519-signed** Dogecoin address TLVs (mainnet/testnet) to `IdentityAnnouncement`; broadcast the current-network address in `sendBroadcastAnnounce`; decode in `handleAnnounce` **after signature verification** into `PeerInfo.dogecoinAddresses` and cache by fingerprint (`SecureIdentityStateManager`/`FavoritesPersistenceService`).
- **"Send DOGE"** action in `MeshPeerListSheet`, private chat header, and `VerificationSheet` → resolves nickname → peerID → fingerprint → address → opens the send flow prefilled; show verified state.

**Acceptance:** switching wallet network re-announces the right address; peer's signed address decodes into `PeerInfo`; "Send DOGE" from peer list/chat/verification prefills correctly; tampered/unsigned address TLVs are rejected.
**Risks (must mitigate):** **(a)** signature-verification dependency — if bypassed, an attacker injects a payee address (mandatory verify); **(b)** address-reuse privacy — one fixed address per peer breaks rotation (consider rotating/derived addresses or a "fresh address" option); **(c)** TLV size growth and **backward compat** — use a tolerant TLV decoder (skip unknown types) so old clients don't break.
**Open Qs:** allow manual override of the advertised address? auto-announce on key reset? multi-network UI (dropdown vs current-network).

---

## Milestone 3 — The mesh superpower (node-optional) — effort L + XL

Where bitchat does what no normal wallet can.

- **3a. `WalletDataSource` abstraction** (M) — the backbone above. Prereq for 3b/3c.
- **3b. Broadcast-over-mesh** (L) — a node-less sender signs locally (already proven) and relays the raw signed tx over Noise-encrypted mesh/Nostr to an **opt-in helper peer** who broadcasts and returns the txid. New `NoisePayloadType.PAYMENT_BROADCAST_REQUEST (0x30)` / `PAYMENT_BROADCAST_RESULT (0x31)`; `PaymentBroadcastPacket`; `BroadcastHelperService` (opt-in flag, validates raw-tx shape, per-peer rate-limit, prefers favorites, custodies nothing). Sender UX: when the local node is unavailable, "ask a connected peer to broadcast" with pending/confirmed state. `MessageHandler.kt`, `BluetoothMeshService.kt`, `DogecoinRpcClient.sendRawTransaction`.
- **3c. Zero-node read + fiat (SPIKE)** (XL) — spike Electrum-style read backend (balance/UTXO with no node) and an on-device SPV client (bitcoinj/libdohj; needs Android checkpoints + AuxPoW/Scrypt validation); each becomes a `WalletDataSource`. Add a thin HTTPS **fiat price** line (compile-out-able). Deliver a spike report (feasibility, APK size, maintenance, privacy) before committing.

**Trust model (3b):** the relayer sees a tx that's public once broadcast and *cannot steal* (already signed); they could delay/censor (mitigate: try multiple helpers, timeout, retry). Rate-limit + opt-in prevents abuse.
**Risks:** relay timeout/peer-disappears (30s timeout + retry + dedup via request UUID); Electrum/SPV privacy (address+IP exposure — consider Tor) cuts against the off-grid premise; SPV maintenance/library health is the big unknown — that's why it's a spike.
**Open Qs:** is mesh-relay a first-class backend or a broadcast-only fallback? privacy stance on public Electrum servers?

---

## Recommended order to build

1. **Milestone 0** — immediate, broad relief; ship it first.
2. **Milestone 1** (address book + payment requests) — big perceived win, low risk.
3. **`WalletDataSource` refactor (3a)** — small, unlocks the rest.
4. **Milestone 2** (pay-@nickname) — the product identity.
5. **Broadcast-over-mesh (3b)** — the flagship demo.
6. **SPV/Electrum spike (3c)** — decide the long-term zero-node endgame.

Each milestone is independently shippable and demoable. M0+M1 alone would have
turned the painful test flow into a pleasant one.
