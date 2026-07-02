# Simple/Family Profile — Tightening Plan

**Source:** full-branch multi-lens sweep of `simple-family-profile` @ `bf3d253` vs `main` (2026-07-01):
6 lens finders (cross-slice correctness, security, lifecycle, hygiene, tests, UX) over the whole
24-commit / +2,820-line diff, findings deduped, every medium+ finding adversarially verified against
the real code by an independent agent. Result: **26 confirmed, 8 low, 2 refuted, 0 uncertain.**
Every item below survived verification with code evidence; line numbers are in the current working tree.

**Recommended merge gate for PR #2:** WP1 + WP2 + the two cheapest WP4 tests (computeGroupId vector,
corrupt-file quarantine). WP3/WP5/WP6 can land as fast-follow commits on the same branch or after merge.

---

> **STATUS (2026-07-01): WP1 IMPLEMENTED + adversarially reviewed + build/tests green.** All six items
> below are done (2 files: `NostrDirectMessageHandler.kt`, `SimpleModeScreen.kt`). A 3-lens review workflow
> (group-security / cross-slice / compose) with per-finding verification found **no critical/high/medium and
> no trust-gate breach** — only LOW hardening on the new out-of-order buffer, which was folded in: the buffer
> is now partitioned per-conversation (per-conv cap 20, max 50 conversations) and only admits a message when a
> mutual favorite is claimed in the group (a cold stranger's fabricated group is dropped, not buffered), and
> the unused `PendingGroupMessage.geohash` field was removed. **Accepted LOW (not fixed):** file gift-wrap
> dedup ids (`filegw:<id>`) share the 10k-capped `delivered` set with 1:1 delivery-ack bookkeeping — the
> prefix prevents semantic collision; capacity pressure is negligible.

## WP1 — Message-loss correctness (the four HIGH bugs + two adjacent mediums)

These are all "family member believes a message was sent/received and it wasn't" — the exact failure
class the Simple profile exists to prevent.

1. **[HIGH] First message to a freshly provisioned favorite is queued forever, UI says "Sent".**
   `SimpleModeScreen.kt:169` / `GeohashViewModel.startGeohashDM`. Provisioning writes only favorites;
   nothing seeds `GeohashAliasRegistry`, so on first send the alias resolves nowhere:
   `canSendViaNostr("nostr_…")` fails both hex regexes → `MessageRouter` queues to the in-memory outbox
   that nothing ever flushes for a `nostr_` alias — while `PrivateChatManager` already marked it
   `DeliveryStatus.Sent`. Works only if the other side messages first.
   **Fix:** `GeohashAliasRegistry.put(convKey, hex)` inside `startGeohashDM` (mirror the tap-add path at
   `SimpleModeScreen.kt:637`); ideally also register at provisioning time in `FamilyProvisioning`.

2. **[HIGH] Mesh-delivered messages never appear in the open Simple thread; own mesh-sent messages
   vanish on reopen.** `SimpleModeScreen.kt:463`. The union reads alias + noiseHex +
   `selectedPrivatePeer`, but BLE messages file under the sender's mesh peerID =
   `sha256(noiseKey).take(16)` — none of the three. Same-house BLE chat (a primary family case) breaks;
   tapping the mesh notification opens a second disjoint thread.
   **Fix:** derive the contact's mesh key from the favorite's noise key and add it to the union +
   `openContactByConvKey`.

3. **[HIGH] Group trust gate drops a non-favorite co-member's message that arrives before the group is
   stored.** `NostrDirectMessageHandler.kt:103`. Relays return stored events newest-first on the 48h
   catch-up, so C receiving B's reply before A's group-creating message is common; the wrap is deduped
   in-memory and never re-processed until the next restart. Silent gap in the group thread.
   **Fix:** buffer gate-rejected messages whose member set hashes to the claimed groupId in a small
   pending map; re-evaluate after each `NostrGroupRegistry.put()` for that convKey.

4. **[HIGH-UX] Zero delivery feedback: a message sent with relays down is pixel-identical to a
   delivered one.** `SimpleModeScreen.kt:667`. `DeliveryStatus` is already set (`Sending` →
   `Delivered/Read`) but `MessageBubble` never renders it; the relay queue is in-memory, so
   Samsung-killing the app silently loses "call me". The connection banner exists only on the home list.
   **Fix:** small status caption under own bubbles ("Sending…" / sent-check / "Not sent"); show the
   `ConnectionTroubleBanner` inside `SimpleConversation` too.

5. **[MED] `isViewing` compares against the alias convKey after send rewrote the selected peer to the
   canonical key** (`NostrDirectMessageHandler.kt:238`): the open thread gets marked unread and read
   receipts are never sent for the rest of the session (peer's phone stuck on "Delivered").
   **Fix:** compute isViewing against the conversation's key *set* (alias + resolved canonical).

6. **[MED] Nostr `FILE_TRANSFER` has no dedup** (`NostrDirectMessageHandler.kt:308`): every launch for
   48h re-saves the file, re-appends a bubble under a fresh UUID (pre-existing), and — new in this
   branch — re-fires a system notification each time; persisted history now accumulates the duplicates.
   **Fix:** dedup by gift-wrap id via `seenStore.hasDelivered(giftWrap.id)`.

## WP2 — Privacy & trust hardening

7. **[HIGH] Disk persistence defeats panic clear — for ALL profiles.** `AppStateStore.kt:323`.
   Triple-tap panic destroys keys/favorites/media but never touches `chat_history_v1.json`;
   history re-hydrates on the next flow emission or relaunch. Also: `clear()` runs `persistNow()`
   outside the lock, so a late inbound can overwrite the flushed file.
   **Fix:** `AppStateStore.wipePersisted()` (cancel job, clear maps without flushing, delete file + tmp)
   called from `panicClearAllData()`; move the `clear()` flush under the same synchronization.

8. **[MED] All DM/channel plaintext now sits unencrypted on disk** (`AppStateStore.kt:192`), reversing
   the app's in-memory-only ephemerality — including the mainnet-money Simple profile's DMs.
   **Decision needed:** encrypt at rest (EncryptedFile / keystore key) vs opt-in persistence per
   profile; verify `allowBackup` excludes the file either way.

9. **[PRIVACY] The maintainer's real home address is in a KDoc on the open PR.**
   `ProfileSetupCoordinator.kt:31` — `WAKEFIELD_FAMILY_ROOM = "drt3ydn6"` documented as
   "30 Wakefield Ave, Wakefield MA" — the exact doxxing-grade location the public room was removed for,
   plus the whole dead room-pinning surface around it (`pinRoom()`, `roomGeohash`/`roomLevel` params,
   stale class KDoc; zero production callers).
   **Fix:** delete the constant + `pinRoom()` + dead params + fix KDoc. **User decision:** the address
   is already in pushed history (`60dfe08`) — accept, or rewrite branch history before merge.

10. **[MED] Group-member name spoofing / tap-added impersonation pair.** `SimpleModeScreen.kt:638` +
    `:393`. `bgm` names are sender-chosen; a member can label any npub "Mom", the victim tap-adds it,
    and the resulting row is pixel-identical to a QR-verified favorite — future DMs (and Dogecoin
    request payments) go to the attacker.
    **Fix:** distinct visual treatment for KnownNpub rows ("Added from group — not verified" + badge),
    disambiguate name collisions with an npub fragment, and say "name unconfirmed" in the Add dialog.

11. **[MED] `isMine = (sender == nickname)`** (`SimpleModeScreen.kt:532`): a same-named member's
    messages render as YOURS (and flip `canPay` on payment bubbles); renaming yourself flips your own
    history to the left side. **Fix:** structural ownership via `senderPeerID == myPeerID`, nickname
    only as last-resort fallback.

12. **[LOW×2, security] (a)** provisioning verifies QRs with `maxAgeSeconds = Long.MAX_VALUE`
    (`AddFamilyScreen.kt:85`) while the new Copy-my-code feature pushes codes over insecure channels —
    bound the age or don't pre-mark `theyFavoritedUs` from an out-of-band code. **(b)**
    `parseGroupMembers` accepts non-64-hex member fields, making the groupId preimage non-injective
    (comma ambiguity) — drop malformed members before hashing (`NostrDirectMessageHandler.kt:162`).

## WP3 — Lifecycle robustness

13. **[MED] Simple nav state is plain `remember`** (`SimpleModeScreen.kt:120`): Activity recreation
    (dark-mode/locale/split-screen) dumps the user to the list while the ViewModel still thinks the
    thread is open → false read receipts + suppressed notifications + lost draft.
    **Fix:** `rememberSaveable` convKey (rebuild target via openGroup/openContactByConvKey), or
    `endPrivateChat()` when composing with `target == null` but a selected peer.

14. **[MED] Corrupt history file = silent total loss, then overwritten.** `AppStateStore.kt:71`:
    `runCatching` swallows parse failures without even a log; the first new message persists empty
    state over the possibly-recoverable file. **Fix:** quarantine (`.corrupt-<ts>` rename) + `Log.e`.

15. **[LOW] Profile pick marks `profile_chosen` before the profile is applied** (`MainActivity.kt:342`):
    Activity death in the window strands a Simple pick in the Power UI with no picker ever again.
    **Fix:** hoist the synchronous `ProfilePreferenceManager.set()` out of the coroutine.

16. **[LOW] Notification-tap intent re-fires from recents on cold start** (`MainActivity.kt:884`) —
    forced navigation into an old thread on every history relaunch; also the SIMPLE path still drives
    the Power sheet state nothing renders. **Fix:** the `clearedIntent` pattern `handleDogecoinIntent`
    already uses; gate the Power-sheet calls on profile.

## WP4 — Test hardening (risk × testability order)

17. **computeGroupId pinned-vector test** — the cross-device thread-identity AND integrity-gate hash,
    100% pure, zero tests (`NostrGroupRegistry.kt:42`). Pin exact output; permutation/case/dup
    invariance; distinctness. *Any silent change bricks every existing family group.*
18. **AppStateStore**: corrupt-file behavior (pairs with #14), DTO round-trip over all 6
    `DeliveryStatus` kinds, `MAX_PER_CONVERSATION` keeps the *newest* 1000.
19. **MessageRouter.sendPrivate 5-way branch-order test** (`MessageRouter.kt:58`) — the order is
    load-bearing per its own comments; needs only an internal/`@VisibleForTesting` constructor.
20. **provisionFamilyContact** — the root of the family trust chain; dropping one of its three calls
    still "succeeds" but silently breaks the group gate + wallet helper gate. Needs a small DI seam.
21. **Trust-gate accept/reject matrix** — extract the gate + `parseGroupMembers` into a pure function
    to make the security invariant testable (also enables #3's pending-buffer change safely).
22. **KnownNpubStore case-normalization round-trip** — free (works without Context).

## WP5 — Target-user UX

23. **Localization: the entire Simple surface is hardcoded English** across
    `SimpleModeScreen/AddFamilyScreen/ProfilePickScreen` (~60 literals), plus a hardcoded 12-hour
    `"h:mm a"` time format — for a product whose stated user is a Japanese relative. **Fix:** extract to
    `strings.xml` + `values-ja`, use `DateFormat.getTimeFormat(context)`. Do this before more UI lands.
24. **Home list has no unread dot, no last-message preview, no timestamps, no activity ordering**
    (`SimpleModeScreen.kt:361`) — the core LINE affordance; all data already collected in `SimpleHome`.
25. **"Switch to full bitchat" fires on a single unconfirmed tap** (`SimpleModeScreen.kt:1141`) — the
    most destructive tap in Simple; wrap in a confirm dialog with Cancel default.
26. **Add-family failure feedback**: invalid/foreign QR scans give zero feedback (scanner runs forever,
    `AddFamilyScreen.kt:195`); the "Added" toast fires even when provisioning returned false; paste
    error shows a red border with no message; permanently-denied camera leaves a dead "Allow camera"
    button (needs the app-settings deep link + pointer to paste).
27. **Auto-scroll yanks the reader to the bottom on any new message** (`SimpleModeScreen.kt:479`) —
    only auto-scroll when already at/near the bottom.

## WP6 — Hygiene & structure

28. **Dead code sweep:** the public-room machinery (item 9's non-privacy remainder), the always-true
    `isPrivate` param + unreachable channel branch + two dead `collectAsState` subscriptions in
    `SimpleConversation` (`SimpleModeScreen.kt:472`), unused API surface
    (`AppProfile.isSimple/isPower`, `KnownNpubStore.get/remove`, `NostrGroupRegistry.contains/clear`),
    three stale phase/increment comments (`ChatViewModel.kt:2167`, `:750`, `ProfilePickScreen.kt:27`).
29. **Split `SimpleModeScreen.kt` (1,191 lines) along its five clean seams:** SimpleConversation(+bubbles),
    ConnectionTrouble(+applyTorMode+rememberHasInternet), SimpleSettingsSheet, AddPeopleSheet; keep the
    ~420-line navigator. Promote `nostrPubkeyToHex`/`applyTorMode` to shared internal helpers.
30. **Persist round-trip loses `senderNostrPubkey`** (`AppStateStore.kt:95`) → tap-a-name-to-add dies
    for all restored messages after every relaunch. One-line DTO field. *(Correctness, but lands
    naturally with the WP1/WP4 persistence work.)*
31. **PR #2 description is stale and now wrong** — still advertises the public "Family Room" (removed
    as a privacy fix) and says "13 commits · 15 files · +1,396/−10" (now 24 / 26 / +2,820). Rewrite
    around the E2E group. Also fold the pending `CONTINUATION_PROMPT.md` working-tree edit into the
    next commit.

## Known & accepted (NOT in this plan's scope)

- Notifications stop if the OS fully kills the app (needs a service-owned subscription or push — big
  follow-up, explicitly deferred).
- Per-member group DELIVERED/READ receipts (suppressed by design for `nostr_grp_` keys, deferred).
- Group subject/naming UX (deferred).
- No media in Simple (intentional).
- **The on-device 2–3-phone interactive group test remains the top validation item** — blocked on
  plugging in the S24 + Pixel 3 (tablet already verified at `bf3d253`: persistence read-back, 3-sender
  group render, relays up).

## Refuted during verification (do not re-chase)

- "Account-DM fallback leaks a geohash persona" — refuted; the alias paths in question are
  account-identity by construction.
- "Tor/profile transitions die with composition-scoped coroutines leaving pref/network divergent" —
  refuted; the coroutine scoping holds.
