# NinePatch Library Agent Guide

Shared 9-patch chunk parsing and drawing library linked by Layoutlib and `standalone-render` (`tools/base/ninepatch`).

## Code Review Invariants

Read and enforce `G1`, `G3` (`Graphics2D`/buffer lifecycle), and `G4` in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md).
