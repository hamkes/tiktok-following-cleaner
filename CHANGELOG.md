# Changelog (public summary)

## v2.0 — hardened salvage of an external draft (2026-10-27)

- An externally produced "v2.0" draft was rejected as-is (did not build;
  dropped the pacing caps, resume, wrong-list invalidation and the
  confirmation gate). Its good ideas were re-implemented on the v1.9 base:
  Arabic-Indic digit normalisation and Arabic relative-date parsing
  (منذ/قبل, duals, month-day rollover), split tab-label merging, a
  suggested-accounts section boundary that keeps suggested handles out of
  the collected list, and faster profile-load polling (200 ms).
- All guards, caps, resume and tests intact: 93 JVM tests (was 74).

## v1.9 — "smart" progress
- Progress is saved after every profile; resume continues from the saved
  count (persisted index vs. saved results — the larger wins).
- Evidence-based wrong-list tripwire replaces "one stray handle voids the
  run": isolated not-followed profiles are skipped, counted and logged;
  a statistically wrong list still invalidates.
- The app now displays saved progress ("classified X of Y") after a
  relaunch, and the final result in the Audit tab's status line.
- 74 JVM tests.

## v1.8 — scroll-target fix
- Root cause of tab flipping found: the collector scrolled the largest
  scrollable node — on the list screen that is the tab *pager*, so the
  tool flipped its own list from Following to Followers mid-run. The
  scroll target is now chosen by a pure rule that excludes pagers.
- Tab-state evidence is included in stop logs; header diagnostics print
  on every unverified stop.

## v1.7.1 — layout hotfix
- Fixed edge-to-edge overlap: header under the status bar, bottom
  navigation under the system nav buttons (targetSdk 35).

## v1.7 — tabs + bilingual (community-style restructure)
- Bottom navigation with 5 tabs (Home / Audit / Unfollow / Log /
  Settings), existing screens moved unchanged.
- Full Arabic + English localisation with a manual language switch
  (Arabic default) and localisation-integrity tests.

## v1.6 — count-aware tab matching
- Tab labels with counts ("Following 9,947") recognised; "Following you"
  excluded; tab row selected by screen band; header diagnostics print
  verbatim top-screen texts for field debugging.

## v1.5 — copyable log
- "Copy log" button: the full in-app log to the clipboard for bug reports.

## v1.4 — tested guard core
- Guard decisions extracted into a pure, unit-tested core; readiness
  checklist card; launcher icon.

## v1.3 — wrong-list protection
- Self-navigation to the Following list + hard tab verification before
  and during collection; per-profile follow-state sanity check. (An
  earlier version audited the Followers list by mistake — this release
  made that class of failure structurally impossible.)

## v1.2 — floating bubble
- Messenger-style floating status bubble during runs (the app's single
  permission, SYSTEM_ALERT_WINDOW).

## v1.1 — visual reskin
- Dark theme matching TikTok's look.

## v1.0 — first audit
- Read-only audit: collects the Following list and classifies profiles
  GONE / FAKE / DEAD / UNKNOWN / ACTIVE into a candidates file. No
  permissions, no unfollow capability.
