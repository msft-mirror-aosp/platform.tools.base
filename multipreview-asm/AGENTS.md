# Multipreview ASM Agent Guide

ASM bytecode scanner (`PreviewMethodFinder`, `MultipreviewAnnotationResolver`) for discovering `@Preview` annotations without loading user classes (`tools/base/multipreview-asm`).

## Code Review Invariants

Read and enforce `G1`, `G3` (KMP `@Preview` discovery), and `G4` in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md).
