# Preview Screenshot Testing Agent Guide

Compose and Wear Tiles Preview screenshot testing Gradle plugin, validation API, and JUnit engine (`tools/base/preview/screenshot`). Shared `@Preview` descriptors live in `tools/adt/idea/preview-elements` and ASM discovery lives in `tools/base/multipreview-asm`.

## Code Review Invariants

Read and enforce `G1` (host IDE decoupling) and `G4` (deterministic testing) in
[../standalone-render/AGENTS.md](../standalone-render/AGENTS.md). In addition,
enforce these `preview/screenshot` invariants on added or modified (`+`) lines:

- **`ResourceEnhancedClassLoader` & `Renderer.close()` lifecycle:** Keep
  objects crossing the `ResourceEnhancedClassLoader` boundary in `Renderer.kt`
  strictly `Serializable` (resolved via `ObjectOutputStream` /
  `ObjectInputStream`—never direct casts across loaders), and close `Renderer`
  (`Closeable.close()` / `.use { ... }`) on all exit paths so classloaders and
  resources are released.
- **AGP `compileOnly` reflection safety:** Guard reflective access to internal
  AGP APIs in `PreviewScreenshotGradlePlugin.kt` (`reflectionCache`) with
  clear failure handling and verify compatibility across AGP versions.
- **Report safety (`maxParallelForks = 1`):** Preserve the
  `maxParallelForks = 1` guard in `PreviewScreenshotValidationTask.kt` unless
  XML report generation is made concurrency-safe across worker JVMs.
