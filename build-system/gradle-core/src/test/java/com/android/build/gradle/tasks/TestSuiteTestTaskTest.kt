/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.tasks

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.gradle.internal.dsl.ManagedDevices
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.resolveTargetDevices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask
import com.android.build.gradle.internal.test.report.JunitAttachmentsArgumentProvider
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.CONNECTED_TEST_TEST_SUITE_NAME
import com.android.build.gradle.tasks.TestSuiteTestTask.Companion.CONNECTED_TEST_TEST_SUITE_TARGET_NAME
import com.android.builder.core.ComponentType
import com.android.builder.testing.api.DeviceConnector
import com.android.builder.testing.api.DeviceException
import com.android.builder.testing.api.DeviceProvider
import com.google.common.truth.Truth.assertThat
import java.util.Properties
import java.util.concurrent.ExecutionException
import java.util.regex.Pattern
import kotlin.test.assertFailsWith
import org.gradle.api.InvalidUserDataException
import org.gradle.api.tasks.VerificationException
import org.gradle.process.CommandLineArgumentProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TestSuiteTestTaskTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun testSerializer() {
    val outputFile = folder.newFile()
    val testedApksProp = AgpTestSuiteInputParameters.TESTED_APKS.propertyName
    TestSuiteTestTask.AgpTestSuiteInputsSerializer.serialize(
      engineInputParameters =
        listOf(
          TestEngineInputProperty(testedApksProp, "universal.apk"),
          TestEngineInputProperty("$testedApksProp[serial1]", "device1.apk"),
          TestEngineInputProperty("$testedApksProp[serial2]", "device2.apk"),
        ),
      engineInputProperties = mapOf("foo" to "fooValue"),
      outputFile,
    )
    assertThat(outputFile.exists()).isTrue()
    val serializedInputs = Properties().also { it.load(outputFile.reader(Charsets.UTF_8)) }
    assertThat(serializedInputs).hasSize(4)
    assertThat(serializedInputs.getProperty(testedApksProp)).isEqualTo("universal.apk")
    assertThat(serializedInputs.getProperty("$testedApksProp[serial1]")).isEqualTo("device1.apk")
    assertThat(serializedInputs.getProperty("$testedApksProp[serial2]")).isEqualTo("device2.apk")
  }

  @Test
  fun testSerializer_withOrchestrator() {
    val outputFile = folder.newFile()
    val testUtilApksProp = AgpTestSuiteInputParameters.TEST_UTIL_APKS.propertyName
    val executionModeProp = AgpTestSuiteInputParameters.ANDROID_TEST_EXECUTION_MODE.propertyName
    TestSuiteTestTask.AgpTestSuiteInputsSerializer.serialize(
      engineInputParameters =
        listOf(
          TestEngineInputProperty(testUtilApksProp, "orchestrator.apk,services.apk"),
          TestEngineInputProperty(executionModeProp, "ANDROIDX_TEST_ORCHESTRATOR"),
        ),
      engineInputProperties = emptyMap(),
      outputFile,
    )
    assertThat(outputFile.exists()).isTrue()
    val serializedInputs = Properties().also { it.load(outputFile.reader(Charsets.UTF_8)) }
    assertThat(serializedInputs).hasSize(2)
    assertThat(serializedInputs.getProperty(testUtilApksProp)).isEqualTo("orchestrator.apk,services.apk")
    assertThat(serializedInputs.getProperty(executionModeProp)).isEqualTo("ANDROIDX_TEST_ORCHESTRATOR")
  }

  @Test
  fun testSerializer_withOnTheFlyCoverage() {
    val outputFile = folder.newFile()
    TestSuiteTestTask.AgpTestSuiteInputsSerializer.serialize(
      engineInputParameters = emptyList(),
      engineInputProperties = mapOf("com.android.agp.test.COVERAGE_TYPE" to "ON_THE_FLY"),
      outputFile,
    )
    assertThat(outputFile.exists()).isTrue()
    val serializedInputs = Properties().also { it.load(outputFile.reader(Charsets.UTF_8)) }
    assertThat(serializedInputs).hasSize(1)
    assertThat(serializedInputs.getProperty("com.android.agp.test.COVERAGE_TYPE")).isEqualTo("ON_THE_FLY")
  }

  @Test
  fun runUnwrappingVerificationFailures_connectedDeviceTestFailure_rethrowsVerificationException() {
    val testFailure = VerificationException("There were failing tests.")

    // DeviceProvider.use wraps the failure once.
    val thrown = assertFailsWith<VerificationException> { runUnwrappingVerificationFailures { throw ExecutionException(testFailure) } }

    assertThat(thrown).isSameInstanceAs(testFailure)
  }

  @Test
  fun runUnwrappingVerificationFailures_managedDeviceTestFailure_rethrowsVerificationException() {
    val testFailure = VerificationException("There were failing tests.")

    // The managed device lock and DeviceProvider.use each wrap the failure.
    val thrown =
      assertFailsWith<VerificationException> {
        runUnwrappingVerificationFailures { throw ExecutionException(ExecutionException(testFailure)) }
      }

    assertThat(thrown).isSameInstanceAs(testFailure)
  }

  @Test
  fun runUnwrappingVerificationFailures_otherFailure_rethrowsUnchanged() {
    val otherFailure = ExecutionException(IllegalStateException("Device went offline."))

    val thrown = assertFailsWith<ExecutionException> { runUnwrappingVerificationFailures { throw otherFailure } }

    assertThat(thrown).isSameInstanceAs(otherFailure)
  }

  @Test
  fun connectedDeviceTargets_offlineDevice_failsOnlyWhenItsConfigIsRead() {
    val onlineDevice = mock<DeviceConnector>()
    whenever(onlineDevice.serialNumber).thenReturn("emulator-5554")
    val offlineDevice = mock<DeviceConnector>()
    whenever(offlineDevice.serialNumber).thenReturn("emulator-5560")
    whenever(offlineDevice.deviceConfig).thenThrow(DeviceException("device offline"))

    // Only app bundle tests read the configuration, so a device that went offline doesn't fail other test suites.
    val targets = TestSuiteTestTask.connectedDeviceTargets(listOf(onlineDevice, offlineDevice))

    assertThat(targets.map { it.serialNumber }).containsExactly("emulator-5554", "emulator-5560").inOrder()
    assertFailsWith<DeviceException> { targets.last().deviceConfigProvider }
  }

  @Test
  fun executeTests_noDevice_notifiesNoDeviceListeners() {
    val project = ProjectFactory.project
    val task = project.tasks.register("testSuiteTestTaskWithoutDevice", TestSuiteTestTask::class.java).get()
    val resultsDir = folder.newFolder().also { it.resolve("TEST-stale-engine.xml").writeText("<testsuite/>") }
    val junitXmlDir = folder.newFolder().also { it.resolve("TEST-stale-gradle.xml").writeText("<testsuite/>") }
    val xmlResultsDir = folder.newFolder().also { it.resolve("TEST-stale-aggregated.xml").writeText("<testsuite/>") }
    task.resultsDir.set(resultsDir)
    task.reports.junitXml.outputLocation.set(junitXmlDir)
    task.xmlResultsDirectory.set(xmlResultsDir)
    task.coverageDir.set(folder.newFolder())
    task.logFile.set(folder.newFile())
    task.streamingOutputFile.set(folder.newFile())
    task.engineInputParameters.add(TestSuiteTestTask.AgpTestSuiteInputParameter(AgpTestSuiteInputParameters.TEST_APKS, project.files()))
    DeviceProviderInstrumentTestTask.DeviceProviderFactory::class
      .java
      .getDeclaredField("deviceProvider")
      .apply { isAccessible = true }
      .set(task.deviceProviderFactory, NoDeviceProvider())
    var notified = false
    task.addNoDeviceListener { notified = true }

    assertFailsWith<DeviceException> { task.executeTests() }

    assertThat(notified).isTrue()
    assertThat(resultsDir.exists()).isFalse()
    assertThat(junitXmlDir.exists()).isFalse()
    assertThat(xmlResultsDir.listFiles()).isEmpty()
  }

  @Test
  fun executeTests_connectedDevicesExcluded_doesNotQueryConnectedDevices() {
    val project = ProjectFactory.project
    val task = project.tasks.register("testSuiteTestTaskManagedDevicesOnly", TestSuiteTestTask::class.java).get()
    task.resultsDir.set(folder.newFolder())
    task.coverageDir.set(folder.newFolder())
    task.logFile.set(folder.newFile())
    task.streamingOutputFile.set(folder.newFile())
    task.engineInputParameters.add(TestSuiteTestTask.AgpTestSuiteInputParameter(AgpTestSuiteInputParameters.TEST_APKS, project.files()))
    task.includeConnectedDevices.set(false)
    val deviceProvider = RecordingDeviceProvider()
    DeviceProviderInstrumentTestTask.DeviceProviderFactory::class
      .java
      .getDeclaredField("deviceProvider")
      .apply { isAccessible = true }
      .set(task.deviceProviderFactory, deviceProvider)
    var notified = false
    task.addNoDeviceListener { notified = true }

    // No managed device is configured, so there is no device to run the tests on.
    val thrown = assertFailsWith<DeviceException> { task.executeTests() }

    assertThat(deviceProvider.initCalled).isFalse()
    assertThat(thrown).hasMessageThat().isEqualTo("No managed devices available!")
    assertThat(notified).isTrue()
  }

  @Test
  fun testHostJarJvmArgumentProvider() {
    val project = ProjectFactory.project
    val providerJava17 = HostJarJvmArgumentProvider(project.providers.provider { true })
    assertThat(providerJava17.asArguments()).containsExactly("--add-opens=java.base/java.io=ALL-UNNAMED")

    val providerJava11 = HostJarJvmArgumentProvider(project.providers.provider { false })
    assertThat(providerJava11.asArguments()).isEmpty()
  }

  @Test
  fun testJunitAttachmentsArgumentProvider() {
    val project = ProjectFactory.project
    val dir = folder.newFolder("attachments")
    val dirProvider = project.objects.directoryProperty().fileValue(dir)
    val suppressProp = project.objects.property(Boolean::class.java)

    val provider = JunitAttachmentsArgumentProvider(dirProvider, suppressProp)
    assertThat(provider.asArguments()).containsExactly("-Djunit.platform.reporting.output.dir=${dir.absolutePath}")

    suppressProp.set(false)
    assertThat(provider.asArguments()).containsExactly("-Djunit.platform.reporting.output.dir=${dir.absolutePath}")

    suppressProp.set(true)
    assertThat(provider.asArguments()).isEmpty()
  }

  @Test
  fun resolveConfiguredJunitReportingOutputDir_respectsUserOverridesAndJvmArgPrecedence() {
    val workDir = folder.newFolder("work")
    val absDir = folder.newFolder("custom-otr")

    assertThat(TestSuiteTestTask.resolveConfiguredJunitReportingOutputDir(emptyMap(), emptyList(), workDir)).isNull()
    assertThat(
        TestSuiteTestTask.resolveConfiguredJunitReportingOutputDir(
          mapOf("junit.platform.reporting.output.dir" to "   "),
          emptyList(),
          workDir,
        )
      )
      .isNull()

    assertThat(
        TestSuiteTestTask.resolveConfiguredJunitReportingOutputDir(
          mapOf("junit.platform.reporting.output.dir" to "build/otr"),
          emptyList(),
          workDir,
        )
      )
      .isEqualTo(workDir.resolve("build/otr"))

    assertThat(
        TestSuiteTestTask.resolveConfiguredJunitReportingOutputDir(
          mapOf("junit.platform.reporting.output.dir" to "build/ignored"),
          listOf("-Djunit.platform.reporting.output.dir=build/earlier", "-Djunit.platform.reporting.output.dir=${absDir.absolutePath}"),
          workDir,
        )
      )
      .isEqualTo(absDir)

    val project = ProjectFactory.project
    val agpDefaultDir = folder.newFolder("agp-default-attachments")
    val agpProvider =
      JunitAttachmentsArgumentProvider(
        project.objects.directoryProperty().fileValue(agpDefaultDir),
        project.objects.property(Boolean::class.java),
      )
    val userProvider = CommandLineArgumentProvider { listOf("-Djunit.platform.reporting.output.dir=build/user-provider-otr") }

    assertThat(
        TestSuiteTestTask.resolveConfiguredJunitReportingOutputDir(
          systemProperties = emptyMap(),
          jvmArgs = emptyList(),
          workingDir = workDir,
          jvmArgumentProviders = listOf(agpProvider),
        )
      )
      .isNull()

    assertThat(
        TestSuiteTestTask.resolveConfiguredJunitReportingOutputDir(
          systemProperties = mapOf("junit.platform.reporting.output.dir" to "build/from-sysprop"),
          jvmArgs = listOf("-Djunit.platform.reporting.output.dir=${absDir.absolutePath}"),
          workingDir = workDir,
          jvmArgumentProviders = listOf(agpProvider, userProvider),
        )
      )
      .isEqualTo(workDir.resolve("build/user-provider-otr"))
  }

  @Test
  fun buildTestsRegexFromFilter_translatesGradlePatterns() {
    fun matches(regex: String, testId: String): Boolean = Pattern.compile(regex).matcher(testId).find()

    assertThat(TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), emptySet(), emptySet())).isNull()
    assertThat(TestSuiteTestTask.buildTestsRegexFromFilter(setOf("*"), setOf("*"), emptySet())).isNull()

    val exactClass = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("com.example.MyTest"), emptySet())!!
    assertThat(matches(exactClass, "com.example.MyTest#testOne")).isTrue()
    assertThat(matches(exactClass, "com.example.MyTestOther#testOne")).isFalse()

    val classAndMethod = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("com.example.MyTest.testOne"), emptySet())!!
    assertThat(matches(classAndMethod, "com.example.MyTest#testOne")).isTrue()
    assertThat(matches(classAndMethod, "com.example.MyTest#testOne[alpha]")).isTrue()
    assertThat(matches(classAndMethod, "com.example.MyTest#testOneMore")).isFalse()

    val paramVariant = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("com.example.MyTest.testOne[alpha]"), emptySet())!!
    assertThat(matches(paramVariant, "com.example.MyTest#testOne[alpha]")).isTrue()
    assertThat(matches(paramVariant, "com.example.MyTest#testOne[beta]")).isFalse()

    val simpleClass = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("MyTest"), emptySet())!!
    assertThat(matches(simpleClass, "com.example.MyTest#testOne")).isTrue()
    assertThat(matches(simpleClass, "com.example.OtherMyTest#testOne")).isFalse()

    val simpleClassAndMethod = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("MyTest.testOne"), emptySet())!!
    assertThat(matches(simpleClassAndMethod, "com.example.MyTest#testOne")).isTrue()

    val upperCaseMethod = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("com.example.MyTest.Given_when_then"), emptySet())!!
    assertThat(matches(upperCaseMethod, "com.example.MyTest#Given_when_then")).isTrue()

    val wildcardClassAndMethod = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("*MyTest.test*"), emptySet())!!
    assertThat(matches(wildcardClassAndMethod, "com.example.MyTest#testOne")).isTrue()
    assertThat(matches(wildcardClassAndMethod, "com.example.MyTest#other")).isFalse()

    val lowerCaseName = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("testOne"), emptySet())!!
    assertThat(matches(lowerCaseName, "com.example.MyTest#testOne")).isFalse()

    val pkgWildcard = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("com.example.*"), emptySet())!!
    assertThat(matches(pkgWildcard, "com.example.MyTest#testOne")).isTrue()
    assertThat(matches(pkgWildcard, "com.example.sub.MyTest#testOne")).isTrue()
    assertThat(matches(pkgWildcard, "com.other.MyTest#testOne")).isFalse()

    val nestedClass = TestSuiteTestTask.buildTestsRegexFromFilter(emptySet(), setOf("com.example.Outer\$Inner.testOne"), emptySet())!!
    assertThat(matches(nestedClass, "com.example.Outer\$Inner#testOne")).isTrue()

    val intersectedAndExcluded =
      TestSuiteTestTask.buildTestsRegexFromFilter(
        includePatterns = setOf("com.example.*"),
        commandLineIncludePatterns = setOf("*MyTest*"),
        excludePatterns = setOf("com.example.MyTest.flakyTest"),
      )!!
    assertThat(matches(intersectedAndExcluded, "com.example.MyTest#stableTest")).isTrue()
    assertThat(matches(intersectedAndExcluded, "com.example.MyTest#flakyTest")).isFalse()
    assertThat(matches(intersectedAndExcluded, "com.other.MyTest#stableTest")).isFalse()
  }

  @Test
  fun resolveTargetDevices_resolvesLocalDevicesAndGroupsAndDeduplicates() {
    val dslServices = createDslServices()
    val managedDevices = ManagedDevices(dslServices)
    val pixel6 = managedDevices.localDevices.create("pixel6") { it.device = "Pixel 6" }
    val pixel8 = managedDevices.localDevices.create("pixel8") { it.device = "Pixel 8" }
    managedDevices.groups.create("phones") {
      it.targetDevices.add(pixel6)
      it.targetDevices.add(pixel8)
    }

    val resolved = managedDevices.resolveTargetDevices(listOf("phones", "pixel6"))
    assertThat(resolved.map { it.name }).containsExactly("pixel6", "pixel8").inOrder()

    val missing = assertFailsWith<InvalidUserDataException> { managedDevices.resolveTargetDevices(listOf("unknownGroup")) }
    assertThat(missing.message).contains("Target device or device group 'unknownGroup' was not found")
  }

  @Test
  fun coverageReportPath_testSuite_isPerVariantAndTarget() {
    assertThat(TestSuiteTestTask.coverageReportPath("coverageRepro", "demoDebug", "host"))
      .isEqualTo("reports/coverage/testSuites/coverageRepro/demoDebug/host")
    assertThat(TestSuiteTestTask.coverageReportPath("coverageRepro", "prodDebug", "host"))
      .isEqualTo("reports/coverage/testSuites/coverageRepro/prodDebug/host")
    assertThat(TestSuiteTestTask.coverageReportPath("coverageRepro", "demoDebug", "device"))
      .isEqualTo("reports/coverage/testSuites/coverageRepro/demoDebug/device")
  }

  @Test
  fun coverageReportPath_builtInAndroidTest_isPerVariantAndTarget() {
    assertThat(TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "debug", CONNECTED_TEST_TEST_SUITE_TARGET_NAME))
      .isEqualTo("reports/coverage/testSuites/androidTest/debug/connected")
    // The managed device tasks use the device name as target.
    assertThat(TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "demoDebug", "pixel6"))
      .isEqualTo("reports/coverage/testSuites/androidTest/demoDebug/pixel6")
  }

  @Test
  fun coverageReportPath_differentTasks_neverShareDirectories() {
    val reportPaths =
      listOf(
        // A test suite on two variants and two targets.
        TestSuiteTestTask.coverageReportPath("coverageRepro", "demoDebug", "host"),
        TestSuiteTestTask.coverageReportPath("coverageRepro", "prodDebug", "host"),
        TestSuiteTestTask.coverageReportPath("coverageRepro", "demoDebug", "device"),
        TestSuiteTestTask.coverageReportPath("coverageRepro", "prodDebug", "device"),
        // Another test suite.
        TestSuiteTestTask.coverageReportPath("other", "demoDebug", "host"),
        // The built-in androidTest tasks, on connected devices and on a managed device.
        TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "demoDebug", CONNECTED_TEST_TEST_SUITE_TARGET_NAME),
        TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "prodDebug", CONNECTED_TEST_TEST_SUITE_TARGET_NAME),
        TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "demoDebug", "pixel6"),
      )

    assertThat(reportPaths).containsNoDuplicates()
  }

  @Test
  fun coverageReportPath_neverOverlapsJacocoReportDirectories() {
    // The JaCoCo report tasks write to reports/coverage/<test component prefix>/..., for example
    // reports/coverage/androidTest/debug/connected, or reports/coverage/androidTest/connected in Kotlin Multiplatform modules.
    val jacocoReportRoots =
      listOf(ComponentType.UNIT_TEST_PREFIX, ComponentType.ANDROID_TEST_PREFIX, ComponentType.SCREENSHOT_TEST_PREFIX).map {
        "reports/coverage/$it"
      }
    val reportPaths =
      listOf(
        TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "debug", CONNECTED_TEST_TEST_SUITE_TARGET_NAME),
        TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "demoDebug", CONNECTED_TEST_TEST_SUITE_TARGET_NAME),
        TestSuiteTestTask.coverageReportPath(CONNECTED_TEST_TEST_SUITE_NAME, "debug", "pixel6"),
        TestSuiteTestTask.coverageReportPath("coverageRepro", "demoDebug", "host"),
        // Screenshot test suites are usually named like the screenshot test component.
        TestSuiteTestTask.coverageReportPath(ComponentType.SCREENSHOT_TEST_PREFIX, "debug", "host"),
      )

    val overlappingPaths = reportPaths.flatMap { path ->
      jacocoReportRoots.filter { root -> path.isSameOrInside(root) || root.isSameOrInside(path) }.map { "$path overlaps $it" }
    }
    assertThat(overlappingPaths).isEmpty()
  }

  /** Whether this directory path is [other] or a directory inside it. */
  private fun String.isSameOrInside(other: String) = this == other || startsWith("$other/")

  /** Records whether the connected devices were queried. */
  private class RecordingDeviceProvider : DeviceProvider() {
    var initCalled = false

    override fun getName() = "recording"

    override fun init() {
      initCalled = true
    }

    override fun terminate() {}

    override fun getDevices(): List<DeviceConnector> = emptyList()

    override fun getTimeoutInMs() = 0

    override fun isConfigured() = true
  }

  @Test
  fun buildSuiteEnvironmentProperties_resolvesManifestMetadataUnescapesLabelAndMergesExtraProperties() {
    val manifestFile = folder.newFile("AndroidManifest.xml")
    manifestFile.writeText(
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="com.example.journalingapp"
          android:versionCode="12"
          android:versionName="1.2.0">
          <uses-sdk android:targetSdkVersion="33" />
          <application android:label="@string/app_name" />
      </manifest>
      """
        .trimIndent()
    )

    val debugRes = folder.newFolder("debugRes")
    val mainRes = folder.newFolder("mainRes")
    // Localized values-af in higher-priority overlay must NOT beat unqualified values/ in mainRes.
    debugRes.resolve("values-af").apply {
      mkdirs()
      resolve("strings.xml").writeText("<resources><string name=\"app_name\">VerkeerdeNaam</string></resources>")
    }
    mainRes.resolve("values").apply {
      mkdirs()
      resolve("strings.xml").writeText("<resources><string name=\"app_name\">\"User\\'s \\\"Journaling\\\" \\\\ App\"</string></resources>")
    }

    val genericProps =
      TestSuiteTestTask.buildSuiteEnvironmentProperties(
        suiteType = null,
        testedApplicationId = "com.example.journalingapp.debug",
        agpVersion = "9.6.0-dev",
        gradleVersion = "9.8.0",
        testLibraryVersion = null,
        runner = "junit-jupiter",
        manifestMetadata = TestSuiteTestTask.parseMergedManifest(manifestFile, emptyList()),
      )
    assertThat(genericProps).doesNotContainKey("suiteType")
    assertThat(genericProps).doesNotContainKey("allowBackup")
    assertThat(genericProps).doesNotContainKey("applicationName")
    assertThat(genericProps["targetSdkVersion"]).isEqualTo("33")

    val enrichedProps =
      TestSuiteTestTask.buildSuiteEnvironmentProperties(
        suiteType = "androidx.test.backup",
        testedApplicationId = "com.example.journalingapp.debug",
        agpVersion = "9.6.0-dev",
        gradleVersion = "9.8.0",
        testLibraryVersion = "1.0.0-alpha04",
        runner = "junit-jupiter",
        manifestMetadata = TestSuiteTestTask.parseMergedManifest(manifestFile, listOf(debugRes, mainRes)),
        extraProperties = mapOf("allowBackup" to "true", "blankIgnored" to "   "),
      )
    assertThat(enrichedProps["suiteType"]).isEqualTo("androidx.test.backup")
    assertThat(enrichedProps["applicationId"]).isEqualTo("com.example.journalingapp.debug")
    assertThat(enrichedProps["applicationName"]).isEqualTo("User's \"Journaling\" \\ App")
    assertThat(enrichedProps["versionName"]).isEqualTo("1.2.0")
    assertThat(enrichedProps["versionCode"]).isEqualTo("12")
    assertThat(enrichedProps["targetSdkVersion"]).isEqualTo("33")
    assertThat(enrichedProps["agpVersion"]).isEqualTo("9.6.0-dev")
    assertThat(enrichedProps["gradleVersion"]).isEqualTo("9.8.0")
    assertThat(enrichedProps["testLibraryVersion"]).isEqualTo("1.0.0-alpha04")
    assertThat(enrichedProps["runner"]).isEqualTo("junit-jupiter")
    assertThat(enrichedProps["allowBackup"]).isEqualTo("true")
    assertThat(enrichedProps).doesNotContainKey("blankIgnored")
  }

  /** Finds no device, as when none is connected. */
  private class NoDeviceProvider : DeviceProvider() {
    override fun getName() = "noDevice"

    override fun init(): Unit = throw DeviceException("No connected devices!")

    override fun terminate() {}

    override fun getDevices(): List<DeviceConnector> = emptyList()

    override fun getTimeoutInMs() = 0

    override fun isConfigured() = true
  }
}
