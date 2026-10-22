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

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

private const val ANDROID_UI_DISPATCHER_FQN = "androidx.compose.ui.platform.AndroidUiDispatcher"
private const val GLOBAL_SNAPSHOT_MANAGER_FQN = "androidx.compose.ui.platform.GlobalSnapshotManager"
private const val GLOBAL_SNAPSHOT_MANAGER_TEST_STAND_IN_FQN = "androidx.compose.ui.platform.GlobalSnapshotManagerTestStandIn"

/** Maximum number of drain passes when flushing `GlobalSnapshotManager` tasks in [clearAndroidUiDispatcherWork]. */
internal const val MAX_GLOBAL_SNAPSHOT_DRAIN_PASSES = 3

/**
 * Maximum depth when unwrapping coroutine continuation fields (`continuation`, `completion`, `delegate`, `uCont`) in
 * [isGlobalSnapshotManagerTask].
 */
private const val MAX_CONTINUATION_UNWRAP_DEPTH = 8

private val logger = Logger.getLogger("com.android.tools.render.ComposeUiDispatcherState")

private val EMPTY_RUNNABLE = Runnable {}

/**
 * Makes sure that the work queued in the Compose `AndroidUiDispatcher.Main` loaded by [classLoader] runs the next time layoutlib callbacks
 * are executed. Must be called on the render thread, inside the render session whose callbacks are going to be executed, because it can
 * post a callback to the main `Handler`, and layoutlib keeps the posted callbacks per render session.
 *
 * `AndroidUiDispatcher` only posts its dispatch callback to the `Handler` and `Choreographer` while `scheduledTrampolineDispatch` and
 * `scheduledFrameDispatch` are `false`, and only that callback sets them back to `false`. `AndroidUiDispatcher.Main` is static, so it is
 * shared by every render session that uses the same class loader. If a callback is posted to a session that ends before executing it (for
 * example by a preview that timed out or failed before disposal completed), both flags stay `true`. From then on, the work dispatched by
 * later previews (`LaunchedEffect`s, animation frames, `FocusRequester.requestFocus()` called from an effect...) is queued without posting
 * a callback, and never runs.
 *
 * This removes `dispatchCallback` from the current session's `Choreographer` and `Handler`, clears both scheduling flags, and, if there is
 * queued work (such as effects queued during the current session's inflation), dispatches an empty task so that a single callback is posted
 * to the current session. Leftover work from a finished preview is cleared when that preview is disposed via
 * [clearComposeUiDispatcherWork].
 *
 * Does nothing if [classLoader] cannot load `AndroidUiDispatcher` or `AndroidUiDispatcher.Main` has not been initialized yet.
 */
internal fun resetComposeUiDispatcherScheduling(classLoader: ClassLoader) {
  val dispatcherClass = findAndroidUiDispatcherClass(classLoader) ?: return
  resetAndroidUiDispatcherScheduling(dispatcherClass)
}

/**
 * Clears any pending user work queued in the Compose `AndroidUiDispatcher.Main` loaded by [classLoader] (`toRunTrampolined` and
 * `toRunOnFrame`), flushes or preserves Compose's `GlobalSnapshotManager` coroutine, removes `dispatchCallback` from the `Choreographer`
 * and `Handler`, and resets `scheduledTrampolineDispatch` and `scheduledFrameDispatch` to `false`.
 *
 * Called on the render thread when releasing a preview so that leftover or unscoped tasks queued on `AndroidUiDispatcher.Main` during one
 * preview cannot leak into and execute during the next preview.
 */
internal fun clearComposeUiDispatcherWork(classLoader: ClassLoader) {
  val dispatcherClass = findAndroidUiDispatcherClass(classLoader) ?: return
  clearAndroidUiDispatcherWork(dispatcherClass)
}

/**
 * Returns `true` if the Compose `AndroidUiDispatcher.Main` loaded by [classLoader] has been initialized and currently has work queued in
 * `toRunTrampolined` or `toRunOnFrame` (or if its reflective shape could not be inspected after initialization, so the bounded callback
 * loop runs all steps conservatively rather than stopping early).
 *
 * Returns `false` if [classLoader] cannot load `AndroidUiDispatcher`, `AndroidUiDispatcher.Main` has not been initialized yet, or a
 * transient operation error occurred.
 */
internal fun hasPendingComposeUiDispatcherWork(classLoader: ClassLoader): Boolean {
  val dispatcherClass = findAndroidUiDispatcherClass(classLoader) ?: return false
  return hasPendingAndroidUiDispatcherWork(dispatcherClass)
}

/**
 * Clears `scheduledTrampolineDispatch` and `scheduledFrameDispatch` on the `Main` instance of [dispatcherClass], which must have the shape
 * of `androidx.compose.ui.platform.AndroidUiDispatcher`, and dispatches an empty task if it has queued work. See
 * [resetComposeUiDispatcherScheduling].
 */
internal fun resetAndroidUiDispatcherScheduling(dispatcherClass: Class<*>) {
  withInitializedHandle(dispatcherClass, defaultValue = Unit) { handle ->
    var removeFailure: Throwable? = null
    // AndroidUiDispatcher only reads and writes these fields while holding `lock`.
    val hasQueuedWork =
      synchronized(handle.lock) {
        try {
          handle.clearScheduledCallbacksAndFlagsLocked()
        } catch (t: Throwable) {
          removeFailure = t
        }
        handle.hasQueuedWorkLocked()
      }
    if (hasQueuedWork) {
      handle.dispatchMethod.invoke(handle.dispatcher, handle.mainContext, EMPTY_RUNNABLE)
    }
    removeFailure?.let { throw it }
  }
}

/**
 * Clears `toRunTrampolined`, `toRunOnFrame`, `scheduledTrampolineDispatch`, and `scheduledFrameDispatch` on the `Main` instance of
 * [dispatcherClass], and removes `dispatchCallback` from its `Choreographer` and `Handler`. See [clearComposeUiDispatcherWork].
 *
 * If Compose's classloader-wide `GlobalSnapshotManager` coroutine was queued in `toRunTrampolined` (for example by a state write during the
 * final `render()` pass), it is executed in a bounded loop (up to [MAX_GLOBAL_SNAPSHOT_DRAIN_PASSES] passes) so that its `sent` flag is
 * reset and its `channel.consumeEach` loop re-suspends on its channel while any follow-up recomposition work it schedules is cleared. If a
 * `GlobalSnapshotManager` task still re-dispatches itself on the final pass, it is put back into `toRunTrampolined` (to run in the next
 * preview's callback phase) rather than being permanently dropped.
 */
internal fun clearAndroidUiDispatcherWork(dispatcherClass: Class<*>) {
  withInitializedHandle(dispatcherClass, defaultValue = Unit) { handle ->
    var removeFailure: Throwable? = null
    fun drainAndResetLocked(): List<Runnable> =
      synchronized(handle.lock) {
        try {
          handle.drainTrampolinedAndClearQueuedWorkLocked()
        } finally {
          try {
            handle.clearScheduledCallbacksAndFlagsLocked()
          } catch (t: Throwable) {
            if (removeFailure == null) removeFailure = t
          }
        }
      }

    for (pass in 0 until MAX_GLOBAL_SNAPSHOT_DRAIN_PASSES) {
      val drainedTasks = drainAndResetLocked()
      // Inspect tasks outside `handle.lock` so reflection never runs while holding the dispatcher lock.
      val globalSnapshotTasks = drainedTasks.filter(::isGlobalSnapshotManagerTask)
      if (globalSnapshotTasks.isEmpty()) {
        removeFailure?.let { throw it }
        return@withInitializedHandle
      }
      for (task in globalSnapshotTasks) {
        logged(
          level = Level.FINE,
          message = { "Unable to flush GlobalSnapshotManager task while clearing ${dispatcherClass.name}.Main" },
          defaultValue = Unit,
          catchAssertionError = true,
        ) {
          task.run()
        }
      }
    }
    val remainingTasks = drainAndResetLocked()
    val remainingGlobalSnapshotTasks = remainingTasks.filter(::isGlobalSnapshotManagerTask)
    if (remainingGlobalSnapshotTasks.isNotEmpty()) {
      synchronized(handle.lock) {
        handle.requeueTrampolinedLocked(remainingGlobalSnapshotTasks)
      }
    }
    removeFailure?.let { throw it }
  }
}

/**
 * Returns `true` if the `Main` instance of [dispatcherClass] has been initialized and has work queued in `toRunTrampolined` or
 * `toRunOnFrame`, or if reflective lookup of its fields failed (`onLookupFailure = true`).
 */
internal fun hasPendingAndroidUiDispatcherWork(dispatcherClass: Class<*>): Boolean =
  withInitializedHandle(dispatcherClass, defaultValue = false, onLookupFailure = true) { handle ->
    synchronized(handle.lock) { handle.hasQueuedWorkLocked() }
  }

/**
 * Returns whether [task] is a resumption of Compose's `GlobalSnapshotManager` coroutine.
 *
 * In Compose, `AndroidUiDispatcher.dispatch` receives coroutine resumptions wrapped in `kotlinx.coroutines.internal.DispatchedContinuation`
 * whose `continuation` (and optional `BaseContinuationImpl.completion` chain) holds the `GlobalSnapshotManager$ensureStarted$1` suspend
 * lambda. We unwrap the `continuation`/`completion`/`delegate`/`uCont` chain by reflection and check the class name of each step, without
 * invoking `task.toString()` (which could run arbitrary user code on non-GSM tasks).
 */
internal fun isGlobalSnapshotManagerTask(task: Runnable): Boolean {
  var current: Any? = task
  for (depth in 0 until MAX_CONTINUATION_UNWRAP_DEPTH) {
    val obj = current ?: break
    if (isGlobalSnapshotManagerClassName(obj.javaClass.name)) return true
    val next =
      try {
        continuationFieldByClass.get(obj.javaClass)?.get(obj)
      } catch (_: Exception) {
        null
      } catch (_: LinkageError) {
        null
      }
    if (next == null || next === obj) break
    current = next
  }
  return false
}

internal fun isGlobalSnapshotManagerClassName(className: String): Boolean =
  className == GLOBAL_SNAPSHOT_MANAGER_FQN ||
    className.startsWith("$GLOBAL_SNAPSHOT_MANAGER_FQN$") ||
    className == GLOBAL_SNAPSHOT_MANAGER_TEST_STAND_IN_FQN ||
    className.startsWith("$GLOBAL_SNAPSHOT_MANAGER_TEST_STAND_IN_FQN$")

/**
 * Caches the wrapped continuation field (`continuation` on `DispatchedContinuation`, `completion` on `BaseContinuationImpl`, `delegate` on
 * `CancellableContinuationImpl`, or `uCont` on `ScopeCoroutine`) for a class so coroutine wrappers can be unwrapped without repeated
 * reflective searches.
 */
private val continuationFieldByClass =
  object : ClassValue<Field?>() {
    override fun computeValue(type: Class<*>): Field? {
      var curr: Class<*>? = type
      while (curr != null && curr != Any::class.java) {
        for (fieldName in listOf("continuation", "completion", "delegate", "uCont")) {
          try {
            return curr.getDeclaredField(fieldName).apply { isAccessible = true }
          } catch (_: NoSuchFieldException) {
            // Try the next candidate field name on this class before walking up to the superclass.
          } catch (_: SecurityException) {
            return null
          } catch (_: LinkageError) {
            return null
          }
        }
        curr = curr.superclass
      }
      return null
    }
  }

private fun findAndroidUiDispatcherClass(classLoader: ClassLoader): Class<*>? =
  logged(
    level = Level.FINE,
    message = { "Unable to load $ANDROID_UI_DISPATCHER_FQN" },
    defaultValue = null,
  ) {
    try {
      Class.forName(ANDROID_UI_DISPATCHER_FQN, false, classLoader)
    } catch (_: ClassNotFoundException) {
      null
    }
  }

private inline fun <T> logged(
  level: Level,
  message: () -> String,
  defaultValue: T,
  catchAssertionError: Boolean = false,
  block: () -> T,
): T =
  try {
    block()
  } catch (e: Exception) {
    logger.log(level, message(), e)
    defaultValue
  } catch (e: LinkageError) {
    logger.log(level, message(), e)
    defaultValue
  } catch (e: AssertionError) {
    if (catchAssertionError) {
      logger.log(level, message(), e)
      defaultValue
    } else {
      throw e
    }
  }

/**
 * Cached reflection handles for an initialized `AndroidUiDispatcher.Main` instance. Keyed via [ClassValue] so cached entries do not prevent
 * class loader unloading.
 */
internal class InitializedDispatcherHandle(
  val mainContext: Any,
  val dispatcher: Any,
  val lock: Any,
  val scheduledTrampolineDispatch: Field,
  val scheduledFrameDispatch: Field,
  val toRunTrampolined: Field,
  val toRunOnFrame: Field,
  val choreographer: Any?,
  val handler: Any?,
  val dispatchCallback: Any?,
  val removeFrameCallback: Method?,
  val removeHandlerCallbacks: Method?,
  val dispatchMethod: Method,
) {
  private val operationFailureCount = AtomicInteger(0)

  fun logOperationFailure(dispatcherClass: Class<*>, error: Throwable) {
    when (operationFailureCount.incrementAndGet()) {
      1 -> logger.log(Level.FINE, "Operation on ${dispatcherClass.name}.Main failed", error)
      2 -> logger.log(Level.WARNING, "Repeated operation failure on ${dispatcherClass.name}.Main", error)
    }
  }

  /** Must be called while holding [lock]. */
  fun clearScheduledCallbacksAndFlagsLocked() {
    try {
      try {
        if (choreographer != null && dispatchCallback != null) {
          removeFrameCallback?.invoke(choreographer, dispatchCallback)
        }
      } finally {
        if (handler != null && dispatchCallback != null) {
          removeHandlerCallbacks?.invoke(handler, dispatchCallback)
        }
      }
    } finally {
      // Always reset the scheduling flags even if removing a callback fails: a duplicate dispatchCallback is far less harmful than leaving
      // the flags stuck at true and preventing queued work in later previews from ever running.
      scheduledTrampolineDispatch.setBoolean(dispatcher, false)
      scheduledFrameDispatch.setBoolean(dispatcher, false)
    }
  }

  /** Must be called while holding [lock]. */
  fun hasQueuedWorkLocked(): Boolean {
    val trampolined =
      toRunTrampolined.get(dispatcher) as? Collection<*> ?: throw IllegalStateException("toRunTrampolined is not a Collection")
    val onFrame = toRunOnFrame.get(dispatcher) as? Collection<*> ?: throw IllegalStateException("toRunOnFrame is not a Collection")
    return trampolined.isNotEmpty() || onFrame.isNotEmpty()
  }

  /** Must be called while holding [lock]. Copies `toRunTrampolined` and clears both queues without invoking `toString()` on tasks. */
  fun drainTrampolinedAndClearQueuedWorkLocked(): List<Runnable> {
    val trampolined =
      toRunTrampolined.get(dispatcher) as? MutableCollection<*>
        ?: throw IllegalStateException("toRunTrampolined is not a MutableCollection")
    val onFrame =
      toRunOnFrame.get(dispatcher) as? MutableCollection<*> ?: throw IllegalStateException("toRunOnFrame is not a MutableCollection")
    val tasks = trampolined.mapNotNull { it as? Runnable }
    trampolined.clear()
    onFrame.clear()
    return tasks
  }

  /** Must be called while holding [lock]. */
  fun requeueTrampolinedLocked(tasks: List<Runnable>) {
    @Suppress("UNCHECKED_CAST")
    val trampolined =
      toRunTrampolined.get(dispatcher) as? MutableCollection<Runnable>
        ?: throw IllegalStateException("toRunTrampolined is not a MutableCollection")
    trampolined.addAll(tasks)
  }
}

internal sealed interface DispatcherHandleState {
  data object Uninitialized : DispatcherHandleState

  class Initialized(val handle: InitializedDispatcherHandle) : DispatcherHandleState

  data object Failed : DispatcherHandleState
}

internal val handleStates =
  object : ClassValue<AtomicReference<DispatcherHandleState>>() {
    override fun computeValue(type: Class<*>): AtomicReference<DispatcherHandleState> = AtomicReference(DispatcherHandleState.Uninitialized)
  }

private inline fun <T> withInitializedHandle(
  dispatcherClass: Class<*>,
  defaultValue: T,
  onLookupFailure: T = defaultValue,
  block: (InitializedDispatcherHandle) -> T,
): T {
  val stateRef = handleStates.get(dispatcherClass)
  val handle =
    try {
      getInitializedHandle(dispatcherClass, stateRef)
        ?: return if (stateRef.get() === DispatcherHandleState.Failed) onLookupFailure else defaultValue
    } catch (e: ReflectiveOperationException) {
      recordFailureOnce(dispatcherClass, stateRef, e)
      return onLookupFailure
    } catch (e: RuntimeException) {
      recordFailureOnce(dispatcherClass, stateRef, e)
      return onLookupFailure
    } catch (e: LinkageError) {
      recordFailureOnce(dispatcherClass, stateRef, e)
      return onLookupFailure
    }
  return try {
    block(handle)
  } catch (e: Exception) {
    handle.logOperationFailure(dispatcherClass, e)
    defaultValue
  } catch (e: LinkageError) {
    handle.logOperationFailure(dispatcherClass, e)
    defaultValue
  }
}

private fun recordFailureOnce(dispatcherClass: Class<*>, stateRef: AtomicReference<DispatcherHandleState>, error: Throwable) {
  if (stateRef.getAndSet(DispatcherHandleState.Failed) !== DispatcherHandleState.Failed) {
    logger.log(Level.WARNING, "Unable to inspect or reset ${dispatcherClass.name}.Main", error)
  }
}

private fun getInitializedHandle(
  dispatcherClass: Class<*>,
  stateRef: AtomicReference<DispatcherHandleState>,
): InitializedDispatcherHandle? {
  when (val state = stateRef.get()) {
    is DispatcherHandleState.Initialized -> return state.handle
    DispatcherHandleState.Failed -> return null
    DispatcherHandleState.Uninitialized -> {}
  }

  // `Main` is declared as `val Main: CoroutineContext by lazy { ... }` in the companion object, so its delegate is a static field of the
  // outer class.
  val mainDelegate = dispatcherClass.accessibleField("Main\$delegate").get(null) ?: return null
  // Never force the initialization: it creates the dispatcher, and the initializer can block in `runBlocking(Dispatchers.Main)` (see
  // RenderSessionCleaner in tools/adt/idea/rendering).
  val isInitialized = mainDelegate.javaClass.getMethod("isInitialized").apply { isAccessible = true }.invoke(mainDelegate) as Boolean
  if (!isInitialized) return null

  val mainContext =
    mainDelegate.javaClass.getMethod("getValue").apply { isAccessible = true }.invoke(mainDelegate)
      ?: throw IllegalStateException("${dispatcherClass.name}.Main is null")
  val dispatcher =
    findCoroutineContextElement(mainContext, dispatcherClass)
      ?: throw IllegalStateException("Cannot find ${dispatcherClass.name} in Main context ${mainContext.javaClass.name}")
  val lock = dispatcherClass.accessibleField("lock").get(dispatcher) ?: throw IllegalStateException("${dispatcherClass.name}.lock is null")
  val scheduledTrampolineDispatch = dispatcherClass.accessibleField("scheduledTrampolineDispatch")
  val scheduledFrameDispatch = dispatcherClass.accessibleField("scheduledFrameDispatch")
  val toRunTrampolined = dispatcherClass.accessibleField("toRunTrampolined")
  val toRunOnFrame = dispatcherClass.accessibleField("toRunOnFrame")
  synchronized(lock) {
    if (toRunTrampolined.get(dispatcher) !is MutableCollection<*>) {
      throw IllegalStateException("toRunTrampolined is not a MutableCollection")
    }
    if (toRunOnFrame.get(dispatcher) !is MutableCollection<*>) {
      throw IllegalStateException("toRunOnFrame is not a MutableCollection")
    }
  }
  // Remove `dispatchCallback` from the Choreographer (and call Handler.removeCallbacks, though in layoutlib Handler posts go to
  // SessionInteractiveData's per-session HandlerMessageQueue while removeCallbacks targets android.os.MessageQueue) before clearing the
  // scheduling flags.
  val choreographer = dispatcherClass.accessibleFieldOrNull("choreographer")?.get(dispatcher)
  val handler = dispatcherClass.accessibleFieldOrNull("handler")?.get(dispatcher)
  val dispatchCallback = dispatcherClass.accessibleFieldOrNull("dispatchCallback")?.get(dispatcher)
  val removeFrameCallback =
    choreographer
      ?.javaClass
      ?.methods
      ?.firstOrNull { it.name == "removeFrameCallback" && it.parameterCount == 1 }
      ?.apply { isAccessible = true }
  val removeHandlerCallbacks =
    handler
      ?.javaClass
      ?.methods
      ?.firstOrNull { it.name == "removeCallbacks" && it.parameterCount == 1 && it.parameterTypes[0] == Runnable::class.java }
      ?.apply { isAccessible = true }
  // Look the method up by shape rather than by parameter class names: the kotlin classes can be relocated (e.g. in the CLI deploy jar).
  // The dispatcher ignores the context, so `Main` itself, which is a CoroutineContext of the right class loader, is passed.
  val dispatchMethod =
    dispatcherClass.methods
      .first { it.name == "dispatch" && it.parameterCount == 2 && it.parameterTypes[1] == Runnable::class.java }
      .apply { isAccessible = true }

  val handle =
    InitializedDispatcherHandle(
      mainContext = mainContext,
      dispatcher = dispatcher,
      lock = lock,
      scheduledTrampolineDispatch = scheduledTrampolineDispatch,
      scheduledFrameDispatch = scheduledFrameDispatch,
      toRunTrampolined = toRunTrampolined,
      toRunOnFrame = toRunOnFrame,
      choreographer = choreographer,
      handler = handler,
      dispatchCallback = dispatchCallback,
      removeFrameCallback = removeFrameCallback,
      removeHandlerCallbacks = removeHandlerCallbacks,
      dispatchMethod = dispatchMethod,
    )
  val newState = DispatcherHandleState.Initialized(handle)
  stateRef.compareAndSet(DispatcherHandleState.Uninitialized, newState)
  return (stateRef.get() as? DispatcherHandleState.Initialized)?.handle ?: handle
}

/**
 * Returns the element of the coroutine [context] that is an instance of [type]. `AndroidUiDispatcher.Main` is `dispatcher + frameClock`,
 * which is a `kotlin.coroutines.CombinedContext` whose `left`/`element` fields hold the individual elements.
 */
private fun findCoroutineContextElement(context: Any?, type: Class<*>): Any? {
  var current = context
  while (current != null) {
    if (type.isInstance(current)) return current
    val currentClass = current.javaClass
    if (!currentClass.name.endsWith("CombinedContext")) return null
    val element = currentClass.accessibleField("element").get(current)
    if (type.isInstance(element)) return element
    current = currentClass.accessibleField("left").get(current)
  }
  return null
}

private fun Class<*>.accessibleField(name: String): Field = getDeclaredField(name).apply { isAccessible = true }

private fun Class<*>.accessibleFieldOrNull(name: String): Field? =
  try {
    accessibleField(name)
  } catch (_: NoSuchFieldException) {
    null
  }
