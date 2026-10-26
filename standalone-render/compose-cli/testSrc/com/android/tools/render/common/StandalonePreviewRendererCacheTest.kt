/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.render.common

import com.android.tools.render.compose.ComposeScreenshot
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StandalonePreviewRendererCacheTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Before
  fun setUp() {
    StandalonePreviewRenderer.closeWarmRenderer()
  }

  @After
  fun tearDown() {
    Thread.interrupted()
    StandalonePreviewRenderer.closeWarmRenderer()
  }

  private class FakeManagedRenderer(
    var disposed: Boolean = false,
    private val onRefresh: () -> Unit = {},
    private val onRender: (PreviewScreenshot, Boolean) -> List<PreviewScreenshotResult> = { screenshot, renderImage ->
      listOf(
        PreviewScreenshotResult(
          previewId = screenshot.previewId,
          methodFQN = screenshot.methodFQN,
          imagePath = if (renderImage) "pkg/${screenshot.previewId}_0.png" else "",
          error = null,
        )
      )
    },
  ) : ManagedRenderer {
    val refreshCalls = AtomicInteger(0)
    val closeCalls = AtomicInteger(0)
    val activeRenderers = AtomicInteger(0)
    val maxConcurrentRenders = AtomicInteger(0)

    override val isDisposed: Boolean
      get() = disposed

    override fun refreshClassPath(newClassPath: List<String>, newProjectClassPath: List<String>) {
      refreshCalls.incrementAndGet()
      onRefresh()
    }

    override fun render(
      screenshot: PreviewScreenshot,
      outputFolderPath: String,
      includeComponentTree: Boolean,
      renderImage: Boolean,
    ): List<PreviewScreenshotResult> {
      val current = activeRenderers.incrementAndGet()
      maxConcurrentRenders.updateAndGet { prev -> maxOf(prev, current) }
      try {
        return onRender(screenshot, renderImage)
      } finally {
        activeRenderers.decrementAndGet()
      }
    }

    override fun close() {
      closeCalls.incrementAndGet()
    }
  }

  @Test
  fun skipsRedundantRefreshOnInitialBootstrapAndRefreshesEverySubsequentWarmBatch() {
    val resApk = tempFolder.newFile("res.apk").apply { writeText("apk-v1") }
    val depJar = tempFolder.newFile("dep.jar").apply { writeText("dep-v1") }
    val projectClassesDir = tempFolder.newFolder("classes")
    File(projectClassesDir, "PreviewKt.class").writeText("bytecode-v1")

    val createdRenderers = mutableListOf<FakeManagedRenderer>()
    val factory: (RendererBootstrapRequest) -> ManagedRenderer = { FakeManagedRenderer().also { createdRenderers.add(it) } }

    fun newRenderer(extraProjectClassPath: List<String> = emptyList()) =
      StandalonePreviewRenderer(
        fontsPath = null,
        resourceApkPath = resApk.absolutePath,
        namespace = "com.example",
        classPath = listOf(projectClassesDir.absolutePath) + extraProjectClassPath + depJar.absolutePath,
        projectClassPath = listOf(projectClassesDir.absolutePath) + extraProjectClassPath,
        layoutlibPath = "/opt/layoutlib",
        resourceDirs = emptyList(),
        rClassJars = emptyList(),
        rendererFactory = factory,
      )

    val screenshot = ComposeScreenshot("com.example.PreviewKt.Card", emptyList(), emptyMap(), "Card")
    val outDir = tempFolder.newFolder("out").absolutePath

    // 1. Initial warm bootstrap already created a fresh project classloader, so no extra refreshClassPath is needed.
    assertThat(newRenderer().render(listOf(screenshot), outDir).globalError).isNull()
    assertThat(createdRenderers).hasSize(1)
    assertThat(createdRenderers[0].refreshCalls.get()).isEqualTo(0)

    // 2. Every subsequent warm batch refreshes the project classloader to pick up edits and isolate user static state.
    assertThat(newRenderer().render(listOf(screenshot), outDir).globalError).isNull()
    assertThat(createdRenderers).hasSize(1)
    assertThat(createdRenderers[0].refreshCalls.get()).isEqualTo(1)

    assertThat(newRenderer().render(listOf(screenshot), outDir).globalError).isNull()
    assertThat(createdRenderers).hasSize(1)
    assertThat(createdRenderers[0].refreshCalls.get()).isEqualTo(2)

    // 3. Adding another projectClassPath entry still reuses the warm Renderer and refreshes its project classloader.
    val extraClassesDir = tempFolder.newFolder("extra_classes").absolutePath
    assertThat(newRenderer(listOf(extraClassesDir)).render(listOf(screenshot), outDir).globalError).isNull()
    assertThat(createdRenderers).hasSize(1)
    assertThat(createdRenderers[0].refreshCalls.get()).isEqualTo(3)
  }

  @Test
  fun evictsAndClosesDisposedStaleOrFailedRefreshRendererAndPreservesWarmCacheDuringOneShotRender() {
    val resApk = tempFolder.newFile("res.apk").apply { writeText("apk-v1") }
    resApk.setLastModified(10_000L)
    val projectJar = tempFolder.newFile("project.jar").apply { writeText("v1") }
    var failRefresh = false
    val createdRenderers = mutableListOf<FakeManagedRenderer>()
    val factory: (RendererBootstrapRequest) -> ManagedRenderer = {
      FakeManagedRenderer(
          onRefresh = {
            if (failRefresh) {
              throw IllegalStateException("simulated refreshClassPath failure")
            }
          }
        )
        .also { createdRenderers.add(it) }
    }

    fun newRenderer() =
      StandalonePreviewRenderer(
        fontsPath = null,
        resourceApkPath = resApk.absolutePath,
        namespace = "com.example",
        classPath = listOf(projectJar.absolutePath),
        projectClassPath = listOf(projectJar.absolutePath),
        layoutlibPath = "/opt/layoutlib",
        resourceDirs = emptyList(),
        rClassJars = emptyList(),
        rendererFactory = factory,
      )

    val screenshot = ComposeScreenshot("com.example.PreviewKt.Card", emptyList(), emptyMap(), "Card")
    val outDir = tempFolder.newFolder("out_dispose").absolutePath

    newRenderer().render(listOf(screenshot), outDir)
    assertThat(createdRenderers).hasSize(1)

    // Cold one-shot render (reuseEnvironment = false) bootstraps and closes its own renderer without evicting warm renderer[0].
    assertThat(newRenderer().render(listOf(screenshot), outDir, reuseEnvironment = false).globalError).isNull()
    assertThat(createdRenderers).hasSize(2)
    assertThat(createdRenderers[0].closeCalls.get()).isEqualTo(0)
    assertThat(createdRenderers[1].closeCalls.get()).isEqualTo(1)

    // External project/module disposal: warm cache detects isDisposed, still calls close(), and re-bootstraps renderer[2].
    createdRenderers[0].disposed = true
    newRenderer().render(listOf(screenshot), outDir)
    assertThat(createdRenderers).hasSize(3)
    assertThat(createdRenderers[0].closeCalls.get()).isEqualTo(1)

    // refreshClassPath failure on the next warm batch evicts and closes renderer[2] so the following render bootstraps renderer[3].
    failRefresh = true
    val failedRefresh = newRenderer().render(listOf(screenshot), outDir)
    assertThat(failedRefresh.globalError).isNotNull()
    assertThat(createdRenderers[2].closeCalls.get()).isEqualTo(1)

    failRefresh = false
    assertThat(newRenderer().render(listOf(screenshot), outDir).globalError).isNull()
    assertThat(createdRenderers).hasSize(4)

    // Modifying res.apk timestamp invalidates WarmEnvironmentKey, closes renderer[3], and bootstraps renderer[4].
    resApk.setLastModified(30_000L)
    newRenderer().render(listOf(screenshot), outDir)
    assertThat(createdRenderers).hasSize(5)
    assertThat(createdRenderers[3].closeCalls.get()).isEqualTo(1)
  }

  @Test
  fun concurrentCacheMissBootstrapsOnceSerializesWarmRendersAndRefreshesSecondCaller() {
    val resApk = tempFolder.newFile("res.apk").apply { writeText("apk") }
    val bootstrapEntered = CountDownLatch(1)
    val releaseBootstrap = CountDownLatch(1)
    val bootstrapCount = AtomicInteger(0)
    val slowRenderEntered = CountDownLatch(1)
    val releaseSlowRender = CountDownLatch(1)
    val createdRenderers = CopyOnWriteArrayList<FakeManagedRenderer>()
    val factory: (RendererBootstrapRequest) -> ManagedRenderer = {
      bootstrapCount.incrementAndGet()
      bootstrapEntered.countDown()
      assertThat(releaseBootstrap.await(5, TimeUnit.SECONDS)).isTrue()
      FakeManagedRenderer(
          onRender = { screenshot, _ ->
            if (screenshot.previewId == "Slow") {
              slowRenderEntered.countDown()
              assertThat(releaseSlowRender.await(5, TimeUnit.SECONDS)).isTrue()
            }
            listOf(PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, "pkg/${screenshot.previewId}_0.png", null))
          }
        )
        .also { createdRenderers.add(it) }
    }

    val outDir = tempFolder.newFolder("out_concurrent").absolutePath
    val slow = ComposeScreenshot("com.example.PreviewKt.Slow", emptyList(), emptyMap(), "Slow")
    val fast = ComposeScreenshot("com.example.PreviewKt.Fast", emptyList(), emptyMap(), "Fast")

    val executor = Executors.newCachedThreadPool()
    try {
      // f1 blocks inside the bootstrap while holding the bootstrap lock.
      val f1 = executor.submit { assertThat(newFakeRenderer(resApk, factory).render(listOf(slow), outDir).globalError).isNull() }
      assertThat(bootstrapEntered.await(5, TimeUnit.SECONDS)).isTrue()

      // f2 misses the cache for the same environment and must queue on the bootstrap lock instead of bootstrapping again.
      val f2 = executor.submit { assertThat(newFakeRenderer(resApk, factory).render(listOf(fast), outDir).globalError).isNull() }
      awaitCondition("second caller to queue for the bootstrap lock") { StandalonePreviewRenderer.queuedBootstrapCallers == 1 }
      releaseBootstrap.countDown()

      // f2 reuses the handle bootstrapped by f1 and must wait for f1's render to finish before refreshing and rendering.
      assertThat(slowRenderEntered.await(5, TimeUnit.SECONDS)).isTrue()
      awaitCondition("second caller to queue for the render lock") { StandalonePreviewRenderer.queuedWarmRenderCallers == 1 }
      assertThat(createdRenderers.single().activeRenderers.get()).isEqualTo(1)
      releaseSlowRender.countDown()

      f1.get(5, TimeUnit.SECONDS)
      f2.get(5, TimeUnit.SECONDS)
      assertWithMessage("Concurrent cache miss for identical environment must bootstrap once").that(bootstrapCount.get()).isEqualTo(1)
      assertWithMessage("Concurrent warm renders on the same handle must be serialized")
        .that(createdRenderers.single().maxConcurrentRenders.get())
        .isEqualTo(1)
      assertWithMessage("Overlapping second caller must still refresh projectClassPath after waiting on renderLock")
        .that(createdRenderers.single().refreshCalls.get())
        .isEqualTo(1)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun queuedCallerRetriesOnFreshHandleAfterActiveHandleIsCorrupted() {
    val resApk = tempFolder.newFile("res.apk").apply { writeText("apk") }
    val corruptingEntered = CountDownLatch(1)
    val releaseCorrupting = CountDownLatch(1)
    val factoryCalls = AtomicInteger(0)
    // Records which bootstrap attempt rendered each preview.
    val renderedByAttempt = ConcurrentHashMap<String, Int>()
    val createdRenderers = CopyOnWriteArrayList<FakeManagedRenderer>()
    val factory: (RendererBootstrapRequest) -> ManagedRenderer = {
      val attempt = factoryCalls.incrementAndGet()
      FakeManagedRenderer(
          onRender = { screenshot, _ ->
            renderedByAttempt[screenshot.previewId] = attempt
            if (attempt == 1 && screenshot.previewId == "Corrupt") {
              corruptingEntered.countDown()
              assertThat(releaseCorrupting.await(5, TimeUnit.SECONDS)).isTrue()
              throw LinkageError("corrupted while queued caller waits on renderLock")
            }
            listOf(PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, "pkg/${screenshot.previewId}_0.png", null))
          }
        )
        .also { createdRenderers.add(it) }
    }

    val outDir = tempFolder.newFolder("out_retry").absolutePath
    val corrupt = ComposeScreenshot("com.example.PreviewKt.Corrupt", emptyList(), emptyMap(), "Corrupt")
    val fast = ComposeScreenshot("com.example.PreviewKt.Fast", emptyList(), emptyMap(), "Fast")

    val executor = Executors.newCachedThreadPool()
    try {
      val fCorrupt = executor.submit {
        assertThat(newFakeRenderer(resApk, factory).render(listOf(corrupt), outDir).globalError).isNotNull()
      }
      assertThat(corruptingEntered.await(5, TimeUnit.SECONDS)).isTrue()

      // fQueued retains the soon-to-be-corrupted handle and waits for its render lock.
      val fQueued = executor.submit { assertThat(newFakeRenderer(resApk, factory).render(listOf(fast), outDir).globalError).isNull() }
      awaitCondition("queued caller to wait on the corrupted handle's render lock") {
        StandalonePreviewRenderer.queuedWarmRenderCallers == 1
      }
      releaseCorrupting.countDown()
      fCorrupt.get(5, TimeUnit.SECONDS)
      fQueued.get(5, TimeUnit.SECONDS)

      // fQueued was waiting on the first handle but rendered on the second one, so it must have gone through the
      // "renderBatch returned null" retry path rather than failing or rendering on the corrupted handle.
      assertWithMessage("Queued caller must re-bootstrap after the active handle is corrupted").that(factoryCalls.get()).isEqualTo(2)
      assertThat(renderedByAttempt["Fast"]).isEqualTo(2)
      assertThat(createdRenderers[0].closeCalls.get()).isEqualTo(1)
      assertThat(createdRenderers[1].closeCalls.get()).isEqualTo(0)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun closeWarmRendererDoesNotBlockOnInFlightBootstrapAndEvictsFreshHandle() {
    val resApk = tempFolder.newFile("res.apk").apply { writeText("apk") }
    val bootstrapEntered = CountDownLatch(1)
    val releaseBootstrap = CountDownLatch(1)
    val createdRenderers = CopyOnWriteArrayList<FakeManagedRenderer>()
    val factory: (RendererBootstrapRequest) -> ManagedRenderer = {
      if (createdRenderers.isEmpty()) {
        bootstrapEntered.countDown()
        assertThat(releaseBootstrap.await(5, TimeUnit.SECONDS)).isTrue()
      }
      FakeManagedRenderer().also { createdRenderers.add(it) }
    }
    val outDir = tempFolder.newFolder("out_close").absolutePath
    val fast = ComposeScreenshot("com.example.PreviewKt.Fast", emptyList(), emptyMap(), "Fast")

    val executor = Executors.newCachedThreadPool()
    try {
      val f = executor.submit { assertThat(newFakeRenderer(resApk, factory).render(listOf(fast), outDir).globalError).isNull() }
      assertThat(bootstrapEntered.await(5, TimeUnit.SECONDS)).isTrue()

      // Must return immediately even while f holds the bootstrap lock inside rendererFactory.
      StandalonePreviewRenderer.closeWarmRenderer()
      releaseBootstrap.countDown()
      f.get(5, TimeUnit.SECONDS)
      assertWithMessage("In-flight renderer evicted by concurrent closeWarmRenderer must close after its render")
        .that(createdRenderers.single().closeCalls.get())
        .isEqualTo(1)

      // The evicted renderer was not published to the cache, so the next render bootstraps again.
      assertThat(newFakeRenderer(resApk, factory).render(listOf(fast), outDir).globalError).isNull()
      assertThat(createdRenderers).hasSize(2)
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun isolatesPerScreenshotExceptionsPropagatesCancellationAndEvictsWarmRendererOnLinkageError() {
    val resApk = tempFolder.newFile("res.apk").apply { writeText("apk") }
    var throwMode: String? = null
    val renderedIds = mutableListOf<String>()
    var lastFake: FakeManagedRenderer? = null
    val factory: (RendererBootstrapRequest) -> ManagedRenderer = {
      FakeManagedRenderer(
          onRender = { screenshot, renderImage ->
            renderedIds.add(screenshot.previewId)
            when {
              throwMode == "INTERRUPT" -> throw InterruptedException("render cancelled")
              throwMode == "CANCEL" -> throw CancellationException("render cancelled")
              throwMode == "LINKAGE" -> throw LinkageError("corrupted classloader")
              screenshot.previewId == "Broken" -> throw IllegalArgumentException("bad bytecode in Broken")
              else ->
                listOf(
                  PreviewScreenshotResult(
                    screenshot.previewId,
                    screenshot.methodFQN,
                    if (renderImage) "com/example/${screenshot.previewId}_0.png" else "",
                    null,
                  )
                )
            }
          }
        )
        .also { lastFake = it }
    }

    val outDir = tempFolder.newFolder("out_errors").absolutePath
    val broken = ComposeScreenshot("com.example.PreviewKt.Broken", emptyList(), emptyMap(), "Broken")
    val healthy = ComposeScreenshot("com.example.PreviewKt.Healthy", emptyList(), emptyMap(), "Healthy")

    // 1. Per-screenshot RuntimeException is isolated into a ScreenshotError without losing sibling results.
    val isolatedResult = newFakeRenderer(resApk, factory).render(listOf(broken, healthy), outDir)
    assertThat(isolatedResult.globalError).isNull()
    assertThat(isolatedResult.isFatalJvmError).isFalse()
    assertThat(isolatedResult.screenshotResults).hasSize(2)
    val byId = isolatedResult.screenshotResults.associateBy { it.previewId }
    assertThat(byId.getValue("Broken").error).isNotNull()
    assertThat(byId.getValue("Broken").error!!.message).contains("bad bytecode in Broken")
    assertThat(byId.getValue("Healthy").error).isNull()

    val firstFake = lastFake!!
    assertThat(firstFake.closeCalls.get()).isEqualTo(0)

    // 2. InterruptedException aborts the remaining screenshots in the batch and restores the thread's interrupt status.
    renderedIds.clear()
    throwMode = "INTERRUPT"
    val interruptedResult = newFakeRenderer(resApk, factory).render(listOf(broken, healthy), outDir)
    assertWithMessage("Thread interrupt status must be restored").that(Thread.interrupted()).isTrue()
    assertThat(interruptedResult.globalError).isNotNull()
    assertThat(interruptedResult.isFatalJvmError).isFalse()
    assertWithMessage("Batch must abort immediately on InterruptedException").that(renderedIds).containsExactly("Broken")

    // 3. CancellationException also aborts the batch instead of becoming a per-screenshot error.
    renderedIds.clear()
    throwMode = "CANCEL"
    val canceledResult = newFakeRenderer(resApk, factory).render(listOf(broken, healthy), outDir)
    assertThat(canceledResult.globalError).isNotNull()
    assertThat(canceledResult.isFatalJvmError).isFalse()
    assertWithMessage("Batch must abort immediately on CancellationException").that(renderedIds).containsExactly("Broken")
    assertThat(firstFake.closeCalls.get()).isEqualTo(0)

    // 4. LinkageError evicts and closes the offending warm renderer while returning globalError with isFatalJvmError == false.
    throwMode = "LINKAGE"
    val linkageResult = newFakeRenderer(resApk, factory).render(listOf(healthy), outDir)
    assertThat(linkageResult.globalError).contains("LinkageError")
    assertThat(linkageResult.isFatalJvmError).isFalse()
    assertThat(firstFake.closeCalls.get()).isEqualTo(1)
  }

  @Test
  fun distinguishesRecoverableStackOverflowFromFatalVirtualMachineErrorsWithoutThrowing() {
    val resApk = tempFolder.newFile("res.apk").apply { writeText("apk") }
    var renderError: Error? = null
    val createdRenderers = mutableListOf<FakeManagedRenderer>()
    val factory: (RendererBootstrapRequest) -> ManagedRenderer = {
      FakeManagedRenderer(
          onRender = { screenshot, _ ->
            renderError?.let { throw it }
            listOf(PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, "pkg/${screenshot.previewId}_0.png", null))
          }
        )
        .also { createdRenderers.add(it) }
    }
    val outDir = tempFolder.newFolder("out_vm_errors").absolutePath
    val healthy = ComposeScreenshot("com.example.PreviewKt.Healthy", emptyList(), emptyMap(), "Healthy")

    // 1. StackOverflowError (e.g. a preview with unbounded recursion) closes the warm renderer and is non-fatal to the JVM.
    renderError = StackOverflowError("simulated unbounded recursion")
    val stackOverflowResult = newFakeRenderer(resApk, factory).render(listOf(healthy), outDir)
    assertThat(stackOverflowResult.globalError).contains("StackOverflowError")
    assertThat(stackOverflowResult.isFatalJvmError).isFalse()
    assertThat(stackOverflowResult.fatalJvmError).isNull()
    assertThat(createdRenderers.single().closeCalls.get()).isEqualTo(1)

    // The next request bootstraps a fresh renderer.
    renderError = null
    val recoveredResult = newFakeRenderer(resApk, factory).render(listOf(healthy), outDir)
    assertThat(recoveredResult.globalError).isNull()
    assertThat(recoveredResult.isFatalJvmError).isFalse()
    assertThat(createdRenderers).hasSize(2)

    // 2. OutOfMemoryError closes the warm renderer and returns a non-throwing result flagged as a fatal JVM error.
    renderError = OutOfMemoryError("simulated OOM")
    val oomResult = newFakeRenderer(resApk, factory).render(listOf(healthy), outDir)
    assertThat(oomResult.globalError).contains("OutOfMemoryError")
    assertThat(oomResult.isFatalJvmError).isTrue()
    assertThat(oomResult.fatalJvmError).isEqualTo("OutOfMemoryError")
    assertThat(createdRenderers[1].closeCalls.get()).isEqualTo(1)

    // 3. The same contract applies to Errors thrown while bootstrapping, before a warm handle exists, and in one-shot mode.
    val failingBootstrapFactory: (RendererBootstrapRequest) -> ManagedRenderer = { throw renderError!! }
    renderError = StackOverflowError("simulated bootstrap recursion")
    val bootstrapStackOverflow = newFakeRenderer(resApk, failingBootstrapFactory).render(listOf(healthy), outDir)
    assertThat(bootstrapStackOverflow.globalError).contains("StackOverflowError")
    assertThat(bootstrapStackOverflow.isFatalJvmError).isFalse()

    renderError = OutOfMemoryError("simulated bootstrap OOM")
    val bootstrapOom = newFakeRenderer(resApk, failingBootstrapFactory).render(listOf(healthy), outDir)
    assertThat(bootstrapOom.globalError).contains("OutOfMemoryError")
    assertThat(bootstrapOom.isFatalJvmError).isTrue()
    assertThat(bootstrapOom.fatalJvmError).isEqualTo("OutOfMemoryError")

    val oneShotOom = newFakeRenderer(resApk, failingBootstrapFactory).render(listOf(healthy), outDir, reuseEnvironment = false)
    assertThat(oneShotOom.globalError).contains("OutOfMemoryError")
    assertThat(oneShotOom.isFatalJvmError).isTrue()
    assertThat(oneShotOom.fatalJvmError).isEqualTo("OutOfMemoryError")
  }

  private fun newFakeRenderer(resApk: File, factory: (RendererBootstrapRequest) -> ManagedRenderer) =
    StandalonePreviewRenderer(
      fontsPath = null,
      resourceApkPath = resApk.absolutePath,
      namespace = "com.example",
      classPath = emptyList(),
      projectClassPath = emptyList(),
      layoutlibPath = "/opt/layoutlib",
      resourceDirs = emptyList(),
      rClassJars = emptyList(),
      rendererFactory = factory,
    )

  /** Waits until [condition] holds, failing after a bounded timeout instead of relying on fixed sleeps. */
  private fun awaitCondition(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
      assertWithMessage("Timed out waiting for $description").that(System.nanoTime() - deadline <= 0).isTrue()
      Thread.onSpinWait()
    }
  }
}
