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

import com.android.tools.androidtest.testengine.instrument.AmInstrumentationListener
import com.android.tools.androidtest.testengine.instrument.AmInstrumentationRunner
import com.android.tools.androidtest.testengine.instrument.InstrumentationResult
import com.android.tools.androidtest.testengine.instrument.TestIdentifier
import com.android.tools.androidtest.testengine.instrument.TestResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.hierarchical.Node

/** Root descriptor for [AndroidTestEngine]. */
class AndroidTestEngineDescriptor(uniqueId: UniqueId) :
  EngineDescriptor(uniqueId, "Android Test Engine"), Node<AndroidTestExecutionContext> {

  override fun mayRegisterTests(): Boolean = true

  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    val config = context.configuration

    val adbApkInstaller = AdbApkInstaller(config.adb, config.aapt, config.deviceSerial, config.installTimeoutMs)

    val listener = Listener(this, dynamicTestExecutor)

    val instrumentationRunner =
      AmInstrumentationRunner(
        config.adb,
        config.deviceSerial,
        config.instrumentationRunnerClass,
        config.instrumentationTargetPackageId,
        setOf(listener),
      )

    val runner =
      AndroidTestRunner(
        adbApkInstaller,
        instrumentationRunner,
        config.testedApks,
        config.testApks,
        config.apkInstallOptions,
        config.testUtilApks,
        config.uninstallApksAfterTests,
      )

    runner.run()

    return context
  }

  /** Bridges Android instrumentation events to JUnit dynamic tests. */
  class Listener(private val rootDescriptor: TestDescriptor, private val dynamicTestExecutor: Node.DynamicTestExecutor) :
    AmInstrumentationListener {

    private val testDescriptors = ConcurrentHashMap<TestIdentifier, AndroidDynamicTestDescriptor>()

    override fun instrumentationStarted(testCount: Int) {}

    override fun testStarted(testIdentifier: TestIdentifier) {
      val uniqueId = rootDescriptor.uniqueId.append("test", "${testIdentifier.testClass}#${testIdentifier.testMethod}")
      val displayName = "${testIdentifier.testClass}.${testIdentifier.testMethod}"
      val testDescriptor = AndroidDynamicTestDescriptor(uniqueId, displayName, testIdentifier.testClass, testIdentifier.testMethod)

      rootDescriptor.addChild(testDescriptor)
      testDescriptors[testIdentifier] = testDescriptor
      dynamicTestExecutor.execute(testDescriptor)
    }

    override fun testEnded(testResult: TestResult) {
      testDescriptors[testResult.testIdentifier]?.resultFuture?.complete(testResult)
    }

    override fun instrumentationFailed(errorMessage: String) {
      val exception = RuntimeException(errorMessage)
      testDescriptors.values.forEach {
        if (!it.resultFuture.isDone) {
          it.resultFuture.completeExceptionally(exception)
        }
      }
    }

    override fun instrumentationEnded(instrumentationResult: InstrumentationResult) {
      val exception = RuntimeException("Instrumentation ended unexpectedly")
      testDescriptors.values.forEach {
        if (!it.resultFuture.isDone) {
          it.resultFuture.completeExceptionally(exception)
        }
      }
    }
  }
}
