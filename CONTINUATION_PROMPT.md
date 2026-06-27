# Continuation Prompt: Bitchat Android Dogecoin Wallet

Continue work in:

```text
C:\Users\rober\Downloads\Projects\bitchat-android
```

Goal: continue the Dogecoin wallet integration in Bitchat Android. Work autonomously, inspect the
relevant files first, keep changes focused, do not revert unrelated user changes, and verify with
focused Gradle + on-device checks. **Money path + signed mesh protocol — review carefully.**

## Current State (branch `dogecoin-m2-pay-nickname`)

The wallet does legacy P2PKH/P2SH signing on-device, with mainnet/testnet/regtest separation and heavy
money-safety guards. The full build→sign→broadcast pipeline is **proven on regtest and public testnet**
(testnet txid `673fdcd5…`, mined). Last green commit is **`d9455f0`** (`:app:testDebugUnitTest` +
`:app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean). **The whole 2026-06-26 session below
is COMMITTED** — the working tree is clean.

### Shipped milestones (older → newer)
- **M0/M1** — UX hardening + send/receive ergonomics (fee presets, address book, payment requests).
- **M2 — Pay @nickname.** Ed25519-signed Dogecoin receive-address TLVs in IdentityAnnouncement (decoded
  only after signature verification); opt-in/default-off advertising; "Send DOGE" entry points.
- **M3b — Broadcast-over-mesh.** A node-less sender relays an already-SIGNED tx to an opt-in helper peer;
  the helper broadcasts via its own node and returns the node-verified txid. `NoisePayloadType` `0x30`/`0x31`
  + `PaymentBroadcastPacket`; `BroadcastHelperService` (per-network opt-in, mainnet default-off, favorites-only
  default-on, Sybil ceilings, holds no keys); `PaymentBroadcastCoordinator` (fan-out ≤2, two-helper
  corroboration ⇒ `Confirmed`, lone helper ⇒ `Claimed`); signed `NODE_HELPER` (`TLVType 0x06`) advert.
- **3b.1 — Nostr off-mesh fallback + explorer corroboration (commit `3a52182`).** When a helper is off-mesh
  but a mutual-favorite over Nostr, the request/result gift-wrap over Nostr relays. Corroboration counts by
  the helper's **Noise static key** (a free-to-mint Nostr pubkey can't fake `Confirmed`). Opt-in explorer
  `Claimed→Confirmed` poll.

### Proven on real hardware THIS SESSION (2026-06-26)
- **Nostr off-mesh broadcast round-trip — PROVEN against LIVE relays** (~1s RTT, Tor OFF): Pixel (BT-off,
  off-mesh) → `MessageRouter` Nostr route → gift-wrap → live relay (`relay.damus.io`/`primal`/`nostr21`) →
  S24 helper gate → result back → coordinator resolved.
- **On-chain peer broadcast — PROVEN, mined to 36 confirmations.** Driven from the debug console with NO
  2nd node: `doge-peer-broadcast` built+signed a real 5-DOGE testnet tx → S24 helper broadcast it via the
  relay-up node → txid `d3be593cd9e3e41be649df17ddc3366718f51c9ee347a0b996faf08badb6b0c9`. **Key insight:**
  the console calls `requestPeerBroadcast(signed)` directly, bypassing the GUI's "can-I-broadcast" CTA gate,
  so the sender's node does NOT need to be relay-down — one relay-up node suffices.

### Session commits (newest first)
- `d9455f0`, `5c82c19`, `339fec5`, `2ce91d7`, `8696ebc` — **wallet UX redesign (P0+P1).** The sheet is one
  `LazyColumn` of `item(key=…)` blocks. Node/RPC/helper/corroboration/danger collapsed behind one **"Node &
  developer settings"** expander (auto-opens when unconfigured); de-jargoned labels (RPC URL→Node address,
  UTXO→coins, etc.); **balance is the hero** (headlineMedium) and moved first; raw coins behind a "Show coins"
  toggle; Request folded into a "Request a specific amount" expander; send-confirmation dialog plain-languaged
  **(every safety gate kept)**. Large block-moves were done with a byte-exact Python relocation script (see
  the scratchpad pattern) — NOT hand-transcription.
- `b939eca` — **explorer-backed "no-node" mode.** `DogecoinExplorerClient` reads UTXOs/balance + broadcasts
  via a public explorer (Blockbook keyless default / Blockchair w/ `?key=`); parsers unit-tested. **BUT free
  public Dogecoin explorers gate anonymous access** — Blockchair → 430 (needs a PAID key), Trezor Blockbook
  + Dogechain → Cloudflare 403. So this mode isn't usable keyless against those endpoints.
- `be0d8c2` — the debug console drives the whole wallet textually (see below).
- `8b20a79` — **fixed a real bug + built the debug console.** The Nostr `PRIVATE_MESSAGE` handler rendered
  `[FAVORITED]` DMs as chat instead of recording `theyFavoritedUs`, so favoriting OFF-MESH never made a pair
  mutual → the helper dropped off-mesh requests (self-undermining). Fixed + `updatePeerFavoritedUs` is now
  create-if-absent.

## Debug console (your primary on-device driver — `DebugConsole`, BuildConfig.DEBUG only)

Drive the app textually over adb (no UI tapping). **Quote the WHOLE am command** so a space-arg survives the
device shell:

```powershell
adb -s <serial> shell "am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver --es cmd 'doge-peer-broadcast nceDC… 5'"
adb -s <serial> logcat -d -s DbgConsole      # output lands here
```

Host registers from `ChatViewModel.init` (the chat screen must be alive — if "no host registered", relaunch
the app). Commands: `myid favorites candidates cansend forcemutual sendfav broadcast-test nostr tor peers
reannounce tor-set nostr-connect/-disconnect | doge-network <net> | doge-rpc-set/-show | doge-address |
doge-import-wif <wif> | doge-balance | doge-self-broadcast <addr> <amt> | doge-peer-broadcast <addr> <amt> |
doge-helper-enable <0|1> | doge-explorer-config <blockbook|blockchair> [key] | doge-explorer-balance/-utxos/
-broadcast/-send`. **Mainnet money-path commands HARD-REFUSE; WIF/keys never logged.**

## Next up / direction

1. **SELF-CONTAINED WALLET via SPV — the agreed next direction** (Blockchair keys cost money; the user wants
   no node + no paid key). Build an **SPV backend like the Langerhans wallet** (`C:\Users\rober\Downloads\
   Projects\dogecoin-wallet-new`): `bitcoinj-core:0.14.7` + `libdohj-core:0.15-SNAPSHOT`, `PeerGroup`/
   `BlockChain`/`SPVBlockStore`/`BloomFilter`/`DnsDiscovery` → talk straight to the Dogecoin P2P network
   (headers + bloom-filtered txs, keys on-device, broadcast to peers; can route over the Arti Tor already
   shipped). Add it ALONGSIDE the RPC + explorer backends, sharing the existing key. **Hurdle:** `libdohj`
   is NOT on a live Maven repo (Langerhans uses `mavenLocal()`+dead `jcenter()`) — build it from source
   (`github.com/dogecoin/libdohj`). **First step = a read-only feasibility spike** (connect a PeerGroup,
   sync headers, read the existing key's balance) before the full build. Lighter alt (deprioritized): public
   ElectrumX (sparse for Dogecoin).
2. **Funded MAINNET broadcast** — still user-gated (irreversible real money via the on-device UI / explicit
   spend authorization). Mainnet is the LAST step.
3. **More console coverage** (optional P1): dm/send/verify/block, geohash, more wallet read-only commands.
4. **More wallet UX polish** (optional): the "Node & developer settings" toggle currently sits just above the
   balance — could move it to the bottom; first-run empty state; activity-row redesign.
5. **iOS cross-platform reserve** of `NoisePayloadType 0x30/0x31` + `TLVType 0x06` — deprioritized (user is
   not on Apple right now); tolerant decode keeps it Android↔iOS-safe meanwhile.

## Local Dogecoin Node (testnet, synced)

```text
"C:\Program Files\Dogecoin\dogecoin-qt.exe" -testnet          # RPC 127.0.0.1:44555 (P2P 44556)
"C:\Program Files\Dogecoin\daemon\dogecoin-cli.exe" -testnet <cmd>
```

- Config (do NOT print rpcpassword): `C:\Users\rober\AppData\Roaming\Dogecoin\dogecoin.conf` (`server=1`,
  `rpcuser`/`rpcpassword`; RPC user is `apstats`). For a phone to reach it over the LAN, launch
  `dogecoin-qt -testnet -rpcbind=10.0.0.24 -rpcbind=127.0.0.1 -rpcallowip=10.0.0.0/24 -rpcallowip=127.0.0.1`
  (currently reachable at `http://10.0.0.24:44555`). A plain `-testnet` restart reverts to localhost-only.
- Funded node-owned address: `nceDCkWAP9tSktE5TJ5X6LVmD2r3HwiAXN` — **~1.72M spendable TESTDOGE** (its WIF is
  imported into the Pixel's wallet). The **S24's own** testnet key is `ni5e6MFNimwAZfFXGbdxWjzC7FBWfwM7iS`
  (its wallet, ~0 balance — it's the helper).
- Coinbase maturity is **240 blocks** (empirically verified). Faucets dead → CPU-mine with pooler minerd.
- For an on-chain peer broadcast: keep the node `networkactive=true` (relay-up) and drive the send from the
  console (`doge-peer-broadcast`), which bypasses the sender-CTA gate so one node suffices. (To repro via the
  GUI CTA you'd need a 2nd relay-up node.) See [[dogecoin-testnet-ops]] in memory.

## Android / Device State

- SDK `C:\Users\rober\AppData\Local\Android\Sdk`; `adb` at `…\Sdk\platform-tools\adb`.
- **Galaxy S24** (Android 16) serial `RFCX81GNBRE` = **helper**; **Pixel 3** (Android 12, arm64) serial
  `89VX0HPX1` = **sender**. Both run the latest debug build (`d9455f0`). Debug app id
  `com.bitchat.droid.debug` (coexists with a Play-installed `com.bitchat.droid`). REGTEST selector is
  DEBUG-only.

```powershell
.\gradlew.bat assembleDebug      # SPLIT per-ABI; both phones are arm64:
adb -s 89VX0HPX1 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s 89VX0HPX1 shell monkey -p com.bitchat.droid.debug -c android.intent.category.LAUNCHER 1
```

Gotchas (hard-won):
- **Samsung (S24) aggressively kills backgrounded bitchat** → "the wallet doesn't show" usually just means
  the app was killed and the ChatViewModel is dead; force-stop + relaunch fixes it. The wallet is
  **Tor-independent** (its own HTTP client) — Tor on/off never affects the wallet.
- **Both phones have a secure lock** that can re-engage on idle; `adb` can't unlock it. UI screenshots may
  need the user to unlock — but the **console works regardless of lock** (it's a BroadcastReceiver).
- **Nostr is forced through embedded Arti Tor** (`TorMode` default ON, SOCKS `127.0.0.1:9060`, fail-closed).
  On this network Arti can't build circuits → off-mesh Nostr is dead-on-arrival by default. **Toggle Tor OFF**
  for Nostr tests: About-sheet switch, or `tor-set off`, or set `tor_mode=OFF` in the plain `bitchat_settings`
  SharedPreferences via `run-as` + restart. Dogecoin RPC bypasses Tor regardless.
- `uiautomator dump` works; estimate tap coords from `bounds`, not scaled screenshots. `keyevent 111`
  (ESCAPE) dismisses the wallet sheet (network resets to mainnet) — never use it inside the sheet; use
  `keyevent 4` (BACK) to drop the keyboard. `adb shell input text` mangles long random strings (verify field
  length). The send confirm dialog is scrollable; the "Ask a peer to broadcast" CTA is below the fold.
- For PowerShell + `adb pull` binary files: pull with `adb pull` (not `>` redirection, which UTF-16-mangles).
  PowerShell has NO heredocs — commit multi-line messages via the Bash tool.

## Key Files

UX/wallet UI: `features/dogecoin/DogecoinWalletSheet.kt` (one big `LazyColumn`; `WalletCard`; item keys
header/balance/address/request/send + collapsed advanced). Wallet core:
`features/dogecoin/{DogecoinWalletRepository,DogecoinWallet,DogecoinTransaction(Builder),DogecoinRpcClient,
DogecoinExplorerClient,DogecoinRawTxValidator,BroadcastHelperService,PaymentBroadcastCoordinator,
PaymentBroadcastResultRouter,DogecoinTxConfirmationChecker,BroadcastHelperCandidates}.kt`. Debug console:
`debug/DebugConsole.kt` + `app/src/debug/AndroidManifest.xml` + the `debugConsoleHost` in `ui/ChatViewModel.kt`.
Mesh/Nostr: `mesh/{MessageHandler,PeerManager,...}.kt`, `services/MessageRouter.kt`,
`nostr/{NostrDirectMessageHandler,NostrTransport,NostrEmbeddedBitChat,NostrRelayManager}.kt`,
`favorites/FavoritesPersistenceService.kt`, `net/{ArtiTorManager,OkHttpProvider,TorPreferenceManager}.kt`.

## Verification

```powershell
# IMPORTANT: never pipe gradlew to tail/grep and trust the exit code — capture to a file and check $LASTEXITCODE.
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
```

Last green at `d9455f0`. Reference wallet for the SPV plan: `C:\Users\rober\Downloads\Projects\dogecoin-wallet-new`
(Langerhans) — `bitcoinj`+`libdohj`, `wallet/src/de/schildbach/wallet/service/{BlockchainService,NonWitnessPeerGroup}.java`.

## Constraints

- No mainnet wallet broadcast without explicit per-spend user authorization (irreversible real money).
- No custodial signing, remote key storage, or seed export without explicit approval.
- Never print private keys or RPC passwords in user-facing output.
- No destructive git commands. Keep changes narrowly scoped; follow existing Kotlin/Compose style.
