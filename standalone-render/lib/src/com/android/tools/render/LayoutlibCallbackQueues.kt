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

import com.android.tools.rendering.RenderResult
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("com.android.tools.render.LayoutlibCallbackQueues")

private const val CLASS_BRIDGE_CONTEXT = "com.android.layoutlib.bridge.android.BridgeContext"

/**
 * Returns whether layoutlib's `SessionInteractiveData` for [result] has pending `Handler` or `Choreographer` callbacks (for example,
 * `Choreographer` frame callbacks remaining after `RenderTask.executeCallbacks`, or work posted by a custom `View` during
 * `RenderTask.render` after `RenderTask.executeCallbacks` had already returned `hasMoreCallbacks = false`).
 *
 * Must be called on the layoutlib render thread so `HandlerMessageQueue` and `ChoreographerCallbacks` are not read concurrently with
 * render-thread mutations.
 *
 * TODO(b/576472744): Remove this reflective check once RenderTask.executeCallbacks / RenderSession.executeCallbacks counts pending
 *   Choreographer callbacks in ExecuteCallbacksResult.hasMoreCallbacks().
 */
internal fun hasPendingLayoutlibCallbacks(result: RenderResult): Boolean {
  val view =
    (result.rootViews.asSequence() + result.systemRootViews.asSequence()).mapNotNull { it.viewObject }.firstOrNull() ?: return false
  return hasPendingLayoutlibCallbacksFromView(view)
}

/**
 * Inspects [view]'s context chain for a layoutlib `BridgeContext` (or [bridgeContextClassName] in unit tests) and returns whether its
 * `SessionInteractiveData` has pending `Handler` or `Choreographer` callbacks.
 *
 * Returns `true` if `BridgeContext` is present on [view] but its reflective callback-queue shape could not be inspected
 * ([BridgeContextHandleState.Failed]), so the bounded callback loop runs all steps conservatively rather than stopping early. Returns
 * `false` if [view] has no `BridgeContext`, `getSessionInteractiveData()` is `null`, or a transient runtime operation error occurs.
 */
internal fun hasPendingLayoutlibCallbacksFromView(view: Any, bridgeContextClassName: String = CLASS_BRIDGE_CONTEXT): Boolean {
  val bridgeContext = findBridgeContextFromView(view, bridgeContextClassName) ?: return false
  val stateRef = bridgeContextHandleStates.get(bridgeContext.javaClass)
  val handle =
    try {
      getInitializedBridgeContextHandle(bridgeContext, stateRef) ?: return stateRef.get() === BridgeContextHandleState.Failed
    } catch (e: ReflectiveOperationException) {
      recordBridgeContextFailureOnce(bridgeContext.javaClass, stateRef, e)
      return true
    } catch (e: RuntimeException) {
      recordBridgeContextFailureOnce(bridgeContext.javaClass, stateRef, e)
      return true
    } catch (e: LinkageError) {
      recordBridgeContextFailureOnce(bridgeContext.javaClass, stateRef, e)
      return true
    }
  return try {
    handle.hasPendingCallbacks(bridgeContext)
  } catch (e: Exception) {
    handle.logOperationFailure(bridgeContext.javaClass, e)
    false
  } catch (e: LinkageError) {
    handle.logOperationFailure(bridgeContext.javaClass, e)
    false
  }
}

private fun findBridgeContextFromView(view: Any, bridgeContextClassName: String): Any? {
  val getContext = getContextMethodByViewClass.get(view.javaClass) ?: return null
  var context: Any? = logged({ "Unable to invoke getContext() on ${view.javaClass.name}" }) { getContext.invoke(view) } ?: return null
  var depth = 0
  while (context != null && context.javaClass.name != bridgeContextClassName && depth < 16) {
    val currentContext = context
    val getBaseContext = getBaseContextMethodByContextClass.get(currentContext.javaClass) ?: break
    val next =
      logged({ "Unable to invoke getBaseContext() on ${currentContext.javaClass.name}" }) { getBaseContext.invoke(currentContext) }
        ?: return null
    if (next === currentContext) break
    context = next
    depth++
  }
  return context?.takeIf { it.javaClass.name == bridgeContextClassName }
}

private val getContextMethodByViewClass =
  object : ClassValue<Method?>() {
    override fun computeValue(viewClass: Class<*>): Method? =
      logged({ "Unable to find getContext() on ${viewClass.name}" }) {
        viewClass.getMethod("getContext").apply { isAccessible = true }
      }
  }

private val getBaseContextMethodByContextClass =
  object : ClassValue<Method?>() {
    override fun computeValue(contextClass: Class<*>): Method? =
      logged({ "Unable to find getBaseContext() on ${contextClass.name}" }) {
        try {
          contextClass.getMethod("getBaseContext").apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
          null
        }
      }
  }

private inline fun <T> logged(message: () -> String, block: () -> T): T? =
  try {
    block()
  } catch (e: Exception) {
    logger.log(Level.FINE, message(), e)
    null
  } catch (e: LinkageError) {
    logger.log(Level.FINE, message(), e)
    null
  }

private class InitializedBridgeContextHandle(
  val getSessionInteractiveData: Method,
  val getHandlerMessageQueue: Method,
  val isHandlerQueueNotEmpty: Method,
  val getChoreographerCallbacks: Method,
  val choreographerCallbacksField: Field,
) {
  private val operationFailureCount = AtomicInteger(0)

  fun logOperationFailure(bridgeContextClass: Class<*>, error: Throwable) {
    when (operationFailureCount.incrementAndGet()) {
      1 -> logger.log(Level.FINE, "Operation on ${bridgeContextClass.name} interactive callback queues failed", error)
      2 -> logger.log(Level.WARNING, "Repeated operation failure on ${bridgeContextClass.name} interactive callback queues", error)
    }
  }

  fun hasPendingCallbacks(bridgeContext: Any): Boolean {
    val interactiveData = getSessionInteractiveData.invoke(bridgeContext) ?: return false
    val handlerQueue = getHandlerMessageQueue.invoke(interactiveData)
    if (handlerQueue != null && isHandlerQueueNotEmpty.invoke(handlerQueue) == true) {
      return true
    }
    val choreographerCallbacks = getChoreographerCallbacks.invoke(interactiveData)
    if (choreographerCallbacks != null) {
      val callbacks =
        choreographerCallbacksField.get(choreographerCallbacks) as? Collection<*>
          ?: throw IllegalStateException("mCallbacks is not a Collection")
      if (synchronized(callbacks) { callbacks.isNotEmpty() }) return true
    }
    return false
  }
}

private sealed interface BridgeContextHandleState {
  data object Uninitialized : BridgeContextHandleState

  class Initialized(val handle: InitializedBridgeContextHandle) : BridgeContextHandleState

  data object Failed : BridgeContextHandleState
}

private val bridgeContextHandleStates =
  object : ClassValue<AtomicReference<BridgeContextHandleState>>() {
    override fun computeValue(type: Class<*>): AtomicReference<BridgeContextHandleState> =
      AtomicReference(BridgeContextHandleState.Uninitialized)
  }

private fun recordBridgeContextFailureOnce(
  bridgeContextClass: Class<*>,
  stateRef: AtomicReference<BridgeContextHandleState>,
  error: Throwable,
) {
  if (stateRef.getAndSet(BridgeContextHandleState.Failed) !== BridgeContextHandleState.Failed) {
    logger.log(Level.WARNING, "Unable to inspect layoutlib interactive callback queues on ${bridgeContextClass.name}", error)
  }
}

private fun getInitializedBridgeContextHandle(
  bridgeContext: Any,
  stateRef: AtomicReference<BridgeContextHandleState>,
): InitializedBridgeContextHandle? {
  when (val state = stateRef.get()) {
    is BridgeContextHandleState.Initialized -> return state.handle
    BridgeContextHandleState.Failed -> return null
    BridgeContextHandleState.Uninitialized -> {}
  }

  val bridgeContextClass = bridgeContext.javaClass
  val getSessionInteractiveData = bridgeContextClass.getMethod("getSessionInteractiveData").apply { isAccessible = true }
  val interactiveData = getSessionInteractiveData.invoke(bridgeContext) ?: return null
  val interactiveDataClass = interactiveData.javaClass
  val getHandlerMessageQueue = interactiveDataClass.getMethod("getHandlerMessageQueue").apply { isAccessible = true }
  val handlerQueue = getHandlerMessageQueue.invoke(interactiveData) ?: throw IllegalStateException("getHandlerMessageQueue returned null")
  val isHandlerQueueNotEmpty = handlerQueue.javaClass.getMethod("isNotEmpty").apply { isAccessible = true }
  val getChoreographerCallbacks = interactiveDataClass.getMethod("getChoreographerCallbacks").apply { isAccessible = true }
  val choreographerCallbacks =
    getChoreographerCallbacks.invoke(interactiveData) ?: throw IllegalStateException("getChoreographerCallbacks returned null")
  val choreographerCallbacksField = choreographerCallbacks.javaClass.getDeclaredField("mCallbacks").apply { isAccessible = true }
  if (choreographerCallbacksField.get(choreographerCallbacks) !is Collection<*>) {
    throw IllegalStateException("mCallbacks is not a Collection")
  }

  val handle =
    InitializedBridgeContextHandle(
      getSessionInteractiveData = getSessionInteractiveData,
      getHandlerMessageQueue = getHandlerMessageQueue,
      isHandlerQueueNotEmpty = isHandlerQueueNotEmpty,
      getChoreographerCallbacks = getChoreographerCallbacks,
      choreographerCallbacksField = choreographerCallbacksField,
    )
  val newState = BridgeContextHandleState.Initialized(handle)
  stateRef.compareAndSet(BridgeContextHandleState.Uninitialized, newState)
  return (stateRef.get() as? BridgeContextHandleState.Initialized)?.handle ?: handle
}
