# Vector Drawable Tool Agent Guide

SVG and VectorDrawable conversion utility (`tools/base/vector-drawable-tool`), including the optional `-d` Swing desktop viewer (`VdCommandLineTool.java`).

## Code Review Invariants

Read and enforce `G1` (Swing EDT rules still apply to the `-d` Swing viewer in `VdCommandLineTool.java`) and `G4` in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md).
