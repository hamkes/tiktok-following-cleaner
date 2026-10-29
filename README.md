# TikTok Following Cleaner

**Audit your own TikTok "Following" list on your own Android phone — find dead,
fake and vanished accounts, review them yourself, and only then clean up.**

The app drives the *official* TikTok app's UI on-device with an Android
Accessibility Service — no root, no account credentials, no TikTok API, and
no network permission at all. The only permission it declares is
`SYSTEM_ALERT_WINDOW`, for the floating status bubble shown during runs.

> ⚠️ **Main now contains the experimental 2.0.7-gemini line.** On 2026-10-29
> the owner chose to replace the guarded v2.0 on `main` with this line.
> **It has NO daily/hourly pacing caps** — only a 150-account batch cap
> (default). Run it in small batches, audit first, review the candidates
> yourself, and keep the Friends protection on unless you deliberately
> want mutual-follow accounts unfollowed too. The guarded v2.0 remains in
> Git history (commit `34fec51f0adb6d1b086fb997dc3eac3785008c0b`).

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
3. **Review** — the candidates (GONE/FAKE/DEAD) are shown in a list **you**
   inspect and approve yourself, with an explicit confirmation step.
4. **Clean (optional)** — unfollow runs only from your approved selection,
   in batches of up to 150. Friends (mutual-follow) accounts are skipped
   while the Friends protection is on (the default).

Progress is saved after every profile, so a run of ~10,000 accounts can be
stopped and resumed across days, exactly where it left off. A resume audit
re-inspects only accounts with no saved result; starting a *new* audit
deletes all saved progress.

## What 2.0.7-gemini fixed

Earlier Gemini-line builds logged an unfollow as successful as soon as a
click was performed, without checking the result — and then deleted the
account from the results file. On 2026-10-28 a run logged 70 unfollows
while the profile's Following count moved by only 1, and a spot-checked
profile still showed its "Friends" button. Root cause: the follow-state
check and the click both took the *first* node labelled "Following" — the
header counter label above the real action button — so the click opened
the Following list instead of unfollowing.

2.0.7 fixes this:

- Follow-state detection now takes the **last** state label in the profile
  header (the button row sits below the counter row).
- The click targets the button node itself (clickable first, lowest on
  screen), found by its exact screen bounds.
- The confirmation step matches by "contains" and polls, so a delayed
  dialog or a "Unfollow @handle" menu item is not missed.
- An unfollow counts **only** after the button is re-read as "Follow" or
  "Follow back". An unverified click keeps the account in the list for a
  later batch instead of deleting it.
- The batch completion log now accounts for every slot: verified
  unfollows, already-unfollowed, failed verifications, unknown states,
  and Friends skips.

## Build

Gradle (preferred on a normal machine):

```sh
./gradlew assembleDebug
```

The Gradle wrapper JAR is not committed as a binary; regenerate it if your
checkout lacks it (`gradle wrapper`, or download it from the Gradle
distribution matching `gradle/wrapper/gradle-wrapper.properties`).

No-daemon manual build (the script used in the maintainer's VM, where the
Gradle daemon is killed deterministically):

```sh
./build-manual.sh
```

See the script header for the JDK 17 / Android SDK 35 / kotlinc layout it
expects; `VERSION_CODE`, `VERSION_NAME` and `OUT_APK` can be overridden
via environment variables.

## Tests

The guard layer is pure Kotlin and unit-tested: 39 JVM tests, including
regression tests for the 2.0.7 counter-label bug (a profile whose header
counter reads "Following" above a "Friends" button must read as `FRIENDS`,
and the button pick must prefer the clickable, lowest node).

```sh
./gradlew testDebugUnitTest
```

## Known issues / help wanted

- TikTok's UI varies by app build, locale and device; tab verification,
  profile loading and button layouts are the fragile parts. Device
  reports (app build, locale, what the log said, what the button
  actually showed) are very welcome.
- The 2.0.7 line has no daily/hourly pacing caps. If you need the
  conservative pacing of the old guarded line, use commit `34fec51f`
  instead — or contribute the caps back on top of this line.

## License

GPLv3 — see `LICENSE`.
