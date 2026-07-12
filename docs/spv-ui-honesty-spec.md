# Spec: SPV UI honesty (balance refresh + network rebind)

**Status:** ready for implementation  
**Cards:** `SPV-BALANCE-REFRESH`, `SPV-NETWORK-REBIND`  
**Queue:** `docs/orchestrator-improvement-queue.md` Wave 3  
**Date:** 2026-07-12  

## Live evidence (S24)

```text
doge-network / address: mainnet DRjrQ6YCEzPXTSPEyFWHSUyq7QxXeuQdGd
doge-spv-balance: avail=3881254000 (~38.81254 DOGE), utxos=1
doge-spv-status: running=true synced=false height≈6285445 peers=6 behind≈1409
UI: Syncing / sometimes "0 behind" / no balance figure
```

Funds exist in the SPV wallet; the Coin sheet fails to present them while headers crawl or stall.

---

## Card SPV-BALANCE-REFRESH

### Root cause

1. Open/start SPV path clears `walletBalance = null` so the ring shows empty while syncing.  
2. Balance re-read is `LaunchedEffect(dogecoinBackend, spvStatus.chainHeight, spvStatus.synced)` only.  
3. If `chainHeight` freezes, the effect does not re-run → permanent empty balance despite `snapshotBalance` succeeding.  
4. `blocksBehind` uses `(bestPeerHeight - chainHeight).coerceAtLeast(0)`; when `bestPeerHeight == 0`, UI can show **“0 behind”** while still not synced.

### Required

1. After SPV `start` applied successfully for the sheet network, call the same balance snapshot path once.  
2. While `dogecoinBackend == SPV` and service running: if `walletBalance == null` OR `!synced`, refresh balance every **10–15s** (cancellable with sheet generation).  
3. Pure helper e.g. `spvBehindLabel(bestPeerHeight, chainHeight, running)`:
   - bestPeer unknown → “Finding peers…” / not “0 behind”
   - else → “N behind” with real N  
4. EN+JA for any new user-visible strings.  
5. Do not require `synced==true` to display a known non-null balance (including 0.0 with utxoCount 0).

### Must not

- Change broadcast/sign gates or minPeers.  
- Change birthdate/checkpoint policy.  
- Enable mainnet node assist.  
- Main-thread SPV work.

### Acceptance

- Unit tests for behind-label helper.  
- Suite green.  
- Manual: mainnet funded key shows balance number in ring/status while still behind tip (status may still say Syncing).

---

## Card SPV-NETWORK-REBIND

### Root cause

Process-owned SPV can keep a prior network’s PeerGroup alive across UI network selection, so status can disagree with the red MAINNET badge and mainnet address.

### Required

1. When sheet `selectedNetwork` (or SPV owner network) changes, requestStop previous network and start the new one (idempotent).  
2. `doge-spv-status` / `_status.network` always equals the active SPV network.  
3. Do not delete the other network’s block store/wallet files.  
4. Clear sheet SPV-derived UI state on network change (balance, spv txs, confirmation observation).

### Must not

- Wipe cross-network persisted SPV state.  
- Feed wrong-chain UTXOs into the signer.  
- Money-gate changes.

### Acceptance

- Switch testnet → mainnet → testnet; console `spv net=` always matches UI network after settle.  
- Suite green.

---

## Dependencies

```text
SPV-BALANCE-REFRESH  (first)
SPV-NETWORK-REBIND   (second)
```
