# Super readiness settings

Super readiness requires a positively matched Super icon **and** yellow ready-control pixels around its localized center. Detection alone does not authorize a tap: target/range/obstruction, gameplay admission, original-capture freshness, geometry, session ownership and queued-input guards still apply.

## Legacy threshold: explicit read-time migration

`cfg/bot_config.toml` retains `super_pixels_minimum`. Its UI label is now **Super readiness threshold**, with the conversion explained beside it. Stored settings and override/reset bookkeeping are not rewritten or deleted.

The localized detector interprets the old scale as:

```text
required yellow fraction = 0.25 * (super_pixels_minimum / 1800)
ready only when measured yellow pixels / localized ROI area > required yellow fraction
```

- `1800` preserves the visual detector's existing strict `>25%` default.
- `900` requires `>12.5%`; `3600` requires `>50%`.
- `7200` and larger deny readiness even for a completely yellow ROI. Large limits are not clamped down.
- `0` still requires some yellow pixels and a positive template match.
- Negative, malformed or nonfinite explicit values deny detection. A missing value uses the historical `1800` default.

This is a deliberate sensitivity migration, **not** a claim that old absolute HSV counts from a different rectangle/color range are numerically equivalent. Normalizing against the actual localized ROI area makes the condition scale-independent while preserving the visual default. The template score must remain `>0.90`; the yellow HSV range remains `(17,170,200)` to `(34,255,255)`.

## Legacy rectangle: automatic default, constrained customization

`cfg/lobby_config.toml` retains `[pixel_counter_crop_area].super` as four rectangle endpoints `[left, top, right, bottom]` in the legacy 1920×1080 reference system.

- A missing value or the **exact historical factory value** `[1460,830,1560,930]` selects the existing automatic template-search window. That factory rectangle misses the Super control in the phone reference layout, so it is not blindly restored as the only crop.
- Any other valid rectangle is a **hard template-search bound**, mapped with the existing legacy aspect-aware horizontal expansion and vertical scaling. No template outside it can be selected. A custom region excluding the control correctly denies detection; it does not silently fall back to a wider search.
- Arrays must have exactly four finite integer endpoints within 1920×1080, with positive width/height. Malformed, reversed, out-of-reference or empty custom values deny detection. A mapped empty/out-of-frame region also denies it.
- The region locates the icon. Yellow coverage is measured in the same existing square around the localized icon, not over the whole search rectangle. Configure a region large enough to fit the icon template at the target capture size.

For example, `[1280,600,1500,850]` is a custom search region; it is not a new universal default or a guarantee for every control layout. The factory rectangle is a reserved automatic-mode sentinel; there is no separate mode setting in this migration.

## Evidence boundary

Policy tests cover defaults, threshold effects and monotonicity, scale normalization, strict boundaries, malformed values, TOML integration and custom-region constraints. Native image replay is a separate maintainer-run Android/OpenCV instrumentation harness, not part of this repository's Gradle/JVM test suite; its observed results and limits must be reported separately. Reference positives derived from the template-source recording are in-sample plumbing/regression evidence, not independent live-game accuracy or proof of actual Super firing. No settings migration weakens session/input authority or adds blind taps.

## Related Chaosdrop continuation boundary

A face-to-coins continuation requires the same observation generation, matching capture dimensions, and original capture timestamps strictly newer than the latest lifecycle invalidation. Pause, Resume, Stop and session replacement revoke the continuation. Re-reading cached pixels cannot renew its TTL, and a late old-generation menu result cannot revoke a newer owner's chain. Unbound classifiers may identify face artwork but cannot arm, consume or revoke a continuation.

The continuation accepts a frame age of at most **3000 ms**, deliberately stricter than the general WindowController screenshot limit of **5000 ms**. Its separate face-to-coins TTL is **5000 ms measured from the original face capture**, not recognition/copy time. All mutating production classifiers supply both their observation generation and the original producer capture timestamp; do not omit that metadata when adding a caller.
