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

import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import com.android.testutils.AssumeUtil
import com.android.testutils.TestUtils
import com.android.tools.deploy.proto.Deploy.DumpResponse
import com.android.tools.deployer.AdbClient
import com.android.tools.deployer.AdbInstaller
import com.android.tools.deployer.AdbInstallerTest
import com.android.tools.deployer.DeployMetric
import com.android.tools.deployer.DeployerException
import com.android.tools.deployer.DeployerRunner
import com.android.tools.deployer.DeployerTestUtils
import com.android.tools.deployer.Installer
import com.android.tools.deployer.Sites
import com.android.tools.deployer.TestLogger
import com.android.tools.deployer.rules.ApiLevel
import com.android.utils.ILogger
import java.util.concurrent.TimeUnit
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

@RunWith(ApiLevel::class)
class InstallTestTest : DeployRunnerTestBase() {

  @Test
  @Throws(Exception::class)
  fun testFullInstallSuccessful() {
    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val file = TestUtils.resolveWorkspacePath(BASE + "sample.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val args = arrayOf("install", "com.example.helloworld", file.toString(), "--force-full-install", "--installers-path=$installersPath")
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())
    assertInstalled("com.example.helloworld", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    Assert.assertFalse(device.hasFile("/data/local/tmp/sample.apk"))
  }

  @Test
  @Throws(Exception::class)
  fun testSkipPostInstallTasks() {
    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val file = TestUtils.resolveWorkspacePath(BASE + "sample.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val args =
      arrayOf(
        "install",
        "com.example.helloworld",
        file.toString(),
        "--force-full-install",
        "--installers-path=$installersPath",
        "--skip-post-install",
      )
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())
    assertInstalled("com.example.helloworld", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    Assert.assertTrue(dexDB.dump().isEmpty())
    Assert.assertFalse(device.hasFile("/data/local/tmp/sample.apk"))
  }

  @Test
  @ApiLevel.InRange(min = 28)
  @Throws(Exception::class)
  fun testInstallCoroutineDebuggerSuccessful() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val file = TestUtils.resolveWorkspacePath(BASE + "sample.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val args = arrayOf("install", "com.example.helloworld", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())

    assertInstalled("com.example.helloworld", file)
    // file should be there after app install
    Assert.assertTrue(device.hasFile(Sites.appCodeCache("com.example.helloworld") + "coroutine_debugger_agent.so"))
  }

  @Test
  @Throws(Exception::class)
  fun testAttemptDeltaInstallWithoutPreviousInstallation() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val file = TestUtils.resolveWorkspacePath(BASE + "sample.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val args = arrayOf("install", "com.example.helloworld", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())

    assertInstalled("com.example.helloworld", file)

    if (device.api < 21) {
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      assertHistory(device, "getprop", "pm install -r -t \"/data/local/tmp/sample.apk\"", "rm \"/data/local/tmp/sample.apk\"")
    } else if (device.api < 24) {
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      assertHistory(
        device,
        "getprop",
        "pm install-create -r -t -S \${size:com.example.helloworld}",
        "pm install-write -S \${size:com.example.helloworld} 1 sample.apk -",
        "pm install-commit 1",
      )
    } else if (device.api < 28) {
      val packageCommand = "dump"
      assertMetrics(runner.metrics, "DELTAINSTALL:DUMP_UNKNOWN_PACKAGE", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.helloworld
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        AdbInstallerTest.CHMOD_INSTALLER,
        AdbInstallerTest.CHMOD_DIR,
        AdbInstallerTest.CHOWN_DIR,
        getInstallerInvocation(), // dump com.example.helloworld
        "/system/bin/run-as com.example.helloworld id -u",
        String.format("/system/bin/cmd package %s com.example.helloworld", packageCommand),
        "cmd package install-create -r -t -S \${size:com.example.helloworld}",
        "cmd package install-write -S \${size:com.example.helloworld} 1 sample.apk -",
        "cmd package install-commit 1",
      )
    } else if (device.api < 35) {
      val packageCommand = "path"
      assertMetrics(runner.metrics, "DELTAINSTALL:DUMP_UNKNOWN_PACKAGE", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.helloworld
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        AdbInstallerTest.CHMOD_INSTALLER,
        AdbInstallerTest.CHMOD_DIR,
        AdbInstallerTest.CHOWN_DIR,
        getInstallerInvocation(), // dump com.example.helloworld
        "/system/bin/run-as com.example.helloworld id -u",
        String.format("/system/bin/cmd package %s com.example.helloworld", packageCommand),
        cmd("%s package install-create -r -t -S \${size:com.example.helloworld}", device),
        cmd("%s package install-write -S \${size:com.example.helloworld} 1" + " sample.apk -", device),
        cmd("%s package install-commit 1", device),
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.helloworld cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.helloworld") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.helloworld") +
          "coroutine_debugger_agent.so"),
      )
    } else {
      val packageCommand = "path"
      assertMetrics(runner.metrics, "DELTAINSTALL:DUMP_UNKNOWN_PACKAGE", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.helloworld
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        AdbInstallerTest.CHMOD_INSTALLER,
        AdbInstallerTest.CHMOD_DIR,
        AdbInstallerTest.CHOWN_DIR,
        getInstallerInvocation(), // dump com.example.helloworld
        "/system/bin/run-as com.example.helloworld id -u",
        String.format("/system/bin/cmd package %s com.example.helloworld", packageCommand),
        cmd("%s package install-create -r -t --dexopt-compiler-filter" + " assume-verified -S \${size:com.example.helloworld}", device),
        cmd("%s package install-write -S \${size:com.example.helloworld} 1" + " sample.apk -", device),
        cmd("%s package install-commit 1", device),
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.helloworld cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.helloworld") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.helloworld") +
          "coroutine_debugger_agent.so"),
      )
    }
  }

  @Test
  @ApiLevel.InRange(max = 29)
  @Throws(Exception::class)
  fun testSkipInstall() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    device.shell.clearHistory()

    args = arrayOf("install", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())

    assertInstalled("com.example.simpleapp", file)

    if (device.api < 24) {
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    } else if (device.api < 28) {
      val packageCommand = "dump"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(),
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        AdbInstallerTest.CHMOD_INSTALLER,
        AdbInstallerTest.CHMOD_DIR,
        AdbInstallerTest.CHOWN_DIR,
        getInstallerInvocation(),
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        "am force-stop com.example.simpleapp",
      )
      assertMetrics(runner.metrics, "INSTALL:SKIPPED_INSTALL")
    } else {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(),
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        "am force-stop com.example.simpleapp",
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
      )
      assertMetrics(runner.metrics, "INSTALL:SKIPPED_INSTALL")
    }
  }

  @Test
  @Throws(Exception::class)
  fun testDeltaInstall() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    var file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    device.shell.clearHistory()

    file = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code.apk")
    args = arrayOf("install", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())

    assertInstalled("com.example.simpleapp", file)

    if (device.api < 24) {
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    } else if (device.api < 28) {
      val packageCommand = "dump"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        AdbInstallerTest.CHMOD_INSTALLER,
        AdbInstallerTest.CHMOD_DIR,
        AdbInstallerTest.CHOWN_DIR,
        getInstallerInvocation(), // dump
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    } else if (device.api < 35) {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    } else {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create --dexopt-compiler-filter" + " assume-verified -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    }
  }

  @Test
  @Throws(Exception::class)
  fun testInstallOldVersion() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val v2 = TestUtils.resolveWorkspacePath(BASE + "apks/simple+ver.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", v2.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", v2)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")

    device.shell.clearHistory()

    val v1 = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    args = arrayOf("install", "com.example.simpleapp", v1.toString(), "--installers-path=$installersPath")

    Mockito.`when`(service.prompt(ArgumentMatchers.anyString())).thenReturn(false)

    val retcode = runner.run(args)
    Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())

    // Check old app still installed
    assertInstalled("com.example.simpleapp", v2)

    if (device.api == 19) {
      assertHistory(
        device,
        "getprop",
        "pm install -r -t \"/data/local/tmp/simple.apk\"", // ,"rm \"/data/local/tmp/simple.apk\""
        // TODO: ddmlib doesn't remove when
        // installation fails
      )
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:INSTALL_FAILED_VERSION_DOWNGRADE")
    } else if (device.api < 24) {
      assertHistory(
        device,
        "getprop",
        "pm install-create -r -t -S \${size:com.example.simpleapp}", // TODO: passing
        // size on create?
        "pm install-write -S \${size:com.example.simpleapp} 2 simple.apk -",
        "pm install-commit 2",
      )
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:INSTALL_FAILED_VERSION_DOWNGRADE")
    } else if (device.api < 28) {
      val packageCommand = "dump"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        AdbInstallerTest.CHMOD_INSTALLER,
        AdbInstallerTest.CHMOD_DIR,
        AdbInstallerTest.CHOWN_DIR,
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:ERROR.INSTALL_FAILED_VERSION_DOWNGRADE")
    } else if (device.api < 35) {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:ERROR.INSTALL_FAILED_VERSION_DOWNGRADE")
    } else {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create --dexopt-compiler-filter" + " assume-verified -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:ERROR.INSTALL_FAILED_VERSION_DOWNGRADE")
    }
    Mockito.verify(service, Mockito.times(1)).prompt(ArgumentMatchers.anyString())
  }

  @Test
  @Throws(Exception::class)
  fun testInstallSplit() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val base = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val split = TestUtils.resolveWorkspacePath(BASE + "apks/split.apk")

    val args = arrayOf("install", "com.example.simpleapp", base.toString(), split.toString(), "--force-full-install")

    val code = runner.run(args)
    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      assertInstalled("com.example.simpleapp", base, split)
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    }
  }

  @Test
  @Throws(Exception::class)
  fun testInstallVersionMismatchSplit() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val base = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val split = TestUtils.resolveWorkspacePath(BASE + "apks/split+ver.apk")

    val args = arrayOf("install", "com.example.simpleapp", base.toString(), split.toString(), "--force-full-install")

    val code = runner.run(args)
    Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
    if (device.api < 21) {
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:INSTALL_FAILED_INVALID_APK")
    }
  }

  @Test
  @ApiLevel.InRange(min = 34, max = 35)
  @Throws(Exception::class)
  fun testAssumeVerifiedSuccessful() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    var file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    device.shell.clearHistory()

    file = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code.apk")
    args = arrayOf("install", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")

    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())

    assertInstalled("com.example.simpleapp", file)

    if (device.api < 35) {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    } else {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create --dexopt-compiler-filter" + " assume-verified -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    }
  }

  @Test
  @Throws(Exception::class)
  fun testBadDeltaOnSplit() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val base = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val split = TestUtils.resolveWorkspacePath(BASE + "apks/split.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args =
      arrayOf(
        "install",
        "com.example.simpleapp",
        base.toString(),
        split.toString(),
        "--force-full-install",
        "--installers-path=$installersPath",
      )

    var code = runner.run(args)
    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      assertInstalled("com.example.simpleapp", base, split)
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    }

    device.shell.clearHistory()

    val update = TestUtils.resolveWorkspacePath(BASE + "apks/split+ver.apk")
    args = arrayOf("install", "com.example.simpleapp", base.toString(), update.toString(), "--installers-path=$installersPath")

    code = runner.run(args)

    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      Assert.assertEquals(1, device.apps.size.toLong())

      // Check old app still installed
      assertInstalled("com.example.simpleapp", base, split)

      if (device.api < 24) {
        assertHistory(
          device,
          "getprop",
          "pm install-create -r -t -S \${size:com.example.simpleapp}",
          "pm install-write -S \${size:com.example.simpleapp:base.apk} 2 simple.apk -",
          "pm install-write -S \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_ver.apk -",
          "pm install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:INSTALL_FAILED_INVALID_APK")
      } else if (device.api < 28) {
        val packageCommand = "dump"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          AdbInstallerTest.RM_DIR,
          AdbInstallerTest.MK_DIR,
          AdbInstallerTest.CHMOD_INSTALLER,
          AdbInstallerTest.CHMOD_DIR,
          AdbInstallerTest.CHOWN_DIR,
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // deltainstall
          "/system/bin/cmd package install-create -t -r",
          ("cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_split_01.apk"),
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " base.apk",
          "/system/bin/cmd package install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:ERROR.INSTALL_FAILED_INVALID_APK")
      } else if (device.api < 35) {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // deltainstall
          "/system/bin/cmd package install-create -t -r",
          ("cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_split_01.apk"),
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " base.apk",
          "/system/bin/cmd package install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:ERROR.INSTALL_FAILED_INVALID_APK")
      } else {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // deltainstall
          "/system/bin/cmd package install-create --dexopt-compiler-filter" + " assume-verified -t -r",
          ("cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_split_01.apk"),
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " base.apk",
          "/system/bin/cmd package install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:ERROR.INSTALL_FAILED_INVALID_APK")
      }
    }
  }

  @Test
  @Throws(Exception::class)
  fun testDeltaOnSplit() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val base = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val split = TestUtils.resolveWorkspacePath(BASE + "apks/split.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args =
      arrayOf(
        "install",
        "com.example.simpleapp",
        base.toString(),
        split.toString(),
        "--force-full-install",
        "--installers-path=$installersPath",
      )

    var code = runner.run(args)
    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      assertInstalled("com.example.simpleapp", base, split)
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    }

    device.shell.clearHistory()

    val update = TestUtils.resolveWorkspacePath(BASE + "apks/split+code.apk")
    args = arrayOf("install", "com.example.simpleapp", base.toString(), update.toString(), "--installers-path=$installersPath")

    code = runner.run(args)

    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      Assert.assertEquals(0, code.toLong())
      Assert.assertEquals(1, device.apps.size.toLong())

      // Check new app installed
      assertInstalled("com.example.simpleapp", base, update)

      if (device.api < 24) {
        assertHistory(
          device,
          "getprop",
          "pm install-create -r -t -S \${size:com.example.simpleapp}",
          "pm install-write -S \${size:com.example.simpleapp:base.apk} 2 simple.apk -",
          "pm install-write -S \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_code.apk -",
          "pm install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else if (device.api < 28) {
        val packageCommand = "dump"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          AdbInstallerTest.RM_DIR,
          AdbInstallerTest.MK_DIR,
          AdbInstallerTest.CHMOD_INSTALLER,
          AdbInstallerTest.CHMOD_DIR,
          AdbInstallerTest.CHOWN_DIR,
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // detalinstall
          "/system/bin/cmd package install-create -t -r -p com.example.simpleapp",
          ("cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_split_01.apk"),
          "/system/bin/cmd package install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
      } else if (device.api < 35) {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // detalinstall
          "/system/bin/cmd package install-create -t -r -p com.example.simpleapp",
          ("cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_split_01.apk"),
          "/system/bin/cmd package install-commit 2",
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
      } else {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // detalinstall
          "/system/bin/cmd package install-create --dexopt-compiler-filter" + " assume-verified -t -r -p com.example.simpleapp",
          ("cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split_split_01.apk"),
          "/system/bin/cmd package install-commit 2",
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
      }
    }
  }

  @Test
  @Throws(Exception::class)
  fun testAddSplit() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val base = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val split = TestUtils.resolveWorkspacePath(BASE + "apks/split.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args =
      arrayOf(
        "install",
        "com.example.simpleapp",
        base.toString(),
        split.toString(),
        "--force-full-install",
        "--installers-path=$installersPath",
      )

    var code = runner.run(args)
    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      assertInstalled("com.example.simpleapp", base, split)
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    }

    device.shell.clearHistory()

    val added = TestUtils.resolveWorkspacePath(BASE + "apks/split2.apk")
    args =
      arrayOf("install", "com.example.simpleapp", base.toString(), split.toString(), added.toString(), "--installers-path=$installersPath")

    code = runner.run(args)

    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      Assert.assertEquals(0, code.toLong())
      Assert.assertEquals(1, device.apps.size.toLong())

      // Check new app installed
      assertInstalled("com.example.simpleapp", base, split, added)

      if (device.api < 24) {
        assertHistory(
          device,
          "getprop",
          "pm install-create -r -t -S \${size:com.example.simpleapp}",
          "pm install-write -S \${size:com.example.simpleapp:base.apk} 2 simple.apk -",
          "pm install-write -S \${size:com.example.simpleapp:split_split_01.apk} 2" + " split.apk -",
          "pm install-write -S \${size:com.example.simpleapp:split_split_02.apk} 2" + " split2.apk -",
          "pm install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else if (device.api < 28) {
        val packageCommand = "dump"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          AdbInstallerTest.RM_DIR,
          AdbInstallerTest.MK_DIR,
          AdbInstallerTest.CHMOD_INSTALLER,
          AdbInstallerTest.CHMOD_DIR,
          AdbInstallerTest.CHOWN_DIR,
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          "cmd package install-create -r -t -S \${size:com.example.simpleapp}",
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " simple.apk -",
          "cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2 split.apk -",
          "cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_02.apk} 2 split2.apk -",
          "cmd package install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:CANNOT_GENERATE_DELTA", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else if (device.api < 35) {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          cmd("%s package install-create -r -t -S \${size:com.example.simpleapp}", device),
          cmd("%s package install-write -S \${size:com.example.simpleapp:base.apk}" + " 2 simple.apk -", device),
          cmd(("%s package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split.apk -"), device),
          cmd(("%s package install-write -S" + " \${size:com.example.simpleapp:split_split_02.apk} 2" + " split2.apk -"), device),
          cmd("%s package install-commit 2", device),
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:CANNOT_GENERATE_DELTA", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          cmd("%s package install-create -r -t --dexopt-compiler-filter" + " assume-verified -S \${size:com.example.simpleapp}", device),
          cmd("%s package install-write -S \${size:com.example.simpleapp:base.apk}" + " 2 simple.apk -", device),
          cmd(("%s package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split.apk -"), device),
          cmd(("%s package install-write -S" + " \${size:com.example.simpleapp:split_split_02.apk} 2" + " split2.apk -"), device),
          cmd("%s package install-commit 2", device),
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:CANNOT_GENERATE_DELTA", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      }
    }
  }

  @Test
  @Throws(Exception::class)
  fun testRemoveSplit() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val base = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val split1 = TestUtils.resolveWorkspacePath(BASE + "apks/split.apk")
    val split2 = TestUtils.resolveWorkspacePath(BASE + "apks/split2.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args =
      arrayOf(
        "install",
        "com.example.simpleapp",
        base.toString(),
        split1.toString(),
        split2.toString(),
        "--force-full-install",
        "--installers-path=$installersPath",
      )

    var code = runner.run(args)
    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      assertInstalled("com.example.simpleapp", base, split1, split2)
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    }

    device.shell.clearHistory()

    args = arrayOf("install", "com.example.simpleapp", base.toString(), split1.toString(), "--installers-path=$installersPath")

    code = runner.run(args)

    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      Assert.assertEquals(0, code.toLong())
      Assert.assertEquals(1, device.apps.size.toLong())

      // Check new app installed
      assertInstalled("com.example.simpleapp", base, split1)

      if (device.api < 24) {
        assertHistory(
          device,
          "getprop",
          "pm install-create -r -t -S \${size:com.example.simpleapp}",
          "pm install-write -S \${size:com.example.simpleapp:base.apk} 2 simple.apk -",
          "pm install-write -S \${size:com.example.simpleapp:split_split_01.apk} 2" + " split.apk -",
          "pm install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else if (device.api < 28) {
        val packageCommand = "dump"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          AdbInstallerTest.RM_DIR,
          AdbInstallerTest.MK_DIR,
          AdbInstallerTest.CHMOD_INSTALLER,
          AdbInstallerTest.CHMOD_DIR,
          AdbInstallerTest.CHOWN_DIR,
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          "cmd package install-create -r -t -S \${size:com.example.simpleapp}",
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " simple.apk -",
          "cmd package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2 split.apk -",
          "cmd package install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:CANNOT_GENERATE_DELTA", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else if (device.api < 35) {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          cmd("%s package install-create -r -t -S \${size:com.example.simpleapp}", device),
          cmd("%s package install-write -S \${size:com.example.simpleapp:base.apk}" + " 2 simple.apk -", device),
          cmd(("%s package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split.apk -"), device),
          cmd("%s package install-commit 2", device),
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:CANNOT_GENERATE_DELTA", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          cmd("%s package install-create -r -t --dexopt-compiler-filter" + " assume-verified -S \${size:com.example.simpleapp}", device),
          cmd("%s package install-write -S \${size:com.example.simpleapp:base.apk}" + " 2 simple.apk -", device),
          cmd(("%s package install-write -S" + " \${size:com.example.simpleapp:split_split_01.apk} 2" + " split.apk -"), device),
          cmd("%s package install-commit 2", device),
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:CANNOT_GENERATE_DELTA", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      }
    }
  }

  @Test
  @Throws(Exception::class)
  fun testAddAsset() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    var file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    device.shell.clearHistory()

    file = TestUtils.resolveWorkspacePath(BASE + "apks/simple+new_asset.apk")
    args = arrayOf("install", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())

    assertInstalled("com.example.simpleapp", file)

    if (device.api < 24) {
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    } else if (device.api < 28) {
      val packageCommand = "dump"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        AdbInstallerTest.CHMOD_INSTALLER,
        AdbInstallerTest.CHMOD_DIR,
        AdbInstallerTest.CHOWN_DIR,
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    } else if (device.api < 35) {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    } else {
      val packageCommand = "path"
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create --dexopt-compiler-filter" + " assume-verified -t -r",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "/system/bin/cmd package install-commit 2",
        "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
          Sites.appCodeCache("com.example.simpleapp") +
          "coroutine_debugger_agent.so"),
      )
      assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
    }
  }

  @Test
  @Throws(Exception::class)
  fun testAddAssetWithSplits() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val base = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val split = TestUtils.resolveWorkspacePath(BASE + "apks/split.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args =
      arrayOf(
        "install",
        "com.example.simpleapp",
        base.toString(),
        split.toString(),
        "--force-full-install",
        "--installers-path=$installersPath",
      )

    var code = runner.run(args)
    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      assertInstalled("com.example.simpleapp", base, split)
      assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    }

    device.shell.clearHistory()

    val newBase = TestUtils.resolveWorkspacePath(BASE + "apks/simple+new_asset.apk")
    args = arrayOf("install", "com.example.simpleapp", newBase.toString(), split.toString(), "--installers-path=$installersPath")

    code = runner.run(args)

    if (device.api < 21) {
      Assert.assertEquals(DeployerException.Error.INSTALL_FAILED.ordinal.toLong(), code.toLong())
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:MULTI_APKS_NO_SUPPORTED_BELOW21")
    } else {
      Assert.assertEquals(0, code.toLong())
      Assert.assertEquals(1, device.apps.size.toLong())

      // Check new app installed
      assertInstalled("com.example.simpleapp", newBase, split)

      if (device.api < 24) {
        assertHistory(
          device,
          "getprop",
          "pm install-create -r -t -S \${size:com.example.simpleapp}",
          "pm install-write -S \${size:com.example.simpleapp:base.apk} 2" + " simple_new_asset.apk -",
          "pm install-write -S \${size:com.example.simpleapp:split_split_01.apk} 2" + " split.apk -",
          "pm install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
      } else if (device.api < 28) {
        val packageCommand = "dump"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          AdbInstallerTest.RM_DIR,
          AdbInstallerTest.MK_DIR,
          AdbInstallerTest.CHMOD_INSTALLER,
          AdbInstallerTest.CHMOD_DIR,
          AdbInstallerTest.CHOWN_DIR,
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // deltainstal
          "/system/bin/cmd package install-create -t -r -p com.example.simpleapp",
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " base.apk",
          "/system/bin/cmd package install-commit 2",
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
      } else if (device.api < 35) {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // deltainstal
          "/system/bin/cmd package install-create -t -r -p com.example.simpleapp",
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " base.apk",
          "/system/bin/cmd package install-commit 2",
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
      } else {
        val packageCommand = "path"
        assertHistory(
          device,
          "getprop",
          getInstallerInvocation(), // dump com.example.simpleapp
          "/system/bin/run-as com.example.simpleapp id -u",
          "id -u",
          String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
          getInstallerInvocation(), // deltainstal
          "/system/bin/cmd package install-create --dexopt-compiler-filter" + " assume-verified -t -r -p com.example.simpleapp",
          "cmd package install-write -S \${size:com.example.simpleapp:base.apk} 2" + " base.apk",
          "/system/bin/cmd package install-commit 2",
          "/data/local/tmp/.studio/bin/installer -version=\$VERSION",
          ("/system/bin/run-as com.example.simpleapp cp -F" +
            " /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
          ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/coroutine_debugger_agent.so " +
            Sites.appCodeCache("com.example.simpleapp") +
            "coroutine_debugger_agent.so"),
        )
        assertMetrics(runner.metrics, "DELTAINSTALL_UPLOAD", "DELTAINSTALL_INSTALL", "DELTAINSTALL:SUCCESS")
      }
    }
  }

  @Test
  @Throws(Exception::class)
  fun testStartApp() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host
    Assert.assertTrue(device.apps.isEmpty())
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    // Install the base apk:
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val args = arrayOf("install", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())
    assertInstalled("com.example.simpleapp", file)

    val cmd = "am start -n com.example.simpleapp/.MainActivity -a android.intent.action.MAIN"
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())
    var processes = device.processes
    Assert.assertEquals(1, processes.size.toLong())
    Assert.assertEquals("com.example.simpleapp", processes[0].application.packageName)

    Assert.assertEquals(0, device.executeScript("am force-stop com.foo", byteArrayOf()).value.toLong())
    processes = device.processes
    Assert.assertEquals(1, processes.size.toLong())
    Assert.assertEquals("com.example.simpleapp", processes[0].application.packageName)

    Assert.assertEquals(0, device.executeScript("am force-stop com.example.simpleapp.bar", byteArrayOf()).value.toLong())
    processes = device.processes
    Assert.assertEquals(1, processes.size.toLong())
    Assert.assertEquals("com.example.simpleapp", processes[0].application.packageName)

    Assert.assertNotEquals(0, device.executeScript("am force-stop", byteArrayOf()).value.toLong())
    processes = device.processes
    Assert.assertEquals(1, processes.size.toLong())
    Assert.assertEquals("com.example.simpleapp", processes[0].application.packageName)

    Assert.assertEquals(0, device.executeScript("am force-stop com.example.simpleapp", byteArrayOf()).value.toLong())
    processes = device.processes
    Assert.assertEquals(0, processes.size.toLong())
  }

  @Test
  @Throws(Exception::class)
  fun testDump() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    val packageName = "com.example.simpleapp"
    Assert.assertTrue(device.apps.isEmpty())
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val runner = DeployerRunner(cacheDb, dexDB, service)

    AndroidDebugBridge.init(AdbInitOptions.DEFAULT)
    val bridge = AndroidDebugBridge.createBridge()
    while (bridge!!.devices.isEmpty()) {
      Thread.sleep(100)
    }
    val iDevice = bridge.devices[0]
    val logger: ILogger = TestLogger()
    val adb = AdbClient(iDevice, logger)
    val metrics = ArrayList<DeployMetric>()
    val installer: Installer = AdbInstaller(installersPath.toString(), adb, metrics, logger)

    // Make sure we have true negative.
    var response = installer.dump(listOf(packageName))
    Assert.assertEquals(DumpResponse.Status.ERROR_PACKAGE_NOT_FOUND, response.status)
    AndroidDebugBridge.disconnectBridge(10, TimeUnit.SECONDS)
    AndroidDebugBridge.terminate()

    // Install our target APK.
    run {
      val file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
      val args = arrayOf("install", packageName, file.toString(), "--installers-path=$installersPath")
      val retcode = runner.run(args)
      Assert.assertEquals(0, retcode.toLong())
      Assert.assertEquals(1, device.apps.size.toLong())
      assertInstalled(packageName, file)
    }

    // Make sure we have true positive and no false negative.
    response = installer.dump(listOf(packageName))
    if (device.api < 24) {
      // No "cmd" on APIs < 24.
      Assert.assertEquals(DumpResponse.Status.ERROR_PACKAGE_NOT_FOUND, response.status)
    } else {
      Assert.assertEquals(DumpResponse.Status.OK, response.status)
      Assert.assertEquals(1, response.packagesCount.toLong())
      Assert.assertEquals(1, response.getPackages(0).apksCount.toLong())
      Assert.assertEquals(device.getAppPaths(packageName)!![0], response.getPackages(0).getApks(0).absolutePath)
    }

    // Make sure we don't have false positive.
    response = installer.dump(listOf("foo.bar"))
    Assert.assertEquals(DumpResponse.Status.ERROR_PACKAGE_NOT_FOUND, response.status)
  }

  @Test
  @ApiLevel.InRange(min = 24)
  @Throws(Exception::class)
  fun testRootPushInstall() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    val oldApk = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val newApk = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code.apk")

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)

    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", oldApk.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", oldApk)

    args =
      arrayOf(
        "install",
        "com.example.simpleapp",
        newApk.toString(),
        "--installers-path=$installersPath",
        "--use-root-push-install",
        "--skip-post-install",
      )

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", newApk)
    assertMetrics(runner.metrics, ":Success", "ROOT_PUSH_INSTALL:Success")
  }

  @Test
  @ApiLevel.InRange(min = 30)
  @Throws(Exception::class)
  fun testCustomUserFlags() {
    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val file = TestUtils.resolveWorkspacePath(BASE + "sample.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val args =
      arrayOf(
        "install",
        "com.example.helloworld",
        file.toString(),
        "--install-flags=-g     -d", // Make sure we are parsing empty space correctly.
        "--force-full-install",
        "--installers-path=$installersPath",
      )
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())
    assertInstalled("com.example.helloworld", file)
    assertHistoryContain(device, "package install-create -r -t -g -d")
  }
}
