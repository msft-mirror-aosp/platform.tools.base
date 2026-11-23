# UI Check Agent Guide

Standalone headless UI Check analyzers and config adapters for rendered previews (`tools/base/uicheck`). Keep `uicheck/common` (`tools.uicheck.common`) and `uicheck/validators` (`tools.uicheck.validators`) strictly LayoutLib-free and `intellij-core`-free (do not depend on `uicheck/config` or `standalone-render/lib`); only `uicheck/config` (`tools.uicheck.config`) bridges to `standalone-render/lib`.

## Code Review Invariants

Read and enforce `G1` (host IDE decoupling) and `G4` (deterministic testing) in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md). For `G3`
`BufferedImage` buffer isolation, `ComponentTreeCheck` implementations reading
`CheckContext.renderedImage` must treat it as read-only and write any pixel
modifications (e.g., via `ColorConverter`) into a newly allocated/isolated
`BufferedImage` rather than mutating it in place or defensively copying inside
`CheckContext` (`ComponentTreeCheckTest` asserts `assertSame`).
