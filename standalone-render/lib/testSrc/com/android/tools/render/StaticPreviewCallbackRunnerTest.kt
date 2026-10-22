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

package com.android.tools.render

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRenderResult(val id: String, val success: Boolean = true, var disposed: Boolean = false)

private class FakePreviewRenderSession(
  var composeClassLoader: ClassLoader? = FakePreviewRenderSession::class.java.classLoader,
  var onInflate: () -> FakeRenderResult? = { FakeRenderResult("inflated") },
  var onPrepareDispatcher: (ClassLoader) -> CompletableFuture<Void?> = { CompletableFuture.completedFuture(null) },
  var onExecuteCallbacks: (Long) -> CompletableFuture<Boolean> = { CompletableFuture.completedFuture(false) },
  var onRender: (Int) -> FakeRenderResult = { round -> FakeRenderResult("render_$round") },
  var onCheckPendingWork: (FakeRenderResult, ClassLoader?) -> CompletableFuture<Boolean> = { _, _ ->
    CompletableFuture.completedFuture(false)
  },
  var onRunAsyncOnRenderThread: (Runnable) -> CompletableFuture<Void?> = { action ->
    action.run()
    CompletableFuture.completedFuture(null)
  },
) : PreviewRenderSession<FakeRenderResult> {
  val executedSessionTimesNanos = mutableListOf<Long>()
  val renderedRounds = mutableListOf<Int>()
  val disposedResults = mutableListOf<FakeRenderResult>()
  val errors = mutableListOf<Pair<String, Throwable?>>()
  var recordedElapsedFrameTimeNanos: Long = -1L
  private var renderCount = 0

  override fun inflate(): FakeRenderResult? = onInflate()

  override fun isSuccess(result: FakeRenderResult): Boolean = result.success

  override fun disposeResult(result: FakeRenderResult) {
    result.disposed = true
    disposedResults.add(result)
  }

  override fun composeClassLoader(result: FakeRenderResult): ClassLoader? = composeClassLoader

  override fun setElapsedFrameTimeNanos(nanos: Long) {
    recordedElapsedFrameTimeNanos = nanos
  }

  override fun prepareComposeUiDispatcher(composeClassLoader: ClassLoader): CompletableFuture<Void?> =
    onPrepareDispatcher(composeClassLoader)

  override fun executeCallbacks(sessionTimeNanos: Long): CompletableFuture<Boolean> {
    executedSessionTimesNanos.add(sessionTimeNanos)
    return onExecuteCallbacks(sessionTimeNanos)
  }

  override fun render(): FakeRenderResult {
    val round = renderCount++
    renderedRounds.add(round)
    return onRender(round)
  }

  override fun checkPendingWorkOnRenderThread(result: FakeRenderResult, composeClassLoader: ClassLoader?): CompletableFuture<Boolean> =
    onCheckPendingWork(result, composeClassLoader)

  override fun runAsyncOnRenderThread(action: Runnable): CompletableFuture<Void?> = onRunAsyncOnRenderThread(action)

  override fun <T> callAsyncOnRenderThread(action: Callable<T>): CompletableFuture<T> = CompletableFuture.completedFuture(action.call())

  override fun reportError(message: String, throwable: Throwable?) {
    errors.add(message to throwable)
  }

  override fun createErrorResult(throwable: Throwable?): FakeRenderResult = FakeRenderResult("error", success = false)
}

private class FakeHandlerMessageQueue {
  var notEmpty: Boolean = false
  var failNextIsNotEmpty: Boolean = false

  fun isNotEmpty(): Boolean {
    if (failNextIsNotEmpty) {
      failNextIsNotEmpty = false
      throw IllegalStateException("Simulated transient queue inspection failure")
    }
    return notEmpty
  }
}

private class FakeChoreographerCallbacks {
  @JvmField val mCallbacks: MutableList<Any> = mutableListOf()
}

private class FakeSessionInteractiveData(
  val handlerQueue: FakeHandlerMessageQueue = FakeHandlerMessageQueue(),
  val choreographerCallbacks: FakeChoreographerCallbacks = FakeChoreographerCallbacks(),
) {
  fun getHandlerMessageQueue(): FakeHandlerMessageQueue = handlerQueue
}

private class FakeBridgeContext(val interactiveData: FakeSessionInteractiveData? = FakeSessionInteractiveData()) {
  fun getSessionInteractiveData(): FakeSessionInteractiveData? = interactiveData
}

private class FakeContextWrapper(private val baseContext: Any?) {
  fun getBaseContext(): Any? = baseContext
}

private class FakeView(private val context: Any?) {
  fun getContext(): Any? = context
}

private class BrokenFakeBridgeContext

private class AdapterWithPublicDispose {
  var disposedCount = 0

  fun dispose() {
    disposedCount++
  }

  fun isDisposed(): Boolean = false

  fun disposeOnDetach() {
    throw AssertionError("disposeOnDetach should not be matched")
  }
}

private class AdapterWithInternalMangledDispose {
  var disposedCount = 0

  @Suppress("FunctionName")
  fun `dispose$ui_tooling`() {
    disposedCount++
  }

  fun isDisposed(): Boolean = false
}

private class AdapterWithOnlyMisleadingDisposeHelpers {
  fun isDisposed(): Boolean = false

  fun disposeOnDetach() {}

  fun disposeWithArg(@Suppress("UNUSED_PARAMETER") flag: Boolean) {}
}

class StaticPreviewCallbackRunnerTest {

  @Test
  fun settlesInOneRoundWhenNoPendingWorkRemainsAndAdvancesElapsedFrameTimeAcrossPreviews() {
    val runner = StaticPreviewCallbackRunner()
    val session1 = FakePreviewRenderSession()
    val seenResults = mutableListOf<FakeRenderResult>()

    val finalResult = runner.renderExecutingCallbacks(session1, "pkg.PreviewKt.Simple", seenResults::add)

    assertEquals("render_0", finalResult.id)
    assertFalse(finalResult.disposed)
    assertEquals(listOf(CALLBACK_TIME_STEP_NANOS), session1.executedSessionTimesNanos)
    assertEquals(listOf(0), session1.renderedRounds)
    assertEquals(listOf("inflated"), session1.disposedResults.map { it.id })
    assertEquals(listOf("inflated", "render_0"), seenResults.map { it.id })
    assertTrue(session1.errors.isEmpty())
    assertTrue(session1.recordedElapsedFrameTimeNanos >= INITIAL_ELAPSED_FRAME_TIME_NANOS)

    val session2 = FakePreviewRenderSession()
    runner.renderExecutingCallbacks(session2, "pkg.PreviewKt.Second") {}
    assertEquals(session1.recordedElapsedFrameTimeNanos + CALLBACK_TIME_STEP_NANOS, session2.recordedElapsedFrameTimeNanos)
  }

  @Test
  fun runsSecondRoundWhenFirstRenderQueuesPendingWork() {
    val runner = StaticPreviewCallbackRunner()
    var pendingAfterFirstRender = true
    val session =
      FakePreviewRenderSession(
        onCheckPendingWork = { result, _ ->
          val pending =
            if (result.id == "render_0" && pendingAfterFirstRender) {
              pendingAfterFirstRender = false
              true
            } else {
              false
            }
          CompletableFuture.completedFuture(pending)
        }
      )

    val finalResult = runner.renderExecutingCallbacks(session, "pkg.PreviewKt.TwoRound") {}

    assertEquals("render_1", finalResult.id)
    assertFalse(finalResult.disposed)
    assertEquals(
      listOf(CALLBACK_TIME_STEP_NANOS, CALLBACK_TIME_STEP_NANOS * 2),
      session.executedSessionTimesNanos,
    )
    assertEquals(listOf(0, 1), session.renderedRounds)
    assertEquals(listOf("inflated", "render_0"), session.disposedResults.map { it.id })
    assertTrue(session.errors.isEmpty())
  }

  @Test
  fun capsInfiniteCallbacksAtMaxExecutionsPerRoundAndMaxRounds() {
    val runner = StaticPreviewCallbackRunner()
    val session = FakePreviewRenderSession(onExecuteCallbacks = { CompletableFuture.completedFuture(true) })

    val finalResult = runner.renderExecutingCallbacks(session, "pkg.PreviewKt.Infinite") {}

    assertEquals("render_1", finalResult.id)
    val expectedSteps = CALLBACK_EXECUTIONS_PER_ROUND * CALLBACK_ROUNDS
    assertEquals(expectedSteps, session.executedSessionTimesNanos.size)
    assertEquals(expectedSteps * CALLBACK_TIME_STEP_NANOS, session.executedSessionTimesNanos.last())
    assertEquals(listOf(0, 1), session.renderedRounds)
    assertTrue(session.errors.isEmpty())
  }

  @Test
  fun inflateReturningNullOrUnsuccessfulResultSkipsCallbacksAndRender() {
    val runner = StaticPreviewCallbackRunner()

    val nullInflateSeen = mutableListOf<FakeRenderResult>()
    val nullInflateSession = FakePreviewRenderSession(onInflate = { null })
    val nullResult = runner.renderExecutingCallbacks(nullInflateSession, "pkg.PreviewKt.NullInflate", nullInflateSeen::add)
    assertEquals("error", nullResult.id)
    assertFalse(nullResult.success)
    assertTrue(nullInflateSeen.isEmpty())
    assertTrue(nullInflateSession.executedSessionTimesNanos.isEmpty())
    assertTrue(nullInflateSession.renderedRounds.isEmpty())

    val failedInflateSeen = mutableListOf<FakeRenderResult>()
    val failedInflateSession = FakePreviewRenderSession(onInflate = { FakeRenderResult("failed_inflate", success = false) })
    val failedInflateResult = runner.renderExecutingCallbacks(failedInflateSession, "pkg.PreviewKt.FailedInflate", failedInflateSeen::add)
    assertEquals("failed_inflate", failedInflateResult.id)
    assertFalse(failedInflateResult.disposed)
    assertEquals(listOf("failed_inflate"), failedInflateSeen.map { it.id })
    assertTrue(failedInflateSession.executedSessionTimesNanos.isEmpty())
    assertTrue(failedInflateSession.renderedRounds.isEmpty())
  }

  @Test
  fun executeCallbacksFailureOrCancellationReportsErrorAndSkipsRender() {
    val runner = StaticPreviewCallbackRunner()

    val executionFailureSession =
      FakePreviewRenderSession(
        onExecuteCallbacks = { CompletableFuture.failedFuture(IllegalStateException("Simulated callback execution failure")) }
      )
    val execFailResult = runner.renderExecutingCallbacks(executionFailureSession, "pkg.PreviewKt.ExecFail") {}
    assertEquals("inflated", execFailResult.id)
    assertTrue(executionFailureSession.renderedRounds.isEmpty())
    assertEquals(1, executionFailureSession.errors.size)
    assertThat(executionFailureSession.errors.single().first).contains("Simulated callback execution failure")

    val cancelledFuture = CompletableFuture<Boolean>().apply { cancel(false) }
    val cancellationSession = FakePreviewRenderSession(onExecuteCallbacks = { cancelledFuture })
    val cancelResult = runner.renderExecutingCallbacks(cancellationSession, "pkg.PreviewKt.ExecCancelled") {}
    assertEquals("inflated", cancelResult.id)
    assertTrue(cancellationSession.renderedRounds.isEmpty())
    assertEquals(1, cancellationSession.errors.size)
    assertThat(cancellationSession.errors.single().second).isInstanceOf(CancellationException::class.java)
  }

  @Test
  fun uncaughtExceptionDuringCallbackStepStopsSteppingImmediatelyAndSkipsRender() {
    val runner = StaticPreviewCallbackRunner()
    val renderThread = Thread.currentThread()
    val session =
      FakePreviewRenderSession(
        onExecuteCallbacks = {
          // Simulate a LaunchedEffect throwing on the render thread during step 1 while other callbacks remain queued.
          renderThread.uncaughtExceptionHandler.uncaughtException(renderThread, IllegalArgumentException("Boom in LaunchedEffect"))
          CompletableFuture.completedFuture(true)
        }
      )

    val result = runner.renderExecutingCallbacks(session, "pkg.PreviewKt.UncaughtInCallback") {}

    assertEquals("inflated", result.id)
    assertEquals("Stepping must stop immediately after an uncaught exception", 1, session.executedSessionTimesNanos.size)
    assertTrue("render() must be skipped when a callback step throws an uncaught exception", session.renderedRounds.isEmpty())
    assertEquals(1, session.errors.size)
    assertThat(session.errors.single().first).contains("Boom in LaunchedEffect")
  }

  @Test
  fun prepareComposeUiDispatcherTimeoutReportsErrorAndSkipsCallbacksAndRender() {
    var fakeNowNanos = 0L
    val runner =
      StaticPreviewCallbackRunner(
        callbacksTimeoutMs = 500L,
        nanoTime = { fakeNowNanos },
      )
    val hungPrepareFuture = CompletableFuture<Void?>()
    val session =
      FakePreviewRenderSession(
        onPrepareDispatcher = {
          fakeNowNanos += TimeUnit.MILLISECONDS.toNanos(600L)
          hungPrepareFuture
        }
      )

    val seenBeforeCallbacks = mutableListOf<String>()
    val result = runner.renderExecutingCallbacks(session, "pkg.PreviewKt.SlowPrepare") { seenBeforeCallbacks.add(it.id) }

    assertEquals("inflated", result.id)
    assertEquals(listOf("inflated"), seenBeforeCallbacks)
    assertTrue(hungPrepareFuture.isCancelled)
    assertTrue(session.executedSessionTimesNanos.isEmpty())
    assertTrue(session.renderedRounds.isEmpty())
    assertEquals(1, session.errors.size)
    assertThat(session.errors.single().first).contains("did not complete within 500ms")
  }

  @Test
  fun recordsInflatedResultBeforeCallbacksAndDisposesItWhenRenderThrows() {
    val runner = StaticPreviewCallbackRunner()
    val seenResults = mutableListOf<FakeRenderResult>()
    var seenBeforeExecuteCallbacks = false
    val session =
      FakePreviewRenderSession(
        onExecuteCallbacks = {
          seenBeforeExecuteCallbacks = seenResults.map { it.id } == listOf("inflated")
          CompletableFuture.completedFuture(false)
        },
        onRender = { throw IllegalStateException("Simulated render failure") },
      )

    val thrown = runCatching {
      runner.renderExecutingCallbacks(session, "pkg.PreviewKt.ThrowingRender", seenResults::add)
    }
      .exceptionOrNull()

    assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    assertTrue(seenBeforeExecuteCallbacks)
    assertEquals(listOf("inflated"), seenResults.map { it.id })
    assertEquals(listOf("inflated"), session.disposedResults.map { it.id })
    assertTrue(seenResults.single().disposed)
  }

  @Test
  fun disposesEachIntermediateResultExactlyOnceWhenSecondRoundRenderThrows() {
    val runner = StaticPreviewCallbackRunner()
    val seenResults = mutableListOf<FakeRenderResult>()
    val session =
      FakePreviewRenderSession(
        onExecuteCallbacks = { CompletableFuture.completedFuture(true) },
        onRender = { round ->
          if (round == 0) {
            FakeRenderResult("render_0")
          } else {
            throw IllegalStateException("Simulated Round 1 render failure")
          }
        },
      )

    val thrown = runCatching {
      runner.renderExecutingCallbacks(session, "pkg.PreviewKt.SecondRoundThrow", seenResults::add)
    }
      .exceptionOrNull()

    assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    assertEquals(listOf("inflated", "render_0"), seenResults.map { it.id })
    // "inflated" is disposed after Round 0 render succeeds; "render_0" is disposed in the outer catch when Round 1 render throws.
    assertEquals(listOf("inflated", "render_0"), session.disposedResults.map { it.id })
  }

  @Test
  fun excludesRenderDurationFromCallbacksTimeoutBudget() {
    var fakeNowNanos = 0L
    val runner =
      StaticPreviewCallbackRunner(
        callbacksTimeoutMs = 1_000L,
        nanoTime = { fakeNowNanos },
      )
    var round1RenderPending = true
    val session =
      FakePreviewRenderSession(
        onExecuteCallbacks = {
          // Each callback step takes 300ms of fake clock time (600ms total across 2 rounds < 1,000ms budget).
          fakeNowNanos += TimeUnit.MILLISECONDS.toNanos(300L)
          CompletableFuture.completedFuture(false)
        },
        onRender = { round ->
          // Round 0 render() takes 60s of fake clock time, which must be excluded from the 1,000ms callback budget.
          fakeNowNanos += TimeUnit.SECONDS.toNanos(60L)
          FakeRenderResult("render_$round")
        },
        onCheckPendingWork = { result, _ ->
          val pending =
            if (result.id == "render_0" && round1RenderPending) {
              round1RenderPending = false
              true
            } else {
              false
            }
          CompletableFuture.completedFuture(pending)
        },
      )

    val finalResult = runner.renderExecutingCallbacks(session, "pkg.PreviewKt.SlowDraw") {}

    assertEquals("render_1", finalResult.id)
    assertEquals(listOf(0, 1), session.renderedRounds)
    assertTrue(session.errors.isEmpty())
  }

  @Test
  fun waitsForFinalTimedOutStepBeforeFinishingRound() {
    var fakeNowNanos = 0L
    val runner =
      StaticPreviewCallbackRunner(
        callbacksTimeoutMs = 1_000L,
        nanoTime = { fakeNowNanos },
      )
    val hungDrainCheck = CompletableFuture<Boolean>()
    val session =
      FakePreviewRenderSession(
        onExecuteCallbacks = {
          // Every step fails with RenderTask's 500ms per-step TimeoutException after 80ms of fake wall time (800ms total for 10 steps).
          fakeNowNanos += TimeUnit.MILLISECONDS.toNanos(80L)
          CompletableFuture.failedFuture(TimeoutException("RenderTask 500ms step timeout"))
        },
        onCheckPendingWork = { _, _ -> hungDrainCheck },
      )

    val result = runner.renderExecutingCallbacks(session, "pkg.PreviewKt.BusyNonInterruptible") {}

    assertEquals("inflated", result.id)
    assertTrue(hungDrainCheck.isCancelled)
    assertTrue(session.renderedRounds.isEmpty())
    assertEquals(1, session.errors.size)
    assertThat(session.errors.single().first).contains("did not complete within 1000ms")
  }

  @Test
  fun reportingUncaughtRenderThreadExceptionsRestoresPreviousHandlerAndForwardsLateExceptionsAfterTimeout() {
    val runner = StaticPreviewCallbackRunner()
    val previousExceptions = mutableListOf<Throwable>()
    val customPreviousHandler = Thread.UncaughtExceptionHandler { _, t -> previousExceptions.add(t) }
    val renderThread = Thread.currentThread()
    val originalHandler = renderThread.uncaughtExceptionHandler
    val deferredRestoreActions = mutableListOf<Runnable>()

    renderThread.uncaughtExceptionHandler = customPreviousHandler
    try {
      // 1. Normal completion: uncaught exception during block is reported to session, and customPreviousHandler is restored synchronously.
      val normalSession = FakePreviewRenderSession()
      runner.reportingUncaughtRenderThreadExceptions(normalSession, renderThreadMayBeBusy = { false }) {
        renderThread.uncaughtExceptionHandler.uncaughtException(renderThread, InterruptedException("simulated 500ms interrupt"))
      }
      assertSame(customPreviousHandler, renderThread.uncaughtExceptionHandler)
      assertEquals(1, normalSession.errors.size)
      assertThat(normalSession.errors.single().first).contains("more than 500ms")
      assertTrue(previousExceptions.isEmpty())

      // 2. Timed-out completion (renderThreadMayBeBusy = true): restore action is queued behind busy work, but active is cleared
      // immediately so a late exception before the restore runs is forwarded to customPreviousHandler rather than the finished session.
      var deferActions = false
      val busySession =
        FakePreviewRenderSession(
          onRunAsyncOnRenderThread = { action ->
            if (deferActions) {
              deferredRestoreActions.add(action)
            } else {
              action.run()
            }
            CompletableFuture.completedFuture(null)
          }
        )
      runner.reportingUncaughtRenderThreadExceptions(busySession, renderThreadMayBeBusy = { true }) {
        deferActions = true
      }

      val lateException = RuntimeException("late exception from timed-out callback")
      renderThread.uncaughtExceptionHandler.uncaughtException(renderThread, lateException)
      assertTrue(busySession.errors.isEmpty())
      assertEquals(listOf(lateException), previousExceptions)

      deferredRestoreActions.forEach { it.run() }
      assertSame(customPreviousHandler, renderThread.uncaughtExceptionHandler)

      // 3. When the render thread had no explicit uncaughtExceptionHandler (so previousHandler is null), a late exception after timeout
      // falls back to the thread's ThreadGroup.
      val threadGroupExceptions = mutableListOf<Throwable>()
      val customGroup =
        object : ThreadGroup("TestRenderThreadGroup") {
          override fun uncaughtException(t: Thread, e: Throwable) {
            threadGroupExceptions.add(e)
          }
        }
      val groupThread =
        Thread(
          customGroup,
          {
            val groupDeferredActions = mutableListOf<Runnable>()
            var groupDefer = false
            val groupSession =
              FakePreviewRenderSession(
                onRunAsyncOnRenderThread = { action ->
                  if (groupDefer) {
                    groupDeferredActions.add(action)
                  } else {
                    action.run()
                  }
                  CompletableFuture.completedFuture(null)
                }
              )
            runner.reportingUncaughtRenderThreadExceptions(groupSession, renderThreadMayBeBusy = { true }) {
              groupDefer = true
            }
            val groupLateException = RuntimeException("late exception with null previousHandler")
            Thread.currentThread().uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), groupLateException)
            assertTrue(groupSession.errors.isEmpty())
            groupDeferredActions.forEach { it.run() }
            assertSame(customGroup, Thread.currentThread().uncaughtExceptionHandler)
          },
        )
      groupThread.start()
      groupThread.join(10_000L)
      assertEquals(1, threadGroupExceptions.size)
      assertThat(threadGroupExceptions.single().message).isEqualTo("late exception with null previousHandler")
    } finally {
      renderThread.uncaughtExceptionHandler = originalHandler
    }
  }

  @Test
  fun handlerInstallTimeoutRecordsTimedOutPreviewAndUsesAsyncRestore() {
    val runner = StaticPreviewCallbackRunner(callbacksTimeoutMs = 500L, renderThreadRecoveryTimeoutMs = 20L)
    val immediateTimeoutInstallFuture =
      object : CompletableFuture<Void?>() {
        override fun get(timeout: Long, unit: TimeUnit): Void? = throw TimeoutException("Simulated handler install timeout")
      }
    var restoreGetCalledSynchronously = false
    val trackingRestoreFuture =
      object : CompletableFuture<Void?>() {
        override fun get(timeout: Long, unit: TimeUnit): Void? {
          restoreGetCalledSynchronously = true
          return null
        }
      }
    var callIndex = 0
    val timeoutSession =
      FakePreviewRenderSession(
        onRunAsyncOnRenderThread = {
          callIndex++
          if (callIndex == 1) immediateTimeoutInstallFuture else trackingRestoreFuture
        }
      )

    val thrown = runCatching {
      runner.renderExecutingCallbacks(timeoutSession, "pkg.PreviewKt.InstallTimeoutPreview") {}
    }
      .exceptionOrNull()

    assertThat(thrown).isInstanceOf(TimeoutException::class.java)
    assertTrue(immediateTimeoutInstallFuture.isCancelled)
    assertFalse("finally must not block synchronously on restore when install timed out", restoreGetCalledSynchronously)
    assertEquals(listOf("inflated"), timeoutSession.disposedResults.map { it.id })

    // Next preview must probe the render thread because timedOutPreview was recorded on install timeout.
    val errors = mutableListOf<String>()
    val available =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = { CompletableFuture() },
        reportError = { msg, _ -> errors.add(msg) },
      )
    assertFalse(available)
    assertThat(errors.single()).contains("pkg.PreviewKt.InstallTimeoutPreview")
  }

  @Test
  fun ensureRenderThreadAvailableRecoversWhenProbeCompletesWithinTimeout() {
    var fakeNowNanos = 0L
    val runner =
      StaticPreviewCallbackRunner(
        callbacksTimeoutMs = 500L,
        renderThreadRecoveryTimeoutMs = 100L,
        nanoTime = { fakeNowNanos },
      )
    val timedOutSession =
      FakePreviewRenderSession(
        onPrepareDispatcher = {
          fakeNowNanos += TimeUnit.MILLISECONDS.toNanos(600L)
          CompletableFuture()
        }
      )
    runner.renderExecutingCallbacks(timedOutSession, "pkg.PreviewKt.TransientSlowPreview") {}
    assertEquals(1, timedOutSession.errors.size)

    var probeCount = 0
    val recoveryErrors = mutableListOf<String>()
    val available =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = { action ->
          probeCount++
          action.run()
          CompletableFuture.completedFuture(null)
        },
        reportError = { msg, _ -> recoveryErrors.add(msg) },
      )
    assertTrue(available)
    assertEquals(1, probeCount)
    assertTrue(recoveryErrors.isEmpty())

    // Once recovered, subsequent previews do not need to probe again.
    val availableAgain =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = {
          probeCount++
          CompletableFuture.completedFuture(null)
        },
        reportError = { msg, _ -> recoveryErrors.add(msg) },
      )
    assertTrue(availableAgain)
    assertEquals(1, probeCount)
  }

  @Test
  fun ensureRenderThreadAvailableMarksThreadPoisonedRecoversWithinBatchWhenProbeCompletesOrAfterRefreshClassPath() {
    var fakeNowNanos = 0L
    val runner =
      StaticPreviewCallbackRunner(
        callbacksTimeoutMs = 500L,
        renderThreadRecoveryTimeoutMs = 20L,
        nanoTime = { fakeNowNanos },
      )
    val timedOutSession =
      FakePreviewRenderSession(
        onPrepareDispatcher = {
          fakeNowNanos += TimeUnit.MILLISECONDS.toNanos(600L)
          CompletableFuture()
        }
      )
    runner.renderExecutingCallbacks(timedOutSession, "pkg.PreviewKt.InfiniteBusyLoop (InfiniteBusyLoop_0)") {}

    var probeCalls = 0
    val hungProbe = CompletableFuture<Void?>()
    val errors = mutableListOf<String>()

    // Next preview probes the render thread; the probe times out after 20ms and marks the thread poisoned, but keeps hungProbe uncancelled
    // so the batch can recover if the slow callback finishes while the batch is still running.
    val firstCheck =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = {
          probeCalls++
          hungProbe
        },
        reportError = { msg, _ -> errors.add(msg) },
      )
    assertFalse(firstCheck)
    assertFalse("Timed-out probe must remain uncancelled so in-batch completion can be observed", hungProbe.isCancelled)
    assertEquals(1, probeCalls)
    assertEquals(1, errors.size)
    assertThat(errors.single()).contains("pkg.PreviewKt.InfiniteBusyLoop (InfiniteBusyLoop_0)")

    // While hungProbe is still pending, subsequent previews in the same batch fail immediately without submitting another probe.
    val secondCheck =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = {
          probeCalls++
          CompletableFuture()
        },
        reportError = { msg, _ -> errors.add(msg) },
      )
    assertFalse(secondCheck)
    assertEquals(1, probeCalls)
    assertEquals(2, errors.size)
    assertThat(errors.last()).contains("pkg.PreviewKt.InfiniteBusyLoop (InfiniteBusyLoop_0)")

    // When a warm Renderer refreshes its classpath while hungProbe is still unfinished, hungProbe is cancelled and poisonedByPreview is
    // demoted to timedOutPreview so the new request probes once.
    runner.onRefreshClassPath()
    assertTrue(hungProbe.isCancelled)
    val queueTimeoutCheck =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = {
          probeCalls++
          CompletableFuture.failedFuture(TimeoutException("RenderExecutor queue timeout"))
        },
        reportError = { msg, _ -> errors.add(msg) },
      )
    assertFalse(queueTimeoutCheck)
    assertEquals(2, probeCalls)
    assertEquals(3, errors.size)
    assertThat(errors.last()).contains("pkg.PreviewKt.InfiniteBusyLoop (InfiniteBusyLoop_0)")

    // If a new request's probe times out initially, and then completes during the same batch, the next preview in the same batch recovers
    // automatically without needing onRefreshClassPath() or another probe.
    runner.onRefreshClassPath()
    val inBatchRecoveringProbe = CompletableFuture<Void?>()
    val thirdCheck =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = {
          probeCalls++
          inBatchRecoveringProbe
        },
        reportError = { msg, _ -> errors.add(msg) },
      )
    assertFalse(thirdCheck)
    assertEquals(3, probeCalls)
    assertEquals(4, errors.size)

    inBatchRecoveringProbe.complete(null)
    val recoveredWithinBatch =
      runner.ensureRenderThreadAvailable(
        runAsyncOnRenderThread = {
          probeCalls++
          CompletableFuture()
        },
        reportError = { msg, _ -> errors.add(msg) },
      )
    assertTrue(recoveredWithinBatch)
    assertEquals("In-batch recovery must reuse completed pendingProbe without submitting a new probe", 3, probeCalls)
    assertEquals(4, errors.size)
  }

  @Test
  fun readConfiguredCallbacksTimeoutMsClampsNonPositiveValuesAndLogsWarning() {
    assertEquals(DEFAULT_CALLBACKS_TIMEOUT_MS, readConfiguredCallbacksTimeoutMs(null))
    assertEquals(2_500L, readConfiguredCallbacksTimeoutMs(2_500L))

    val logger = Logger.getLogger(StaticPreviewCallbackRunner::class.java.name)
    val records = mutableListOf<LogRecord>()
    val testHandler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records.add(record)
        }

        override fun flush() {}

        override fun close() {}
      }
    val previousLevel = logger.level
    val previousUseParentHandlers = logger.useParentHandlers
    logger.level = Level.ALL
    logger.useParentHandlers = false
    logger.addHandler(testHandler)
    try {
      assertEquals(1L, readConfiguredCallbacksTimeoutMs(0L))
      assertEquals(1L, readConfiguredCallbacksTimeoutMs(-100L))
    } finally {
      logger.removeHandler(testHandler)
      logger.useParentHandlers = previousUseParentHandlers
      logger.level = previousLevel
    }

    assertEquals(2, records.size)
    assertTrue(records.all { it.level == Level.WARNING })
  }

  @Test
  fun hasPendingLayoutlibCallbacksFromViewDetectsHandlerAndChoreographerWorkAndLogsFineThenWarningOnOperationFailure() {
    val interactiveData = FakeSessionInteractiveData()
    val bridgeContext = FakeBridgeContext(interactiveData)
    val wrappedView = FakeView(FakeContextWrapper(bridgeContext))

    assertFalse(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))
    assertFalse(
      hasPendingLayoutlibCallbacksFromView(FakeView(FakeBridgeContext(interactiveData = null)), FakeBridgeContext::class.java.name)
    )
    assertFalse(hasPendingLayoutlibCallbacksFromView(FakeView(Any()), FakeBridgeContext::class.java.name))

    interactiveData.handlerQueue.notEmpty = true
    assertTrue(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))

    interactiveData.handlerQueue.notEmpty = false
    interactiveData.choreographerCallbacks.mCallbacks.add(Any())
    assertTrue(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))

    val logger = Logger.getLogger("com.android.tools.render.LayoutlibCallbackQueues")
    val records = mutableListOf<LogRecord>()
    val testHandler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records.add(record)
        }

        override fun flush() {}

        override fun close() {}
      }
    val previousLevel = logger.level
    val previousUseParentHandlers = logger.useParentHandlers
    logger.level = Level.ALL
    logger.useParentHandlers = false
    logger.addHandler(testHandler)
    try {
      // Transient operation failure returns false without poisoning the cached BridgeContext handle, logging FINE on the 1st failure and
      // WARNING once on the 2nd failure.
      interactiveData.handlerQueue.failNextIsNotEmpty = true
      assertFalse(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))
      assertEquals(listOf(Level.FINE), records.map { it.level })

      interactiveData.handlerQueue.failNextIsNotEmpty = true
      assertFalse(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))
      assertEquals(listOf(Level.FINE, Level.WARNING), records.map { it.level })

      interactiveData.handlerQueue.failNextIsNotEmpty = true
      assertFalse(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))
      assertEquals(listOf(Level.FINE, Level.WARNING), records.map { it.level })

      assertTrue(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))
    } finally {
      logger.removeHandler(testHandler)
      logger.useParentHandlers = previousUseParentHandlers
      logger.level = previousLevel
    }

    interactiveData.choreographerCallbacks.mCallbacks.clear()
    assertFalse(hasPendingLayoutlibCallbacksFromView(wrappedView, FakeBridgeContext::class.java.name))
  }

  @Test
  fun brokenBridgeContextCachesFailureLogsWarningOnceAndConservativelyReportsPendingWork() {
    val brokenView = FakeView(BrokenFakeBridgeContext())
    val logger = Logger.getLogger("com.android.tools.render.LayoutlibCallbackQueues")
    val records = mutableListOf<LogRecord>()
    val testHandler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records.add(record)
        }

        override fun flush() {}

        override fun close() {}
      }
    val previousLevel = logger.level
    val previousUseParentHandlers = logger.useParentHandlers
    logger.level = Level.ALL
    logger.useParentHandlers = false
    logger.addHandler(testHandler)
    try {
      assertTrue(hasPendingLayoutlibCallbacksFromView(brokenView, BrokenFakeBridgeContext::class.java.name))
      assertTrue(hasPendingLayoutlibCallbacksFromView(brokenView, BrokenFakeBridgeContext::class.java.name))
    } finally {
      logger.removeHandler(testHandler)
      logger.useParentHandlers = previousUseParentHandlers
      logger.level = previousLevel
    }

    assertEquals(1, records.size)
    assertEquals(Level.WARNING, records.single().level)
  }

  @Test
  fun disposeMethodByClassMatchesOnlyZeroArgDisposeOrMangledDispose() {
    val publicAdapter = AdapterWithPublicDispose()
    val publicMethod = disposeMethodByClass.get(AdapterWithPublicDispose::class.java)
    publicMethod!!.invoke(publicAdapter)
    assertEquals(1, publicAdapter.disposedCount)

    val mangledAdapter = AdapterWithInternalMangledDispose()
    val mangledMethod = disposeMethodByClass.get(AdapterWithInternalMangledDispose::class.java)
    mangledMethod!!.invoke(mangledAdapter)
    assertEquals(1, mangledAdapter.disposedCount)

    val logger = Logger.getLogger("com.android.tools.render.ComposeViewAdapters")
    val records = mutableListOf<LogRecord>()
    val testHandler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records.add(record)
        }

        override fun flush() {}

        override fun close() {}
      }
    val previousLevel = logger.level
    val previousUseParentHandlers = logger.useParentHandlers
    logger.level = Level.ALL
    logger.useParentHandlers = false
    logger.addHandler(testHandler)
    try {
      assertNull(disposeMethodByClass.get(AdapterWithOnlyMisleadingDisposeHelpers::class.java))
      assertNull(disposeMethodByClass.get(AdapterWithOnlyMisleadingDisposeHelpers::class.java))
    } finally {
      logger.removeHandler(testHandler)
      logger.useParentHandlers = previousUseParentHandlers
      logger.level = previousLevel
    }

    assertEquals(1, records.size)
    assertEquals(Level.FINE, records.single().level)
  }
}
