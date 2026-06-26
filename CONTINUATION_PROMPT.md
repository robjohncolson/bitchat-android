# Continuation Prompt: Bitchat Android Dogecoin Wallet

Continue work in:

```text
C:\Users\rober\Downloads\Projects\bitchat-android
```

Goal: continue the Dogecoin wallet integration in Bitchat Android. Work autonomously, inspect the
relevant files first, keep changes focused, do not revert unrelated user changes, and verify with
focused Gradle + on-device checks. **Money path + signed mesh protocol — review carefully.**

## Current State (branch `dogecoin-m2-pay-nickname`)

The wallet does legacy P2PKH/P2SH signing on-device against a user-run Dogecoin Core RPC node, with
mainnet/testnet/regtest separation and heavy money-safety guards. The full build→sign→broadcast
pipeline is **proven on regtest and on public testnet** (real testnet broadcast txid
`673fdcd5c6723d5820872ae72a6d95cbd6ae1407904220a4f77e4a10e4dab507`, mined in testnet block 65694937).

Milestones shipped on this branch (newest first):

- **M3b — Broadcast-over-mesh (mesh portion), commits `754b404`→`68fc1c1`.** A node-less sender relays
  an already-SIGNED tx to an opt-in helper peer over the Noise mesh; the helper broadcasts via its own
  node and returns the node-verified txid. NoisePayloadType `0x30`/`0x31` + `PaymentBroadcastPacket`
  codec; `BroadcastHelperService` (per-network opt-in, **mainnet default-off + explicit consent**,
  favorites-only default-on, hardened gate order, Sybil ceilings, holds no keys); `PaymentBroadcastCoordinator`
  (fan-out ≤2, txid cross-check, no single-helper abort); wallet "Ask a peer to broadcast" CTA + helper
  opt-in card; signed `NODE_HELPER` (TLVType `0x06`) capability advert (verify-then-store).
  Adversarially reviewed at the implementation level — a HIGH money-safety stale-receipt bug + 2 LOWs
  were found and fixed. **The full mesh round-trip is now PROVEN end-to-end on two phones** (request →
  helper gates → `NODE_NOT_READY` result → sender display), after a fix (commit `6fa3a52`) that makes the
  helper toggle re-announce so the sender can actually discover the helper. See "Two-phone test status".
- **M2 — Pay @nickname.** Ed25519-signed Dogecoin receive-address TLVs in IdentityAnnouncement (decoded
  only after signature verification); "Send DOGE" from peer list / chat / verification. Address
  advertising is opt-in/default-off.
- **M0/M1 — UX hardening + send/receive ergonomics** (fee presets, address book, payment requests, etc.).

### Not done yet / next up

1. **Nostr off-mesh fallback (3b.1)** — ✅ **SHIPPED + adversarially reviewed (uncommitted working tree).**
   The `TODO(Task 10)` hooks are now wired. Send side: `NostrTransport.sendPaymentBroadcast{Request,Result}`
   (favorites→npub) + `sendPaymentBroadcastResultToPubkey` (helper reply to the gift-wrap sender's pubkey);
   `NostrEmbeddedBitChat.encodeNoisePayloadForNostr` gift-wraps the `0x30`/`0x31` `NoisePayload`;
   `MessageRouter.sendPaymentBroadcast{Request,Result}` route over Nostr when the peer is off-mesh +
   Nostr-reachable. Receive side: `NostrDirectMessageHandler` `0x30` arm → `BroadcastHelperService.handleRequest`
   (mutual-favorites-only over Nostr; drops others silently) → reply over Nostr; `0x31` arm →
   `PaymentBroadcastResultRouter` (new singleton bridge) → the active ViewModel's
   `PaymentBroadcastCoordinator.onResult`. Off-mesh mutual favorites are appended to the candidate list
   (lower-priority than mesh). **Budget kept at the proven 90s (append tier, per the chosen "minimal & safe"
   approach — coordinator UNCHANGED); slow relays degrade to `Claimed`/`Failed` but never a false `Confirmed`.**
   **Money-safety crux (found by adversarial review, fixed):** the two-helper corroboration must count by a
   helper's STABLE identity, not a transport reply id. A Nostr pubkey is free to mint, so the first cut let a
   single helper mint identities (or reply on both transports) to fake `Confirmed`. Fix: corroboration id is
   now the helper's **Noise static key** end-to-end — both receive paths resolve to Noise-key hex, the Nostr
   arm DROPS anything that doesn't resolve to a known favorite, and mesh drops if unresolved (symmetric). One
   physical helper = one identity across transports. **Still ships UNVALIDATED against live relays** (no infra
   in-repo); the design is safe-by-construction regardless of relay RTT.
2. **Single-ACCEPTED corroboration (3b.1)** — ✅ **corroboration shipped (commit `88fa814`); explorer poll
   now SHIPPED too (uncommitted).** A lone helper's ACCEPTED is `Outcome.Claimed` (uncorroborated, strong
   "verify before settled" receipt disclaimer via `peerCorroborated`); `Outcome.Confirmed` requires **two
   distinct** positive helpers. **NEW:** opt-in/default-OFF on-chain corroboration —
   `DogecoinTxConfirmationChecker`/`ExplorerTxConfirmationChecker` (built-in Blockchair for mainnet,
   user-configurable `{txid}` URL per network; HTTPS-only-to-public URL policy) polled by
   `ChatViewModel.resolveClaimedPeerBroadcast` to upgrade `Claimed→Confirmed` on a positive sighting (decided
   before emitting so the receipt is built once). Repository prefs added; wallet-sheet toggle + URL field
   (hidden on regtest). `parsePresence` returns `true` only on a definitive sighting. Known shared limitation:
   the Nostr/mesh broadcast encoder is `version=1u` (64 KB payload cap) — large txs (~32 KB+) fail on BOTH
   transports; not a regression (mesh `sendNoisePayloadToPeer` is also v1). Tests: candidate-merge (8),
   explorer parse+URL policy (13), result-router (4), Nostr embed round-trip (2), coordinator (now 17).
3. **iOS cross-platform pre-merge gate** — reserve `NoisePayloadType` `0x30`/`0x31` and `TLVType` `0x06`
   on the iOS client before shipping. Cannot verify from this repo; tolerant decode keeps it
   Android↔iOS-safe meanwhile.
4. **Funded mainnet broadcast** — still user-gated (irreversible real-money action via the on-device UI).
5. **Core bug — mutual-favorite key mismatch — ✅ FIXED (commit `c9b031d`).** `isMutual` never resolved on
   the peerID path: `FavoritesPersistenceService.getFavoriteStatus(peerID: String)` prefix-matched the
   16-hex peerID against the raw *noise-key* hex, but peerID = `SHA-256(noiseKey).take(16)` (a *fingerprint*
   prefix), so it never matched. Now matches against the derived fingerprint (+ direct hit for a full
   noise-key hex; blank → null). The ByteArray overload was correct and is untouched. Fixed the silent
   breakage of Nostr DM routing to offline mutual favorites + offline fingerprint/nickname display; all 5
   String-overload call sites verified to consume the now-resolving result defensively.
   `FavoritesPersistenceServicePeerIdTest` (8 cases) added. Did NOT affect the Dogecoin broadcast-helper
   path (it uses the ByteArray overload).
6. **Successful *on-chain* peer broadcast** — the two-phone round-trip is proven, but landing a peer
   broadcast on the actual chain needs a **second relay-up node** for the helper (the one shared node was
   set `networkactive=false` to force the CTA, so it returns `NODE_NOT_READY`). The `sendrawtransaction`
   step itself is already proven via `DogecoinLiveNodeIntegrationTest` + the Pixel-3 direct broadcast.

## Local Dogecoin Node (synced)

Dogecoin Core runs locally; the testnet node is **fully synced**:

```text
"C:\Program Files\Dogecoin\dogecoin-qt.exe" -testnet     # RPC 127.0.0.1:44555 (P2P 44556)
"C:\Program Files\Dogecoin\daemon\dogecoin-cli.exe" -testnet <cmd>
```

- Config (do NOT print the rpcpassword): `C:\Users\rober\AppData\Roaming\Dogecoin\dogecoin.conf`
  (`server=1`, `rpcbind=127.0.0.1`, `rpcallowip=127.0.0.1`, plus `rpcuser`/`rpcpassword`). For an
  emulator/LAN device to reach it, set `rpcbind=0.0.0.0` + `rpcallowip=10.0.2.2` (emulator) or the PC LAN
  IP (physical), then restart.
  **CURRENT STATE: the node is running LAN-open via *ephemeral CLI flags*, NOT the conf** — it was launched
  `dogecoin-qt -testnet -rpcbind=10.0.0.24 -rpcbind=127.0.0.1 -rpcallowip=10.0.0.0/24 -rpcallowip=127.0.0.1`
  (currently listening on both `127.0.0.1:44555` and `10.0.0.24:44555`). A plain `dogecoin-qt -testnet`
  restart REVERTS to localhost-only, so re-launch with those flags (or edit the conf) before a phone re-test.
  It can take several minutes to warm up after a restart (block-index check + any unclean-shutdown rewind).
- Funded node-owned testnet address: `nceDCkWAP9tSktE5TJ5X6LVmD2r3HwiAXN` — **~1.72M spendable TESTDOGE
  across 166 UTXOs** (2026-06-25; the old maturing coinbase has matured; ~100 spent in the round-trip test).
  `dumpprivkey` it for the harness WIF. Its WIF is already imported into the Pixel's testnet wallet.
- **Coinbase maturity is 240 blocks** (empirically verified — NOT 100). Faucets are dead; get test coins
  by CPU-mining: `minerd -a scrypt -o http://127.0.0.1:44555 -O <user>:<pass> --coinbase-addr=<addr> -t 12`
  (pooler cpuminer, github.com/pooler/cpuminer releases). The node's own `generatetoaddress` is too slow
  and mines stale blocks on the active chain.
- Automated end-to-end: `DogecoinLiveNodeIntegrationTest` with
  `DOGE_RPC_URL=http://127.0.0.1:44555 DOGE_RPC_USER=<u> DOGE_RPC_PASS=<p> DOGE_NETWORK=testnet DOGE_WIF=<funded> DOGE_BROADCAST=true`
  (hard-refuses mainnet broadcast).

## Android / Device State

- SDK `C:\Users\rober\AppData\Local\Android\Sdk`; `adb` at `…\Sdk\platform-tools\adb`.
- Two physical devices for the mesh test: **Galaxy S24** (Android 16) serial `RFCX81GNBRE` = **helper**;
  **Pixel 3** (Android 12, arm64) serial `89VX0HPX1` = **sender**. Both have the current debug build
  (incl. the `6fa3a52` fix). (S24 has a secure lock screen — a human must unlock it; once unlocked + the
  app foregrounded, both phones were driven entirely via adb — see the gotchas below.)
- **Debug app id is `com.bitchat.droid.debug`** (`applicationIdSuffix = ".debug"`), so it coexists with a
  Play-installed `com.bitchat.droid`. The custom force-finish permission is namespaced
  `${applicationId}.permission.FORCE_FINISH`. The REGTEST network selector is exposed only when
  `BuildConfig.DEBUG`.

```powershell
.\gradlew.bat assembleDebug
# Output is SPLIT per-ABI (no single app-debug.apk). Both phones are arm64:
adb -s RFCX81GNBRE install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s RFCX81GNBRE shell monkey -p com.bitchat.droid.debug -c android.intent.category.LAUNCHER 1
```

Driving Compose via adb (this whole two-phone test was driven this way, both phones, once unlocked):
`uiautomator dump` works; `adb pull /sdcard/...` needs `MSYS_NO_PATHCONV=1` (Git Bash mangles `/sdcard`).
Estimate tap coords from the dump's `bounds`, not scaled screenshots. Hard-won gotchas:
- The wallet bottom-sheet dismisses on a downward swipe at its top, and **`keyevent 111` (ESCAPE) also
  dismisses it** (network resets to mainnet on reopen). Never use 111 inside the sheet.
- To drop the soft keyboard before tapping a lower field, use **`keyevent 4` (BACK)** — it hides the IME
  *without* dismissing the sheet; then re-dump for the field's new bounds. Tapping a field while the
  keyboard / predictive-suggestion strip is up sends input to the wrong place.
- `adb shell input text` **mangles long random strings** (a 36-char rpcpassword landed wrong → node logged
  `incorrect password attempt`). Clear with MOVE_END(123)+DEL(67) and verify field length vs the source.
- The Broadcast-confirm dialog is **scrollable**; the "Ask a peer to broadcast" CTA sits *below the fold*
  (after the signed-txid + copy buttons) — scroll inside the dialog to reveal it.

**Two-phone test status (2026-06-25):**
- ✅ **Sender pipeline proven on Pixel-3 hardware (testnet):** with the node opened to the LAN
  (`http://10.0.0.24:44555`), drove the whole send flow through the app UI on the Pixel 3 — switched to
  testnet, **imported the funded WIF** (`nceDC…`, ~1.72M TESTDOGE, node-owned → balance loaded with no
  rescan), built a 100-DOGE send to `nUW5oqZKqcLfyDAVBW1uAoigt9esediX35`, **signed on-device**, broadcast
  through the node. Result txid `7dfb78e55888ad94088e2654ba6f60de94333376f708f483208e8ee4f845171b` matched
  the signed txid, node-accepted (vout = 100 to recipient + 69899.98 change to `nceDC`), **mined to 2 conf**
  in testnet block `25154995…`. So the M3b *sender* produces network-valid signed txs on real hardware.
- ✅ **FULL mesh round-trip PROVEN two-phone (after a fix).** The "Ask a peer to broadcast" CTA was not
  appearing because `ChatViewModel.hasBroadcastHelperCandidate()` requires `hasEstablishedSession(peer) &&
  (peer advertises the network || mutual favorite)`, and the **advert never propagated** to the sender: the
  helper Switch only saved the flag — unlike the working advertise-address toggle, it did **not** re-announce,
  so a running app never re-broadcast the capability. **Fix (committed):** the helper toggle now calls a new
  `onHelperEnabledChanged` → `ChatScreen` → `viewModel.reannounceIdentity()` (renamed from
  `reannounceDogecoinReceiveAddress`; just `mesh.sendBroadcastAnnounce()`), plus a `PeerManager` diagnostic
  log. After rebuild+reinstall the Pixel logged `🛰️ peer 22381df3 NODE_HELPER networks updated -> [testnet,
  regtest]`, and with a Noise session (open a DM to establish it) the CTA rendered (it sits **below the fold**
  in the scrollable Broadcast-confirm dialog). Tapping it fired: Pixel `📤 Sent payment broadcast request
  (565 bytes)` → S24 `💸 Payment broadcast request received` → result back → Pixel `No connected peer accepted
  the transaction` in ~11s (a real `NODE_NOT_READY`, the expected single-`networkactive=false`-node outcome).
  A *successful on-chain* peer broadcast still needs a 2nd relay-up node, but the wire path is fully proven.
- ✅ **FIXED core bug — mutual-favorite key mismatch (commit `c9b031d`, NOT the Dogecoin feature).**
  `FavoritesPersistenceService.getFavoriteStatus(peerID: String)` prefix-matched the 16-hex peerID against
  the raw *noise-key* hex, but peerID is a *fingerprint* prefix (`SHA-256(noiseKey).take(16)`) → never matched,
  so `isMutual` never resolved on the peerID path (silently broke Nostr DM routing + offline display). Now
  matches the derived fingerprint. See "Not done yet" #5. (adb-driving gotchas live in "Driving Compose via adb".)
- ✅ VERIFIED on hardware (earlier): app installs + launches crash-free on both (S24 Android 16, Pixel 3 Android 12);
  the S24 wallet sheet + the new helper opt-in card render correctly (enable switch OFF by default,
  "Only help my mutual favorites" ON by default); the two phones form a **BLE mesh** and Ed25519-**verify**
  each other's signed announce (logcat `MessageHandler: ✅ Verified announce from <peerID>`); enabling the
  S24 helper grew its signed announce **88 → 97 bytes** (the `NODE_HELPER` TLV: type+len+"regtest") and the
  Pixel **re-verified** the larger announce — **capability advert proven live**.
- ✅ Round-trip recipe (this is what was run, and how to re-run): (1) node opened to the LAN
  (`dogecoin-cli -testnet` reachable at `http://10.0.0.24:44555` from both phones); (2) both phones on
  testnet, RPC URL + creds set, **S24 helper ON for testnet** (favorites-only OFF); (3) funded WIF
  (`dumpprivkey nceDC…`) imported into the Pixel via wallet → Import WIF (loads UTXOs, no rescan since the
  node owns it); (4) **establish a Noise session** — open a DM Pixel→S24 (`hasEstablishedSession` is a hard
  precondition for the candidate, and announces alone don't create one); (5) `dogecoin-cli -testnet
  setnetworkactive false` so the Pixel's `canBroadcastFor=false` (CTA path) while `listUnspent` still works;
  (6) build a small Review send → scroll inside the confirm dialog → check the ack box → "Ask a peer to
  broadcast". With ONE shared (relay-down) node the helper returns `NODE_NOT_READY` → "No connected peer
  accepted" (this is what we got — proves the wire path). For a **successful on-chain** peer broadcast,
  give the helper a *second* relay-up node (see "Not done yet" #6). **Restore `setnetworkactive true` after.**

## Key Files

3b broadcast-over-mesh: `model/{NoiseEncrypted,IdentityAnnouncement,PaymentBroadcastPacket}.kt`,
`mesh/{MessageHandler,PeerManager,BluetoothMeshService,MeshCore,MeshService,UnifiedMeshService,MeshDelegate}.kt`,
`wifi-aware/WifiAwareMeshService.kt`, `services/MessageRouter.kt`, `nostr/NostrDirectMessageHandler.kt`,
`features/dogecoin/{DogecoinRawTxValidator,BroadcastHelperService,PaymentBroadcastCoordinator,DogecoinHelperAnnouncement,DogecoinRpcClient,DogecoinWalletRepository,DogecoinWalletSheet}.kt`,
`ui/{ChatViewModel,ChatScreen,VerificationSheet,MeshPeerListSheet}.kt`.

## Verification

```powershell
# IMPORTANT: never pipe gradlew to `tail`/`grep` and trust the exit code — the pipe returns the
# filter's exit (0) and MASKS a BUILD FAILED. Capture to a file and check $LASTEXITCODE.
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
```

Last green at commit `c9b031d` (testDebugUnitTest + assembleDebug; the mutual-favorite peerID fix). The
3b.1 Nostr-fallback + explorer-corroboration work is **green in the working tree but UNCOMMITTED** (the user
has not asked to commit): `:app:testDebugUnitTest :app:assembleDebug` BUILD SUCCESSFUL, `git diff --check`
clean. New files: `features/dogecoin/{PaymentBroadcastResultRouter,BroadcastHelperCandidates,DogecoinTxConfirmationChecker}.kt`
+ 4 test files; edits to `nostr/{NostrEmbeddedBitChat,NostrTransport,NostrDirectMessageHandler}.kt`,
`services/MessageRouter.kt`, `ui/ChatViewModel.kt`, `features/dogecoin/{DogecoinWalletRepository,DogecoinWalletSheet}.kt`,
`res/values/strings.xml`.

## Constraints

- No mainnet wallet broadcast without explicit per-spend user authorization (irreversible real money).
- No custodial signing, remote key storage, or seed export without explicit approval.
- Never print private keys or RPC passwords in user-facing output.
- No destructive git commands. Keep changes narrowly scoped; follow existing Kotlin/Compose style.
