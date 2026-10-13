# Current Android development snapshot

Upstream: https://github.com/countryside99/PylaAndroid. Original attribution and the CC BY-NC 4.0 license from this snapshot's source baseline are retained. Upstream subsequently changed its license to GPLv3; this PR does not import that change or newer upstream code, and does not claim to relicense third-party game artwork.

This branch brings the current Android source snapshot into the fork for review. It is a development candidate, not an upstream release or a guarantee of unattended gameplay on every phone.

## Included behavior

- Bounded diagnostics with explicit Confirm/Cancel consent before upload.
- Fresh, exact brawler recognition, vertical grid search, localized card taps, DETAIL SELECT confirmation and verified lobby return. Already committed card/SELECT taps are not replayed.
- DETAIL confirmation checks stability around the title and SELECT control instead of rejecting unrelated background animation. Individual OCR attempts are capped at two seconds within the unchanged 15-second phase; the three-second pair freshness limit remains unchanged.
- Missing, ambiguous, wrong, shifted or covered controls, stale/future frames, cancellation, lost authority and expired deadlines remain fail-closed.
- Continuous movement and attack pulses during admitted gameplay, bounded blocked-path recovery, geometry-aware input composition, and verified defeated-screen EXIT continuation. Unrecognized gameplay never grants input authority.
- The fourteen small `video_state_cues` crops required by the current recognizer are explicitly included despite the generated-asset ignore rule. Existing models and assets are unchanged; the crops do not grant rights to third-party game artwork.

## Validation and limits

The source snapshot passed 463 JVM tests and 93 private composed selector cases, including realistic OCR timeout consumption. The private host fixtures use Android/OCR doubles and a synthetic clock; they are not a phone/game proof. The private API-36 emulator gates covered movement/attack/EXIT input composition, v18-to-v19 update with retained configuration, and clean installation. A real ML Kit reference-replay probe accepted synthetic changes outside the title/SELECT windows and denied changed SELECT controls, missing identity and expiry. These probes use a retained screenshot, not a live game server or the user's phone.

The clean PR worktree is separately compiled and tested before publication. `BrawlerDetailSelectTest` has optional full-reference image checks enabled with `PYLA_DETAIL_FIXTURE_DIR`; without that private fixture directory those optional image checks are not evidence of recognition accuracy. No private reports, fixture recordings, credentials, signing material or APKs are included in this PR.

## Build and privacy boundaries

Use Java 17, Android SDK 34 and Gradle 8.9. Upstream-tracked cfg/models/images/playstyles remain available. `copyPylaAssets` can refresh them from an optional `PylaAI-OriginalPCVersion` directory. The supplied cue crops remain tracked for a fresh checkout.

`PYLA_DIAGNOSTIC_UPLOAD_TOKEN` is supplied externally for an owner diagnostic build, never committed. An ordinary build without it cannot use the owner's private log-upload capability. The configured diagnostic endpoint is not a public service entitlement. Diagnostics do not collect screenshot pixels or raw OCR content. The optional Mobile Tactical experiment is not installed as a default playstyle.
