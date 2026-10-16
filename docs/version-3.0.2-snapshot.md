# Version 3.0.2 source snapshot

This branch synchronizes the previously delivered owner-test generation. It is not a claim that the newly reported gameplay problems are fixed, and it is not a public APK release.

## Included

- Canvas/WindowManager floating bubble and compact controls.
- An independent, non-touchable one-line status strip that remains visible when the menu is closed.
- Visually located red attack-control centers instead of configured fallback coordinates for basic auto-aim.
- Two distinct fresh observations, revision/geometry checks and stationary tap/charge handling.
- Version name3.0.2 and version code22, matching the delivered private test build.

## Known limitations and follow-up work

- The control detector can reject a visible resting/translucent attack button. This has been reproduced on two later owner-provided screenshots; the corresponding phone report contains ATTACK_BUTTON_UNVERIFIED and no logged basic attacks. The current thresholds are retained to preserve this snapshot, not presented as a solution to those cases.
- Selected .pyla scripts execute, but their movement output does not control the joystick; the engine planner does. Script attack() remains an engine-owned no-op. Restoring authoritative strategy intent is follow-up work.
- Reliable crate attack targeting and loose power-cube pickup targeting are not newly implemented here. No crate/pickup recognition claim is made.
- Passing fixture tests do not establish a live phone/gameplay result. The existing tests retain their original scope and private fixtures are not published.

## Verification scope

The delivered source passed676 unit tests with the available private fixtures, lint and a debug build; its native Android/OpenCV detector replay passed23 checks. Earlier overlay-specific native checks covered bubble/menu wiring and independent status-strip touch passthrough. Those are scoped prior results, not a claim that this synchronization has fixed the limitations above.

A clean-checkout test run without private screenshot directories skips fixture-dependent tests explicitly. Local and hosted PR results are recorded in the pull request. Diagnostic upload capability remains optional environment configuration and is not embedded in repository sources or credential-free CI builds. Private reports, screenshots, APKs, signing material and Drive credentials are excluded.
