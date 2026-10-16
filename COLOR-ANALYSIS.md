# Screenshot color analysis

Original captures are unchanged private evidence. `COLOR-MEASUREMENTS.json` contains the reproducible parent measurement from genuine `video-025.0.png` (1560×720): annulus centered at the visually labeled whole control (1370,410), radius 28–44, excluding dark outlines/background using R≥100 and R≥2G,2B. The 3,624 retained pixels have median RGB **(177,20,27)**, range R138–211/G0–67/B2–87; a raw pixel at (1370,360) is (170,26,39). These are distributions of a shaded, composited red control, not a universal single hexadecimal color.

The production conservative body mask is R≥120, G≤45, B≤55, R−G≥60, R−B≥55. It deliberately excludes pale highlights, black outlines and orange scenery rather than requiring every button pixel to satisfy the mask. Shape, a non-red interior, robust full-circle geometry and uniqueness are also mandatory. A pixel being red alone is insufficient.

`video-045.0.png` independently shows another genuine red control near (1408,468). A neighboring red wall connects to its red component, biasing its bounding-box midpoint to (1392,465). This is a behavioral counterexample to using the component rectangle's midpoint. The production robust circle fit targets the circular control, not the attached wall or movable interior thumb indicator.

Production capture integers are little-endian ABGR transport values despite the snapshot field's historical `argb` name. OpenCV disk import uses BGR and is converted exactly once to RGB. Native replay explicitly tests red/blue controls, a genuine red screenshot pixel and `PylaUtils.argbToRgbMat` transport. These checks are separate from detector accuracy. See per-case native receipts; neither native replay nor JVM tests claim a live phone/game capture test.
