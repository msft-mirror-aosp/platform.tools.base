# Visual Lint Agent Guide

Post-render Visual Lint analyzers for layout bounds, wear, text, color blindness, and adaptive UI defects (`tools/base/visual-lint`; keep `visual-lint-core` LayoutLib-free for `uicheck`).

## Code Review Invariants

Read and enforce `G1` (host IDE decoupling) and `G4` (deterministic testing) in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md). For `G3`
`BufferedImage` buffer isolation, note that `ColorBlindMode.imageTransform`
(`colorblindmode/ColorConstants.kt`) intentionally calls
`ColorConverter.convert(image, image)` to mutate in place; callers sharing a
read-only `BufferedImage` (such as `uicheck`'s `CheckContext.renderedImage`)
must pass a newly allocated `postImage` buffer instead of aliasing `startImage`.
