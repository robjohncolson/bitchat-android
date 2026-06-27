# Continuation Prompt: Bitchat Android Dogecoin Wallet

Continue work in:

```text
C:\Users\rober\Downloads\Projects\bitchat-android
```

Goal: continue the Dogecoin wallet integration in Bitchat Android. Work autonomously, inspect the
relevant files first, keep changes focused, do not revert unrelated user changes, and verify with
focused Gradle + on-device checks. **Money path + signed mesh protocol — review carefully.**

## CURRENT FOCUS: self-contained SPV wallet (no node, no paid key)

The active work is making the Dogecoin wallet self-contained via an **SPV light client** (bitcoinj +
libdohj), added ALONGSIDE the existing RPC + explorer backends, sharing the same on-device key. The
agreed end state: free, no user-run node, no paid explorer key, keys on-device. Branch
`dogecoin-m2-pay-nickname`. **Last green commit: `b34ca4c`** (SPV Phase 3 testnet broadcast;
`:app:testDebugUnitTest` + `:app:assembleDebug` BUILD SUCCESSFUL; `git diff --check` clean). Working tree clean.

> **CRITICAL: the app now uses bitcoinj/libdohj `0.14.7` (spongycastle), NOT `0.15.9`.** bitcoinj 0.15+
> uses `org.bouncycastle` + `ECPoint.isCompressed()`, REMOVED in bcprov 1.70 — the bcprov the app uses for
> `EncryptionService`/`NostrCrypto`/`NoiseEncryptionService`. 0.14.7 uses `com.madgag.spongycastle`
> (`org.spongycastle.*`, separate namespace), so the app keeps bcprov 1.70 untouched and bitcoinj's crypto
> is isolated + never signs (Option B). The feasibility-spike numbers below were on 0.15.9; re-validated on
> 0.14.7 (199,710 testnet headers through AuxPoW, zero errors).

### The SPV arc so far (newest first) — feasibility PROVEN; Phases 0, 1, 2 (read-only) AND 3 (testnet broadcast) SHIPPED

- **`b34ca4c` — SPV Phase 3: testnet broadcast, FAIL-CLOSED, mainnet hard-blocked (Option B).** New
  `DogecoinSpvBroadcastVerifier` is the fail-closed chokepoint: decode the normalized signed hex → reject
  segwit framing via the BIP144 marker byte (`Transaction.hasWitnesses()` is ABSENT on the libdohj 0.14.7
  fork) → require `tx.bitcoinSerialize().contentEquals(inputBytes)` byte-for-byte (default serializer is
  NON-retaining ⇒ a real re-encode; a test pins `isParseRetainMode==false`) → require `tx.hashAsString ==
  DogecoinTransactionBuilder.transactionId(normalized)`; ANY divergence THROWS, so bitcoinj's own bytes never
  reach the wire. `DogecoinSpvService.broadcast()` is verifier-gated + peer-floor-pinned
  (`minBroadcastConnections=MIN_PEERS`), calls `wallet.receivePending` to reserve inputs (anti same-input
  double-spend), and awaits `future.get(25s)` OFF-lock — a TIMEOUT returns Claimed, NOT failure (thin testnet
  peers rarely re-announce). `confirmationDepth()` drives Claimed→Confirmed from our own synced chain.
  `DogecoinSpvDataSource.broadcast()` un-thrown + `withContext(Dispatchers.IO)` (the ANR fix — the sheet scope
  is Main). Sheet: SPV send routes via the datasource; `mempoolAcceptance(checked=false)` (note:
  `requiresPolicyUnavailableAcknowledgement()` is MAINNET-gated, so no ack pops on testnet — intended);
  Claimed-only receipt; send+confirm buttons enabled for a synced TESTNET SPV backend (route-mirroring);
  mesh-helper CTA gated OFF under SPV; clearnet/testnet-only disclosure. Console `doge-spv-broadcast`
  (node-free, mainnet-refused, IO). Tests: verifier fail-closed matrix + datasource mainnet refusal; signer
  canaries green. **MAINNET blocked at 4 layers.** Built by a plan workflow (`8720092` =
  `docs/dogecoin-spv-phase3-plan.md`) + a 5-lens adversarial code review (15 confirmed findings, none
  critical/high; fixes folded in). **NOT yet run on-device — next is the testnet soak.**
- **`3256ea2` — SPV Phase 2 (FINAL): wallet-sheet backend selector + sync status — PHASE 2 COMPLETE.**
  `DogecoinSpvService` is now a process SINGLETON (`getInstance`) so the sheet + console share ONE
  PeerGroup/SPVBlockStore. `DogecoinWalletSheet`: a "Connection" selector under Advanced settings ("My node"
  RPC default / "Built-in" SPV; hidden for REGTEST); `walletReadSource()` branches on backend (SPV→
  `DogecoinSpvDataSource`, RPC byte-identical); SPV start-on-select / stop-on-dispose; a visible "Built-in
  node" card shows sync progress (`StateFlow`); balance fills in reactively. **Sending DISABLED under SPV**
  (broadcast is Phase 3) with an inline note. Behavior-preserving for default RPC. The read-only SPV backend
  is now FULLY USABLE in the app.
- **`101fb0b` — SPV Phase 2: DbgConsole drivers.** ChatViewModel owns a lazy `DogecoinSpvService` (stopped
  in onCleared); DEBUG console commands `doge-spv-start/-stop/-status/-balance/-unspents` (REGTEST refused;
  broadcast still throws). The on-device driver surface for SPV.
- **`a2d7bae` — SPV Phase 2: LIVE READ VALIDATION (SUCCEEDED).** Spike `balance mode`
  (`-PwatchKeyHex=<hex> [-Pcheckpoint=<path>]`) mirrors `DogecoinSpvService`. Funded a fresh testnet addr
  `nZjD…j6w1` with 12.34 TESTDOGE from the node (txid `fada3b6a…`); SPV seeded at cp 65,648,620, synced
  ~50k headers to the tip in ~4 min, **bloom-matched the funding tx**, reported AVAILABLE=ESTIMATED=12.34
  with the exact UTXO (`fada3b6a…:0`=12.34, depth=2). Proves checkpoint-seed + AuxPoW/Scrypt validation +
  bloom matching + balance accounting end-to-end for Dogecoin. (`CheckpointManager.checkpoint(...,time)`
  subtracts a 7-DAY margin before selecting — never seeds past birthdate; testnet 7d≈1M blocks, mainnet
  7d≈10k. `getCheckpointBefore(now)` directly = the recent cp, for fast validation seeding.)
- **`f94262a` — SPV Phase 2: smart RPC checkpoint generator + validated testnet checkpoints asset.**
  `tools/spv-checkpoints/gen-dogecoin-checkpoint.ps1` builds bitcoinj `StoredBlock` compact records DIRECTLY
  from RPC (no full sync): `getblockheader` gives the raw 80-byte header + cumulative `chainwork`, packed as
  12-byte chainwork(BE) + 4-byte height(BE) + 80-byte header, emitted as `TXT CHECKPOINTS 1`. Testnet
  chainwork fits 0.14.7's 12-byte limit (mainnet may not eventually — Phase 4 risk). Asset
  `app/src/main/assets/dogecoin-checkpoints-testnet.txt` (3 cps). **VALIDATED**: spike `verifyCheckpoint`
  mode loads it via `CheckpointManager` + seeds the store; head hash == node `getblockhash 64698620` exactly.
  (Quirk: `& script.ps1` makes dogecoin-cli intermittently EOF on this box — run the body inline/dot-sourced;
  pass `true`/`false` literally; a `Cli` function name collides with the `Clear-Item` alias.)
- **`0459bd6` — SPV Phase 2: `DogecoinSpvService` + `DogecoinSpvDataSource` (read-only, no money path).**
  The on-device light client (bitcoinj 0.14.7). Compiled CLEANLY on 0.14.7 first try. Service: imports the
  EXISTING key via `ECKey.fromPrivate` (key creation time = `loadSpvBirthdateMillis`); `SPVBlockStore` in
  filesDir seeded by `CheckpointManager.checkpoint` from a `dogecoin-checkpoints-<net>.txt` asset IF present
  (else slow sync); `BlockChain`+`PeerGroup` (bloom on); `HighestHeightDownloadPeerGroup.selectDownloadPeer`
  override (no-SegWit); `DownloadProgressTracker`+peer listeners → `StateFlow<DogecoinSpvStatus>` (`synced` =
  caught-up-within-2-blocks AND ≥4 peers); `snapshotBalance`/`snapshotUnspents` propagate the bitcoinj
  Context. DataSource `broadcast()` THROWS this phase. REGTEST disabled. **0.14 read APIs that worked:
  `wallet.unspents`, `out.parentTransactionHash`/`parentTransaction.confidence.depthInBlocks`/`value.value`/
  `scriptBytes`, `Wallet.BalanceType.AVAILABLE`/`ESTIMATED` (NOT `AVAILABLE_SPENDABLE`).** NOT yet wired into
  UI/console (backbone only, like Phase 1's adapters).
- **`5e776b5` — SPV Phase 2: conservative `spv_birthdate` policy.** Separate per-network pref
  (`"${id}_spv_birthdate"`): generate → set to generation time (exact, fast); import → lower to a
  conservative floor (`DEFAULT_SPV_IMPORT_BIRTHDATE_MILLIS` = 2021-01-01, TUNABLE), NEVER the import
  timestamp (which would let `CheckpointManager` skip funding txs); reset → removed. `lowerSpvBirthdateMillis`
  only ever DECREASES. `loadSpvBirthdateMillis` falls back to the floor. Test in `DogecoinWalletRepositoryTest`.
- **`d8b3f25` — SPV Phase 2 foundation: switch to bitcoinj 0.14.7 (spongycastle) + key-import gate.**
  See the CRITICAL note above. `libdohj 0.14.7` + `guava 18.0` + `exclude com.google.guava:listenablefuture`
  (guava-18 bundles `ListenableFuture` → dup-class with the standalone artifact gms pulls); dropped the
  now-moot bcprov-jdk15to18 exclude. **`DogecoinSpvKeyImportTest` (the Phase-2 canary):** bitcoinj+libdohj
  `ECKey.toAddress(params)` MUST equal `DogecoinKeyGenerator`'s address — else SPV watches the wrong address
  (silent zero balance). **0.14 API diffs from 0.15:** address = `ecKey.toAddress(params)` (NOT
  `LegacyAddress.fromKey`); `VersionMessage.NODE_BLOOM` constant is gone (use literal `1L<<2`); on
  Windows+JDK17 the spike's `SPVBlockStore.close()` hits `WindowsMMapHack`/`sun.nio.ch` (added `--add-opens`)
  — **WindowsMMapHack NEVER runs on Android, so the app is unaffected.**
- **`e10012a` — SPV Phase 1: `DogecoinWalletDataSource` read abstraction (behavior-preserving).**
  Narrow interface (`getBalance`/`listUnspent`/`broadcast` only — node-specific status/watch/mempool/
  rescan + rich activity stay RPC-specific, per the design critique's leaky-interface warning).
  `DogecoinRpcDataSource` (thin delegation using the caller's CAPTURED config → byte-identical),
  `DogecoinExplorerDataSource` (READ-only; `broadcast()` HARD-THROWS so the refactor doesn't re-expose
  explorer broadcast). `DogecoinBackend{RPC,EXPLORER,SPV}` enum + repo `loadBackend`/`saveBackend`
  (per-network, default RPC, key `"${network.id}_backend"`). Routed the 7 read sites in
  `DogecoinWalletSheet` (3 getBalance + 4 listUnspent) through a local `walletReadSource(config)`
  helper (currently always RPC; Phase 2 makes it branch on backend). Default RPC ⇒ zero behavior change.
- **`faf9f6f` — SPV Phase 0: deps + bcprov isolation + signing regression gate (no feature code).**
  JitPack repo in `settings.gradle.kts`; `libdohj v0.15.9` (→ `bitcoinj-core:0.15.9`) + `guava
  28.2-android` pin in `libs.versions.toml`/`app/build.gradle.kts`. **EXCLUDE bitcoinj's bundled
  `org.bouncycastle:bcprov-jdk15to18`** so the app's audited `bcprov-jdk15on:1.70` stays the SOLE
  `org.bouncycastle` (same package, different module → would otherwise dup-class). R8 keep/dontwarn for
  bitcoinj/libdohj/protobuf/spongycastle. **`DogecoinSignerRegressionTest`** pins byte-for-byte signed
  hex+txid (captured pre-bitcoinj) ⇒ proves the money-path signer is UNCHANGED with bitcoinj present.
  **APK delta = +2.0 MB/ABI** (arm64 16.2→18.2; R8 shrinks the ~6-8MB stack to ~2MB). User: size not a concern.
- **`827060f` — SPV integration plan: `docs/dogecoin-spv-integration-plan.md`.** Produced by a grounded
  design workflow + adversarial money-path critique. **UTXO fork RESOLVED → Option B**: bitcoinj is ONLY
  a UTXO source + broadcast sink; `DogecoinTransactionBuilder` stays the SOLE signer (every safety gate
  preserved, additive). 5 phases (0 deps → 1 read abstraction → 2 read-only SPV → 3 testnet broadcast →
  4 mainnet). READ THIS DOC before continuing.
- **`f9d13c1` — dev-only feasibility spike: `tools/spv-spike/` (standalone pure-JVM Gradle, JDK 17).**
  PROVED: libdohj v0.15.9 resolves from JitPack + compiles; **synced 275,487 testnet headers in 8 min,
  ZERO `VerificationException`**, through the testnet DigiShield (~157.5k) + AuxPoW (~158.1k) transitions
  (libdohj issue #15 rejected nothing); connected to LOCAL + PUBLIC testnet peers, all `NODE_BLOOM`.
  Doubles as a re-validation tool + basis for a `BuildCheckpoints` generator. Run: `gradlew -p
  tools/spv-spike run` (default local node, or `-PnoLocalhost`, or `-PpeerHost=127.0.0.1`).

### NEXT STEP: on-device TESTNET SOAK of Phase 3 broadcast → then Phase 4 (mainnet)

PHASE 3 (testnet broadcast, fail-closed) is SHIPPED + COMMITTED (`b34ca4c`) and build+tests green, but
**NOT yet run on-device.** The next step is a real testnet broadcast soak (needs a funded testnet key).

**Console soak (no second node needed):**
```powershell
adb -s <serial> shell "am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver --es cmd 'doge-network testnet'"
# fund the wallet's testnet address (doge-address) from the local node, then:
# doge-spv-start  -> poll doge-spv-status until synced=true peers>=4
# doge-spv-balance / doge-spv-unspents -> confirm the funded UTXOs are visible
# doge-spv-broadcast <addr> <amtDoge> [feePerKbKoinu] -> expect "CLAIMED txid=..."
# poll doge-spv-status (depth) and/or an explorer until the tx is mined (depth>=1)
adb -s <serial> logcat -d -s DbgConsole
```
**Sheet soak:** Connection -> "Built-in" on testnet; send disabled while syncing (hint shown), enabled once
synced; run a send; receipt shows "Claimed — …not yet confirmed", flips to confirmed after on-chain depth>=1.
**Negatives to confirm:** broadcast-before-sync -> ERR; regtest -> SPV not offered; mainnet -> blocked everywhere.

OPEN questions to settle during/after the soak (see `docs/dogecoin-spv-phase3-plan.md`): peer-floor
reachability on testnet (MIN_PEERS=4 may need a pinned known-good peer via `addAddress`); the `spv_birthdate`
floor for OLD imported keys (pre-2021 funds invisible under SPV); whether bitcoinj `PeerGroup` can route over
the embedded Arti Tor SOCKS (UNVERIFIED — clearnet-with-disclosure ships now, NEVER silent fallback).

Deferred Phase-3 review nits (reviewer-sanctioned): reorg one-way receipt (depth>=1 never downgrades),
interruptible 25s await, enabling "Use Max" under SPV.

Later: **Phase 4 (mainnet, user-gated)** — remove the 4-layer mainnet block after a mainnet read-only soak +
WIF-backup requirement + mainnet/high-fee/policy acks. MAINNET is the last switch, per-spend authorized.

### Node state + deferred balance-match spike (Task 3)
**The testnet node is now HEALTHY** (user closed the mainnet instance 2026-06-27; only testnet runs —
synced to block ~65.7M, verifyprogress 1.0, RPC responds; P2P 44556 also fine). This unblocks the smart
RPC checkpoint generation AND the balance-match validation. Balance-match spike: point the spike at the
local node (`-PpeerHost=127.0.0.1`), watch a funded address, assert SPV balance == node oracle — but it
needs a checkpoint near that address's funding height first (testnet from genesis is infeasible), so it
follows the smart-checkpoint step.

## Older shipped milestones (background — all committed pre-`f9d13c1`)
- **M0/M1** — UX hardening (fee presets, address book, payment requests). **M2 — Pay @nickname**
  (Ed25519-signed receive-address TLVs). **M3b — Broadcast-over-mesh** (node-less sender relays a SIGNED
  tx to an opt-in helper; `NoisePayloadType 0x30/0x31`, `BroadcastHelperService`,
  `PaymentBroadcastCoordinator`, two-helper corroboration ⇒ Confirmed; signed `NODE_HELPER` TLV 0x06).
- **3b.1 — Nostr off-mesh fallback + explorer corroboration** (corroboration counts by the helper's
  Noise static key, never a free-to-mint Nostr pubkey). Proven on hardware: Nostr off-mesh broadcast
  round-trip vs LIVE relays (Tor OFF); on-chain peer broadcast mined (testnet txid `d3be593c…`, 36 conf).
- **Wallet UX redesign (P0+P1)** — one `LazyColumn` of `item(key=…)`; node/dev settings collapsed;
  balance hero; de-jargoned. **Explorer "no-node" mode (`b939eca`)** — but free public explorers gate
  anonymous access (Blockchair → paid key; Trezor/Dogechain → 403). THIS is why SPV is the direction.

## Debug console (on-device driver — `DebugConsole`, BuildConfig.DEBUG only)
Drive the app textually over adb (no UI tapping). **Quote the WHOLE am command** so a space-arg survives:
```powershell
adb -s <serial> shell "am broadcast -n com.bitchat.droid.debug/com.bitchat.android.debug.DebugCommandReceiver --es cmd 'doge-peer-broadcast nceDC… 5'"
adb -s <serial> logcat -d -s DbgConsole
```
Host registers from `ChatViewModel.init` (chat screen must be alive — if "no host registered", relaunch).
Commands incl.: `doge-network/-rpc-set/-address/-import-wif/-balance/-self-broadcast/-peer-broadcast/
-helper-enable/-explorer-config/-explorer-balance/-utxos/-broadcast/-send`; `nostr tor tor-set peers
reannounce`. **Mainnet money-path commands HARD-REFUSE; WIF/keys never logged.** (Phase 2: add SPV
sync/balance + SPV-vs-node cross-check commands here as the soak surface.)

## Local Dogecoin Node (testnet)
```text
"C:\Program Files\Dogecoin\dogecoin-qt.exe" -testnet            # RPC 127.0.0.1:44555 (P2P 44556)
"C:\Program Files\Dogecoin\daemon\dogecoin-cli.exe" -testnet <cmd>
```
- Config (do NOT print rpcpassword): `C:\Users\rober\AppData\Roaming\Dogecoin\dogecoin.conf` (`server=1`,
  RPC user `apstats`). For a phone over LAN: `dogecoin-qt -testnet -rpcbind=10.0.0.24 -rpcbind=127.0.0.1
  -rpcallowip=10.0.0.0/24 -rpcallowip=127.0.0.1` (was reachable at `http://10.0.0.24:44555`). For SPV the
  phone uses P2P (44556) directly, not RPC. For `BuildCheckpoints`, keep the node synced + RPC up.
- Funded node-owned address `nceDCkWAP9tSktE5TJ5X6LVmD2r3HwiAXN` — ~1.72M TESTDOGE (WIF imported into the
  Pixel's wallet). The S24's own testnet key is `ni5e6MFNimwAZfFXGbdxWjzC7FBWfwM7iS` (~0; it's the helper).
- Coinbase maturity 240 blocks. Faucets dead → CPU-mine with pooler minerd. See [[dogecoin-testnet-ops]].

## Android / Device State
- SDK `C:\Users\rober\AppData\Local\Android\Sdk`; `adb` at `…\Sdk\platform-tools\adb`.
- **Galaxy S24** (Android 16) serial `RFCX81GNBRE` = helper; **Pixel 3** (Android 12, arm64) serial
  `89VX0HPX1` = sender. Both arm64; debug app id `com.bitchat.droid.debug` (coexists with Play
  `com.bitchat.droid`). REGTEST selector is DEBUG-only.
```powershell
.\gradlew.bat assembleDebug      # SPLIT per-ABI; both phones arm64:
adb -s 89VX0HPX1 install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
adb -s 89VX0HPX1 shell monkey -p com.bitchat.droid.debug -c android.intent.category.LAUNCHER 1
```
Gotchas (hard-won):
- **Windows: ALWAYS pass `gradlew -p "<repo>"`** — a stale shell CWD makes Gradle resolve the wrong
  project dir ("Project directory … is not part of the build"). Never pipe gradlew to `tail`/`grep` and
  trust the exit code (capture to a file, check `$LASTEXITCODE`).
- **gitnexus MCP tools were NOT connected this session** (despite CLAUDE.md) — do impact analysis manually
  via grep, or check if they're back.
- **bitcoinj 0.15.9 predates new JDKs**: the spike pins a JDK-17 Gradle toolchain. The app's AGP build
  runs on JAVA_HOME (JDK 22) with source/target 8 — fine; the signing regression test guards the signer.
- **Samsung (S24) aggressively kills backgrounded bitchat** → "wallet doesn't show" = app was killed;
  force-stop + relaunch. The wallet is **Tor-independent** (own HTTP client). **Nostr is forced through
  Arti Tor** (default ON, SOCKS `127.0.0.1:9060`, fail-closed) — toggle OFF (`tor-set off`) for Nostr
  tests; Dogecoin RPC bypasses Tor. **SPV P2P over Arti is UNVERIFIED.**
- Both phones have a secure lock that re-engages on idle; `adb` can't unlock, but the console works
  regardless (it's a BroadcastReceiver). `keyevent 4` (BACK) drops the keyboard; never `keyevent 111`
  (ESC) inside the sheet (resets network to mainnet). `adb shell input text` mangles long random strings.
- PowerShell has NO heredocs — commit multi-line messages via the Bash tool. `adb pull` binary files
  (don't `>`-redirect — UTF-16 mangles).

## Key Files
SPV (new): `features/dogecoin/{DogecoinWalletDataSource,DogecoinRpcDataSource,DogecoinExplorerDataSource,
DogecoinSpvService,DogecoinSpvDataSource}.kt` + `spv_birthdate`/`DogecoinBackend` in `DogecoinWalletRepository.kt`;
tests `DogecoinSpvKeyImportTest`/`DogecoinSignerRegressionTest`/`DogecoinWalletDataSourceTest`;
checkpoints `tools/spv-checkpoints/gen-dogecoin-checkpoint.ps1` + `app/src/main/assets/dogecoin-checkpoints-testnet.txt`;
plan `docs/dogecoin-spv-integration-plan.md`; spike `tools/spv-spike/` (+ its README; `-PverifyCheckpoint=<path>`
mode). Reference SPV
wallet: `C:\Users\rober\Downloads\Projects\dogecoin-wallet-new` (Langerhans, bitcoinj+libdohj) —
`wallet/src/de/schildbach/wallet/service/{BlockchainService,NonWitnessPeerGroup}.java` + `Constants.java`
(checkpoints asset `checkpoints.txt`, `CheckpointManager.checkpoint(...)`). Wallet core:
`features/dogecoin/{DogecoinWalletRepository,DogecoinWallet,DogecoinTransaction(Builder),DogecoinRpcClient,
DogecoinExplorerClient,DogecoinRawTxValidator,BroadcastHelperService,PaymentBroadcastCoordinator}.kt`. UI:
`features/dogecoin/DogecoinWalletSheet.kt` (4200+ lines; `walletReadSource()` helper near `rpcClient`).
Debug console: `debug/DebugConsole.kt` + `app/src/debug/AndroidManifest.xml` + `debugConsoleHost` in
`ui/ChatViewModel.kt`. Mesh/Nostr/Tor: `mesh/*`, `services/MessageRouter.kt`, `nostr/*`, `net/*`.

## Verification
```powershell
# IMPORTANT: pass -p; never pipe gradlew to tail/grep and trust the exit code.
.\gradlew.bat -p "C:\Users\rober\Downloads\Projects\bitchat-android" :app:testDebugUnitTest :app:assembleDebug --console=plain
git diff --check
```
Last green at `3256ea2`. The `DogecoinSignerRegressionTest` (app signer) AND `DogecoinSpvKeyImportTest`
(bitcoinj derives the app's address) are the money-path canaries — if either ever fails
after a dep change, the audited ECDSA signer changed; REJECT the change, do not re-baseline.

## Constraints
- No mainnet wallet broadcast without explicit per-spend user authorization (irreversible real money).
- No custodial signing, remote key storage, or seed export without explicit approval.
- Never print private keys or RPC passwords in user-facing output.
- No destructive git commands. Keep changes narrowly scoped; follow existing Kotlin/Compose style.
- SPV must NOT be the sole money-path source of truth until mainnet-validated; keep RPC/explorer as the
  cross-check anchor. bitcoinj NEVER signs (Option B) — `DogecoinTransactionBuilder` is the only signer.
