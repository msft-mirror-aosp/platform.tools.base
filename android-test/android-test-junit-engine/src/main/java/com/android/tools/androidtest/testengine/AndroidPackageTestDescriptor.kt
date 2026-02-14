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

package com.android.tools.androidtest.testengine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.PackageSource
import org.junit.platform.engine.support.hierarchical.Node

/**
 * A test descriptor representing a test package running on an Android device.
 *
 * This descriptor acts as a dynamic container for test classes discovered during the instrumentation run. It uses a Kotlin [Channel] to
 * receive new class descriptors and executes them using the JUnit [Node.DynamicTestExecutor].
 */
class AndroidPackageTestDescriptor(uniqueId: UniqueId, packageName: String) :
  AbstractTestDescriptor(uniqueId, packageName, PackageSource.from(packageName)), Node<AndroidTestExecutionContext> {

  private sealed class Event {
    /** Signals that a new test class has been discovered and should be executed. */
    data class NewClass(val descriptor: AndroidClassTestDescriptor) : Event()
  }

  /**
   * Internal channel used to stream discovered test classes to the [execute] method. Using an unlimited channel ensures that the
   * instrumentation runner (which produces events) never blocks, even if the JUnit executor is busy.
   */
  private val events = Channel<Event>(Channel.UNLIMITED)

  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

  /**
   * Blocks until all test classes for this package have been processed and the [finish] method is called.
   *
   * This method runs in a [runBlocking] block to consume events from the [events] channel. It delegates the execution of discovered classes
   * to the provided [dynamicTestExecutor].
   */
  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    runBlocking {
      for (event in events) {
        when (event) {
          is Event.NewClass -> dynamicTestExecutor.execute(event.descriptor)
        }
      }
    }
    return context
  }

  /**
   * Dynamically registers a new test class within this package.
   *
   * This method is called by the instrumentation listener as new classes are encountered in the raw output.
   *
   * @param classDescriptor The descriptor for the newly discovered class.
   */
  fun addClass(classDescriptor: AndroidClassTestDescriptor) {
    classDescriptor.setParent(this)
    events.trySend(Event.NewClass(classDescriptor)).getOrThrow()
  }

  /**
   * Closes the event channel, signaling that no more test classes will be added to this package. This allows the [execute] method to
   * complete.
   */
  fun finish() {
    events.close()
  }
}
