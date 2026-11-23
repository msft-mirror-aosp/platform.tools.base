# Standalone Render & Guest Runtime Agent Guide

Guidance and code review invariants for `tools/base/standalone-render` and
sibling UI Tools guest, bytecode, rendering, screenshot-testing, and inspector
modules in `platform/tools/base`.

## Running Tests

```bash
# Fast unit tests:
tools/base/bazel/bazel test //tools/base/standalone-render/lib:tools.standalone-render.lib.tests

# Full suite excluding eternal PerfGate benchmarks:
tools/base/bazel/bazel test --test_tag_filters=-ci:perfgate-linux //tools/base/standalone-render/...
```

## Code Review Invariants (UI Tools Guest & Standalone Runtime)

Enforce these diff-scoped invariants on added or modified (`+`) lines across UI
Tools `tools/base` modules (`standalone-render`, `multipreview-asm`, `uicheck`,
`visual-lint`, `preview`, `layoutlib-api`, `layoutlib-extensions`,
`dynamic-layout-inspector`, `ui-inspector`, `aspects_agent`, `draw9patch`,
`ninepatch`, and `vector-drawable-tool`), applying each rule to the modules in
its documented scope:

### G1. Host IDE Decoupling & Standalone Runtime Boundary (All Covered Modules)
- **Rule:** Keep guest rendering, bytecode scanning, asset utility, and device
  inspection source code and standalone build targets strictly decoupled from
  the full Android Studio IDE:
  - Production source files and standalone `tools.*` / `maven_library` / agent
    targets in pure guest/bytecode/utility/inspector modules (`aspects_agent`,
    `draw9patch`, `dynamic-layout-inspector`, `layoutlib-api`,
    `layoutlib-extensions`, `multipreview-asm`, `ninepatch`, `preview`,
    `ui-inspector`, `uicheck/common`, `uicheck/validators`,
    `vector-drawable-tool`, `visual-lint`) must
    **never** import IntelliJ SDK (`com.intellij.*`) or Android Studio IDE
    plugin (`com.android.tools.idea.*`) classes, or add new
    `@intellij//:intellij-sdk` / IDE-plugin target dependencies outside the
    carve-outs below.
  - `standalone-render` and `uicheck/config` (via `standalone-render/lib`) may
    link headless `//prebuilts/tools/common/lint-psi/intellij-core`
    (`Disposable`, `MockProject`, `MockApplication`) and dual-targeted `tools.*`
    rendering libraries, but must **never** add dependencies on full
    `@intellij//:intellij-sdk` GUI classes or `intellij.android.*` IDE plugin
    targets.
- **Why:** Pulling IDE plugin or Swing/IntelliJ GUI classes into standalone
  `tools/base` libraries breaks headless CLI rendering (`android preview`,
  screenshot testing, CI UI Check) and on-device inspection agents.
- **Carve-outs & exceptions:**
  - Non-IDE packages that share the `com.android.tools.idea.*` prefix are
    allowed where already used: Layoutlib/ATF packages
    (`com.android.tools.idea.layoutlib.*`, `com.android.tools.idea.validator.*`)
    and shaded/inspector protos (`com.android.tools.idea.protobuf.*`,
    `com.android.tools.idea.layoutinspector.*`).
  - Existing `iml_module` IDE wrapper targets in `BUILD` files (such as in
    `draw9patch`, `layoutlib-api`, `layoutlib-extensions`, `multipreview-asm`,
    `ninepatch`, and `visual-lint`) may continue declaring
    `@intellij//:intellij-sdk` alongside their standalone `tools.*` /
    `maven_library` targets, and `dynamic-layout-inspector/common/BUILD`
    defines only `iml_module(name = "studio.dynamic-layout-inspector.common")`
    (depended on and bundled by `dynamic-layout-inspector/agent/appinspection/BUILD`),
    provided production source files remain free of `com.intellij.*` and
    IDE-plugin imports.
  - IDE-specific Swing EDT, `AnAction`, and IntelliJ `Project`/`PsiElement`
    rules in base `skills/studio/SKILL.md` do not apply to these headless or
    on-device modules (Swing EDT rules apply only to desktop UI classes in
    `draw9patch` and the `-d` Swing viewer in `vector-drawable-tool`'s
    `VdCommandLineTool.java`).

### G2. `ModuleClassLoader` & `Layoutlib Render Thread` Isolation (Layoutlib & Rendering Modules)
- **Scope:** `standalone-render`, `layoutlib-api`, `layoutlib-extensions`.
- **Rule:** Cross-classloader calls between host renderer code and untrusted
  user/guest preview classes inside `ModuleClassLoader` must use reflection
  (`ViewLoader`, `ComposeViewAdapter` / AndroidX `ComposableInvoker` bridges),
  ASM bytecode transforms, or primitive/JDK bridge contracts—never direct casts
  across classloaders or uncleaned `ThreadLocal` state on the
  `Layoutlib Render Thread` (which pins reloaded `ModuleClassLoader`s). Never
  call `runBlocking` inside `ComposeViewAdapter`, `RenderTask` callbacks, or
  `Layoutlib Render Thread` execution paths. Note that `ComposeViewAdapter`
  internal Kotlin methods (such as `dispose` / `dispose$<module>`) are name-
  mangled and require prefix lookup + `isAccessible = true` (see
  `ComposeViewAdapters.kt`).
- **Anti-pattern:**
  ```kotlin
  fun disposeAdapter(adapter: Any) {
    runBlocking { // Deadlocks single-threaded Layoutlib Render Thread!
      val view = adapter as ComposeViewAdapter // ClassCastException across ModuleClassLoader!
      view.dispose()
    }
  }
  ```
- **Preferred pattern:**
  ```kotlin
  // Match Kotlin-mangled ComposeViewAdapter methods (e.g. dispose / dispose$<module>) reflectively:
  val disposeMethod =
    adapterInstance.javaClass.methods
      .firstOrNull { (it.name == "dispose" || it.name.startsWith("dispose$")) && it.parameterCount == 0 }
      ?.apply { isAccessible = true }
  disposeMethod?.invoke(adapterInstance)
  ```
- **Exceptions:** Top-level standalone CLI `main()` entry points outside the
  `Layoutlib Render Thread` may bridge to coroutines at process boundaries.

### G3. `RenderTask`, `RenderResult` & `Graphics2D` Disposal, Cache Keys, and Multiplatform `@Preview`
- **Scope:** `standalone-render`, `layoutlib-api`, `multipreview-asm`,
  `draw9patch`, `ninepatch` (plus `BufferedImage` buffer isolation only in
  `uicheck` and `visual-lint`; see `../preview/AGENTS.md` for
  `preview/screenshot`'s `Renderer.close()` and classloader invariants).
- **Rule:** Dispose every `RenderTask` (`task.disposeAsync()`), every returned
  `RenderResult` via `Disposer.dispose(result)` (never raw `result.dispose()`,
  which bypasses child `Disposable` cleanup such as `releaseRender(...)`
  registered via `Disposer.register(result, ...)` in `Renderer.kt`), every
  standalone render session (`Renderer.close()`), and every `Graphics2D` from
  `BufferedImage.createGraphics()` (`g2d.dispose()`) in `finally` /
  `whenComplete` blocks across success, timeout, cancellation, and exception
  paths. Key warm environment and classloader caches (`WarmEnvironmentKey`,
  `WarmDepsKey`, `LibraryClassBinaryCache`) on all relevant environment,
  resource, and classpath paths, modification timestamps, parent classloader
  identity, and project `R`-class signatures (while keeping user
  `projectClassPath` out of `WarmEnvironmentKey` so warm batches reload via
  `Renderer.refreshClassPath(...)`), and defensively isolate mutable
  `ByteArray` / `BufferedImage` buffers shared across callers. In `@Preview` /
  `@PreviewParameter` bytecode/ASM discovery (`multipreview-asm`:
  `PreviewMethodFinder`, `MultipreviewAnnotationResolver`), support both
  `androidx.compose.ui.tooling.preview.*` and
  `org.jetbrains.compose.ui.tooling.preview.*` (`commonMain` / KMP) annotations
  unless explicitly Android-only.
- **Exceptions & warm-renderer lifecycle contracts:**
  - In `standalone-render`, `Renderer.releaseRender(...)` intentionally defers
    `renderTask.disposeAsync()` into `pendingDisposeTasks` (draining in
    `disposeCompletedRenders()`, `refreshClassPath()`, or `Renderer.close()`)
    rather than calling `disposeAsync()` eagerly per preview (which would run
    `RenderSessionCleaner.dispose` and wipe static state in the shared
    `DefaultModuleClassLoader`). Do not replace this deferred drain with eager
    per-render disposal.
  - `StandaloneRenderModelModule.dispose()` is intentionally a no-op because
    `RenderTask.disposeAsync()` calls `getModule().dispose()` on shared warm
    modules; preserve `Renderer.close()` calling `module.close()` before
    `Disposer.dispose(project)` (and `Main.kt` closing the warm `Renderer`
    before `Disposer.dispose(IJFramework)`).
  - Shared `RenderTask`s / `RenderResult`s whose ownership is transferred to a
    `Disposable` container that guarantees `task.disposeAsync()` /
    `Disposer.dispose(result)` across error and teardown paths.
  - Android-only `PreviewDiscoveryEngine` in `standalone-render` (intentionally
    AndroidX-only), asset/bridge libraries (`layoutlib-api`, `draw9patch`,
    `ninepatch`), and post-render analyzers (`uicheck`, `visual-lint`) do not
    process KMP `@Composable` previews (and `uicheck` / `visual-lint` do not
    manage `RenderTask`, `RenderResult`, or `Graphics2D`).

### G4. Deterministic Guest, Bytecode & Inspector Regression Testing (All Covered Modules)
- **Rule:** Accompany bug fixes (`Bug:` / `Fixes:`) and behavioral changes in
  standalone rendering, `multipreview-asm`, `aspects_agent`, `preview`,
  `uicheck`, `visual-lint`, or inspector protocols with targeted, deterministic
  unit tests when a testable seam exists. Avoid fragile mocks of internal
  Compose runtime structures (`LayoutNode`, `CompositionData`, slot tables) and
  avoid `Thread.sleep` where virtual coroutine time (`runTest`,
  `advanceUntilIdle`) or explicit synchronization primitives work.
- **Exceptions:**
  - Docs/OWNERS/config-only CLs or environment-specific device bugs whose commit
    message documents why a unit test is infeasible.
  - Real-socket I/O timeout tests (where `runTest` virtual clocks break real
    socket timeouts, requiring `runBlocking`, as in `ui-inspector`'s
    `CommandSenderTest.kt`) and JVM thread/concurrency tests that require real
    wall-clock scheduling.
