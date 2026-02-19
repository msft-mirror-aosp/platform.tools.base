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
package com.android.tools.tracer

import androidx.tracing.Tracer

// TODO(b/467364934): Finalize the tracing APIs below for use within Studio and other tools.

/**
 * Traces the [block] as a named section of code in the trace with context propagation. If [block] is suspending, you should use
 * [traceCoroutine] instead since Kotlin cannot overload the function with the same name. This returns an [AutoCloseable] instance that can
 * be used to close the trace section.
 *
 * It's useful to add a [category] to trace events so that they can be filtered if necessary using the appropriate trace configuration.
 * [name] gives a name to the trace section. [isRoot] provides a hint to the [Tracer] that this trace section is an entry point that all
 * subsequent trace spans can be attributed to. Some [Tracer] implementations treat trace sections as a forest, and require that there is at
 * least one top level root span.
 */
fun <T> trace(category: String? = null, name: String? = null, isRoot: Boolean = false, block: () -> T): T {
  val instance = TracingService.getInstance() ?: return block.invoke()
  val traceName = name ?: block.toString()
  val traceCategory = category ?: "default"
  return instance.tracer.trace(category = traceCategory, name = traceName, isRoot = isRoot, block = block)
}

/**
 * Traces the suspending [block] as a named section of code in the trace with context propagation. If [block] is *not* suspending, you
 * should use [trace] instead since Kotlin cannot overload the function with the same name. See [trace] for further details, as they are
 * otherwise identical.
 */
internal suspend fun <T> traceCoroutine(
  category: String? = null,
  name: String? = null,
  isRoot: Boolean = false,
  block: suspend () -> T,
): T {
  val instance = TracingService.getInstance() ?: return block.invoke()
  val traceName = name ?: block.toString()
  val traceCategory = category ?: "default"
  return instance.tracer.traceCoroutine(category = traceCategory, name = traceName, isRoot = isRoot, block = block)
}
