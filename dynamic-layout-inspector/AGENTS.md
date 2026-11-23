# Dynamic Layout Inspector Agent Guide

On-device View inspection agent (`ViewLayoutInspector`), common utilities, and foreground-process-detection (`tools/base/dynamic-layout-inspector`). The on-device Compose inspection agent lives externally in AndroidX (`compose/ui/ui-inspection`, packaged inside `androidx.compose.ui:ui` < `1.5.0-beta01` and `androidx.compose.ui:ui-android` >= `1.5.0-beta01`).

## Code Review Invariants

Read and enforce `G1` and `G4` in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md).
