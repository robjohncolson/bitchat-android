# Trusted personal node host runbook

Status: DES-1-F operator runbook  
Applies to: a dedicated Dogecoin Core watch-only host reached through Tailscale Serve  
Does not authorize: a mainnet canary, public RPC, signing in Core, or importing an Android private key

This runbook implements the host boundary in
[`dogecoin-trusted-personal-node-mainnet-design.md`](./dogecoin-trusted-personal-node-mainnet-design.md).
It is an operator checklist, not evidence that the host is honest. A watch-only Core
wallet can still omit, fabricate, delay, or censor information. The phone remains the
only signer, and a node acceptance remains **Claimed** until independent Built-in SPV
observation.

## Invariants

Stop setup if any invariant cannot be met:

- Core RPC listens on IPv4 loopback only: `127.0.0.1`.
- Tailscale **Serve**, never Funnel, is the only remote path to RPC.
- The Core instance, data directory, and wallet are dedicated to this service and hold
  no valuable or application spending keys.
- The Android wallet address is imported watch-only. Its WIF, seed, and every other
  private key remain on the phone and are never typed, pasted, copied, or imported on
  the host.
- Each phone gets a separate `rpcauth` username/password pair.
- `txindex=1` is enabled so proof retrieval can fall back to historical full
  transactions. If full previous-transaction bytes are unavailable, the app remains
  display-only.
- The chain is unpruned and the historical watch-only rescan completes before the
  operator attests that the profile is ready.
- Nothing in this document or an operator log contains a real password, WIF, seed,
  signed-attempt ledger, or raw signed transaction. Connection-profile export excludes
  the RPC password by default; if the user explicitly elects to include it, the export
  is a bearer secret and must use a separately trusted transfer channel.

## Placeholders

Choose values locally. Do not replace the placeholders in a committed copy of this
file.

| Placeholder | Meaning |
|---|---|
| `{DATADIR}` | Dedicated Core data directory with restrictive OS permissions |
| `{CONF}` | Dedicated Core configuration file |
| `{WATCH_WALLET}` | Dedicated watch-only Core wallet name/file |
| `{RPC_PORT}` | Operator-selected loopback RPC port |
| `{RPC_USER}` | Unique, non-identifying username for one phone |
| `{PHONE_ADDRESS}` | Public Dogecoin address shown by that phone |
| `{PHONE_LABEL}` | Non-secret label for the watch-only import |
| `{MACHINE}` / `{TAILNET}` | Labels forming the exact Serve origin |
| `{PHONE_DEVICE}` | Named and approved phone identity in the tailnet |

The connection origin entered in the app is exactly
`https://{MACHINE}.{TAILNET}.ts.net`: lowercase ASCII, implicit port 443, and no path,
query, fragment, trailing dot, user information, or wallet suffix. The wallet identity
is a separate profile field.

## 1. Prepare the host

1. Patch the operating system, Dogecoin Core, and Tailscale.
2. Create a dedicated unprivileged OS account if practical. Restrict `{DATADIR}` and
   `{CONF}` to that account and administrators.
3. Create a new data directory and watch-only wallet. Do not copy an existing
   `wallet.dat`, application wallet, mining wallet, or any file containing private
   keys.
4. Keep this instance unpruned. A historical watch-only rescan needs the full chain.
5. Record Core and Tailscale versions in the operator log. Record no credentials.
6. A legacy Core wallet file may contain unrelated Core-generated private keys even
   when only the Android address is intentionally imported watch-only. It is not needed
   to recover the phone's funds. If policy requires retaining it, protect it as secret
   wallet material and never share it as mere metadata; otherwise the watch-only host
   can be rebuilt and rescanned. The phone's own wallet backup remains required.

For a testnet soak, use a distinct data directory, configuration, wallet, RPC port, and
credential. Put network selection on the launch command with `-testnet`; do not rely on
a shared configuration file to choose the network.

## 2. Generate one `rpcauth` credential per phone

Use the standard Core `rpcauth.py` HMAC-SHA256 generator from a trusted source tree.
Run it without putting the plaintext password in a command-line argument or shell
history. For example, from the source tree containing the script:

```text
python share/rpcauth/rpcauth.py "{RPC_USER}"
```

The generator prints:

- an `rpcauth={RPC_USER}:<salt>$<hash>` configuration line; and
- a generated plaintext password to enter on that one phone.

Put only the generated `rpcauth=` line in `{CONF}`. Transfer the plaintext password
directly to the intended phone and retain it only in an approved password manager if
recovery is required. Never commit it, place it in an operator log, send it through an
untrusted chat, put it in a screenshot, or reuse it for another phone. An explicitly
password-bearing connection-profile export is equivalent to the plaintext password;
use an authenticated confidential channel, import it promptly, and delete every
transient copy. Do not use a shared `rpcuser`/`rpcpassword` pair.

Raw Core `rpcauth` is not method-scoped. A stolen credential plus tailnet access can
invoke Core methods outside the app's fixed allow-list. The dedicated instance holding
no funded or application keys, per-phone credentials, ACLs, and prompt revocation are
containment controls.

### Connection-profile handoff

An app export can package the current phone's origin, username, and wallet identity;
the password is excluded by default. The app cannot create a new host `rpcauth` entry.

- For a replacement/handoff, transfer through a trusted channel, complete the full
  trust ceremony on the receiver, then rotate or remove the source credential and
  revoke source tailnet access as appropriate.
- For an additional concurrently active phone, do **not** clone the exported username
  or password. Create a new per-phone `rpcauth` entry on the host and overwrite the
  imported draft with that recipient-specific username/password before any setup test.
- Never use export to escape `AUTH_REQUIRED` or `DISPUTED`; repair or revoke the source
  profile through its prescribed path.

## 3. Configure dedicated Core

Use a dedicated `{CONF}` containing the following shape:

```ini
server=1
rpcbind=127.0.0.1
rpcallowip=127.0.0.1
rpcport={RPC_PORT}
rpcauth={RPC_USER}:<generated-salt>$<generated-hmac>
txindex=1
wallet={WATCH_WALLET}
```

Hard stops:

- no `rpcbind=0.0.0.0`, `rpcbind=::`, LAN address, or Tailscale address;
- no broad or RFC1918 `rpcallowip` entry;
- no port-forward, public/other reverse proxy or ingress, Funnel, or public DNS proxy
  to `{RPC_PORT}`; the one allowed proxy is the private Tailscale Serve mapping in
  section 6;
- no `prune` setting;
- no WIF, seed, or private-key import command.

Start Core with both paths explicit. Use `-testnet` only for the isolated testnet soak:

```text
dogecoind -datadir="{DATADIR}" -conf="{CONF}"
dogecoind -datadir="{TESTNET_DATADIR}" -conf="{TESTNET_CONF}" -testnet
```

When enabling `txindex=1` on an existing chain, follow the installed Core version's
documented reindex procedure and wait for the index to finish. Do not rely on a partial
index. If the installed version cannot demonstrate complete historical raw-transaction
lookup, treat the TPN as read-only.

## 4. Verify chain, wallet, and loopback exposure

Run local Core RPC commands against the dedicated instance and wallet. Exact CLI flags
vary by platform and Core packaging; always pass the dedicated data/config and selected
wallet explicitly.

Required results:

- `getblockchaininfo.chain` is the intended chain.
- `initialblockdownload` is `false`, `blocks == headers` or within the approved lag,
  and the chain is unpruned.
- `getnetworkinfo.connections` meets the readiness floor before use.
- the loaded-wallet list identifies `{WATCH_WALLET}`, and wallet-scoped
  `getwalletinfo` reports no active scan before the profile is attested ready.
- a historical full transaction can be returned for proof fallback; lack of full bytes
  means display-only.

Inspect listeners with the host OS, for example:

```text
# Linux
ss -ltnp

# Windows PowerShell
Get-NetTCPConnection -State Listen -LocalPort {RPC_PORT}
```

The only RPC listener is `127.0.0.1:{RPC_PORT}`. An IPv6 wildcard, `0.0.0.0`, a LAN IP,
or a Tailscale IP is a failure. Core's P2P listener may remain public.

Prove the generated phone credential locally with an authenticated, non-mutating JSON-
RPC call. Use a client mode that prompts for the password instead of placing it in the
command or process list. Then repeat with a deliberately wrong throwaway value and
require HTTP 401. Redact the Authorization header and all credentials from captured
output.

## 5. Import the phone address watch-only and rescan

This is a host operation. The Android app must never call `importaddress`, create/load a
Core wallet, start a rescan, or perform any key operation.

1. On the phone, display its public address and compare it character for character with
   `{PHONE_ADDRESS}` through a trusted channel.
2. Against `{WATCH_WALLET}`, run:

   ```text
   validateaddress "{PHONE_ADDRESS}"
   ```

   Require `isvalid=true`. Hard-stop if `ismine=true`; investigate before continuing,
   because the phone is intended to be the only signer.
3. Import the public address with a historical rescan enabled:

   ```text
   importaddress "{PHONE_ADDRESS}" "{PHONE_LABEL}" true
   ```

   Run it only against the dedicated watch-only wallet and unpruned chain. It may block
   for a long time. Do not interrupt it merely because RPC is slow.
4. After it returns, run `validateaddress` again and require:

   ```text
   isvalid=true
   ismine=false
   iswatchonly=true
   ```

5. Verify the wallet is no longer scanning, record the chain tip height/hash and UTC
   completion time, and inspect `listunspent`/history for the public address.

The app's rescan checkbox is an operator attestation that these steps completed. It is a
UX gate, not cryptographic proof: the same node supplies the watch and rescan claims.
Dogecoin Core may create unrelated legacy wallet keys; never fund them.

## 6. Publish loopback RPC with Tailscale Serve

Confirm the installed Tailscale CLI syntax, then publish only the loopback target in
background mode. The current expected shape is:

```text
tailscale serve --bg http://127.0.0.1:{RPC_PORT}
tailscale serve status
tailscale funnel status
```

`tailscale serve status` must show exactly:

```text
https://{MACHINE}.{TAILNET}.ts.net -> http://127.0.0.1:{RPC_PORT}
```

There must be nothing enabled under Funnel. Never run `tailscale funnel`, never publish
the raw RPC port, and never substitute a LAN or `100.64.0.0/10` URL in the app.

From the approved phone or another explicitly allowed tailnet client:

- an unauthenticated request to the exact origin reaches Core and returns the expected
  RPC HTTP rejection (commonly 401);
- an authenticated, non-mutating `getblockchaininfo` request succeeds and reports the
  intended chain;
- redirects are not part of the topology.

Do not paste the authenticated command into an operator log: command lines, shell
history, headers, and captured traffic can expose Basic credentials.

## 7. Tailnet security checklist

The app cannot attest these controls. An operator must check them in the Tailscale admin
console and retain a credential-free record:

- [ ] The host is a named, approved device with an appropriate server tag.
- [ ] Only the intended named phone identity/device can reach the host on TCP 443.
- [ ] No wildcard user/group/device source is granted access to the host.
- [ ] The Core RPC port is not granted directly to any tailnet principal.
- [ ] A second, unapproved tailnet device is denied while `{PHONE_DEVICE}` succeeds.
- [ ] Device approval is enabled and both phone and host are approved/current.
- [ ] MFA protects every tailnet administrator account.
- [ ] Tailnet Lock is enabled with at least two signing devices.
- [ ] Tailnet Lock disablement/recovery material is stored offline and tested by policy.
- [ ] Funnel status is off/empty and no public sharing feature exposes the service.
- [ ] Per-phone `rpcauth` entries are inventoried by username only, with a revocation
      owner and date.

An ACL permits reachability; it does not prove that Core, the laptop, the control plane,
or the data returned to the phone is honest.

## 8. Required exposure tests

Perform all tests after every Core, firewall, Tailscale Serve, DNS, router, or ACL
change.

1. **Local listener:** only `127.0.0.1:{RPC_PORT}` listens for RPC.
2. **LAN negative:** from a second LAN machine, connecting to
   `http://{HOST_LAN_IP}:{RPC_PORT}` fails. A 401 is a failure here because it proves
   the port is reachable.
3. **Host-LAN negative:** from the host, connecting through its own LAN IP also fails.
4. **Tailnet raw-port negative:** connecting to `{HOST_TAILSCALE_IP}:{RPC_PORT}` fails.
5. **Allowed Serve positive:** `{PHONE_DEVICE}` reaches the exact HTTPS origin, receives
   the expected unauthenticated rejection, then succeeds with its own credential.
6. **Tailnet ACL negative:** an authenticated but unapproved tailnet device cannot reach
   the Serve origin.
7. **Public negative:** a device outside the tailnet cannot reach the service. Test from
   an unrelated network; do not infer this from the LAN test.
8. **Funnel negative:** `tailscale funnel status` shows no published service and the
   admin console shows no public exposure.
9. **Wrong-password negative:** an allowed device using a deliberately wrong value gets
   HTTP 401 and no RPC result.

Capture status codes, listener addresses, timestamps, and redacted status output only.
Never capture Authorization headers or plaintext credentials.

## 9. Testnet soak release gate

No mainnet authorization or spend is implied by finishing setup. First run a multi-day
testnet soak using a fully isolated testnet instance and testnet-only funds.

The production TPN profile is deliberately typed to mainnet. Do not weaken that type or
point a mainnet profile at testnet merely to satisfy this checklist. The soak must use an
isolated test build/harness that exercises the same coordinator, proof, reservation, and
settlement policies with testnet constants. If that harness is unavailable, the release
gate remains blocked; ordinary testnet home-node assist alone is not evidence for the TPN
money path.

### Baseline

- [ ] Correct testnet chain, not IBD, peer/readiness floor met, unpruned history, and
      `txindex=1` complete.
- [ ] Dedicated testnet wallet reports the testnet phone address as
      `ismine=false` and `iswatchonly=true` after a completed historical rescan.
- [ ] Every listener, Serve, Funnel, ACL, and external negative test in sections 6–8
      passes.
- [ ] A proof-backed snapshot obtains and Android verifies a full previous transaction
      for every selectable input; scalar-only or incomplete proof leaves the app
      display-only.
- [ ] Android builds and signs locally, hard-fee checks locally, preflights and submits
      identical bytes on the one bound route, and Core returns the exact local txid.
- [ ] Core acceptance displays **Claimed** only; independent Built-in SPV observation
      produces **Observed**, and fully synced SPV depth at least 6 produces
      **Confirmed**.

### Repetition and recovery

- [ ] Run for multiple days across ordinary phone and laptop sleep/wake, app process
      death, Core restart, Tailscale restart, Wi-Fi/mobile changes, and hotspot
      reconnects.
- [ ] Exercise a dropped RPC response after submission and prove same-byte/same-route
      recovery is idempotent after both timeout and process death.
- [ ] Verify persistent input reservations are not released by elapsed time, restart,
      node claims, or profile revocation; only the designed SPV evidence releases them.
- [ ] Verify no profile export contains a WIF, attempt ledger, signed bytes, or stored
      authorization state, and importing it starts a new trust ceremony.

### Fail-closed fault matrix

Each row must produce a bounded error/degraded state with no alternate route, no silent
SPV/helper/RPC cascade for the same action, and no weakening of the normal SPV gates:

- [ ] Tailscale down; host offline/asleep; DNS/TLS/hostname failure.
- [ ] Funnel/Serve or ACL misconfiguration; unexpected redirect.
- [ ] Wrong/revoked password; HTTP 401/403; credential rotation during a session.
- [ ] Core down, IBD, low peers, excessive block/header lag, wrong chain, or active
      rescan.
- [ ] Origin, wallet identity, Android address, credential, policy, or profile revision
      changed during readiness/snapshot/review.
- [ ] Empty, chunked, malformed, oversized, delayed, or cancelled RPC response.
- [ ] Missing, truncated, fabricated, wrong-script, wrong-amount, duplicate, spent, or
      stale previous transaction.
- [ ] Final selected-input recheck changes; `testmempoolaccept` rejects/unavailable;
      Core txid differs from the locally computed txid.
- [ ] Node reports confirmation or receipt status for its own claim; state remains
      uncorroborated until independent SPV evidence.
- [ ] Stable SPV-versus-node conflict enters `DISPUTED` and clears only under the
      separately specified independent recovery policy.
- [ ] A deliberately falsified test proxy exercises malicious-node vectors while it is
      isolated from the production host and all mainnet funds.

Record the duration, app build, host versions, testnet transaction IDs, redacted
results, failures, and repairs. Do not record secrets or raw signed bytes.

## 10. Mainnet go/no-go

A passing soak permits only a review. Before mainnet:

- all pure, Android/HTTP, process-death, localization, accessibility, and adversarial
  money-path tests must pass;
- a read-only mainnet dry run must pass the exact topology and proof checks;
- the operator must review the residual oracle/privacy risk and host attestation;
- the money-path review must confirm there is no ordinary RPC, helper, raw-export,
  debug, signer, WIF, or reservation bypass; and
- a separate human must explicitly authorize a tiny manual mainnet canary.

There is no automated mainnet spend in this runbook. A failed or incomplete gate leaves
the profile inactive or display-only.

## 11. Rotation, revocation, and incident response

For a lost/stolen phone, leaked credential, unexpected tailnet device, or compromised
host:

1. Disable/revoke the affected tailnet device or ACL access.
2. Remove the affected phone's `rpcauth` line from `{CONF}` and restart/reload Core as
   required by the installed version.
3. Rotate credentials for any other profile that may have been exposed.
4. Revoke the app profile locally. Local deletion alone does **not** revoke Core or
   tailnet access.
5. Preserve only credential-free evidence needed for investigation. Do not export the
   Android WIF, attempt ledger, or raw signed transactions as support data.
6. Rebuild/re-provision the dedicated host and repeat this entire runbook before any
   new trust ceremony.

Changing the origin, wallet identity, Android address, network, or policy requires a
new trust ceremony. Importing a shared connection profile also requires the complete
ceremony; a profile file is connection material, never authorization.
