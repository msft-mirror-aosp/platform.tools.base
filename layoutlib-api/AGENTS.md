# Layoutlib API Agent Guide

Host/guest bridge interfaces between Android Studio, standalone renderers, and Layoutlib (`tools/base/layoutlib-api`).

## Code Review Invariants

Read and enforce `G1`, `G2`, `G3` (`Graphics2D`/buffer lifecycle), and `G4` in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md).
