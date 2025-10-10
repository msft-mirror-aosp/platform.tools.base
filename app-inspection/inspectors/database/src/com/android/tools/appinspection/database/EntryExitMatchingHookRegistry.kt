/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.tools.appinspection.database

import androidx.inspection.ArtTooling.EntryHook
import androidx.inspection.InspectorEnvironment
import com.android.tools.appinspection.common.threadLocal
import java.util.ArrayDeque
import java.util.Deque

/**
 * The class allows for observing method's thisObject and args in ExitHook.
 *
 * It works by registering both (entry and exit) hooks and keeping its own method frame stack. On
 * exit, it calls [OnExitCallback] provided by the user.
 *
 * TODO: handle cases when frames could be dropped (e.g. because of an Exception) causing internal
 *   state to be corrupted.
 *
 * Thread safe by using a [ThreadLocal].
 */
internal class EntryExitMatchingHookRegistry(private val environment: InspectorEnvironment) {

  private val frameStack: Deque<Frame> by threadLocal { ArrayDeque() }

  inline fun <reified Origin, Result> registerHook(
    originClass: Class<out Origin>,
    originMethod: String,
    entryHook: EntryHook? = null,
    onExitCallback: OnExitCallback<Origin, Result>,
  ) {
    val artTooling = environment.artTooling()

    artTooling.registerEntryHook(originClass, originMethod) { thisObject, args ->
      frameStack.addLast(Frame(originMethod, thisObject, args))
      entryHook?.onEntry(thisObject, args)
    }

    artTooling.registerExitHook<Result>(originClass, originMethod) { result ->
      val frame = frameStack.pollLast()
      // TODO: make more specific and handle
      check(originMethod == frame.method)
      check(frame.thisObject is Origin?)
      onExitCallback.onExit(frame.thisObject, frame.args, result)
    }
  }

  internal data class Frame(
    val method: String,
    val thisObject: Any?,
    val args: List<Any?>,
    val result: Any? = null,
  )

  internal fun interface OnExitCallback<O, R> {
    fun onExit(thisObject: O?, args: List<Any?>, result: R?): R?
  }
}
