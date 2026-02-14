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

/**
 * Root descriptor for [AndroidTestEngine].
 *
 * This descriptor manages the high-level lifecycle of an Android instrumentation test run. It sets up the [AndroidTestRunner], handles APK
 * installation, and launches the instrumentation process. It uses a [Listener] to dynamically populate the test hierarchy as events are
 * reported from the device.
 */
class AndroidTestEngineDescriptor(uniqueId: UniqueId) :
  EngineDescriptor(uniqueId, "Android Test Engine"), Node<AndroidTestExecutionContext> {

  override fun mayRegisterTests(): Boolean = true

  /**
   * Orchestrates the test execution.
   *
   * This method initializes the [AndroidTestRunner] and [AmInstrumentationRunner], and triggers the test run. It ensures that the
   * [Listener] is properly cleaned up (marked as finished) in the `finally` block to prevent the engine from hanging.
   */
  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    val config = context.configuration

    val adbApkInstaller = AdbApkInstaller(config.adb, config.aapt2, config.deviceSerial, config.installTimeoutMs)

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

    try {
      runner.run()
    } finally {
      listener.finish()
    }

    return context
  }

  /**
   * Bridges Android instrumentation events to JUnit dynamic tests.
   *
   * This listener implementation translates low-level instrumentation events (like `testStarted` and `testEnded`) into high-level JUnit
   * [TestDescriptor] operations. It dynamically creates [AndroidPackageTestDescriptor], [AndroidClassTestDescriptor], and
   * [AndroidDynamicTestDescriptor] instances as tests are discovered, effectively building the test tree at runtime.
   */
  class Listener(private val rootDescriptor: TestDescriptor, private val dynamicTestExecutor: Node.DynamicTestExecutor) :
    AmInstrumentationListener {

    private val packageDescriptors = ConcurrentHashMap<String, AndroidPackageTestDescriptor>()
    private val classDescriptors = ConcurrentHashMap<String, AndroidClassTestDescriptor>()
    private val testDescriptors = ConcurrentHashMap<TestIdentifier, AndroidDynamicTestDescriptor>()

    override fun instrumentationStarted(testCount: Int) {}

    /**
     * Called when a test case starts on the device.
     *
     * This method ensures that the parent package and class descriptors are created and registered if they don't already exist, then
     * creates a new dynamic test descriptor and adds it to the class container.
     */
    override fun testStarted(testIdentifier: TestIdentifier) {
      val packageName = testIdentifier.testPackage
      val packageDescriptor =
        packageDescriptors.computeIfAbsent(packageName) { name ->
          val packageUniqueId = rootDescriptor.uniqueId.append("package", name)
          val newPackageDescriptor = AndroidPackageTestDescriptor(packageUniqueId, name)
          newPackageDescriptor.setParent(rootDescriptor)
          dynamicTestExecutor.execute(newPackageDescriptor)
          newPackageDescriptor
        }

      val fullClassName = if (packageName.isNotEmpty()) "$packageName.${testIdentifier.testClass}" else testIdentifier.testClass
      val classDescriptor =
        classDescriptors.computeIfAbsent(fullClassName) { className ->
          val classUniqueId = packageDescriptor.uniqueId.append("class", className)
          val newClassDescriptor = AndroidClassTestDescriptor(classUniqueId, className)
          packageDescriptor.addClass(newClassDescriptor)
          newClassDescriptor
        }

      val uniqueId = classDescriptor.uniqueId.append("test", testIdentifier.testMethod)
      val displayName = testIdentifier.testMethod
      val testDescriptor = AndroidDynamicTestDescriptor(uniqueId, displayName, fullClassName, testIdentifier.testMethod)

      classDescriptor.addTest(testDescriptor)
      testDescriptors[testIdentifier] = testDescriptor
    }

    /** Completes the [AndroidDynamicTestDescriptor.resultDeferred] for the corresponding test. */
    override fun testEnded(testResult: TestResult) {
      testDescriptors[testResult.testIdentifier]?.resultDeferred?.complete(testResult)
    }

    /** Handles instrumentation failures by completing all pending test results exceptionally and closing all dynamic containers. */
    override fun instrumentationFailed(errorMessage: String) {
      val exception = RuntimeException(errorMessage)
      testDescriptors.values.forEach {
        if (!it.resultDeferred.isCompleted) {
          it.resultDeferred.completeExceptionally(exception)
        }
      }
      finish()
    }

    /**
     * Handles unexpected instrumentation termination by completing all pending test results exceptionally and closing all dynamic
     * containers.
     */
    override fun instrumentationEnded(instrumentationResult: InstrumentationResult) {
      // In case of unexpected termination, ensure all pending results are completed exceptionally.
      val exception = RuntimeException("Instrumentation ended unexpectedly")
      testDescriptors.values.forEach {
        if (!it.resultDeferred.isCompleted) {
          it.resultDeferred.completeExceptionally(exception)
        }
      }
      finish()
    }

    /**
     * Signals all dynamic containers (Packages and Classes) to stop waiting for new children. This is critical to allow the hierarchical
     * execution to finish correctly.
     */
    fun finish() {
      classDescriptors.values.forEach { it.finish() }
      packageDescriptors.values.forEach { it.finish() }
    }
  }
}
