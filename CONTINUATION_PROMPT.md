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
  were found and fixed.
- **M2 — Pay @nickname.** Ed25519-signed Dogecoin receive-address TLVs in IdentityAnnouncement (decoded
  only after signature verification); "Send DOGE" from peer list / chat / verification. Address
  advertising is opt-in/default-off.
- **M0/M1 — UX hardening + send/receive ergonomics** (fee presets, address book, payment requests, etc.).

### Not done yet / next up

1. **Nostr off-mesh fallback (3b.1)** — deliberately deferred (most-severable; gift-wrap latency vs the
   10-min signed-tx window unvalidated). Staged as non-breaking `TODO(Task 10)` hooks in
   `services/MessageRouter.kt` (`sendPaymentBroadcastRequest/Result` Nostr branch) and
   `nostr/NostrDirectMessageHandler.kt` (the `0x30`/`0x31` arms).
2. **Single-ACCEPTED corroboration (3b.1)** — documented in `PaymentBroadcastCoordinator`: a lone
   helper's ACCEPTED is its *claim*, not chain-verified by the node-less sender. Add corroboration
   (second ACCEPTED) or an explorer/txid poll before presenting a settled receipt. No funds at risk;
   favorites-only default limits exposure.
3. **iOS cross-platform pre-merge gate** — reserve `NoisePayloadType` `0x30`/`0x31` and `TLVType` `0x06`
   on the iOS client before shipping. Cannot verify from this repo; tolerant decode keeps it
   Android↔iOS-safe meanwhile.
4. **Funded mainnet broadcast** — still user-gated (irreversible real-money action via the on-device UI).
5. **Full live peer-broadcast round-trip** (send→helper→result) NOT yet run — needs the node opened
   to the LAN + a funded sender (see "Two-phone test" below). Everything up to it is verified.

## Local Dogecoin Node (synced)

Dogecoin Core runs locally; the testnet node is **fully synced**:

```text
"C:\Program Files\Dogecoin\dogecoin-qt.exe" -testnet     # RPC 127.0.0.1:44555 (P2P 44556)
"C:\Program Files\Dogecoin\daemon\dogecoin-cli.exe" -testnet <cmd>
```

- Config (do NOT print the rpcpassword): `C:\Users\rober\AppData\Roaming\Dogecoin\dogecoin.conf`
  (`server=1`, `rpcbind=127.0.0.1`, `rpcallowip=127.0.0.1`). For an emulator/LAN device to reach it,
  set `rpcbind=0.0.0.0` + `rpcallowip=10.0.2.2` (emulator) or the PC LAN IP (physical), then restart.
- Funded node-owned testnet address: `nceDCkWAP9tSktE5TJ5X6LVmD2r3HwiAXN` (~90k spendable + ~1.6M maturing
  TESTDOGE). `dumpprivkey` it for the harness WIF.
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
  **Pixel 3** (Android 12, arm64) serial `89VX0HPX1` = **sender**. Both have the current debug build;
  Pixel 3 was granted BLE/location perms via `pm grant`. (S24 has a secure lock screen — needs the user
  to unlock; blind adb coordinate-taps proved flaky, so have a human navigate the wallet sheet.)
- **Debug app id is `com.bitchat.droid.debug`** (`applicationIdSuffix = ".debug"`), so it coexists with a
  Play-installed `com.bitchat.droid`. The custom force-finish permission is namespaced
  `${applicationId}.permission.FORCE_FINISH`. The REGTEST network selector is exposed only when
  `BuildConfig.DEBUG`.

```powershell
.\gradlew.bat assembleDebug
adb -s RFCX81GNBRE install -r app\build\outputs\apk\debug\app-debug.apk
adb -s RFCX81GNBRE shell monkey -p com.bitchat.droid.debug -c android.intent.category.LAUNCHER 1
```

Driving Compose via adb: `uiautomator dump` works; `adb pull /sdcard/...` needs `MSYS_NO_PATHCONV=1`
(Git Bash mangles `/sdcard`). Estimate tap coords from the dump's `bounds`, not scaled screenshots. The
wallet bottom-sheet dismisses on a downward swipe at its top — navigate with upward finger-swipes.

**Two-phone test status (2026-06-25):**
- ✅ VERIFIED on hardware: app installs + launches crash-free on both (S24 Android 16, Pixel 3 Android 12);
  the S24 wallet sheet + the new helper opt-in card render correctly (enable switch OFF by default,
  "Only help my mutual favorites" ON by default); the two phones form a **BLE mesh** and Ed25519-**verify**
  each other's signed announce (logcat `MessageHandler: ✅ Verified announce from <peerID>`); enabling the
  S24 helper grew its signed announce **88 → 97 bytes** (the `NODE_HELPER` TLV: type+len+"regtest") and the
  Pixel **re-verified** the larger announce — **capability advert proven live**.
- ⏳ NOT yet run: the full send→helper→result round-trip. Recipe: (1) open the testnet node to the LAN
  (dogecoin.conf: `rpcbind=0.0.0.0` + `rpcallowip=10.0.0.0/24`, restart `dogecoin-qt -testnet`), both phones
  point at `http://10.0.0.24:44555`; (2) set both phones to testnet, enable the S24 helper for testnet
  (favorites-only OFF for simplicity); (3) import the funded WIF (`dumpprivkey nceDC…`) into the Pixel via
  wallet → Import WIF so it has UTXOs; (4) on the Pixel build a small Review send. To force the "Ask a peer
  to broadcast" CTA the sender's node must be read-OK but broadcast-unavailable:
  `dogecoin-cli -testnet setnetworkactive false` makes `canBroadcastFor=false` while `listUnspent` still
  works. With ONE shared node the helper's broadcast then returns `NODE_NOT_READY` (proves the round-trip);
  a *successful* on-chain peer-broadcast needs a second relay-up node for the helper — but the
  `sendrawtransaction` step itself is already proven via `DogecoinLiveNodeIntegrationTest` (txid `673fdcd5…`).

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

Last green at commit `68fc1c1` (testDebugUnitTest + assembleDebug).

## Constraints

- No mainnet wallet broadcast without explicit per-spend user authorization (irreversible real money).
- No custodial signing, remote key storage, or seed export without explicit approval.
- Never print private keys or RPC passwords in user-facing output.
- No destructive git commands. Keep changes narrowly scoped; follow existing Kotlin/Compose style.
