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

import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.RenderTask
import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.util.Disposer
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger(StaticPreviewCallbackRunner::class.java.name)

/** System property allowing callers or tests to override the per-preview callback wall-time budget in milliseconds. */
internal const val CALLBACKS_TIMEOUT_SYSPROP = "layoutlib.callbacks.timeout.ms"

/**
 * Default wall-time budget (in milliseconds) that executing the callbacks of a single preview can take across all steps and rounds,
 * excluding time spent in [RenderTask.render].
 */
internal const val DEFAULT_CALLBACKS_TIMEOUT_MS = 10_000L

/**
 * Configured wall-time budget (in milliseconds) for executing the callbacks of a single preview across all steps and rounds, excluding time
 * spent in [RenderTask.render]. Defaults to [DEFAULT_CALLBACKS_TIMEOUT_MS] and can be overridden via [CALLBACKS_TIMEOUT_SYSPROP] (clamped
 * to at least `1ms`).
 *
 * Note that [RenderTask.executeCallbacks] also gives each individual callback execution step a 500ms action timeout, after which
 * `RenderExecutor` interrupts the render thread. Interruptible blocking work (such as `Thread.sleep`, lock waits, or `runBlocking`)
 * therefore fails after 500ms with an [InterruptedException], whereas [CONFIGURED_CALLBACKS_TIMEOUT_MS] bounds total callback wall time for
 * work that ignores interrupts or spans multiple steps.
 */
internal val CONFIGURED_CALLBACKS_TIMEOUT_MS: Long = readConfiguredCallbacksTimeoutMs()

internal fun readConfiguredCallbacksTimeoutMs(rawValue: Long? = java.lang.Long.getLong(CALLBACKS_TIMEOUT_SYSPROP)): Long {
  if (rawValue == null) return DEFAULT_CALLBACKS_TIMEOUT_MS
  if (rawValue < 1L) {
    logger.log(Level.WARNING, "Invalid $CALLBACKS_TIMEOUT_SYSPROP=$rawValue; must be >= 1ms. Clamping to 1ms.")
    return 1L
  }
  return rawValue
}

/**
 * Default wall-time timeout (in milliseconds) for the render-thread recovery probe run before the next preview after a preview's callbacks
 * time out. If the render thread is still blocked after this probe timeout, it is marked poisoned and remaining previews fail fast until
 * the probe completes.
 */
internal const val DEFAULT_RENDER_THREAD_RECOVERY_TIMEOUT_MS = 30_000L

/** Maximum number of times callbacks are executed before each render. */
internal const val CALLBACK_EXECUTIONS_PER_ROUND = 10

/** Maximum number of (execute callbacks, render) rounds after inflation. Studio renders up to twice, with callbacks before each. */
internal const val CALLBACK_ROUNDS = 2

/** Initial `Choreographer_Delegate.sChoreographerTime` set by `RenderTask.createRenderSession` (`TimeUnit.MILLISECONDS.toNanos(500)`). */
internal val INITIAL_ELAPSED_FRAME_TIME_NANOS = TimeUnit.MILLISECONDS.toNanos(500)

/** Virtual time that passes on each callback execution. This is the step of the `SteppingSessionClock` Studio uses for previews. */
internal val CALLBACK_TIME_STEP_NANOS = TimeUnit.MILLISECONDS.toNanos(500)

internal const val RENDER_TIMEOUT_SECONDS = 100L

/** Timeout for the short actions that set and restore the uncaught exception handler of the render thread. */
internal const val RENDER_THREAD_ACTION_TIMEOUT_SECONDS = 10L

/**
 * Passed to [RenderTask.runAsyncRenderActionWithSession] with [TimeUnit.SECONDS] to select `RenderTask`'s default render-thread action
 * timeout (`RenderAsyncActionExecutor.DEFAULT_RENDER_THREAD_TIMEOUT_MS`, 10s by default or `layoutlib.thread.timeout`). Note that `0` does
 * not mean "no timeout": `RenderTask.runAsyncRenderAction` treats any `timeout < 1` as the default render-thread timeout.
 */
internal const val DEFAULT_RENDER_THREAD_TIMEOUT_SECONDS = 0L

/** Outcome of preparing or executing callbacks for a round in [StaticPreviewCallbackRunner]. */
internal sealed interface CallbacksOutcome {
  data class Settled(val hasMoreWork: Boolean, val sessionTimeNanos: Long) : CallbacksOutcome

  data object TimedOut : CallbacksOutcome

  data object Failed : CallbacksOutcome
}

/**
 * Abstraction over a single preview's [RenderTask] and the layoutlib render thread used by [StaticPreviewCallbackRunner], allowing unit
 * tests to exercise the stepping loop, timeout accounting, uncaught-exception scoping, and poisoned-thread recovery deterministically with
 * fakes.
 */
internal interface PreviewRenderSession<R> {
  fun inflate(): R?

  fun isSuccess(result: R): Boolean

  fun disposeResult(result: R)

  fun composeClassLoader(result: R): ClassLoader?

  fun setElapsedFrameTimeNanos(nanos: Long)

  fun prepareComposeUiDispatcher(composeClassLoader: ClassLoader): CompletableFuture<Void?>

  fun executeCallbacks(sessionTimeNanos: Long): CompletableFuture<Boolean>

  fun render(): R

  fun checkPendingWorkOnRenderThread(result: R, composeClassLoader: ClassLoader?): CompletableFuture<Boolean>

  fun runAsyncOnRenderThread(action: Runnable): CompletableFuture<Void?>

  fun <T> callAsyncOnRenderThread(action: Callable<T>): CompletableFuture<T>

  fun reportError(message: String, throwable: Throwable?)

  fun createErrorResult(throwable: Throwable? = null): R
}

/**
 * Runs the static-preview callback stepping loop, timeout bookkeeping, render-thread uncaught exception scoping, and poisoned render-thread
 * recovery for [Renderer].
 *
 * Note: A [StaticPreviewCallbackRunner] instance is used by a single [Renderer] from one caller thread at a time (matching layoutlib's
 * single render thread).
 */
internal class StaticPreviewCallbackRunner(
  /** Per-preview wall-time budget for callback execution, overridable by unit tests. */
  @set:VisibleForTesting var callbacksTimeoutMs: Long = CONFIGURED_CALLBACKS_TIMEOUT_MS,
  /** Per-timeout wall-time budget for the render-thread recovery probe before the next preview, overridable by unit tests. */
  @set:VisibleForTesting var renderThreadRecoveryTimeoutMs: Long = DEFAULT_RENDER_THREAD_RECOVERY_TIMEOUT_MS,
  /** Monotonic wall-clock source in nanoseconds, overridable by unit tests. */
  @set:VisibleForTesting var nanoTime: () -> Long = System::nanoTime,
) {
  /**
   * Name of the preview whose callback execution most recently timed out and may still be running on the single layoutlib render thread, or
   * `null` if the render thread is known to be idle.
   */
  @Volatile private var timedOutPreview: String? = null

  /**
   * Name of the preview that wedged the single layoutlib render thread (verified when the recovery probe timed out after
   * [renderThreadRecoveryTimeoutMs]), or `null` if the render thread is not currently poisoned.
   */
  @Volatile private var poisonedByPreview: String? = null

  /**
   * In-flight recovery probe that timed out waiting on the render thread. Kept uncancelled so that if the slow callback eventually finishes
   * during the same batch, a subsequent preview in the batch can observe `probe.isDone` and recover automatically.
   */
  @Volatile private var pendingProbe: CompletableFuture<Void?>? = null

  companion object {
    /**
     * Cumulative virtual time offset, in nanoseconds, across all [Renderer] instances and [RenderTask] sessions on the layoutlib render
     * thread.
     *
     * Layoutlib's `Choreographer` is a thread-local on the single render thread and keeps `mLastFrameTimeNanos` across render sessions
     * (clamping each frame time to `max(now, mLastFrameTimeNanos)`), whereas `RenderTask.createRenderSession` resets
     * `Choreographer_Delegate.sChoreographerTime` to 500ms and starts each new session's `SessionInteractiveData.mNanosTime` at 0. Before
     * executing callbacks on a newly inflated session, we advance the session's `sChoreographerTime` by [virtualTimeNanos] via
     * [RenderTask.setElapsedFrameTimeNanos] and pass per-session elapsed time to [RenderTask.executeCallbacks] while incrementing
     * [virtualTimeNanos] on each step, so `sChoreographerTime` advances monotonically across sessions and by [CALLBACK_TIME_STEP_NANOS] per
     * step (including in round 2 after callbacks posted during round 1's `render()`).
     *
     * Note on determinism: Compose animations (`animate*AsState`, `InfiniteTransition`, `Animatable`, `CircularProgressIndicator`, etc.)
     * compute play time relative to the start timestamp captured on their first frame, so their rendered frames are independent of a
     * preview's position in the run. A preview that directly formats and renders the raw `withFrameNanos` timestamp or `Choreographer`
     * frame time will observe the cumulative virtual time.
     */
    internal val virtualTimeNanos = AtomicLong(0)
  }

  /**
   * Called when a warm [Renderer] refreshes its project classpath for a new render request.
   *
   * If a previous request's preview marked the render thread poisoned (because its callback did not finish within the initial
   * [renderThreadRecoveryTimeoutMs] probe window), checks whether the uncancelled [pendingProbe] has already completed (recovering
   * immediately if so) or cancels it and demotes [poisonedByPreview] back to [timedOutPreview] so the new request probes the render thread
   * once before its first preview.
   */
  fun onRefreshClassPath() {
    val probe = pendingProbe
    if (probe != null) {
      if (probe.isDone && !probe.isCompletedExceptionally && !probe.isCancelled) {
        pendingProbe = null
        poisonedByPreview = null
        timedOutPreview = null
        return
      }
      probe.cancel(false)
      pendingProbe = null
    }
    val culprit = poisonedByPreview
    if (culprit != null) {
      timedOutPreview = culprit
      poisonedByPreview = null
    }
  }

  /**
   * Checks whether a previous preview's timed-out callback is still blocking the single layoutlib render thread.
   *
   * If a previous preview timed out, submits a no-op probe via [runAsyncOnRenderThread] and waits up to [renderThreadRecoveryTimeoutMs]
   * before allowing the next preview to inflate. Because the probe is submitted on `RenderingTopic.NOT_SPECIFIED` after the timed-out
   * preview's uncaught-exception-handler restore and `releaseRender` actions, its completion also guarantees that those cleanup actions
   * have finished before the next preview starts. If the probe times out or fails to execute (for example due to `RenderExecutor` queue
   * timeout or queue eviction while the render thread is blocked), the render thread is marked poisoned by the culprit preview and
   * subsequent previews fail fast without waiting `RENDER_TIMEOUT_SECONDS` (100s) each. When the probe times out, it is left queued in
   * [pendingProbe] without cancelling it so that if the slow callback eventually finishes during the batch, later previews observe
   * `probe.isDone` and recover automatically.
   *
   * @return `true` if the render thread is ready to run the next preview, or `false` if it is poisoned (in which case an error naming the
   *   culprit preview has been reported to [reportError]).
   */
  fun ensureRenderThreadAvailable(
    runAsyncOnRenderThread: (Runnable) -> CompletableFuture<Void?>,
    reportError: (String, Throwable) -> Unit,
  ): Boolean {
    poisonedByPreview?.let { culprit ->
      val existingProbe = pendingProbe
      if (existingProbe != null && existingProbe.isDone && !existingProbe.isCompletedExceptionally && !existingProbe.isCancelled) {
        pendingProbe = null
        poisonedByPreview = null
        timedOutPreview = null
        return true
      }
      reportPoisonedRenderThread(culprit, reportError)
      return false
    }
    val culprit = timedOutPreview ?: return true
    val probe = runAsyncOnRenderThread(Runnable {})
    fun markPoisoned(keepPendingProbe: Boolean): Boolean {
      if (keepPendingProbe) {
        pendingProbe = probe
      } else {
        probe.cancel(false)
        pendingProbe = null
      }
      poisonedByPreview = culprit
      timedOutPreview = null
      reportPoisonedRenderThread(culprit, reportError)
      return false
    }
    return try {
      probe.get(maxOf(renderThreadRecoveryTimeoutMs, 0L), TimeUnit.MILLISECONDS)
      pendingProbe = null
      timedOutPreview = null
      true
    } catch (_: TimeoutException) {
      // Keep `probe` queued on the render thread without cancelling it: if the slow callback finishes while the batch is still running,
      // `probe` will complete normally and a later preview in the same batch will observe `probe.isDone` and recover automatically.
      markPoisoned(keepPendingProbe = true)
    } catch (_: CancellationException) {
      // A no-op Runnable never throws when executed; CancellationException (e.g. RenderExecutor EvictedException when the pending queue is
      // full) means the render thread is still blocked.
      markPoisoned(keepPendingProbe = false)
    } catch (_: ExecutionException) {
      // A no-op Runnable never throws when executed; ExecutionException (e.g. RenderExecutor queue timeout while waiting for the blocked
      // render thread) means the probe could not run on the render thread.
      markPoisoned(keepPendingProbe = false)
    }
  }

  private fun reportPoisonedRenderThread(culpritPreview: String, reportError: (String, Throwable) -> Unit) {
    val effectiveTimeoutMs = callbacksTimeoutMs.coerceAtLeast(1L)
    val message =
      "The layoutlib render thread is still blocked by a previous preview ($culpritPreview) whose main thread work did not complete " +
        "within ${effectiveTimeoutMs}ms."
    reportError(message, TimeoutException(message))
  }

  /**
   * Renders [session] like Android Studio renders a static preview (see `SurfacePreviewsUpdate` and `LayoutlibSceneRenderer` in
   * tools/adt/idea), so that the image includes the work the composition schedules on the main thread: `LaunchedEffect`s, animations,
   * `FocusRequester.requestFocus()` calls made from effects, `Popup`s that position their content asynchronously, etc.
   *
   * Inflation creates and measures the view hierarchy (running initial Compose composition) without drawing a bitmap. It is followed by up
   * to [CALLBACK_ROUNDS] rounds of executing callbacks and rendering. Each round executes callbacks up to [CALLBACK_EXECUTIONS_PER_ROUND]
   * times (stepping the virtual clock each time rather than using a wall-time limit, so the rendered frame does not depend on machine
   * speed) and stops early as soon as neither layoutlib (`hasMoreCallbacks()`, `Handler`, `Choreographer`) nor Compose's
   * `AndroidUiDispatcher.Main` has further work queued. A second round is only executed when callback work remained at the end of the first
   * round or when the first render pass queued new work (such as a `Popup` whose `PopupLayout` size was first reported during that render,
   * or a custom `View` posting work during `onLayout`/`onDraw`).
   *
   * Each callback execution step is given 500ms by [RenderTask.executeCallbacks] before the render thread is interrupted (failing
   * interruptible blocking work such as `Thread.sleep` or `runBlocking`), and total callback wall time across all steps and rounds of a
   * preview is bounded at [callbacksTimeoutMs] (excluding the time spent in [PreviewRenderSession.render], which has its own
   * [RENDER_TIMEOUT_SECONDS] timeout). If executing the callbacks exceeds [callbacksTimeoutMs], an error is reported, the preview is not
   * rendered again, and [timedOutPreview] is recorded so [ensureRenderThreadAvailable] probes the render thread before the next preview.
   *
   * @param onResult Invoked synchronously on the calling thread immediately after [PreviewRenderSession.inflate] (before any callback step)
   *   and after each [PreviewRenderSession.render] pass, before the previous intermediate result is disposed.
   */
  fun <R> renderExecutingCallbacks(
    session: PreviewRenderSession<R>,
    previewName: String,
    onResult: (R) -> Unit,
  ): R {
    var result: R = session.inflate() ?: return session.createErrorResult()
    onResult(result)
    try {
      if (!session.isSuccess(result)) return result
      val composeClassLoader = session.composeClassLoader(result)
      session.setElapsedFrameTimeNanos(INITIAL_ELAPSED_FRAME_TIME_NANOS + virtualTimeNanos.get())
      var sessionTimeNanos = 0L
      val effectiveCallbacksTimeoutMs = callbacksTimeoutMs.coerceAtLeast(1L)
      var remainingCallbackBudgetNanos = TimeUnit.MILLISECONDS.toNanos(effectiveCallbacksTimeoutMs)
      var callbacksCompleted = true
      reportingUncaughtRenderThreadExceptions(
        session = session,
        previewName = previewName,
        renderThreadMayBeBusy = { !callbacksCompleted },
      ) { hasUncaughtException ->
        for (round in 0 until CALLBACK_ROUNDS) {
          if (!session.isSuccess(result)) return@reportingUncaughtRenderThreadExceptions
          val roundDeadlineNanos = nanoTime() + maxOf(remainingCallbackBudgetNanos, 0L)
          if (composeClassLoader != null) {
            when (prepareComposeUiDispatcher(session, composeClassLoader, roundDeadlineNanos, sessionTimeNanos)) {
              is CallbacksOutcome.Settled -> {}
              CallbacksOutcome.TimedOut -> {
                callbacksCompleted = false
                timedOutPreview = previewName
                return@reportingUncaughtRenderThreadExceptions
              }
              CallbacksOutcome.Failed -> {
                callbacksCompleted = false
                return@reportingUncaughtRenderThreadExceptions
              }
            }
          }
          val outcome =
            executeCallbacks(
              session = session,
              result = result,
              composeClassLoader = composeClassLoader,
              deadlineNanos = roundDeadlineNanos,
              initialSessionTimeNanos = sessionTimeNanos,
              hasUncaughtException = hasUncaughtException,
            )
          val hasMoreCallbacks =
            when (outcome) {
              is CallbacksOutcome.Settled -> {
                sessionTimeNanos = outcome.sessionTimeNanos
                outcome.hasMoreWork
              }
              CallbacksOutcome.TimedOut -> {
                callbacksCompleted = false
                timedOutPreview = previewName
                return@reportingUncaughtRenderThreadExceptions
              }
              CallbacksOutcome.Failed -> {
                callbacksCompleted = false
                return@reportingUncaughtRenderThreadExceptions
              }
            }
          if (hasUncaughtException()) {
            callbacksCompleted = false
            return@reportingUncaughtRenderThreadExceptions
          }
          remainingCallbackBudgetNanos = roundDeadlineNanos - nanoTime()
          val previousResult = result
          result = session.render()
          try {
            onResult(result)
          } finally {
            session.disposeResult(previousResult)
          }
          if (round < CALLBACK_ROUNDS - 1) {
            val hasPendingWork =
              if (hasMoreCallbacks) {
                true
              } else {
                val checkDeadlineNanos = nanoTime() + maxOf(remainingCallbackBudgetNanos, 0L)
                val checkOutcome =
                  checkPendingWorkOnRenderThread(
                    session = session,
                    result = result,
                    composeClassLoader = composeClassLoader,
                    deadlineNanos = checkDeadlineNanos,
                    sessionTimeNanos = sessionTimeNanos,
                    slowExecution = null,
                  )
                remainingCallbackBudgetNanos = checkDeadlineNanos - nanoTime()
                when (checkOutcome) {
                  is CallbacksOutcome.Settled -> checkOutcome.hasMoreWork
                  CallbacksOutcome.TimedOut -> {
                    callbacksCompleted = false
                    timedOutPreview = previewName
                    return@reportingUncaughtRenderThreadExceptions
                  }
                  CallbacksOutcome.Failed -> {
                    callbacksCompleted = false
                    return@reportingUncaughtRenderThreadExceptions
                  }
                }
              }
            if (!hasPendingWork) break
          }
        }
      }
      return result
    } catch (t: Throwable) {
      session.disposeResult(result)
      throw t
    }
  }

  /**
   * Runs [resetComposeUiDispatcherScheduling] for [composeClassLoader] inside [session], so that the work queued in Compose's
   * `AndroidUiDispatcher` runs when the callbacks of this session are executed, even if an earlier session left the dispatcher believing
   * its callback was already posted.
   */
  private fun <R> prepareComposeUiDispatcher(
    session: PreviewRenderSession<R>,
    composeClassLoader: ClassLoader,
    deadlineNanos: Long,
    sessionTimeNanos: Long,
  ): CallbacksOutcome {
    val future = session.prepareComposeUiDispatcher(composeClassLoader)
    try {
      future.get(maxOf(deadlineNanos - nanoTime(), 0L), TimeUnit.NANOSECONDS)
    } catch (e: TimeoutException) {
      future.cancel(false)
      reportCallbacksTimeout(session, null)
      return CallbacksOutcome.TimedOut
    } catch (e: CancellationException) {
      logger.log(Level.FINE, "Resetting Compose UI dispatcher was cancelled before executing layoutlib callbacks", e)
    } catch (e: ExecutionException) {
      logger.log(Level.FINE, "Unable to reset the Compose UI dispatcher before executing layoutlib callbacks", e.cause ?: e)
    }
    return CallbacksOutcome.Settled(hasMoreWork = false, sessionTimeNanos = sessionTimeNanos)
  }

  /**
   * Executes the layoutlib callbacks of [session] up to [CALLBACK_EXECUTIONS_PER_ROUND] times, stepping the virtual clock each time and
   * stopping early once neither layoutlib nor Compose's `AndroidUiDispatcher` has further pending work (or immediately if a callback
   * reports an uncaught exception via [hasUncaughtException]).
   */
  private fun <R> executeCallbacks(
    session: PreviewRenderSession<R>,
    result: R,
    composeClassLoader: ClassLoader?,
    deadlineNanos: Long,
    initialSessionTimeNanos: Long,
    hasUncaughtException: () -> Boolean,
  ): CallbacksOutcome {
    // The last execution that RenderTask reported as too slow. Its stack trace is the one of the render thread at that point.
    var slowExecution: TimeoutException? = null
    var lastExecutionTimedOut = false
    var hasMoreWork = false
    var sessionTimeNanos = initialSessionTimeNanos
    for (step in 0 until CALLBACK_EXECUTIONS_PER_ROUND) {
      if (hasUncaughtException()) return CallbacksOutcome.Failed
      val remainingNanos = deadlineNanos - nanoTime()
      if (remainingNanos <= 0) {
        reportCallbacksTimeout(session, slowExecution)
        return CallbacksOutcome.TimedOut
      }
      virtualTimeNanos.addAndGet(CALLBACK_TIME_STEP_NANOS)
      sessionTimeNanos += CALLBACK_TIME_STEP_NANOS
      val future = session.executeCallbacks(sessionTimeNanos)
      try {
        val hasMoreLayoutlibCallbacks = future.get(remainingNanos, TimeUnit.NANOSECONDS)
        lastExecutionTimedOut = false
        if (hasUncaughtException()) return CallbacksOutcome.Failed
        if (hasMoreLayoutlibCallbacks) {
          hasMoreWork = true
        } else {
          when (
            val checkOutcome =
              checkPendingWorkOnRenderThread(session, result, composeClassLoader, deadlineNanos, sessionTimeNanos, slowExecution)
          ) {
            is CallbacksOutcome.Settled -> hasMoreWork = checkOutcome.hasMoreWork
            CallbacksOutcome.TimedOut,
            CallbacksOutcome.Failed -> return checkOutcome
          }
        }
        if (!hasMoreWork) break
      } catch (e: TimeoutException) {
        // The render thread is still busy with earlier callbacks. Do not leave this execution queued behind them.
        future.cancel(false)
        reportCallbacksTimeout(session, slowExecution ?: e)
        return CallbacksOutcome.TimedOut
      } catch (e: CancellationException) {
        session.reportError("Error while executing layoutlib callbacks: $e", e)
        return CallbacksOutcome.Failed
      } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        if (cause is TimeoutException) {
          if (hasUncaughtException()) return CallbacksOutcome.Failed
          // RenderTask gives each execution 500ms, after which it interrupts the render thread and fails the future, but the callbacks
          // can keep running (e.g. busy work that ignores interruptions). The next executions are queued after them, so keep going until
          // the deadline.
          slowExecution = cause
          lastExecutionTimedOut = true
          hasMoreWork = true
          logger.log(Level.FINE, "Executing layoutlib callbacks took longer than expected", cause)
        } else {
          session.reportError("Error while executing layoutlib callbacks: $cause", cause)
          return CallbacksOutcome.Failed
        }
      }
    }
    if (lastExecutionTimedOut) {
      if (hasUncaughtException()) return CallbacksOutcome.Failed
      // The final execution of this round exceeded RenderTask's 500ms per-call timeout and is still running on the render thread.
      // Wait for it to finish up to the round's deadline and check whether more work remains in the same render-thread hop so a slow final
      // execution cannot bypass callbacksTimeoutMs or race with caller-thread queue inspection.
      val remainingNanos = deadlineNanos - nanoTime()
      if (remainingNanos <= 0) {
        reportCallbacksTimeout(session, slowExecution)
        return CallbacksOutcome.TimedOut
      }
      val future = session.checkPendingWorkOnRenderThread(result, composeClassLoader)
      hasMoreWork =
        try {
          future.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
          future.cancel(false)
          reportCallbacksTimeout(session, slowExecution ?: e)
          return CallbacksOutcome.TimedOut
        } catch (e: CancellationException) {
          session.reportError("Error while executing layoutlib callbacks: $e", e)
          return CallbacksOutcome.Failed
        } catch (e: ExecutionException) {
          val cause = e.cause ?: e
          session.reportError("Error while executing layoutlib callbacks: $cause", cause)
          return CallbacksOutcome.Failed
        }
      if (hasUncaughtException()) return CallbacksOutcome.Failed
    }
    return CallbacksOutcome.Settled(hasMoreWork = hasMoreWork, sessionTimeNanos = sessionTimeNanos)
  }

  private fun <R> checkPendingWorkOnRenderThread(
    session: PreviewRenderSession<R>,
    result: R,
    composeClassLoader: ClassLoader?,
    deadlineNanos: Long,
    sessionTimeNanos: Long,
    slowExecution: TimeoutException?,
  ): CallbacksOutcome {
    val remainingNanos = deadlineNanos - nanoTime()
    if (remainingNanos <= 0) {
      reportCallbacksTimeout(session, slowExecution)
      return CallbacksOutcome.TimedOut
    }
    val future = session.checkPendingWorkOnRenderThread(result, composeClassLoader)
    return try {
      val hasPending = future.get(remainingNanos, TimeUnit.NANOSECONDS)
      CallbacksOutcome.Settled(hasMoreWork = hasPending, sessionTimeNanos = sessionTimeNanos)
    } catch (e: TimeoutException) {
      future.cancel(false)
      reportCallbacksTimeout(session, slowExecution ?: e)
      CallbacksOutcome.TimedOut
    } catch (e: CancellationException) {
      session.reportError("Error while executing layoutlib callbacks: $e", e)
      CallbacksOutcome.Failed
    } catch (e: ExecutionException) {
      val cause = e.cause ?: e
      session.reportError("Error while executing layoutlib callbacks: $cause", cause)
      CallbacksOutcome.Failed
    }
  }

  private fun <R> reportCallbacksTimeout(session: PreviewRenderSession<R>, renderThreadTimeout: TimeoutException?) {
    val effectiveTimeoutMs = callbacksTimeoutMs.coerceAtLeast(1L)
    val message =
      "The main thread work of the preview (e.g. LaunchedEffects or animations) did not complete within ${effectiveTimeoutMs}ms. " +
        "Previews must not run long or blocking work on the main thread."
    session.reportError(
      message,
      TimeoutException(message).apply { renderThreadTimeout?.let { stackTrace = it.stackTrace } },
    )
  }

  /**
   * Runs [block] while exceptions that reach the uncaught exception handler of the layoutlib render thread are reported to [session].
   *
   * Coroutines dispatched by Compose's `AndroidUiDispatcher` (e.g. `LaunchedEffect`s) run inside layoutlib Handler callbacks, and an
   * exception they throw ends up in the uncaught exception handler of the render thread, which by default only prints it to stderr.
   *
   * The handler is installed and restored via [PreviewRenderSession.runAsyncOnRenderThread], which submits directly to the render thread's
   * `RenderingTopic.NOT_SPECIFIED` FIFO queue without requiring an active `RenderSession` or `RenderTask` timeout wrapper. This ordering
   * relies on standalone rendering submitting all render, callback, exception-handler, and `releaseRender` actions on the same
   * `RenderingTopic.NOT_SPECIFIED` topic without calling `RenderAsyncActionExecutor.cancelActionsByTopic` or `cancelLowerPriorityActions`.
   *
   * If installing the handler times out or [renderThreadMayBeBusy] returns `true` once [block] has run, the previous handler is restored
   * asynchronously instead of waiting for the render thread; the `active` flag is cleared immediately so any late uncaught exception thrown
   * by a timed-out callback before the restore task runs is logged and forwarded to `previousHandler` (or the thread's `ThreadGroup`
   * default handler) rather than written to the already-consumed [RenderLogger]. The restore action still runs in FIFO order before any
   * action submitted later, such as the ones of the next preview.
   */
  internal fun <R> reportingUncaughtRenderThreadExceptions(
    session: PreviewRenderSession<R>,
    previewName: String = "preview",
    renderThreadMayBeBusy: () -> Boolean,
    block: (hasUncaughtException: () -> Boolean) -> Unit,
  ) {
    val active = AtomicBoolean(true)
    val uncaughtExceptionReported = AtomicBoolean(false)
    val previousHandler = AtomicReference<Thread.UncaughtExceptionHandler?>(null)
    val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
      if (active.get()) {
        uncaughtExceptionReported.set(true)
        val hint =
          if (throwable is InterruptedException || throwable.cause is InterruptedException) {
            " (the render thread is interrupted when the main thread work of a preview runs for more than 500ms without returning)"
          } else {
            ""
          }
        session.reportError("Uncaught exception while executing layoutlib callbacks: $throwable$hint", throwable)
      } else {
        logger.log(Level.FINE, "Uncaught exception on render thread after preview timed out", throwable)
        (previousHandler.get() ?: thread.threadGroup)?.uncaughtException(thread, throwable)
      }
    }
    val installFuture =
      session.runAsyncOnRenderThread(
        Runnable {
          if (!active.get()) return@Runnable
          val thread = Thread.currentThread()
          // When no handler is set, the getter returns the thread group, which is used again once the handler is reset to null.
          previousHandler.set(thread.uncaughtExceptionHandler.takeIf { it !== thread.threadGroup })
          thread.uncaughtExceptionHandler = handler
        }
      )
    var installTimedOut = false
    try {
      try {
        installFuture.get(RENDER_THREAD_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      } catch (e: TimeoutException) {
        installFuture.cancel(false)
        installTimedOut = true
        timedOutPreview = previewName
        throw e
      }
      block(uncaughtExceptionReported::get)
    } finally {
      active.set(false)
      try {
        val restored =
          session.runAsyncOnRenderThread(
            Runnable {
              val thread = Thread.currentThread()
              if (thread.uncaughtExceptionHandler === handler) thread.uncaughtExceptionHandler = previousHandler.get()
            }
          )
        if (installTimedOut || renderThreadMayBeBusy()) {
          restored.whenComplete { _, t ->
            if (t != null) logger.log(Level.FINE, "Unable to restore the uncaught exception handler of the render thread", t)
          }
        } else {
          restored.get(RENDER_THREAD_ACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
      } catch (e: Exception) {
        logger.log(Level.FINE, "Unable to restore the uncaught exception handler of the render thread", e)
      }
    }
  }
}

/** Production [PreviewRenderSession] backed by [RenderTask] and [RenderService.getRenderAsyncActionExecutor]. */
internal class RenderTaskPreviewSession(
  private val renderTask: RenderTask,
  private val renderLogger: RenderLogger,
  private val createErrorRenderResult: (RenderLogger, Throwable?) -> RenderResult,
  private val defaultClassLoader: ClassLoader? = null,
) : PreviewRenderSession<RenderResult> {
  override fun inflate(): RenderResult? = renderTask.inflate().get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)

  override fun isSuccess(result: RenderResult): Boolean = result.renderResult.isSuccess

  override fun disposeResult(result: RenderResult) {
    Disposer.dispose(result)
  }

  override fun composeClassLoader(result: RenderResult): ClassLoader? =
    findComposeViewAdapters(result.rootViews).firstOrNull()?.javaClass?.classLoader ?: defaultClassLoader

  override fun setElapsedFrameTimeNanos(nanos: Long) {
    renderTask.setElapsedFrameTimeNanos(nanos)
  }

  override fun prepareComposeUiDispatcher(composeClassLoader: ClassLoader): CompletableFuture<Void?> =
    renderTask.runAsyncRenderActionWithSession(
      { resetComposeUiDispatcherScheduling(composeClassLoader) },
      DEFAULT_RENDER_THREAD_TIMEOUT_SECONDS,
      TimeUnit.SECONDS,
    )

  override fun executeCallbacks(sessionTimeNanos: Long): CompletableFuture<Boolean> =
    renderTask.executeCallbacks(sessionTimeNanos).thenApply { it.hasMoreCallbacks() }

  override fun render(): RenderResult = renderTask.render().get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)

  override fun checkPendingWorkOnRenderThread(result: RenderResult, composeClassLoader: ClassLoader?): CompletableFuture<Boolean> =
    callAsyncOnRenderThread {
      // Both helpers return true on reflective lookup failure (running all bounded steps conservatively) and false on transient runtime
      // operation errors.
      hasPendingLayoutlibCallbacks(result) || (composeClassLoader != null && hasPendingComposeUiDispatcherWork(composeClassLoader))
    }

  override fun runAsyncOnRenderThread(action: Runnable): CompletableFuture<Void?> =
    RenderService.getRenderAsyncActionExecutor().runAsyncAction(action)

  override fun <T> callAsyncOnRenderThread(action: Callable<T>): CompletableFuture<T> =
    RenderService.getRenderAsyncActionExecutor().runAsyncAction(action)

  override fun reportError(message: String, throwable: Throwable?) {
    // RenderLogger.error passes `message` to HtmlBuilder.add(String), which XML-escapes plain text automatically.
    renderLogger.error(null, message, throwable, null, null)
  }

  override fun createErrorResult(throwable: Throwable?): RenderResult = createErrorRenderResult(renderLogger, throwable)
}
