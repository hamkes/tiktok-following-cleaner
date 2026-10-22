# Contributing

Contributions are welcome — especially **device reports**. This app's hard
problems are all "TikTok's UI behaves differently on my build", and every
new build/region/language teaches the guard layer something.

## Filing a useful device report

Please include:

1. Device model + Android version.
2. TikTok app version and UI language.
3. What you did, what happened, what you expected.
4. The in-app log: **Log tab → «نسخ السجل» / "Copy log"** and paste it.
   The log includes the built-in header diagnostics (the verbatim texts
   the app saw at the top of the list screen) — that is usually the
   decisive evidence.
5. A screenshot of the app state if the status line shows an error.

Usernames in logs are your own data — feel free to redact handles before
posting publicly; the diagnostics lines matter more than the names.

## Code contributions

- The safety-critical logic lives in pure Kotlin classes
  (`Classifier`, `GuardDecisions`, `NavLogic`) and is covered by JVM
  tests. **Changes there must keep the suite green and add tests for new
  decision rules.** Decision logic must stay deterministic — no ML, no
  network calls.
- The accessibility service (`CleanerAccessibilityService`) is the only
  place that touches the live node tree; keep raw UI interaction there
  and decisions in the pure core.
- Hard invariants (do not change without discussion):
  - Audit mode is read-only; it can never unfollow.
  - Unfollow runs only from a user-approved candidates file plus the
    explicit in-app confirmation.
  - Pacing ceilings in code: ≤150/day, ≤20/hour; defaults 60/day, 12/hour.
  - Hard stop on CAPTCHA in unfollow mode; pause-and-wait in audit mode.
  - Exactly one permission: `SYSTEM_ALERT_WINDOW`. No INTERNET, ever.
  - `UNKNOWN` (private/unreadable) is never a cleanup candidate.

## Good first issues

- Test the APK on a different device / TikTok build / UI language and
  file a device report (see above).
- Add tab-label markers for another UI language.
- Improve the profile-load confirmation to cut the UNKNOWN rate.
