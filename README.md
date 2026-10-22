# TikTok Following Cleaner

**Audit your own TikTok "Following" list on your own Android phone — find dead,
fake and vanished accounts, review them yourself, and only then clean up.
Read-only audit first. Nothing is ever unfollowed without your explicit,
per-list approval.**

Following thousands of accounts over the years leaves a list full of deleted
accounts, bots that never posted, and pages that died years ago. TikTok gives
you no tool to audit that list. This app does it on-device, using an Android
Accessibility Service to read the *official* TikTok app's UI — no root, no
account credentials, no TikTok API, no network permission at all.

> Status: **working, but rough on real devices** — see *Known issues* below.
> This project is published specifically to get help with TikTok-UI
> reliability across devices, app builds and locales. Device reports and
> PRs are very welcome.

## How it works

1. **Collect** — the app opens your Following list inside the official TikTok
   app, verifies that the visible list really is the *Following* tab (and
   keeps re-verifying while it scrolls), and records every username.
2. **Classify** — each profile is opened (read-only) and classified from
   visible UI signals only:
   - `GONE` — account deleted / not found
   - `FAKE` — default-style handle (`user123456…`) with zero videos
   - `DEAD` — zero videos, or newest video older than the threshold (180 days by default)
   - `UNKNOWN` — private or unreadable (never treated as a candidate)
   - `ACTIVE` — everything else
3. **Review** — the candidates (GONE/FAKE/DEAD) are written to a file **you**
   inspect and approve yourself, with an explicit confirmation step.
4. **Clean (optional)** — unfollow runs only from your approved file, with
   conservative pacing: 60/day and 12/hour by default, hard caps of
   150/day and 20/hour in code, randomised gaps, a rolling 24 h counter,
   and a hard stop on any CAPTCHA or repeated failures.

Progress is saved after **every** profile, so a run of ~10,000 accounts can
be stopped and resumed across days, exactly where it left off.

## Safety design (the interesting part)

The guard layer is pure Kotlin and fully unit-tested (74 JVM tests), because
the failure modes are nasty:

- **Wrong-list protection.** The worst possible bug is auditing your
  *Followers* list while believing it is your *Following* list. The app
  verifies the selected tab from the accessibility node tree before
  collecting a single name, re-verifies before every collection batch,
  only scrolls a real list container (never the tab pager — a scroll
  gesture on the pager flips tabs!), and sanity-checks every opened
  profile's follow-state button.
- **Evidence-based invalidation.** A single stray handle (e.g. collected
  from a "suggested / LIVE" element on the list screen) used to void an
  entire multi-hour run. Now follow-state evidence accumulates in a
  "tripwire": isolated not-followed profiles are skipped and logged,
  while a statistically wrong list still invalidates everything.
- **Deterministic, not AI.** Every decision is a pure function of the
  visible node tree — no ML, no network, no probabilities near the
  safety-critical paths.

## Permissions

Exactly one: `SYSTEM_ALERT_WINDOW` (the small floating status bubble shown
during runs). **No INTERNET permission** — the app cannot send your data
anywhere, by construction.

## Build

- Android Studio / Gradle: standard build (`minSdk 26`, `targetSdk 35`,
  Kotlin, no AndroidX). The Gradle wrapper JAR is not committed — open the
  project in Android Studio once, or run `gradle wrapper` with a local
  Gradle 8.9, to regenerate it.
- `./build-manual.sh` — the maintainer's no-daemon pipeline
  (aapt2 → kotlinc → d8 → zipalign → apksigner) that produces a signed
  debug APK directly.
- JVM tests (classifier, guard decisions, navigation logic, localisation
  integrity, scroll targeting, tripwire): plain JUnit 4 against the pure
  core classes.

## Known issues — help wanted!

These are real, reproduced on a Galaxy S24 Ultra with TikTok's English UI,
and they are why this repo is public:

1. **Tab verification is brittle.** Runs sometimes stop with "could not
   confirm the Following list" even while the user is looking at it.
   TikTok's list header varies (counts in tab labels, LIVE rails,
   suggested strips). More node-tree dumps from other builds/regions
   would help harden the matcher.
2. **Profile loading via deep links is flaky.** Profiles are opened with
   `tiktok.com/@user` intents; a large share of profiles time out and
   classify as `UNKNOWN` (≈40 of 54 in one run). A more reliable
   open-and-confirm strategy is the top priority.
3. **Collection completeness.** One run collected 7,745 unique names while
   the profile header showed ~9,946 following. Lazy-loading stalls,
   dedupes and unavailable accounts may explain part of it — unproven.
4. **Only one device / one TikTok build tested so far.** Reports from
   other devices, TikTok versions and UI languages (markers exist for
   English + Arabic) are gold.

See `CONTRIBUTING.md` for how to file a useful device report (the app's
built-in header diagnostics + log copy were designed for exactly this).

## Roadmap

- Harden tab verification with multi-build node dumps
- Faster, reliable profile opening for classification
- Optional export/import of the collected list
- Localise the floating bubble (currently Arabic-only)

## License

GPLv3 (see `LICENSE`) — free software: anyone who distributes a modified version must publish their changes under the same license. © the maintainer and contributors.
