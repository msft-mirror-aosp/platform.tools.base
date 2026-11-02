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

import androidx.compose.ui.platform.GlobalSnapshotManagerTestStandIn
import com.android.testutils.TestUtils
import com.intellij.util.ref.GCUtil
import java.io.File
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.ZipFile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stand-in for the frame clock that `AndroidUiDispatcher.Main` combines with the dispatcher. */
private class FakeFrameClock : AbstractCoroutineContextElement(FakeFrameClock) {
  companion object Key : CoroutineContext.Key<FakeFrameClock>
}

/** Stand-in for `android.view.Choreographer` whose callback list is per render session in layoutlib. */
private class FakeChoreographer {
  val callbacks = mutableListOf<Any>()

  fun postFrameCallback(callback: Any) {
    callbacks.add(callback)
  }

  fun removeFrameCallback(callback: Any) {
    callbacks.removeAll { it === callback }
  }
}

/** Stand-in for `android.os.Handler` whose message queue is per render session in layoutlib. */
private class FakeHandler {
  val callbacks = mutableListOf<Runnable>()
  var failNextRemoveCallbacks = false

  fun post(callback: Runnable): Boolean {
    callbacks.add(callback)
    return true
  }

  fun removeCallbacks(callback: Runnable) {
    if (failNextRemoveCallbacks) {
      failNextRemoveCallbacks = false
      throw IllegalStateException("Simulated transient teardown error in removeCallbacks")
    }
    callbacks.removeAll { it === callback }
  }
}

/** Has the members of `androidx.compose.ui.platform.AndroidUiDispatcher` that [resetAndroidUiDispatcherScheduling] relies on. */
private class FakeAndroidUiDispatcher : AbstractCoroutineContextElement(FakeAndroidUiDispatcher) {
  internal val lock = Any()
  private val choreographer = FakeChoreographer()
  internal val handler = FakeHandler()
  private val dispatchCallback = Runnable {}
  private var scheduledTrampolineDispatch = false
  private var scheduledFrameDispatch = false
  private val toRunTrampolined = ArrayDeque<Runnable>()
  private val toRunOnFrame = mutableListOf<Any>()

  /** Number of callbacks posted to the (fake) Handler. */
  var postedCallbacks = 0
    private set

  /** Number of callbacks currently registered in the current session's (fake) Handler. */
  val handlerCallbackCount: Int
    get() = synchronized(lock) { handler.callbacks.size }

  /** Number of frame callbacks currently registered in the current session's (fake) Choreographer. */
  val choreographerCallbackCount: Int
    get() = synchronized(lock) { choreographer.callbacks.size }

  /** Like `AndroidUiDispatcher.dispatch`: queues [block] and posts a callback unless one is believed to be posted already. */
  @Suppress("UNUSED_PARAMETER")
  fun dispatch(context: CoroutineContext, block: Runnable) =
    synchronized(lock) {
      toRunTrampolined.addLast(block)
      if (!scheduledTrampolineDispatch) {
        scheduledTrampolineDispatch = true
        postedCallbacks++
        handler.post(dispatchCallback)
        if (!scheduledFrameDispatch) {
          scheduledFrameDispatch = true
          choreographer.postFrameCallback(dispatchCallback)
        }
      }
    }

  /** Like `AndroidUiDispatcher.postFrameCallback`: queues [callback] and posts `dispatchCallback` to the Choreographer if needed. */
  fun postFrameCallback(callback: Any) =
    synchronized(lock) {
      toRunOnFrame.add(callback)
      if (!scheduledFrameDispatch) {
        scheduledFrameDispatch = true
        choreographer.postFrameCallback(dispatchCallback)
      }
    }

  /** Simulates the state left by a dispatch whose posted callback will never run, e.g. because its render session has ended. */
  fun loseCallback(queuedWork: Runnable? = null, queuedFrameWork: Any? = null) =
    synchronized(lock) {
      queuedWork?.let { toRunTrampolined.addLast(it) }
      queuedFrameWork?.let { toRunOnFrame.add(it) }
      scheduledTrampolineDispatch = true
      scheduledFrameDispatch = true
    }

  fun clear() =
    synchronized(lock) {
      toRunTrampolined.clear()
      toRunOnFrame.clear()
      choreographer.callbacks.clear()
      handler.callbacks.clear()
      handler.failNextRemoveCallbacks = false
      scheduledTrampolineDispatch = false
      scheduledFrameDispatch = false
      postedCallbacks = 0
    }

  val queuedWork: List<Runnable>
    get() = synchronized(lock) { toRunTrampolined.toList() }

  val queuedFrameWork: List<Any>
    get() = synchronized(lock) { toRunOnFrame.toList() }

  val isAnyDispatchScheduled: Boolean
    get() = synchronized(lock) { scheduledTrampolineDispatch || scheduledFrameDispatch }

  companion object Key : CoroutineContext.Key<FakeAndroidUiDispatcher> {
    val Main: CoroutineContext by lazy { FakeAndroidUiDispatcher() + FakeFrameClock() }
  }
}

/** Same shape as [FakeAndroidUiDispatcher], but its `Main` is never accessed so that it stays uninitialized. */
private class UninitializedFakeAndroidUiDispatcher : AbstractCoroutineContextElement(UninitializedFakeAndroidUiDispatcher) {
  private val lock = Any()
  private var scheduledTrampolineDispatch = false
  private var scheduledFrameDispatch = false

  companion object Key : CoroutineContext.Key<UninitializedFakeAndroidUiDispatcher> {
    @Suppress("unused") val Main: CoroutineContext by lazy { UninitializedFakeAndroidUiDispatcher() + FakeFrameClock() }
  }
}

/** Initialized `Main` whose class does not have the expected `AndroidUiDispatcher` fields. */
private class BrokenFakeAndroidUiDispatcher : AbstractCoroutineContextElement(BrokenFakeAndroidUiDispatcher) {
  companion object Key : CoroutineContext.Key<BrokenFakeAndroidUiDispatcher> {
    val Main: CoroutineContext by lazy { BrokenFakeAndroidUiDispatcher() + FakeFrameClock() }
  }
}

/**
 * Stand-in for `kotlinx.coroutines.internal.DispatchedContinuation` whose `continuation` field points to a coroutine continuation and whose
 * `toString()` deliberately omits `GlobalSnapshotManager` so tests verify reflective continuation unwrapping.
 */
private class FakeDispatchedContinuation(val continuation: Runnable) : Runnable {
  override fun run() {
    continuation.run()
  }

  override fun toString(): String = "FakeDispatchedContinuation"
}

/**
 * Stand-in for `kotlin.coroutines.jvm.internal.BaseContinuationImpl` whose `completion` field points to the enclosing suspend continuation.
 */
private class FakeInnerContinuation(val completion: Runnable) : Runnable {
  override fun run() {
    completion.run()
  }

  override fun toString(): String = "FakeInnerContinuation"
}

/** Stand-in for `kotlinx.coroutines.CancellableContinuationImpl` whose `delegate` field points to the wrapped `DispatchedContinuation`. */
private class FakeCancellableContinuation(val delegate: Runnable) : Runnable {
  override fun run() {
    delegate.run()
  }

  override fun toString(): String = "FakeCancellableContinuation"
}

class ComposeUiDispatcherStateTest {
  @JvmField @Rule val tmpFolder = TemporaryFolder()

  private val dispatcher = FakeAndroidUiDispatcher.Main[FakeAndroidUiDispatcher]!!

  @Before
  fun setUp() {
    dispatcher.clear()
  }

  @Test
  fun resetClearsTheSchedulingFlagsOfMain() {
    dispatcher.loseCallback()
    assertTrue(dispatcher.isAnyDispatchScheduled)
    assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))

    resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)

    assertFalse(dispatcher.isAnyDispatchScheduled)
    assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
    // Nothing is queued, so there is no need to post a callback.
    assertEquals(0, dispatcher.postedCallbacks)
    assertEquals(0, dispatcher.handlerCallbackCount)
    assertEquals(0, dispatcher.choreographerCallbackCount)
  }

  @Test
  fun resetPostsACallbackForQueuedWork() {
    val work = Runnable {}
    dispatcher.loseCallback(queuedWork = work)
    assertTrue(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))

    resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)

    // A new callback is posted, so the queued work runs the next time callbacks are executed, and the queued work is kept.
    assertEquals(1, dispatcher.postedCallbacks)
    assertEquals(1, dispatcher.handlerCallbackCount)
    assertEquals(1, dispatcher.choreographerCallbackCount)
    assertTrue(dispatcher.isAnyDispatchScheduled)
    assertTrue(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
    assertEquals(work, dispatcher.queuedWork.first())
  }

  @Test
  fun resetPostsACallbackForQueuedFrameWork() {
    val frameWork = Any()
    dispatcher.loseCallback(queuedFrameWork = frameWork)
    assertTrue(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))

    resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)

    assertEquals(1, dispatcher.postedCallbacks)
    assertEquals(1, dispatcher.handlerCallbackCount)
    assertEquals(1, dispatcher.choreographerCallbackCount)
    assertTrue(dispatcher.isAnyDispatchScheduled)
    assertTrue(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
    assertEquals(frameWork, dispatcher.queuedFrameWork.single())
  }

  @Test
  fun resetDoesNotDuplicateCallbacksAlreadyPostedToCurrentSession() {
    val work = Runnable {}
    val frameWork = Any()
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, work)
    dispatcher.postFrameCallback(frameWork)
    assertEquals(1, dispatcher.handlerCallbackCount)
    assertEquals(1, dispatcher.choreographerCallbackCount)

    resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)

    // The existing Handler and Choreographer callbacks in the current session are replaced rather than duplicated.
    assertEquals(1, dispatcher.handlerCallbackCount)
    assertEquals(1, dispatcher.choreographerCallbackCount)
    assertTrue(dispatcher.isAnyDispatchScheduled)
    assertEquals(work, dispatcher.queuedWork.first())
    assertEquals(frameWork, dispatcher.queuedFrameWork.single())
  }

  @Test
  fun clearDropsQueuedWorkAndClearsScheduledCallbacks() {
    val work = Runnable {}
    val frameWork = Any()
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, work)
    dispatcher.postFrameCallback(frameWork)
    assertTrue(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
    assertEquals(1, dispatcher.handlerCallbackCount)
    assertEquals(1, dispatcher.choreographerCallbackCount)

    clearAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java)

    assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
    assertFalse(dispatcher.isAnyDispatchScheduled)
    assertTrue(dispatcher.queuedWork.isEmpty())
    assertTrue(dispatcher.queuedFrameWork.isEmpty())
    assertEquals(0, dispatcher.handlerCallbackCount)
    assertEquals(0, dispatcher.choreographerCallbackCount)
  }

  @Test
  fun clearDrainsGlobalSnapshotManagerTasksAndClearsFollowUpWork() {
    var snapshotTaskRan = false
    var regularTaskRan = false
    var followUpTaskRan = false

    val snapshotTask =
      GlobalSnapshotManagerTestStandIn(
        Runnable {
          snapshotTaskRan = true
          // GlobalSnapshotManager.sendApplyNotifications() may schedule follow-up work on the
          // dispatcher; clearAndroidUiDispatcherWork must clear that follow-up work as well.
          dispatcher.dispatch(FakeAndroidUiDispatcher.Main, Runnable { followUpTaskRan = true })
          dispatcher.postFrameCallback(Runnable { followUpTaskRan = true })
        }
      )
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, Runnable { regularTaskRan = true })
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, snapshotTask)

    clearAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java)

    assertTrue(snapshotTaskRan)
    assertFalse(regularTaskRan)
    assertFalse(followUpTaskRan)
    assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
    assertFalse(dispatcher.isAnyDispatchScheduled)
    assertTrue(dispatcher.queuedWork.isEmpty())
    assertTrue(dispatcher.queuedFrameWork.isEmpty())
    assertEquals(0, dispatcher.handlerCallbackCount)
    assertEquals(0, dispatcher.choreographerCallbackCount)
  }

  @Test
  fun clearDrainsDispatchedContinuationWrappedGlobalSnapshotManagerTask() {
    var snapshotTaskRan = false
    var regularTaskRan = false

    // Matches production kotlinx.coroutines wrapping: CancellableContinuationImpl.delegate -> DispatchedContinuation.continuation ->
    // inner Continuation.completion -> GlobalSnapshotManager$... without relying on toString().
    val wrappedSnapshotTask =
      FakeCancellableContinuation(
        FakeDispatchedContinuation(
          FakeInnerContinuation(
            GlobalSnapshotManagerTestStandIn.EnsureStartedContinuation(
              Runnable {
                snapshotTaskRan = true
                dispatcher.dispatch(FakeAndroidUiDispatcher.Main, Runnable { regularTaskRan = true })
              }
            )
          )
        )
      )
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, Runnable { regularTaskRan = true })
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, wrappedSnapshotTask)

    clearAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java)

    assertTrue(snapshotTaskRan)
    assertFalse(regularTaskRan)
    assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
  }

  @Test
  fun clearDoesNotInvokeToStringOrRunNonGlobalSnapshotManagerTaskWithMisleadingToString() {
    var toStringInvoked = false
    var misleadingTaskRan = false

    val misleadingTask =
      object : Runnable {
        override fun run() {
          misleadingTaskRan = true
        }

        override fun toString(): String {
          toStringInvoked = true
          return "DispatchedContinuation[AndroidUiDispatcher@1, Continuation at androidx.compose.ui.platform.GlobalSnapshotManager\$ensureStarted\$1@2]"
        }
      }
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, misleadingTask)

    clearAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java)

    assertFalse(toStringInvoked)
    assertFalse(misleadingTaskRan)
    assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
  }

  @Test
  fun clearDrainsMultiPassGlobalSnapshotManagerTasksAndClearsFollowUpWork() {
    var firstPassRan = false
    var secondPassRan = false
    var followUpUserTaskRan = false

    val secondPassGsmTask =
      FakeDispatchedContinuation(
        GlobalSnapshotManagerTestStandIn.EnsureStartedContinuation(
          Runnable {
            secondPassRan = true
            dispatcher.dispatch(FakeAndroidUiDispatcher.Main, Runnable { followUpUserTaskRan = true })
          }
        )
      )
    val firstPassGsmTask =
      FakeDispatchedContinuation(
        GlobalSnapshotManagerTestStandIn.EnsureStartedContinuation(
          Runnable {
            firstPassRan = true
            // Re-dispatches the GSM coroutine itself alongside regular follow-up work.
            dispatcher.dispatch(FakeAndroidUiDispatcher.Main, secondPassGsmTask)
            dispatcher.dispatch(FakeAndroidUiDispatcher.Main, Runnable { followUpUserTaskRan = true })
          }
        )
      )
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, firstPassGsmTask)

    clearAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java)

    assertTrue(firstPassRan)
    assertTrue(secondPassRan)
    assertFalse(followUpUserTaskRan)
    assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
    assertFalse(dispatcher.isAnyDispatchScheduled)
  }

  @Test
  fun clearRequeuesGlobalSnapshotManagerTaskStillPendingAfterMaxDrainPassesAndClearsFollowUpWork() {
    var passCount = 0
    var followUpUserTaskRan = false
    lateinit var selfRedispatchingGsmTask: Runnable
    selfRedispatchingGsmTask =
      FakeDispatchedContinuation(
        GlobalSnapshotManagerTestStandIn.EnsureStartedContinuation(
          Runnable {
            passCount++
            dispatcher.dispatch(FakeAndroidUiDispatcher.Main, selfRedispatchingGsmTask)
            dispatcher.dispatch(FakeAndroidUiDispatcher.Main, Runnable { followUpUserTaskRan = true })
            dispatcher.postFrameCallback(Runnable { followUpUserTaskRan = true })
          }
        )
      )
    dispatcher.dispatch(FakeAndroidUiDispatcher.Main, selfRedispatchingGsmTask)

    clearAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java)

    assertEquals(MAX_GLOBAL_SNAPSHOT_DRAIN_PASSES, passCount)
    assertFalse(followUpUserTaskRan)
    // Follow-up frame work and non-GSM tasks are cleared, scheduling flags are reset, and only the remaining GSM task is requeued so the
    // next preview's resetAndroidUiDispatcherScheduling can re-post a callback for it instead of permanently dropping it.
    assertTrue(dispatcher.queuedFrameWork.isEmpty())
    assertEquals(listOf(selfRedispatchingGsmTask), dispatcher.queuedWork)
    assertFalse(dispatcher.isAnyDispatchScheduled)
    assertTrue(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))

    resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)
    assertEquals(1, dispatcher.handlerCallbackCount)
    assertTrue(dispatcher.isAnyDispatchScheduled)
  }

  @Test
  fun isGlobalSnapshotManagerClassNameMatchesExactAndInnerClassesOnly() {
    assertTrue(isGlobalSnapshotManagerClassName("androidx.compose.ui.platform.GlobalSnapshotManager"))
    assertTrue(isGlobalSnapshotManagerClassName("androidx.compose.ui.platform.GlobalSnapshotManager\$ensureStarted\$1"))
    assertFalse(isGlobalSnapshotManagerClassName("androidx.compose.ui.platform.GlobalSnapshotManagerHelper"))
    assertFalse(isGlobalSnapshotManagerClassName("androidx.compose.ui.platform.AndroidUiDispatcher"))
  }

  @Test
  fun transientOperationFailureStillResetsFlagsLogsFineThenWarningOnceAndDoesNotPoisonHandle() {
    val logger = Logger.getLogger("com.android.tools.render.ComposeUiDispatcherState")
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
      // 1. Even if handler.removeCallbacks throws during resetAndroidUiDispatcherScheduling, the scheduling flags are still cleared in
      // finally and queued work still triggers a fresh dispatch.
      val work = Runnable {}
      dispatcher.loseCallback(queuedWork = work)
      dispatcher.handler.failNextRemoveCallbacks = true
      resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)
      assertEquals(1, dispatcher.handlerCallbackCount)
      assertTrue(dispatcher.isAnyDispatchScheduled)
      assertEquals(listOf(Level.FINE), records.map { it.level })

      // 2. A second failure logs at WARNING; a third failure does not log another WARNING.
      dispatcher.handler.failNextRemoveCallbacks = true
      clearAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java)
      assertFalse(dispatcher.isAnyDispatchScheduled)
      assertFalse(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
      assertEquals(listOf(Level.FINE, Level.WARNING), records.map { it.level })

      dispatcher.loseCallback()
      dispatcher.handler.failNextRemoveCallbacks = true
      resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)
      assertFalse(dispatcher.isAnyDispatchScheduled)
      assertEquals(listOf(Level.FINE, Level.WARNING), records.map { it.level })

      // 3. Subsequent calls still work because the reflective handle lookup succeeded and was not marked Failed.
      dispatcher.loseCallback(queuedWork = work)
      assertTrue(hasPendingAndroidUiDispatcherWork(FakeAndroidUiDispatcher::class.java))
      resetAndroidUiDispatcherScheduling(FakeAndroidUiDispatcher::class.java)
      assertEquals(1, dispatcher.handlerCallbackCount)
    } finally {
      logger.removeHandler(testHandler)
      logger.useParentHandlers = previousUseParentHandlers
      logger.level = previousLevel
    }
  }

  @Test
  fun resetAndClearDoNotInitializeMain() {
    val dispatcherClass = UninitializedFakeAndroidUiDispatcher::class.java

    assertFalse(hasPendingAndroidUiDispatcherWork(dispatcherClass))
    resetAndroidUiDispatcherScheduling(dispatcherClass)
    clearAndroidUiDispatcherWork(dispatcherClass)

    val mainDelegate = dispatcherClass.getDeclaredField("Main\$delegate").apply { isAccessible = true }.get(null) as Lazy<*>
    assertFalse(mainDelegate.isInitialized())
  }

  @Test
  fun classLoaderWithoutAndroidUiDispatcherIsNoOp() {
    val emptyClassLoader =
      object : ClassLoader(null) {
        override fun findClass(name: String): Class<*> = throw ClassNotFoundException(name)
      }

    resetComposeUiDispatcherScheduling(emptyClassLoader)
    clearComposeUiDispatcherWork(emptyClassLoader)
    assertFalse(hasPendingComposeUiDispatcherWork(emptyClassLoader))
  }

  @Test
  fun brokenDispatcherClassCachesFailureLogsWarningOnceAndConservativelyReportsPendingWork() {
    // Ensure Main is initialized on the broken class so getInitializedHandle attempts to inspect its fields.
    assertNotNull(BrokenFakeAndroidUiDispatcher.Main[BrokenFakeAndroidUiDispatcher])

    val logger = Logger.getLogger("com.android.tools.render.ComposeUiDispatcherState")
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
      resetAndroidUiDispatcherScheduling(BrokenFakeAndroidUiDispatcher::class.java)
      clearAndroidUiDispatcherWork(BrokenFakeAndroidUiDispatcher::class.java)
      assertTrue(hasPendingAndroidUiDispatcherWork(BrokenFakeAndroidUiDispatcher::class.java))
      assertTrue(hasPendingAndroidUiDispatcherWork(BrokenFakeAndroidUiDispatcher::class.java))
    } finally {
      logger.removeHandler(testHandler)
      logger.useParentHandlers = previousUseParentHandlers
      logger.level = previousLevel
    }

    assertEquals(1, records.size)
    assertEquals(Level.WARNING, records.single().level)
  }

  @Test
  fun realAndroidUiDispatcherAndGlobalSnapshotManagerFromComposePrebuiltsMatchReflectiveContract() {
    val urls = buildIsolatedComposeClassLoaderUrls()
    newIsolatedComposeClassLoader(urls).use { composeClassLoader ->
      // Verify the real GlobalSnapshotManager coroutine class in ui-android matches isGlobalSnapshotManagerClassName.
      val gsmLambdaClass = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager\$ensureStarted\$1", false, composeClassLoader)
      assertTrue(isGlobalSnapshotManagerClassName(gsmLambdaClass.name))

      val dispatcherClass = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher", false, composeClassLoader)
      // Before Main is initialized, reflective helpers must not force initialization.
      assertFalse(hasPendingComposeUiDispatcherWork(composeClassLoader))
      resetComposeUiDispatcherScheduling(composeClassLoader)
      clearComposeUiDispatcherWork(composeClassLoader)
      val mainDelegate = dispatcherClass.getDeclaredField("Main\$delegate").apply { isAccessible = true }.get(null) as Lazy<*>
      assertFalse(mainDelegate.isInitialized())

      val executor = Executors.newSingleThreadExecutor()
      try {
        executor
          .submit(
            Callable {
              // Initialize AndroidUiDispatcher.Main on the dedicated main-looper thread.
              val companion = dispatcherClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
              val mainContext = companion.javaClass.getMethod("getMain").invoke(companion) as CoroutineContext
              assertTrue(mainDelegate.isInitialized())

              assertFalse(hasPendingComposeUiDispatcherWork(composeClassLoader))
              val stateAfterLookup = handleStates.get(dispatcherClass).get()
              assertTrue(
                "Expected InitializedDispatcherHandle for real AndroidUiDispatcher, got $stateAfterLookup",
                stateAfterLookup is DispatcherHandleState.Initialized,
              )
              val handle = (stateAfterLookup as DispatcherHandleState.Initialized).handle
              assertNotNull("Expected real AndroidUiDispatcher.choreographer to be found", handle.choreographer)
              assertNotNull("Expected real AndroidUiDispatcher.handler to be found", handle.handler)
              assertNotNull("Expected real AndroidUiDispatcher.dispatchCallback to be found", handle.dispatchCallback)
              assertNotNull("Expected real Choreographer.removeFrameCallback to be found", handle.removeFrameCallback)
              assertNotNull("Expected real Handler.removeCallbacks to be found", handle.removeHandlerCallbacks)

              // Start the real GlobalSnapshotManager on AndroidUiDispatcher.Main and verify that the kotlinx.coroutines task it queues is
              // recognized by isGlobalSnapshotManagerTask and flushed (while non-GSM user work is dropped) by clearComposeUiDispatcherWork.
              val gsmClass = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager", false, composeClassLoader)
              val gsmInstance = gsmClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
              gsmClass.getMethod("ensureStarted").apply { isAccessible = true }.invoke(gsmInstance)

              val queuedAfterGsmStart =
                synchronized(handle.lock) {
                  (handle.toRunTrampolined.get(handle.dispatcher) as Collection<*>).filterIsInstance<Runnable>()
                }
              assertEquals(1, queuedAfterGsmStart.size)
              assertTrue(
                "Expected real GlobalSnapshotManager coroutine continuation to be recognized as a GSM task",
                isGlobalSnapshotManagerTask(queuedAfterGsmStart.single()),
              )

              val dispatcherElement = handle.dispatcher
              val dispatchMethod = dispatcherClass.getMethod("dispatch", CoroutineContext::class.java, Runnable::class.java)
              var userTaskRan = false
              dispatchMethod.invoke(dispatcherElement, mainContext, Runnable { userTaskRan = true })
              assertFalse(
                "User Runnable must not be classified as a GlobalSnapshotManager task",
                isGlobalSnapshotManagerTask(
                  synchronized(handle.lock) {
                    (handle.toRunTrampolined.get(handle.dispatcher) as Collection<*>).filterIsInstance<Runnable>().last()
                  }
                ),
              )
              assertTrue(hasPendingComposeUiDispatcherWork(composeClassLoader))

              resetComposeUiDispatcherScheduling(composeClassLoader)
              assertTrue(hasPendingComposeUiDispatcherWork(composeClassLoader))

              clearComposeUiDispatcherWork(composeClassLoader)
              assertFalse(hasPendingComposeUiDispatcherWork(composeClassLoader))
              assertFalse(userTaskRan)
              assertSame(stateAfterLookup, handleStates.get(dispatcherClass).get())
            }
          )
          .get(30, TimeUnit.SECONDS)
      } finally {
        executor.shutdownNow()
      }
    }
  }

  @Test
  fun warmGlobalSnapshotObserverRestoresAfterSnapshotKtClearAndDoesNotRetainClosedClassLoader() {
    val urls = buildIsolatedComposeClassLoaderUrls()
    val loaderRef = exerciseWarmGlobalSnapshotObserverAndCloseLoader(urls)

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline && loaderRef.get() != null) {
      GCUtil.tryGcSoftlyReachableObjects()
      System.gc()
      Thread.sleep(10)
    }
    assertNull("Closed Compose ClassLoader must not be retained by ClassValue caches", loaderRef.get())
  }

  private fun exerciseWarmGlobalSnapshotObserverAndCloseLoader(urls: Array<URL>): WeakReference<ClassLoader> {
    newIsolatedComposeClassLoader(urls).use { composeClassLoader ->
      // Verify non-DefaultModuleClassLoader fallback in findAlreadyLoadedModuleOrWarmClass.
      assertNull(findAlreadyLoadedModuleOrWarmClass(composeClassLoader, "com.example.NonExistentClass"))
      val snapshotKtClass = findAlreadyLoadedModuleOrWarmClass(composeClassLoader, "androidx.compose.runtime.snapshots.SnapshotKt")
      assertNotNull("Expected SnapshotKt to be resolved via non-DefaultModuleClassLoader fallback", snapshotKtClass)
      assertSame(composeClassLoader, snapshotKtClass!!.classLoader)

      val dispatcherClass = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher", false, composeClassLoader)
      val gsmClass = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager", false, composeClassLoader)
      val snapshotStateKtClass = Class.forName("androidx.compose.runtime.SnapshotStateKt", false, composeClassLoader)
      val mutableStateClass = Class.forName("androidx.compose.runtime.MutableState", false, composeClassLoader)
      val policyClass = Class.forName("androidx.compose.runtime.SnapshotMutationPolicy", false, composeClassLoader)

      val executor = Executors.newSingleThreadExecutor()
      try {
        executor
          .submit(
            Callable {
              // Initialize AndroidUiDispatcher.Main and start GlobalSnapshotManager.
              val companion = dispatcherClass.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
              companion.javaClass.getMethod("getMain").invoke(companion)
              val gsmInstance = gsmClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
              gsmClass.getMethod("ensureStarted").apply { isAccessible = true }.invoke(gsmInstance)

              // Draining per-render work captures GlobalSnapshotManager$ensureStarted$2 into ClassValue and suspends the GSM coroutine on
              // channel.receive().
              clearComposeUiDispatcherWork(composeClassLoader)
              assertFalse(hasPendingComposeUiDispatcherWork(composeClassLoader))

              val policy = snapshotStateKtClass.getMethod("structuralEqualityPolicy").invoke(null)
              val mutableState = snapshotStateKtClass.getMethod("mutableStateOf", Any::class.java, policyClass).invoke(null, "v0", policy)
              val setValueMethod = mutableStateClass.getMethod("setValue", Any::class.java)

              val observersField = snapshotKtClass.getDeclaredField("globalWriteObservers").apply { isAccessible = true }
              val advanceGlobalSnapshotMethod =
                snapshotKtClass.declaredMethods.first { it.name == "advanceGlobalSnapshot" && it.parameterCount == 0 }.apply {
                  isAccessible = true
                }

              // Simulate RenderSessionCleaner.dispose() clearing SnapshotKt.globalWriteObservers while SnapshotKt and GlobalSnapshotManager
              // remain warm in the parent ClassLoader.
              val oldObserversList = observersField.get(null) as MutableCollection<*>
              assertEquals(1, oldObserversList.size)
              oldObserversList.clear()
              advanceGlobalSnapshotMethod.invoke(null)
              assertTrue((observersField.get(null) as Collection<*>).isEmpty())

              // While cleared, mutating state does not notify GlobalSnapshotManager.
              setValueMethod.invoke(mutableState, "while-cleared")
              assertFalse(hasPendingComposeUiDispatcherWork(composeClassLoader))

              // Restoring the warm write observer replaces globalWriteObservers with a fresh ArrayList (immune to delayed old-list clears)
              // and advances the global snapshot so subsequent state mutations trigger GlobalSnapshotManager again.
              restoreWarmGlobalSnapshotObserver(composeClassLoader)
              val restoredList = observersField.get(null) as Collection<*>
              assertEquals(1, restoredList.size)
              assertNotSame(oldObserversList, restoredList)
              oldObserversList.clear()
              assertEquals(1, (observersField.get(null) as Collection<*>).size)

              setValueMethod.invoke(mutableState, "after-restore")
              assertTrue(hasPendingComposeUiDispatcherWork(composeClassLoader))
              clearComposeUiDispatcherWork(composeClassLoader)
              assertFalse(hasPendingComposeUiDispatcherWork(composeClassLoader))

              // If an off-thread write raced with queue teardown (leaving sent == true with no GSM continuation queued), restoring resets
              // sent and started so the next ensureStarted() cleanly re-registers GlobalSnapshotManager.
              val sentFlag = gsmClass.getDeclaredField("sent").apply { isAccessible = true }.get(null) as AtomicBoolean
              val startedFlag = gsmClass.getDeclaredField("started").apply { isAccessible = true }.get(null) as AtomicBoolean
              sentFlag.set(true)
              restoreWarmGlobalSnapshotObserver(composeClassLoader)
              assertFalse(sentFlag.get())
              assertFalse(startedFlag.get())
            }
          )
          .get(30, TimeUnit.SECONDS)
      } finally {
        executor.shutdownNow()
      }
      return WeakReference(composeClassLoader)
    }
  }

  private fun buildIsolatedComposeClassLoaderUrls(): Array<URL> {
    val aarPaths =
      listOf(
        "androidx/compose/ui/ui-android/1.9.5/ui-android-1.9.5.aar",
        "androidx/compose/runtime/runtime-android/1.9.5/runtime-android-1.9.5.aar",
        "androidx/core/core/1.15.0/core-1.15.0.aar",
      )
    val extractedJars = aarPaths.map { mavenPath ->
      val aarFile = TestUtils.getLocalMavenRepoFile(mavenPath).toFile()
      assertTrue("Missing prebuilt AAR: $aarFile", aarFile.isFile)
      val outJar = tmpFolder.newFile()
      ZipFile(aarFile).use { zip ->
        val entry = zip.getEntry("classes.jar")
        assertNotNull("Missing classes.jar in $aarFile", entry)
        zip.getInputStream(entry).use { input ->
          Files.copy(input, outJar.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
      }
      outJar.toURI().toURL()
    }
    val collectionJar = TestUtils.getLocalMavenRepoFile("androidx/collection/collection-jvm/1.5.0/collection-jvm-1.5.0.jar").toFile()
    assertTrue("Missing prebuilt JAR: $collectionJar", collectionJar.isFile)
    // Compile minimal pure-Java android.os.Looper, android.os.Handler, and android.view.Choreographer so the real
    // AndroidUiDispatcher.Main from ui-android.aar can be initialized in a unit test without loading layoutlib's native JNI library.
    val androidStubsDir = compileAndroidUiDispatcherPlatformStubs()
    return (listOf(androidStubsDir.toURI().toURL(), collectionJar.toURI().toURL()) + extractedJars).toTypedArray()
  }

  private fun newIsolatedComposeClassLoader(urls: Array<URL>): URLClassLoader =
    object : URLClassLoader(urls, ComposeUiDispatcherStateTest::class.java.classLoader) {
      override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
          val loaded = findLoadedClass(name)
          val clazz =
            loaded
              ?: if (
                name == "android.os.Looper" ||
                  name == "android.os.Handler" ||
                  name.startsWith("android.os.Build") ||
                  name.startsWith("android.view.Choreographer") ||
                  name.startsWith("androidx.")
              ) {
                try {
                  findClass(name)
                } catch (_: ClassNotFoundException) {
                  super.loadClass(name, false)
                }
              } else {
                super.loadClass(name, false)
              }
          if (resolve) resolveClass(clazz)
          clazz
        }
    }

  private fun compileAndroidUiDispatcherPlatformStubs(): File {
    val srcDir = tmpFolder.newFolder()
    val outDir = tmpFolder.newFolder()
    val sources =
      listOf(
        Triple(
          "android.os",
          "Build",
          """
          package android.os;
          public class Build {
            public static class VERSION {
              public static final int SDK_INT = 34;
            }
          }
          """
            .trimIndent(),
        ),
        Triple(
          "android.os",
          "Looper",
          """
          package android.os;
          public class Looper {
            private static final Looper MAIN = new Looper();
            public static Looper myLooper() { return MAIN; }
            public static Looper getMainLooper() { return MAIN; }
            public Thread getThread() { return Thread.currentThread(); }
          }
          """
            .trimIndent(),
        ),
        Triple(
          "android.os",
          "Handler",
          """
          package android.os;
          import java.util.ArrayList;
          import java.util.List;
          public class Handler {
            public final List<Runnable> callbacks = new ArrayList<>();
            public Handler() {}
            public Handler(Looper looper) {}
            public static Handler createAsync(Looper looper) { return new Handler(looper); }
            public boolean post(Runnable r) { callbacks.add(r); return true; }
            public void removeCallbacks(Runnable r) { callbacks.removeIf(it -> it == r); }
          }
          """
            .trimIndent(),
        ),
        Triple(
          "android.view",
          "Choreographer",
          """
          package android.view;
          import java.util.ArrayList;
          import java.util.List;
          public class Choreographer {
            public interface FrameCallback {
              void doFrame(long frameTimeNanos);
            }
            private static final Choreographer INSTANCE = new Choreographer();
            public final List<FrameCallback> callbacks = new ArrayList<>();
            public static Choreographer getInstance() { return INSTANCE; }
            public void postFrameCallback(FrameCallback callback) { callbacks.add(callback); }
            public void removeFrameCallback(FrameCallback callback) { callbacks.removeIf(it -> it == callback); }
          }
          """
            .trimIndent(),
        ),
      )
    val javaFiles = sources.map { (pkg, className, javaSource) ->
      val pkgDir = File(srcDir, pkg.replace('.', File.separatorChar)).apply { mkdirs() }
      File(pkgDir, "$className.java").apply { writeText(javaSource) }.absolutePath
    }
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
    assertNotNull("System Java compiler is required", compiler)
    val args = (listOf("-d", outDir.absolutePath) + javaFiles).toTypedArray()
    assertEquals(0, compiler.run(null, null, null, *args))
    return outDir
  }

  @Test
  fun restoreWarmGlobalSnapshotObserverRestoresInPlaceWhenGlobalWriteObserversFieldIsStaticFinal() {
    val srcDir = tmpFolder.newFolder()
    val outDir = tmpFolder.newFolder()
    val snapshotPkg = File(srcDir, "androidx/compose/runtime/snapshots").apply { mkdirs() }
    val uiPkg = File(srcDir, "androidx/compose/ui/platform").apply { mkdirs() }
    val snapshotFile =
      File(snapshotPkg, "SnapshotKt.java").apply {
        writeText(
          """
          package androidx.compose.runtime.snapshots;
          import java.util.ArrayList;
          import java.util.List;
          public final class SnapshotKt {
            private static final Object lock = new Object();
            // Mirrors Compose 1.5.x where globalWriteObservers is `private static final List`.
            private static final List<Object> globalWriteObservers = new ArrayList<>();
            public static int advanceCount = 0;
            public static List<Object> getObserversForTest() { return globalWriteObservers; }
            public static void advanceGlobalSnapshot() { advanceCount++; }
          }
          """
            .trimIndent()
        )
      }
    val gsmFile =
      File(uiPkg, "GlobalSnapshotManager.java").apply {
        writeText(
          """
          package androidx.compose.ui.platform;
          public final class GlobalSnapshotManager {
            public static final class WriteObserver {}
          }
          """
            .trimIndent()
        )
      }
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
    assertNotNull("System Java compiler is required", compiler)
    assertEquals(0, compiler.run(null, null, null, "-d", outDir.absolutePath, snapshotFile.absolutePath, gsmFile.absolutePath))

    java.net.URLClassLoader(arrayOf(outDir.toURI().toURL()), null).use { cl ->
      val snapshotKtClass = Class.forName("androidx.compose.runtime.snapshots.SnapshotKt", true, cl)
      val observerClass = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager\$WriteObserver", true, cl)
      val observer = observerClass.getDeclaredConstructor().newInstance()
      @Suppress("UNCHECKED_CAST")
      val observers = snapshotKtClass.getMethod("getObserversForTest").invoke(null) as MutableList<Any?>
      observers.add(observer)

      captureWarmGlobalSnapshotObserver(cl)
      observers.clear()
      assertTrue(observers.isEmpty())

      restoreWarmGlobalSnapshotObserver(cl)
      assertEquals(1, observers.size)
      assertSame(observer, observers.single())
      assertEquals(1, snapshotKtClass.getField("advanceCount").getInt(null))
    }
  }
}
