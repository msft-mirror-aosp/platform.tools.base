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
package com.android.tools.deployer.deployerrunner

import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.DeployMetric
import com.android.tools.deployer.DeploymentCacheDatabase
import com.android.tools.deployer.SqlApkFileDatabase
import com.android.tools.deployer.UIService
import com.android.tools.deployer.Version
import com.android.tools.deployer.devices.FakeDevice
import com.android.tools.deployer.rules.ApiLevel
import com.android.tools.deployer.rules.FakeDeviceConnection
import com.android.tools.perflogger.Benchmark
import com.android.tools.tracer.Trace
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.TestName
import org.mockito.Mockito

/*
How these tests work:
====================
These tests use the FakeAdbServer infrastructure but none of the default Handler.
Instead, we install our own FakeDeviceHandler.

DeployerRunner -> DDMLIB -> FakeAdbServer -> FakeDeviceHandler | Fake sync (for install command)
                                                               | Fake shell/exec ->| Fake ls
                                                                                   | Fake mkdir
                                                                                   | ...
                                                                                   | installer (external command)

The installer executable is built and runs on the local machine. To work in a non-Android environment,
two mechanisms are used:
1- For filesystem operations, an IO system configured via FAKE_DEVICE_ROOT environment variable redirects all
open/read/write/close/state to a test directory.
2- For exec(3) operations, the workspace Executor is substituted to a RedirectExecutor which forward requests
to a shell based on FAKE_DEVICE_SHELL environment variable.


The DeployerRunner runs as is and the DeviceHandler records all sync/exec/shell commands received by the device.
As the end of each test, the list of commands received is compared against the list of commands expected.

Concurrency: These tests are NEVER sharded on the same machine. Therefore, having one FakeAdbServer is not a problem.
             A single FakeAdbServer can also be used in other tests.
*/

val INSTALLER_INVOCATION: String = AdbInstaller.INSTALLER_PATH + " -version=\$VERSION"

abstract class DeployRunnerTestBase {

  @JvmField @Rule var testName: TestName = TestName()

  @JvmField @Rule @ApiLevel.Init var connection: FakeDeviceConnection? = null

  companion object {

    const val BASE: String = "tools/base/deploy/deployer/src/test/resource/"

    private lateinit var dexDbFile: File

    @BeforeClass
    @JvmStatic
    @Throws(Exception::class)
    fun prepare() {
      dexDbFile = File.createTempFile("cached_db", ".bin")
      dexDbFile.delete()
      // Fill in the database file by calling dump() at least once.
      // From then on, we will just keep copying this file and reusing it
      // for every test.
      SqlApkFileDatabase(dexDbFile, null).dump()
      dexDbFile.deleteOnExit()
    }
  }

  protected lateinit var cacheDb: DeploymentCacheDatabase
  protected lateinit var dexDB: SqlApkFileDatabase
  protected lateinit var service: UIService
  protected lateinit var device: FakeDevice

  protected var benchmark: Benchmark? = null
  private var startTime: Long = 0

  protected fun getInstallerInvocation() = INSTALLER_INVOCATION

  @Before
  @Throws(java.lang.Exception::class)
  open fun setUp() {
    this.device = connection!!.device
    this.service = Mockito.mock(UIService::class.java)

    val dbFile = File.createTempFile("test_db", ".bin")
    dbFile.deleteOnExit()
    FileUtils.copyFile(dexDbFile, dbFile)
    dexDB = SqlApkFileDatabase(dbFile, null)
    cacheDb = DeploymentCacheDatabase(2)

    if ("true" == System.getProperty("dashboards.enabled")) {
      // Put all APIs (parameters) of a particular test into one benchmark.
      val benchmarkName: String = testName.methodName
      benchmark = Benchmark.Builder(benchmarkName).setProject("Android Studio Deployment").build()
      startTime = System.currentTimeMillis()
    }

    Trace.begin(testName.methodName)
  }

  @After
  @Throws(java.lang.Exception::class)
  open fun tearDown() {
    val currentTime = System.currentTimeMillis()
    Trace.end()
    if (benchmark != null) {
      val timeTaken = currentTime - startTime

      // Benchmark names can only include [a-zA-Z0-9_-] characters in them.
      val metricName = String.format("%s-%s_time", testName.methodName, connection!!.deviceId)
      benchmark!!.log(metricName, timeTaken)
    }
    print(getLogcatContent(device))
    Mockito.verifyNoMoreInteractions(service)
  }

  @Throws(IOException::class)
  protected fun assertHistory(device: FakeDevice, vararg expectedHistory: String) {
    val actualHistory = device.shell.history
    val actual = java.lang.String.join("\n", actualHistory)
    var expected = java.lang.String.join("\n", *expectedHistory)

    // Apply the right version
    expected = expected.replace("\\\$VERSION".toRegex(), Version.hash())

    // Find the right sizes:
    val pattern = Pattern.compile("\\$\\{size:([^:}]*)(:([^:}]*))?}")
    val matcher = pattern.matcher(expected)
    val buffer = StringBuffer()
    while (matcher.find()) {
      val pkg = matcher.group(1)
      val file = matcher.group(3)
      val paths = device.getAppPaths(pkg)
      var size = 0
      for (path in paths!!) {
        if (file == null || path.endsWith("/$file")) {
          size += device.readFile(path).size
        }
      }
      matcher.appendReplacement(buffer, size.toString())
    }
    matcher.appendTail(buffer)
    expected = buffer.toString()

    Assert.assertEquals(expected, actual)
  }

  protected fun assertHistoryContain(device: FakeDevice, line: String) {
    Assert.assertTrue(device.shell.history.any { it.contains(line) })
  }

  @Throws(IOException::class)
  protected fun assertInstalled(packageName: String, vararg files: Path?) {
    Assert.assertArrayEquals(arrayOf(packageName), device!!.apps.toTypedArray())
    val paths = device!!.getAppPaths(packageName)
    Assert.assertEquals(files.size.toLong(), paths!!.size.toLong())
    for (i in paths.indices) {
      val expected = Files.readAllBytes(files[i])
      Assert.assertArrayEquals(expected, device!!.readFile(paths[i]))
    }
  }

  protected fun assertMetrics(metrics: List<DeployMetric>, vararg expected: String) {
    val actual = metrics.map { m: DeployMetric -> m.name + (if (m.hasStatus()) ":" + m.status else "") }.toTypedArray()

    // Don't use assertArraysEqual, they don't show enough detail to understand what went wrong
    // e.g: When several ":Success" are expected, we get only the index which failed.
    if (expected.size != actual.size) {
      dumpMetricDiff(expected, actual)
      Assert.fail("metric differ")
    }

    for (i in expected.indices) {
      if (expected[i] != actual[i]) {
        dumpMetricDiff(expected, actual)
        Assert.fail("metric differ")
      }
    }
  }

  protected fun dumpMetricDiff(expected: Array<out String>, actual: Array<String>) {
    println("Expected:" + java.lang.String.join(",", *expected))
    println("Actual  :" + java.lang.String.join(",", *actual))
  }

  protected fun getLogcatContent(device: FakeDevice): String {
    return try {
      String(Files.readAllBytes(device.logcatFile.toPath()), Charsets.UTF_8)
    } catch (io: IOException) {
      ""
    }
  }

  protected fun assertRedefined(logcat: String, vararg classes: String) {
    assertPrefixedInLogcat(logcat, "JVMTI::RedefineClasses:", *classes)
  }

  protected fun assertRetransformed(logcat: String, vararg classes: String) {
    assertPrefixedInLogcat(logcat, "JVMTI::RetransformClasses:", *classes)
  }

  protected fun assertHiddenAPISilencer(logcat: String, vararg classes: String) {
    assertPrefixedInLogcat(logcat, "JVMTI::HiddenAPIWarning:", *classes)
  }

  protected fun assertPrefixedInLogcat(logcat: String, prefix: String, vararg expected: String) {
    val actual = logcat.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    var expectedIndex = 0
    for (i in actual.indices) {
      val idx = actual[i].indexOf(prefix)
      if (idx == -1) {
        continue
      }

      if (expectedIndex == expected.size) {
        Assert.fail("Unexpected logcat line: " + actual[i])
      }

      val trimmed = actual[i].substring(idx)
      Assert.assertEquals("Unexpected logcat line", prefix + expected[expectedIndex], trimmed)
      ++expectedIndex
    }
    if (expectedIndex != expected.size) {
      Assert.fail("Missing logcat line: " + prefix + expected[expectedIndex])
    }
  }

  // Some command use EXEC cmd while others use abb_exec. This is an utility function to generate
  // both types of commands based on the device version
  protected fun cmd(format: String, device: FakeDevice): String {
    return if (device.api >= 30) {
      String.format(format, "abb_exec")
    } else {
      String.format(format, "cmd")
    }
  }
}
