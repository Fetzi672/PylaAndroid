# Fork status — current Android development snapshot

- **Upstream:** https://github.com/countryside99/PylaAndroid
- **Development build:** `1.1.18-dev (20)`, planned tag `v1.1.18-dev`. Matching experimental APKs belong on the [contribution fork's releases page](https://github.com/Fetzi672/PylaAndroid/releases); verify the tag and checksum before installing. A contribution PR is not an upstream release.

This community contribution brings the current **experimental** Android source snapshot upstream for review. It is a development candidate, not an upstream release and **not a guarantee of unattended gameplay on any phone**. For concrete defects and limits, see [KNOWN_ISSUES.md](KNOWN_ISSUES.md); for diagnostics, see [DIAGNOSTICS.md](DIAGNOSTICS.md).

## Status: EARLY BETA / EXPERIMENTAL

Everything here is early beta. Treat every run as supervised. The project does not promise that the bot plays a full match correctly on your device.

## License and attribution

- Original attribution and the upstream project link are preserved.
- The project is now licensed under the **GNU General Public License v3.0** — see [LICENSE](../LICENSE).
- This relicensing covers the project's own source only. It does **not** relicense third-party game artwork or any asset whose rights belong to others.
- The [CC BY-NC 4.0](https://creativecommons.org/licenses/by-nc/4.0/) license applied to earlier snapshots of this fork's source baseline only. Any statement that CC BY-NC 4.0 still governs the current project is stale.

## What works today

The following behaviours are user-reported as reliable in current builds:

- **Name-based brawler selection and scrolling.** Selection is driven by recognised names rather than fixed card positions, so it follows the list as it scrolls.
- **Automatic attacks.** Attack input fires during admitted gameplay.
- **Stuck detection with a walk-around recovery.** When the bot detects that it is stuck, it attempts a bounded move to free itself.
- **Input stops when the app is closed.** Closing the app stops input as expected; this is improved but not guaranteed in every situation (see [KNOWN_ISSUES.md](KNOWN_ISSUES.md)).

## Partially reliable

These work some of the time and should not be relied on:

- **Movement.** Movement is only partially reliable.
- **Solo Showdown EXIT.** Leaving Solo Showdown is unreliable; the bot may not exit cleanly.

## Known limits and assumptions

- **Trophy counting is wrong across the 0–1000 range.** The counter wrongly admits `1014`; at prestige 1 a value of `14` was observed. See [KNOWN_ISSUES.md](KNOWN_ISSUES.md).
- **Device coverage is one phone.** Only a **Samsung Galaxy S26 Ultra** has been tested. The exact game app version and Android version were not recorded, so behaviour on other devices and versions is unknown.
- **English only.** The game must be set to English.
- **Coordinate scaling is not proven universal.** Dynamic coordinate scaling is implemented, but it is not proven to work across all screen resolutions. Coordinate templates and OCR bands remain **assumptions**, not universal layout guarantees.

## Safety

To stop the bot immediately, turn off the accessibility service for PylaAndroid (**Settings → Accessibility → PylaAndroid**). If the phone cannot be controlled and you cannot regain control, follow your **manufacturer's force-restart instructions** (for example, press and hold Power, or Power + Volume Down). A forced restart is a safety fallback, **not** a routine part of the workflow.

## Diagnostics (summary)

Diagnostic upload is opt-in and requires explicit Confirm/Cancel consent. Reports are redacted and are intended to exclude screenshots, raw OCR text and credentials — this is the design intent, not a promise that a report can never contain anything sensitive. Full details live in [DIAGNOSTICS.md](DIAGNOSTICS.md). Sharing a report ID publicly may allow others to read it; review before posting.

## Validation

Prior hosted CI on fork source `5dbc1a5f426b91c643ad0ab76355465ce1a3c553`: **448 JVM tests passed, 15 optional image-fixture checks were skipped, and Android lint reported 0 errors and 40 warnings.** This is historical evidence, not the final-head verdict for this contribution; see the PR checks and release notes for the actual source-matched results. The skipped checks are the optional full-reference image comparisons in `BrawlerDetailSelectTest`, gated behind `PYLA_DETAIL_FIXTURE_DIR`; hosted CI does not exercise them, so a green JVM run is not a recognition-accuracy or gameplay proof. Additional private composed-selector cases are run on the development host. No private reports, fixture recordings, credentials, signing material or APKs are included in this contribution.

## Build boundaries

Use Java 17, Android SDK 34 and Gradle 8.9. `PYLA_DIAGNOSTIC_UPLOAD_TOKEN` is supplied externally for an owner diagnostic build and is never committed; an ordinary build without it cannot use the private upload path.
