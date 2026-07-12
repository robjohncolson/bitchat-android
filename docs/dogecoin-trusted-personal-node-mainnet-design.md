# Mainnet `TRUSTED_PERSONAL_NODE` design

**Status:** Proposed, design only

**Date:** 2026-07-11

**Scope:** Mainnet reads and user-triggered sends through one explicitly trusted personal Dogecoin Core node while the built-in SPV wallet is behind

**Implementation authorization:** None. This document does not authorize a mainnet experiment or a code change.

`MUST`, `MUST NOT`, `SHOULD`, and `MAY` are normative.

## 1. Sources and precedence

This design executes DES-1 in [`orchestrator-improvement-queue.md`](./orchestrator-improvement-queue.md). It follows the current Tailscale direction in [`../CONTINUATION_PROMPT.md`](../CONTINUATION_PROMPT.md), which is authoritative for this feature:

```text
Android signer
  -> exact Tailscale HTTPS origin
  -> Tailscale Serve (never Funnel)
  -> loopback-only Dogecoin Core RPC
  -> dedicated watch-only Core wallet
```

The non-persistent assist, SPV cross-check, disputed-source, one-broadcast-path, and claim-until-corroborated rules in [`family-wallet-ux-learnings-spec.md`](./family-wallet-ux-learnings-spec.md) remain in force.

[`dogecoin-remote-node-access-spec.md`](./dogecoin-remote-node-access-spec.md) remains a proposed, deferred Tor/gateway architecture. Its threat analysis and fail-closed principles inform this design, but its custom gateway, OpenAPI service, onion transport, and Arti program are not part of `TRUSTED_PERSONAL_NODE`.

## 2. Decision

`TRUSTED_PERSONAL_NODE` is a distinct, named mainnet trust mode. It is not another spelling of `RPC`, not a property inferred from a valid URL, and not a successful “Test connection” result.

The mode permits Android to use one explicitly authorized personal node as the source of mainnet UTXO availability while SPV is behind. That exception exists to make the personal node useful when SPV cannot yet spend. Requiring fully synced SPV before every node-assisted send would defeat the feature.

The exception is narrow:

- SPV remains the persisted/default backend and continues syncing.
- Android remains the only transaction builder and signer.
- The node never receives the Android WIF or any other Android private key.
- The exact Tailscale origin, mainnet wallet address, Core wallet identity, and trust-policy version are bound in a separate authorization record.
- Use of that record is session-only and explicit. App restart returns to SPV.
- Every selected input requires a full previous-transaction proof checked on Android before signing.
- Actual fee is computed from those locally verified previous outputs and locally parsed signed outputs.
- Absolute and relative fee limits are hard blocks with no acknowledgement override.
- Broadcast is user-triggered, one-route, same-bytes idempotent, and only **Claimed** until an independent source observes it.

This mode accepts a personal node as an availability, chain-membership, and unspentness oracle. It does not claim that the node is honest.

## 3. Security statement

### 3.1 What each control proves

| Control | What it provides | What it does not provide |
|---|---|---|
| Exact `https://<machine>.<tailnet>.ts.net` origin + platform TLS | A pragmatic binding to the configured Tailscale HTTPS route | App-managed SPKI pinning, proof that Funnel is off, or honest Core data |
| Tailnet authentication and ACL | Private reachability from approved tailnet devices when correctly administered | Anonymity, protection from a compromised tailnet admin/control plane, or proof of host integrity |
| Dedicated Core wallet with the Android address imported watch-only | Core can track that public address without needing the Android WIF | Proof that the host is honest, complete, current, or incapable of possessing some separately obtained key |
| Fully validating Core | The honest software validates the chain it follows | Proof to Android that the reported chain, peers, UTXOs, or status are truthful |
| Full previous-transaction verification on Android | The selected outpoint's txid commits to the amount and script Android uses | Proof that the transaction is in the canonical chain, sufficiently confirmed, or currently unspent |
| `testmempoolaccept` | The selected Core instance presently reports that the signed bytes pass its policy | Independent propagation, miner acceptance, or an honest answer from a compromised node |
| Hard fee caps | A bound on fee computed from cryptographically verified previous-output values | A chain-inclusion or broadcast guarantee |

“Watch-only” is a key-custody property, not an oracle-integrity property. A compromised authenticated laptop can still:

- omit real UTXOs or history;
- report spent or off-chain outpoints as available;
- fabricate a self-consistent off-chain previous transaction paying the Android address;
- report false tip, peer, rescan, balance, policy, or broadcast state;
- censor a transaction or delay it indefinitely;
- observe the wallet address, queries, and signed public transaction.

Raw Core `rpcauth` is method-unscoped. The Android policy can restrict which methods the app calls, but a stolen phone credential combined with tailnet access can invoke the full RPC surface of that Core instance. The dedicated keyless/no-value Core instance, per-phone credential, narrow tailnet ACL, and prompt operator-side credential/device revocation are containment controls; deleting a credential from Android does not revoke it on Core.

Mandatory previous-transaction verification prevents the most dangerous value lie. A node cannot change the amount or script of a real selected outpoint while preserving its txid without breaking the transaction hash. A fabricated or already-spent outpoint can still cause an invalid transaction and denial of service, but it cannot silently turn an unknown difference into miner fee.

If bounded full previous-transaction bytes are unavailable for any selected input, the mainnet send MUST fail before signing. Falling back to a node-reported scalar amount is forbidden. A cap calculated from the same scalar a malicious node supplied would not bound the real fee.

### 3.2 Accepted residual trust

The user explicitly accepts that the authorized laptop, its operating system, the Tailscale account/control plane and administrators, the tailnet configuration, Tailscale Serve, Dogecoin Core, and the Core data directory can deny service or lie about chain inclusion and unspentness.

The design limits the likely consequence of those lies to false/stale display, invalid transaction construction, censorship, privacy loss, or an uncorroborated broadcast claim. It does not make a compromised personal node safe or independently verifiable.

## 4. Goals and non-goals

### 4.1 Goals

1. Show clearly attributed mainnet balance, UTXOs, and activity from the personal node while SPV is behind.
2. Permit a user-authorized mainnet send without requiring SPV to reach tip first.
3. Authenticate and bind one exact Tailscale origin before it receives credentials, wallet address, or signed bytes.
4. Prevent a node-reported input-value lie from becoming an unbounded miner fee.
5. Preserve all existing signer, WIF-backup, mainnet acknowledgement, transaction-shape, review-expiry, txid, and broadcast gates except the explicitly replaced UTXO-source decision.
6. Keep SPV running as the default independent view and use it later for cross-check and settlement observation.
7. Fail closed without silently changing backend, origin, or broadcast route.

### 4.2 Non-goals

- Treating watch-only Core as an honest or complete oracle.
- Importing the Android WIF into Core, signing in Core, or calling wallet-send RPCs.
- Public RPC, Tailscale Funnel, raw `100.64.0.0/10` access, port forwarding, or broad `rpcbind`/`rpcallowip`.
- Legacy LAN or local HTTP mainnet spending.
- Trusting arbitrary or merely well-formed `.ts.net` URLs.
- The deferred Tor gateway, Arti rebuild, OpenAPI service, or public HTTPS gateway.
- Electrum/explorer redesign or automatic explorer queries.
- Mesh-helper relay through the trusted-personal-node credential.
- Automatic failover, automatic retry, or cascading the same action to SPV, helper, explorer, LAN, or another node.
- Remote Core administration, address import, rescans, wallet loading, key operations, mining, or peer management from this mode.
- Proving that the operator followed every Tailnet Lock, ACL, firewall, or host-hardening instruction; the app cannot currently attest those controls.

## 5. Current baseline and implementation prerequisite

The current RPC guardrails are necessary but not sufficient for this mode:

- `requireTrustedDogecoinRpcRoute()` is a central pre-request choke point.
- redirects are disabled and response bodies are bounded;
- editing/displaying drafts performs no I/O and only explicit Save persists settings; an explicit draft test is a separate user action;
- WalletSheet-owned multi-call workflows carry a configuration generation;
- `TAILSCALE_HTTPS` enforces a strict origin shape;
- mainnet is excluded from the current session-assist offer;
- Android validates scripts, builds/signs locally, recomputes txid, preflights, and checks the Core-returned txid.

Important gaps remain:

1. `TAILSCALE_HTTPS` is a URL-shape/transport-eligibility class, not a provisioned-host authorization.
2. The route choke point is network-agnostic. A saved ordinary `RPC` backend can still reach a shape-valid Tailscale or local route on mainnet even though the session-assist card is hidden.
3. Existing RPC UTXOs trust node-reported amounts and do not carry a verified full previous transaction into the signer.
4. Existing high-fee thresholds can be overridden by acknowledgement.
5. Existing ordinary RPC success is not a first-class Claimed/Observed settlement state.
6. Existing helper eligibility does not distinguish this wallet-scoped trust grant from a general relay-capable node.
7. Helper, debug, and receipt-status paths are not all owned by the WalletSheet configuration generation and require the same future central profile/session revision check.
8. Existing RPC balance/activity methods may call `importaddress(..., rescan=false)`. Trusted-personal-node reads must use a non-mutating method set against an address and wallet provisioned on the host.
9. Existing receipt-status RPC observations can corroborate a receipt. A trusted personal node must never corroborate its own payment/broadcast claim.

Therefore DES-1 does not bless any current mainnet RPC flow. Before implementing this feature, ordinary mainnet RPC reads/signing/broadcast MUST fail closed unless a valid `TRUSTED_PERSONAL_NODE` authorization and active session are present. This MUST be a new central policy decision, not `network != MAINNET` being removed from `dogecoinNodeAssistEligible()`.

## 6. Host and transport boundary

### 6.1 Required topology

Core RPC MUST remain loopback-only:

```ini
server=1
rpcbind=127.0.0.1
rpcallowip=127.0.0.1
```

The exact RPC port is operator-selected. Tailscale Serve publishes `http://127.0.0.1:<rpc-port>` as private HTTPS. Funnel MUST be off. Core P2P may remain public; Core RPC may not.

The operator MUST use:

- a separate Core instance/data directory with no valuable or application spending keys;
- a dedicated Core wallet in which the Android address is watch-only;
- a completed historical rescan before relying on balance/UTXOs;
- a unique `rpcauth` credential per phone;
- a narrow tailnet grant from named phone identities to the tagged laptop on TCP 443 only;
- MFA, device approval, and Tailnet Lock with at least two signing devices and disablement/recovery material stored offline;
- an external/LAN check that the Core RPC port is unreachable and a Serve-status check showing no Funnel.

Dogecoin Core 1.14.9 may create unrelated legacy wallet keys. Those keys MUST NOT receive funds, and the Android WIF MUST never be imported. `validateaddress` reporting `ismine=false` and `iswatchonly=true` is a required setup/readiness check, but is still a statement made by the node and not cryptographic proof of key absence.

### 6.2 Exact origin binding

The existing endpoint classifier answers “may this transport shape receive RPC I/O?” It MUST NOT answer “did this user authorize this node for mainnet inputs?”

A mainnet trust record binds all of:

- canonical exact origin `https://<machine>.<tailnet>.ts.net`;
- `MAINNET`;
- the active Android wallet address;
- the exact Core wallet identity/name selected after connection;
- a monotonically increasing profile/configuration revision;
- a consent-policy version.

The origin accepted by this mode is lowercase ASCII with exactly the expected four DNS labels, implicit port 443, and no userinfo, explicit port, trailing dot, path, query, or fragment. Unicode/IDN and `xn--` labels are rejected. The following are never equivalent:

- a sibling `.ts.net` hostname;
- an extra-label or lookalike hostname;
- a raw Tailscale/CGNAT IP;
- `LOCAL_PRIVATE`;
- arbitrary public HTTPS;
- `.onion`;
- a redirect target.

The client constructs any Core wallet path itself from the separately bound wallet identity. The user cannot put a wallet path into the saved origin. Redirects remain disabled; every 3xx is an error.

Changing origin, wallet identity, network, Android address/key, consent version, or saved route revision invalidates the active session, readiness result, UTXO snapshot, transaction review, and pending broadcast authorization. A sibling host requires a new trust ceremony. Credential rotation may preserve the trust record only after an authenticated explicit save and a fresh readiness check.

Platform TLS plus tailnet identity is the pragmatic route identity for v1. It is not app-managed SPKI pinning. The app cannot infer from the hostname whether Funnel, ACLs, device approval, or Tailnet Lock are correctly configured; those remain operator prerequisites unless a future Tailscale integration provides attestable evidence.

## 7. Opt-in and state model

### 7.1 Durable authorization versus session use

The exact profile authorization MAY persist so the operator does not repeat provisioning after every process start. Its use does not persist:

- SPV remains the saved backend.
- App start, process death, network switch, wallet reset/import, origin edit, credential edit, or wallet-identity edit leaves assist inactive.
- The user explicitly chooses “Use trusted personal node” for the current session.
- Every mainnet transaction built from node-only inputs requires a separate per-spend acknowledgement in the final review. The existing generic mainnet acknowledgement does not substitute for this oracle-risk acknowledgement.
- Once SPV becomes fully synced and cross-check succeeds, reads revert to SPV and the assist session ends.

Simple/family devices may consume a provisioner-created profile, but activation and the per-spend warning remain explicit on the signing phone. No profile may silently arrive through chat, an Android deep link, or mutable display-name identity.

### 7.2 Trust ceremony

1. Edit origin, username, password, and wallet identity in a draft. Editing performs zero network I/O.
2. Validate the exact canonical origin locally. URL syntax and `TAILSCALE_HTTPS` classification are necessary, not authorization.
3. Show the full exact origin, `MAINNET`, bound Android address, route/privacy disclosures, and the fact that the upcoming test will send credentials and the public wallet address to that origin.
4. On explicit “Test this exact origin,” create an in-memory, revision-bound `PROVISIONING` authorization. Only then may the app check TLS, authentication, chain, node readiness, resolved wallet identity, watch state, and rescan state. The result cannot authorize balance/UTXO reads, signing, or broadcast.
5. Show the test result, full exact origin, resolved Core wallet identity, and the oracle warning. Require the operator to confirm:
   - this is the personally controlled laptop;
   - Core RPC is loopback-only behind Serve and Funnel is off;
   - the address is watch-only and the Android WIF has never been imported;
   - the node can lie or censor and is not independently verified by SPV;
   - Tailscale is encrypted private transport, not Tor or anonymity.
6. Persist the authorization atomically, separately from encrypted credentials and ordinary backend selection.
7. Activation later requires a fresh readiness check and creates an in-memory session tied to the profile/configuration revision.

A successful probe, existing testnet configuration, saved Basic credentials, or URL shape MUST NOT migrate automatically into this state.

### 7.3 States

| State | Meaning | Money behavior |
|---|---|---|
| `UNAUTHORIZED` | No explicit mainnet trust grant | Zero mainnet personal-node I/O |
| `PROVISIONING` | User confirmed one exact origin/config revision for a one-shot setup test | Fixed readiness/watch calls only; no balance/UTXO reads, signing, or broadcast |
| `AUTHORIZED_INACTIVE` | Exact profile is stored; SPV remains active/default | No node reads/signing until explicit activation |
| `CHECKING` | User activated; readiness is being checked | No signing/broadcast |
| `ACTIVE_UNVERIFIED` | Fresh readiness passed; node data may be displayed with warning | Proof-backed mainnet send may proceed through all gates |
| `DEGRADED` | Transient route/node/read failure or stale data | Cached node values may be shown stale; no new signing/broadcast |
| `AUTH_REQUIRED` | Core rejected the stored credential | Session inactive; operator must rotate/remove the host `rpcauth` entry and provision a replacement |
| `DISPUTED` | Stable impossible invariant or independent cross-check conflict | Hide node amounts behind dispute UI; no node signing/broadcast/helper use |
| `REVOKED` | Profile deleted, wallet changed, or trust withdrawn | Credentials deleted locally; host-side `rpcauth` and Tailscale-device revocation remain explicit operator actions |

`DEGRADED` may return to active only through an explicit fresh check in the same bound session. `AUTH_REQUIRED` cannot recover through repeated requests with the rejected credential. `DISPUTED` is persistent and requires the recovery decision in section 14.

## 8. Readiness and read provenance

### 8.1 Fresh readiness

Before any node-assisted mainnet snapshot or broadcast, a locally timed readiness result MUST establish:

- exact bound origin/profile/configuration revision;
- expected chain `main`;
- `initialblockdownload=false`;
- finite, sane verification progress at the release threshold;
- headers not behind blocks and blocks no more than the release lag threshold behind headers;
- network active, relay ready, and at least the mainnet Core peer floor;
- exact bound Core wallet loaded/ready;
- bound Android address reports `ismine=false` and `iswatchonly=true`;
- historical watch rescan is complete for the address;
- required raw-previous-transaction and `testmempoolaccept` methods are available;
- no rescan or wallet mutation is in progress.

Candidate v1 defaults are peer count at least 4, block/header lag at most 2, and a readiness/snapshot lifetime no longer than 2 minutes. These values require human confirmation before implementation.

All freshness uses the phone's monotonic/local time, never a server timestamp. A cached status never authorizes signing or broadcast.

Readiness is an operational sanity gate, not proof of honesty. A compromised node can lie about every field.

The trusted-personal-node client is non-mutating except for `testmempoolaccept` and `sendrawtransaction` after final user authorization. Provisioning/readiness/read paths MUST NOT call `importaddress`, `rescanblockchain`, `loadwallet`, `createwallet`, key/signing RPCs, `sendtoaddress`, peer/mining/admin methods, or arbitrary RPC. Address import and historical rescan are host-console operations completed before authorization.

### 8.2 Read semantics

While active, node balance, UTXOs, and activity may display before SPV is synced, using copy equivalent to:

```text
Trusted personal node · node-reported, not yet verified by Built-in · updated 38s ago
```

Rules:

- A successful authenticated, well-formed, rescan-complete empty snapshot is “node-reported zero,” not independently verified zero.
- Timeout, TLS, auth, parse, upstream, rescan, or route errors are unavailable/stale, never zero.
- Cached values show the exact source and locally measured age.
- Switching source clears or re-labels the old value before display; SPV data is never labeled node data or vice versa.
- Data from a disputed profile is not shown as an ordinary stale node balance.
- Node-reported relay fee, incremental fee, or dust policy is diagnostic only. It cannot silently raise a user fee, discard change into fee, or relax local output rules.

## 9. Proof-backed UTXO snapshot

### 9.1 Snapshot binding

Raw Core RPC has no typed atomic snapshot. The app therefore freezes a local snapshot record containing:

- profile/configuration revision and exact origin;
- network and Android address;
- locally recorded fetch time;
- node best-block hash/height before and after collection;
- the candidate UTXO set;
- a verified previous-output proof for every candidate eligible for selection.

A normal monotonic tip extension during collection does not invalidate hash-committed previous outputs. The app accepts only a bounded extension whose reported header ancestry includes the starting tip, then rechecks every selected outpoint at the final tip. Height regression, same-height hash replacement, non-ancestor/reorg evidence, or an extension beyond the bound discards the snapshot. These checks guard accidental races; they do not make a malicious node honest. Snapshots expire after the approved short lifetime and are invalidated by every trust-relevant change.

### 9.2 Mandatory previous-output proof

For every UTXO that can enter the signer, Android MUST obtain bounded raw bytes for the complete previous transaction and then:

1. Reject more than 1,000,000 raw bytes for one previous transaction or more than 4 MiB of previous-transaction proof bytes in one snapshot; stricter protocol/count bounds still apply.
2. Strictly parse and fully consume the transaction with protocol size/count/overflow bounds.
3. Recompute the previous transaction's double-SHA256 txid over the exact received bytes, never a parsed-and-reserialized form.
4. Require exact equality with the selected outpoint txid.
5. Require `vout` to exist.
6. Derive amount and script exclusively from that referenced serialized output.
7. Require a positive amount, checked sums, and the exact P2PKH script for the active Android address.
8. Require the node's `listunspent` and immediate `gettxout(..., include_mempool=true)` fields to agree with the locally derived amount/script.
9. Reject duplicate outpoints, malformed txids, unsupported scripts, missing bytes, trailing bytes, or any arithmetic overflow.

The proof parser is not the outbound raw-transaction policy validator. Historical transactions may legitimately contain coinbase inputs, data outputs, or scripts unrelated to the selected output. It must parse them safely while applying wallet ownership/value rules only to the referenced `vout`.

The resulting signer input is conceptually a `VerifiedPrevout`, not a generic node `DogecoinUtxo`. The type/seam must make it impossible for a scalar amount from `listunspent` to reach the `TRUSTED_PERSONAL_NODE` signer accidentally.

This proof establishes amount and script conditional on the outpoint. It does not prove canonical-chain inclusion, confirmation count, or unspentness. A fake self-consistent off-chain previous transaction therefore causes an invalid/unrelayable spend rather than silent fee burn.

## 10. Mainnet send gates

### 10.1 SPV gates that remain

| SPV rule | `TRUSTED_PERSONAL_NODE` behavior |
|---|---|
| SPV is the persisted/default backend | Unchanged |
| SPV continues syncing during assist | Required |
| Mainnet SPV `synced` means at least 4 peers and no more than 2 blocks behind | Unchanged; presentation “near tip” is never substituted |
| SPV broadcast requires its existing synced/peer/authorization gates | Unchanged |
| Fully synced SPV UTXO intersection before signing | Deliberately not required in this named mode; mandatory previous-output proof replaces the value-integrity role |
| Positive SPV evidence contradicts the exact outpoint (for example, SPV independently observed it spent) | Conservative veto for the current send; never overridden by the node |
| SPV reaches fully synced state | Run cross-check, restore SPV read provenance, and end assist after success |
| Stable impossible cross-check mismatch | Persist profile-scoped `DISPUTED` |
| Transaction settlement | Only exact independent SPV observation upgrades the node claim in v1 |

While SPV is behind or its wallet history does not cover the outpoint, simple absence is non-evidence: it neither corroborates nor vetoes a node-only input. Otherwise this mode would recreate the SPV-intersection prerequisite it exists to remove. Positive independently derived evidence about that exact outpoint may veto conservatively. Even after headers are synced, a birthdate-limited SPV wallet can omit older wallet history; that leaves the cross-check inconclusive rather than accusing the node. Wrong-chain data, malformed proofs, or the same outpoint with conflicting hash-committed amount/script are dispute-class failures.

### 10.2 Existing gates that remain

The named mode MUST preserve:

- Android wallet/network/address consistency;
- mainnet WIF-backup gate;
- existing mainnet user acknowledgement;
- a separate per-spend personal-node oracle acknowledgement;
- valid reviewed recipient and amount;
- locally enforced minimum standard output and fee floor;
- signed-review expiry (currently ten minutes);
- canonical raw-transaction shape and bounded size;
- local txid recomputation;
- selected-input freshness recheck;
- exact Core-returned txid equality;
- one broadcast path per user action.

The existing policy-unavailable and high-fee acknowledgement overrides do **not** apply in this mode. `testmempoolaccept` must be available, must check the exact final bytes, and must return allowed before the app calls `sendrawtransaction`; section 12.1 governs the fact that preflight itself discloses broadcastable bytes.

### 10.3 Build and final review

The flow is:

1. Require an active, fresh, revision-bound trusted-personal-node session.
2. Fetch a fresh proof-backed snapshot.
3. Select only node-reported-confirmed, unique, exact-address `VerifiedPrevout` inputs; confirmation count remains part of the accepted node-oracle claim.
4. Build and sign on Android.
5. Parse the signed bytes locally and require an exact input-outpoint bijection with the frozen verified proof set; no added, removed, or substituted input is allowed.
6. Require parsed outputs to match the reviewed recipient, amount, and exact change back to the bound Android address, with no unreviewed output.
7. Recompute txid and actual fee from verified input totals minus locally parsed output totals.
8. Show source, exact origin, recipient, amount, actual fee, change, input count, and the node-oracle warning in the final review. No signed byte has left Android yet.
9. Require the normal mainnet acknowledgement and the personal-node acknowledgement.
10. Immediately before signed-byte disclosure, refresh readiness and recheck every selected outpoint through `gettxout(..., include_mempool=true)`, requiring the same amount/script/proof binding.
11. Re-parse the unchanged signed bytes, recompute fee/txid, and enforce all hard caps again.
12. Atomically persist the encrypted, backup-excluded same-byte recovery record and reserve the selected inputs before making any call that contains the signed bytes.
13. Run `testmempoolaccept` on those exact bytes. This is the first disclosure and occurs only after final user confirmation. If it returns allowed, immediately call `sendrawtransaction` with the same bytes through the same origin as part of that one user action.
14. Treat only the exact locally computed txid response (or the bounded same-byte reconciliation in section 12.1) as `CLAIMED`. Every other outcome after step 13 is `SUBMISSION_UNKNOWN`, even when the node reports a policy rejection or malformed result.

Before step 13, a configuration revision, wallet change, tip-coherence failure, expired snapshot/review, or changed selected input discards the undisclosed bytes and restarts from a fresh snapshot. It never patches an already reviewed transaction in place. After step 13, section 12.1's durable `SUBMISSION_UNKNOWN` rules apply instead.

## 11. Fee policy

Fee is computed only as:

```text
sum(locally verified selected previous outputs)
  - sum(locally parsed signed transaction outputs)
```

All operations use checked integer koinu arithmetic. Negative fee, overflow, an unknown output, or a mismatch with the review model fails closed.

For the initial design, crossing either existing high-fee threshold is a hard block:

- absolute: `feeKoinu >= 100_000_000` (1 DOGE);
- relative: `feeKoinu >= ceil(sendAmountKoinu / 10)` (10%).

No checkbox, advanced setting, node policy value, debug command, raw export, or policy-unavailable acknowledgement overrides either cap inside this mode. Both are checked before review and immediately before broadcast.

At the current 0.01 DOGE minimum transaction fee, the 10% rule makes sends at or below approximately 0.1 DOGE unavailable through this mode. That is an honest consequence of applying the existing threshold as a hard limit; the human must decide whether to accept that product behavior or approve a separately reviewed cap formula.

Node-reported relay/incremental fees may cause preflight to reject a locally bounded transaction. They do not authorize the app to raise the fee automatically. The user must start a fresh review within local bounds, or use another explicit mode after SPV is ready.

## 12. Broadcast and settlement state

### 12.1 One route and idempotency

Final confirmation selects exactly the bound Tailscale/Core route. It does not cascade to SPV, a mesh helper, explorer, LAN, raw export, another node, or another hostname.

Before the first signed-byte disclosure, including `testmempoolaccept`, atomically:

- persist an encrypted, backup-excluded recovery record containing the exact signed bytes, frozen review/profile revision, local txid, and proof references needed for safe reconciliation;
- persistently reserve the selected outpoints; and
- record a random local correlation identifier.

Raw Core has no application idempotency-key parameter. The random identifier is local correlation metadata; exact same bytes and therefore the same txid are the effective replay identity. Crash recovery must restore the attempt and reservations before offering any new spend.

A retry is allowed only as an explicit user action using:

- the same unexpired signed bytes;
- the same locally computed txid;
- the same exact origin/profile revision;
- the same idempotency record.

Once preflight receives the signed bytes, a compromised node can broadcast them even while returning rejected, unavailable, malformed, or mismatched output. Therefore every non-exact outcome after that point is `SUBMISSION_UNKNOWN`; the app may stop before its own `sendrawtransaction` call, but it cannot treat the bytes as retractable or build a replacement automatically.

Raw Core commonly reports “already known” as an error. It becomes an accepted `CLAIMED` reconciliation only through a version-tested bounded Core error classification followed by a same-origin query that returns raw transaction bytes exactly equal to the stored bytes. Arbitrary or localized error text alone is never acceptance. If exact bytes cannot be retrieved, the state remains `SUBMISSION_UNKNOWN`. Same-node reconciliation is still a claim, not independent observation.

Process death, timeout, auth rejection, parse failure, or txid mismatch after disclosure leaves the durable attempt and input reservations intact. The app offers only same-bytes/same-route reconciliation or retry while the signed review remains eligible. Elapsed time alone MUST NOT release an input: non-locktime signed bytes remain broadcastable while their inputs remain unspent. Release requires independent SPV evidence that the exact transaction confirmed, or that a conflicting spend irreversibly invalidated it under the approved confirmation/reorg policy. Any destructive manual override requires a separate warning and policy decision.

Review expiry prevents a new submission or retry of old bytes. Read-only same-origin and SPV reconciliation may continue after expiry, but it cannot authorize another disclosure. Expiry cannot retract bytes already delivered to the laptop, which may broadcast them later. UI copy must state that limitation after an ambiguous submission.

### 12.2 Claim until independently observed

Settlement provenance is explicit:

| State | Evidence |
|---|---|
| `SUBMISSION_UNKNOWN` | The request may have reached Core, but no exact accepted response returned |
| `CLAIMED` | The bound Core route returned the exact locally computed txid or exact same-byte “already known” result |
| `OBSERVED` | The local SPV wallet receives the exact txid from its peer/chain view; a locally injected pending record does not count |
| `CONFIRMED` | Fully mainnet-synced SPV (including its peer floor) sees the exact txid in its validated chain at the product-approved confirmation depth |

The same personal node's `gettransaction`, mempool, block, or confirmation response can never corroborate its own claim. Repeated RPC success remains `CLAIMED`. Merely inserting the locally built transaction into the SPV wallet also does not corroborate it; `OBSERVED` must be peer-derived or block-derived. An unsynced SPV peer observation may establish `OBSERVED`, but never `CONFIRMED`.

An automatic `dogepaid:` receipt may be emitted only after the existing successful Claim anchor and exact txid/network checks. The wire receipt is still a claim. Neither sender nor receiver displays “paid/confirmed” from that message or from the trusted personal node alone.

Payment-status and receipt-status consumers are part of the same central mainnet policy. A TPN RPC activity row may be displayed as node-reported context, but it cannot be supplied to the claim/corroboration resolver as an independent observation. This applies on both the sending and receiving device.

An opt-in independent explorer could become a later observer only with separate privacy consent and credential isolation. It is not part of v1 and is never queried automatically.

## 13. Helper, debug, and alternate-path policy

- `TRUSTED_PERSONAL_NODE` is wallet-address scoped and MUST NOT make `BroadcastHelperService` or `DogecoinHelperAnnouncement` eligible.
- It cannot relay arbitrary peers' transactions.
- Debug commands cannot construct, authorize, activate, inspect secrets from, or spend through this profile. The existing separately confirmation-gated SPV mainnet debug command is outside this design; the release review must prove it cannot inherit this profile, its proofs, or its input authorization, and the human may require disabling it separately.
- No new TPN/mainnet WIF, trust record, credential, raw previous transaction, or signed transaction is accepted through the exported debug path or written to logs. Existing non-mainnet debug key-import behavior is unchanged and outside DES-1.
- Every TPN-built signed transaction carries immutable source/profile provenance and is categorically ineligible for raw export, mesh helper, generic RPC, SPV broadcast, or debug-console submission. Only the bound TPN coordinator may perform preflight/replay/broadcast for those bytes.
- A later separately initiated send cannot reuse persistently reserved inputs until the earlier TPN attempt is independently reconciled and released under the approved policy.

A future general relay capability requires a separate credential, consent, rate policy, and money-path review.

## 14. Cross-check, dispute, and recovery

SPV keeps syncing throughout the session. When its full mainnet `synced` predicate becomes true and its wallet has sufficient historical coverage for the relevant outpoints:

1. Freeze node-assisted signing.
2. Fetch fresh node and SPV views.
3. Compare network and the relevant outpoints by txid, vout, hash-committed amount, script, and spent visibility.
4. On agreement, label the read view “cross-checked with Built-in,” restore SPV as the read source, and end assist.
5. On wrong-chain, malformed-proof, or stable same-outpoint invariant conflict, persist `DISPUTED` for the exact profile/address/network tuple.
6. On positive spent visibility that contradicts the node, block affected spending and repeat after bounded fresh sync/reorg allowance before calling it disputed.

Mere absence from an incomplete or birthdate-limited SPV wallet is not a mismatch and does not veto a node-only input. It leaves provenance unverified and the cross-check inconclusive. The session does not automatically earn “cross-checked with Built-in” or enter `DISPUTED`; the operator must improve SPV wallet coverage or continue under the original explicit personal-node trust.

`DISPUTED` suspends all profile reads presented as ordinary balance, signing, broadcast, and helper use. The last independent SPV value may remain visible with its own provenance.

Recovery MUST NOT be “tap trust again.” It requires a fully synced SPV view, repeated successful comparison, and explicit operator confirmation, or deletion/re-provisioning of the profile after fixing/rebuilding the host. Exact recovery timing and evidence remain an open question.

## 15. Failure behavior

Every failure disables only the assist attempt/session. SPV remains persisted and usable; the app does not silently submit the same transaction elsewhere.

The table describes the direct state transition. If the failure happens after signed bytes first leave Android at preflight, the attempt additionally remains `SUBMISSION_UNKNOWN` with its durable input reservations; no response can make those bytes retractable.

| Failure | Required result |
|---|---|
| Missing authorization or inactive session | No balance/UTXO/sign/broadcast I/O; only the explicitly confirmed, fixed-call `PROVISIONING` test may run before durable authorization |
| Draft edit or unsaved settings | Zero money-path I/O; prior readiness cannot authorize |
| Origin/wallet/address/network/revision mismatch | Cancel in-flight work, discard snapshot/review, require new authorization where applicable |
| Redirect, TLS, hostname, Tailscale, DNS, or connection failure | `DEGRADED`; no credential forwarding or alternate origin |
| HTTP 401/403 | Enter `AUTH_REQUIRED`; require operator-side `rpcauth`/Tailscale repair or revocation, never Core-password fallback |
| Oversized/malformed/ambiguous response | Discard response; no value becomes zero or enters signer |
| Wrong chain, IBD, excessive lag, low peers, network inactive | No fresh reads for signing and no broadcast |
| Wallet missing/wrong or `ismine=true` | Hard architectural stop; revoke/repair setup before use |
| Address not watch-only or rescan incomplete/unknown | Node-reported reads may be unavailable; no signing |
| Any client path attempts import/rescan/load/key/admin RPC | Central policy refusal; no request built |
| Successful empty snapshot | Display “node-reported zero,” not independent zero |
| Stale snapshot/review | Discard and restart; no acknowledgement override |
| Missing/invalid previous transaction or proof mismatch | No signing; stable impossible mismatch may dispute |
| Duplicate/foreign/wrong-script/spent input | Reject selection and refresh; never substitute silently |
| Fee cap or checked-arithmetic failure | Hard block; no acknowledgement override |
| Preflight method unavailable before disclosure | No signing/review authorization |
| `testmempoolaccept` unavailable/rejected after receiving signed bytes | Do not call `sendrawtransaction`, but retain `SUBMISSION_UNKNOWN` and reservations because the node may already have relayed the bytes |
| Selected-input recheck changed | Discard review and signed bytes; start over |
| Core txid differs from local txid | `SUBMISSION_UNKNOWN`, retained reservations, and dispute candidate |
| Timeout after submission | `SUBMISSION_UNKNOWN`; same-bytes/same-route user reconciliation only |
| Exact Core acceptance | `CLAIMED`, never confirmed |
| SPV disagreement | Block; persistent stable invariant conflict becomes `DISPUTED` |
| Lost/stolen phone or leaked Basic credential | Delete locally, revoke the Tailscale device/access, and remove/rotate the host `rpcauth` entry; local deletion alone is not revocation |
| Laptop/tailnet compromise or censorship | Accepted residual oracle/availability risk; never described as safe |

## 16. Test plan

### 16.1 Pure JVM policy tests

- Exact-origin vectors: uppercase, trailing dot, explicit/default/alternate ports, userinfo, path, wallet-path injection, query, fragment, sibling/extra-label hosts, Unicode/punycode lookalikes, raw `100.64/10`, local/private HTTP, public HTTPS, and `.onion`.
- `TAILSCALE_HTTPS` shape eligibility never implies mainnet authorization.
- Authorization tuple and state transitions, including process death and consent-policy migration.
- Network/address/key/wallet/origin/configuration-revision changes invalidate session, snapshot, and review.
- Ordinary mainnet RPC cannot read/sign/broadcast without the named profile and active session.
- Snapshot lifetime, local monotonic freshness, bounded monotonic tip extension, regression/reorg rejection, final-tip input recheck, and stale-response generation rejection.
- Previous-transaction parser fixed vectors and fuzzing: per-transaction/aggregate body caps, truncated/oversized counts, coinbase, data outputs, trailing bytes, invalid varints, vout bounds, txid mismatch, wrong script, zero/overflow amount, duplicates, and unsupported forms.
- Proof-to-signed-transaction bijection and reviewed recipient/change-output invariants.
- Malicious node vectors: lower/greater scalar amount versus committed previous output, fabricated off-chain previous transaction, spent outpoint, foreign address, wrong script, duplicate outpoint, stale proof, and inconsistent `gettxout`.
- Actual-fee checked arithmetic and absolute/relative hard caps, including boundary equality and proof that acknowledgements cannot override them.
- Mandatory preflight, exact local/Core txid, exact-byte replay, bounded already-known reconciliation, ambiguous submission, and one-route-per-action state tables.
- Crash-point tests immediately before/after atomic attempt persistence, input reservation, preflight disclosure, Core acceptance, response parsing, and same-byte recovery.
- Settlement state: node response and TPN receipt-status read stay Claimed; only correct-network, exact-txid peer/block-derived SPV observation advances it, and only fully synced mainnet SPV can mark it Confirmed.
- SPV catch-up/cross-check/dispute/recovery transitions, including birthdate-related absence that remains inconclusive without accusing the node or vetoing a node-only input.

### 16.2 Android and HTTP tests

- Zero requests while editing, viewing a draft, or opening the wallet; the one-shot `PROVISIONING` test runs only after exact-origin disclosure/confirmation and cannot authorize wallet reads or money operations.
- Explicit activation only; app restart leaves authorization stored but session inactive and SPV selected.
- Redirects disabled for default and injected clients; credentials never reach another origin.
- TLS/hostname/401/403/404/405/5xx, empty/chunked/oversized bodies, malformed JSON, timeout, cancellation, and configuration-generation races.
- Encrypted credential storage, backup exclusion, logging redaction, process death, wallet reset/import, credential rotation, and revocation.
- Encrypted/backup-excluded signed-byte recovery and persistent input reservations survive process death atomically and never appear in logs/support export.
- RPC-spy tests prove provisioning/read/status paths never issue import, rescan, wallet-load/create, key/signing, wallet-send, peer/mining/admin, or arbitrary RPC methods.
- Compose tests for full-origin confirmation, node-oracle warning, Tor-bypass/Tailscale disclosure, node-reported/stale/disputed provenance, hard-fee block, submission unknown, and claim/observed/confirmed states in EN and JA.
- No helper announcement/relay eligibility; TPN provenance cannot enter raw export, generic RPC/SPV broadcast, or debug paths; existing separate mainnet debug behavior cannot inherit TPN authorization.

### 16.3 Live testnet and operational matrix

The full money path is proven on testnet before any mainnet authorization:

1. Core chain correct, not IBD, sufficiently peered, and watch rescan complete.
2. `ismine=false`, `iswatchonly=true`; Android WIF absent from Core.
3. RPC listens only on loopback; LAN/raw RPC connection fails.
4. Tailscale Serve maps exact HTTPS origin to loopback RPC; Funnel status is off; public reachability fails.
5. Narrow tailnet ACL allows the intended phone and rejects another device.
6. Proof-backed snapshot obtains and verifies every selected previous transaction.
7. Android builds/signs, re-parses, fee-checks, preflights, and broadcasts identical bytes.
8. Exact Core txid produces Claimed; local SPV observation advances the state.
9. Same-byte retry is idempotent after dropped response/process death.
10. Tailscale down, TLS failure, wrong password, Core down/syncing, rescan, low peers, laptop sleep/reboot, tailnet revocation, config edit, and hotspot reconnect all fail closed without fallback.
11. Deliberately falsified proxy responses exercise every malicious-node vector without exposing mainnet funds.

No automated test spends mainnet funds. A read-only mainnet dry run and any tiny manual mainnet canary require separate, explicit human authorization after the fault matrix passes.

### 16.4 Release gates

Implementation is blocked until:

- all human questions in section 18 that affect policy are resolved;
- the ordinary mainnet RPC gap is closed centrally;
- the TPN client uses a fixed non-mutating read method set, and every helper/debug/payment-status consumer is behind the same profile/session revision policy;
- Core 1.14.9 can supply bounded full previous-transaction bytes for the watch-only wallet, or v1 explicitly requires a safe host configuration such as `txindex=1`;
- the proof-carrying input type and signed-transaction parser are independently reviewed;
- encrypted attempt persistence and persistent input reservations pass every preflight/broadcast process-death crash point;
- testnet Tailscale soak and fault injection pass;
- operational instructions prove loopback-only RPC and Serve-not-Funnel;
- EN/JA, accessibility, secret-redaction, and process-death tests pass;
- a money-path review confirms no WIF, ordinary RPC, helper, raw export, or debug bypass.

## 17. Suggested implementation boundaries (future work only)

This is sequencing guidance, not implementation approval:

1. **Mainnet route guard:** deny ordinary mainnet RPC and define the central named authorization seam.
2. **Profile/state:** exact-origin/address/wallet trust record, explicit activation, invalidation, dispute persistence, and provenance UI.
3. **Input proof:** bounded historical transaction parser, `VerifiedPrevout`, proof-backed snapshot, local actual-fee policy, and malicious-vector tests.
4. **Send coordinator:** frozen review, final recheck, mandatory preflight, same-route idempotency, and settlement provenance.
5. **Operational release:** testnet soak, host/Tailscale runbook, EN/JA/accessibility, recovery, and separate mainnet go/no-go.

Do not combine all five boundaries into one money-path change.

## 18. Open questions for the human

1. **Fee limits:** Keep the initial hard blocks at `>= 1 DOGE` or `>= 10%`, accepting that the current fee floor prevents very small trusted-node sends, or approve a different separately reviewed formula?
2. **Node readiness:** Confirm peer floor 4, maximum block/header lag 2, verification-progress threshold, snapshot lifetime, minimum input confirmations, and the exact condition for “rescan complete.”
3. **Previous-transaction source:** On supported Dogecoin Core 1.14.9, should the host require `txindex=1`, use wallet-scoped `gettransaction` hex, or use bounded `getrawtransaction` with a block hash? If none is reliable, the mode remains read-only.
4. **Opt-in cadence:** Is durable profile authorization + session activation + per-spend acknowledgement the intended UX, or should the full oracle warning repeat on every activation/send?
5. **Legacy mainnet RPC:** Should a small guardrail change disable the current generic mainnet “My node” path before the rest of this feature is built?
6. **Rescan evidence:** What durable operator/app record proves that a previously funded address's historical rescan completed, given that the same node can lie about it?
7. **Raw Core versus gateway:** Is a dedicated watch-only Core instance behind Serve with method-unscoped `rpcauth` acceptable for v1, or must a typed/scoped gateway become a prerequisite for mainnet?
8. **Tailscale posture:** Are manual Serve/Funnel/ACL/Tailnet Lock checks acceptable, or is API/LocalAPI-backed device and exposure evidence required before authorization?
9. **Dispute recovery:** How many fresh fully synced comparisons, over what reorg allowance, are required to clear `DISPUTED` without deleting the profile?
10. **Settlement:** What SPV confirmation depth changes `OBSERVED` to `CONFIRMED`, and should a separately opted-in explorer ever count as an observer?
11. **Ambiguous broadcast:** How long is same-byte retry offered, what confirmation/reorg evidence releases reservations, and is any separately warned destructive override allowed? Elapsed time alone cannot release them.
12. **Release proof:** What testnet soak duration/fault matrix is required, and will a tiny manual mainnet canary be authorized separately after read-only dry runs?
13. **Debug mainnet path:** Must the existing separately confirmation-gated SPV mainnet debug command be removed before release, or may it remain after an explicit proof that it cannot inherit any TPN profile, proof, or reserved input?
