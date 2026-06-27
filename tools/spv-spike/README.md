# spv-spike — Dogecoin SPV feasibility spike (dev-only)

A standalone **pure-JVM** Gradle project that proves a `bitcoinj` + `libdohj` SPV backend can
header-sync the Dogecoin network. It is **not** part of the Android app build (separate
`settings.gradle`, no Android plugin) — bitcoinj's P2P stack is plain Java, so feasibility can be
validated on the desktop without a phone or a paid explorer key.

It doubles as a **re-validation tool** (does libdohj still resolve + sync correctly?) and the basis
for a future **checkpoint generator** (bitcoinj `BuildCheckpoints`).

## What it proved (2026-06-26)

- **libdohj is obtainable**: `com.github.dogecoin:libdohj:v0.15.9` resolves from JitPack and pulls
  `org.bitcoinj:bitcoinj-core:0.15.9` (+ `guava:28.2-android`, bouncycastle, protobuf) from Maven
  Central, and compiles on JDK 17. (Gotcha: bitcoinj exposes Guava `ListenableFuture` via
  `waitForPeers`/`startAsync` but declares Guava `implementation`, so you must add
  `com.google.guava:guava:28.2-android` to your own compile classpath — see `build.gradle`.)
- **The engine validates Dogecoin headers**: synced **275,487 testnet headers in 8 min with zero
  `VerificationException`**, straight through the testnet DigiShield (~157.5k) and AuxPoW (~158.1k)
  transitions — i.e. libdohj's Scrypt PoW + merged-mining header validation is correct through the
  era that libdohj issue #15 could have broken.
- **The no-node path works**: connected to a local Dogecoin node *and* to public testnet peers from
  the DNS seed; all advertise `NODE_BLOOM` (BIP37 bloom serving), which Dogecoin Core still defaults on.

## Practical findings that shape the real wallet

- Dogecoin **testnet is ~65.7M blocks** (min-difficulty spam) and ships no checkpoints, so a
  from-genesis sync is impractical. A **generated checkpoints file is essential**; with one, a fresh
  on-device key (birthdate ≈ now) downloads only recent headers and syncs near-instantly.
- bitcoinj auto-prefers a detected `127.0.0.1` node; the witness wart needs a `selectDownloadPeer`
  override (Dogecoin has no SegWit), handled here by `HighestHeightDownloadPeerGroup`.

## Requirements

- **JDK 17** available for the Gradle toolchain (auto-detected; bitcoinj 0.15.9 predates newer JDKs).

## Run

```bash
# Default: 8-min header-sync; auto-uses a local 127.0.0.1:44556 testnet node if present, else public DNS.
./gradlew run

# Force the public network only (don't prefer a localhost node):
./gradlew run -PnoLocalhost -PrunMinutes=4

# Pin to a specific node (isolation), e.g. the local testnet node:
./gradlew run -PpeerHost=127.0.0.1 -PrunMinutes=4
```

Heartbeat lines (`[hb] height=...`) and a final `[spike] ===== SUMMARY =====` print to stdout; a
`VerificationException` would surface in the bitcoinj logs (set to `warn` in `build.gradle`).

> Money-path note: this spike is **read-only / testnet** and never builds or broadcasts a spend.
