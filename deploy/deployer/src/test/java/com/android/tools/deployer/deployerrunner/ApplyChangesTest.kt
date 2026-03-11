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

import com.android.testutils.AssumeUtil
import com.android.testutils.TestUtils
import com.android.tools.deployer.DeployerException
import com.android.tools.deployer.DeployerRunner
import com.android.tools.deployer.DeployerTestUtils
import com.android.tools.deployer.Sites
import com.android.tools.deployer.SqlApkFileDatabase
import com.android.tools.deployer.devices.shell.FailingMkdir
import com.android.tools.deployer.rules.ApiLevel
import java.io.File
import java.io.IOException
import java.time.Duration
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(ApiLevel::class)
class ApplyChangesTest : DeployRunnerTestBase() {

  @Test
  @ApiLevel.InRange(max = 29)
  @Throws(Exception::class)
  fun testBasicSwap() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.getApps().isEmpty())
    val runner: DeployerRunner = DeployerRunner(cacheDb, dexDB, service)
    var file = TestUtils.resolveWorkspacePath(DeployRunnerTestBase.BASE + "apks/simple.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")

    val cmd = "am start -n com.example.simpleapp/.MainActivity -a android.intent.action.MAIN"
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    device.getShell().clearHistory()

    file = TestUtils.resolveWorkspacePath(DeployRunnerTestBase.BASE + "apks/simple+code.apk")
    args = arrayOf("codeswap", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    val logcat: String = getLogcatContent(device)

    if (device.getApi() < 26) {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26.ordinal.toLong(), retcode.toLong())
      assertMetrics(runner.metrics) // No metrics
    } else if (device.getApi() < 28) {
      Assert.assertEquals(DeployerException.Error.NO_ERROR.ordinal.toLong(), retcode.toLong())
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
        "/system/bin/cmd package dump com.example.simpleapp",
        getInstallerInvocation(), // deltapreinstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        getInstallerInvocation(), // swap
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        ("/system/bin/run-as com.example.simpleapp cp -n" +
          " /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION"),
        "cp -n /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION",
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStudio("com.example.simpleapp"),
        "mkdir " + Sites.appStudio("com.example.simpleapp"),
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/agent.so " +
          Sites.appStartupAgent("com.example.simpleapp") +
          "\$VERSION-agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/agent.so " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so"),
        ("/system/bin/cmd activity attach-agent 10001 " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so=irsocket-0"),
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Success",
        "COMPARE:Success",
        "SWAP:Success",
      )
      assertRetransformed(logcat, "android.app.ActivityThread", "dalvik.system.DexPathList\$Element")
    } else if (device.getApi() < 30) {
      Assert.assertEquals(DeployerException.Error.NO_ERROR.ordinal.toLong(), retcode.toLong())
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        "/system/bin/cmd package path com.example.simpleapp",
        getInstallerInvocation(), // deltapreinstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        getInstallerInvocation(), // swap
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        ("/system/bin/run-as com.example.simpleapp cp -n" +
          " /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION"),
        "cp -n /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION",
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStudio("com.example.simpleapp"),
        "mkdir " + Sites.appStudio("com.example.simpleapp"),
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/agent.so " +
          Sites.appStartupAgent("com.example.simpleapp") +
          "\$VERSION-agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/agent.so " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so"),
        ("/system/bin/cmd activity attach-agent 10001 " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so=irsocket-0"),
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Success",
        "COMPARE:Success",
        "SWAP:Success",
      )
      assertRetransformed(logcat, "android.app.ActivityThread", "dalvik.system.DexPathList\$Element")
    } else {
      assertRetransformed(
        logcat,
        "android.app.ApplicationLoaders",
        "android.app.ActivityThread",
        "dalvik.system.DexPathList\$Element",
        "dalvik.system.DexPathList",
        "android.app.ResourcesManager",
      )
    }

    TestUtils.eventually(
      {
        try {
          if (dexDB.dump().isEmpty()) {
            Assert.fail()
          }
        } catch (e: DeployerException) {
          Assert.fail()
        }
      },
      Duration.ofSeconds(5),
    )

    Assert.assertFalse(dexDB.hasDuplicates())
  }

  @Test
  @Throws(java.lang.Exception::class)
  fun testSwapWithAppNotRunning() {
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
    args = arrayOf("codeswap", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)

    if (device.api < 26) {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26.ordinal.toLong(), retcode.toLong())
      assertMetrics(runner.metrics) // No metrics
    } else if (device.api < 28) {
      Assert.assertEquals(DeployerException.Error.DUMP_UNKNOWN_PROCESS.ordinal.toLong(), retcode.toLong())
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
        ("/system/bin/cmd package " + (if (device.api < 28) "dump" else "path") + " com.example.simpleapp"),
        getInstallerInvocation(), // deltapreinstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "cmd package install-abandon 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Success",
        "COMPARE:Success",
        "SWAP:Failed",
      )
    } else if (device.api < 30) {
      Assert.assertEquals(DeployerException.Error.DUMP_UNKNOWN_PROCESS.ordinal.toLong(), retcode.toLong())
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        ("/system/bin/cmd package " + (if (device.api < 28) "dump" else "path") + " com.example.simpleapp"),
        getInstallerInvocation(), // deltapreinstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "cmd package install-abandon 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Success",
        "COMPARE:Success",
        "SWAP:Failed",
      )
    }

    // TODO: API 30 tests.
  }

  @Test
  @Throws(java.lang.Exception::class)
  fun testCodeSwapThatFails() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    var file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")

    val cmd = "am start -n com.example.simpleapp/.MainActivity -a android.intent.action.MAIN"
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    device.shell.clearHistory()

    file = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code+res.apk")
    args = arrayOf("codeswap", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)

    if (device.api < 26) {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26.ordinal.toLong(), retcode.toLong())
      assertMetrics(runner.metrics) // No metrics
    } else if (device.api < 28) {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_RESOURCE.ordinal.toLong(), retcode.toLong())
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
        ("/system/bin/cmd package " + (if (device.api < 28) "dump" else "path") + " com.example.simpleapp"),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "cmd package install-abandon 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Failed",
      )
    } else if (device.api < 30) {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_RESOURCE.ordinal.toLong(), retcode.toLong())
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        ("/system/bin/cmd package " + (if (device.api < 28) "dump" else "path") + " com.example.simpleapp"),
        getInstallerInvocation(), // deltainstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "cmd package install-abandon 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Failed",
      )
    } else {
      // TODO: Pipeline 2.0 tests.
    }
  }

  @Test
  @ApiLevel.InRange(max = 29)
  @Throws(java.lang.Exception::class)
  fun testResourceAndCodeSwap() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    var file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", file)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")

    val cmd = "am start -n com.example.simpleapp/.MainActivity -a android.intent.action.MAIN"
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    device.shell.clearHistory()

    file = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code+res.apk")
    args = arrayOf("fullswap", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)
    val logcat = getLogcatContent(device)

    if (device.api < 26) {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26.ordinal.toLong(), retcode.toLong())
      assertMetrics(runner.metrics) // No metrics
    } else if (device.api < 28) {
      Assert.assertEquals(DeployerException.Error.NO_ERROR.ordinal.toLong(), retcode.toLong())
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
        "/system/bin/cmd package dump com.example.simpleapp",
        getInstallerInvocation(), // deltapreinstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        getInstallerInvocation(), // swap
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        ("/system/bin/run-as com.example.simpleapp cp -n" +
          " /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION"),
        "cp -n /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION",
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStudio("com.example.simpleapp"),
        "mkdir " + Sites.appStudio("com.example.simpleapp"),
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/agent.so " +
          Sites.appStartupAgent("com.example.simpleapp") +
          "\$VERSION-agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/agent.so " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so"),
        ("/system/bin/cmd activity attach-agent 10001 " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so=irsocket-0"),
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Success",
        "COMPARE:Success",
        "SWAP:Success",
      )
      assertRetransformed(logcat, "android.app.ActivityThread", "dalvik.system.DexPathList\$Element")
    } else if (device.api < 30) {
      Assert.assertEquals(DeployerException.Error.NO_ERROR.ordinal.toLong(), retcode.toLong())
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        "/system/bin/cmd package path com.example.simpleapp",
        getInstallerInvocation(), // deltapreinstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        getInstallerInvocation(), // swap
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        ("/system/bin/run-as com.example.simpleapp cp -n" +
          " /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION"),
        "cp -n /data/local/tmp/.studio/tmp/\$VERSION/install_server" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION",
        ("/system/bin/run-as com.example.simpleapp" +
          " /data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" +
          " com.example.simpleapp"),
        "/data/data/com.example.simpleapp/code_cache/install_server-\$VERSION" + " com.example.simpleapp",
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "mkdir " + Sites.appStartupAgent("com.example.simpleapp"),
        "/system/bin/run-as com.example.simpleapp mkdir " + Sites.appStudio("com.example.simpleapp"),
        "mkdir " + Sites.appStudio("com.example.simpleapp"),
        ("/system/bin/run-as com.example.simpleapp cp -F" +
          " /data/local/tmp/.studio/tmp/\$VERSION/agent.so " +
          Sites.appStartupAgent("com.example.simpleapp") +
          "\$VERSION-agent.so"),
        ("cp -F /data/local/tmp/.studio/tmp/\$VERSION/agent.so " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so"),
        ("/system/bin/cmd activity attach-agent 10001 " + Sites.appStartupAgent("com.example.simpleapp") + "\$VERSION-agent.so=irsocket-0"),
        "/system/bin/cmd package install-commit 2",
      )
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Success",
        "COMPARE:Success",
        "SWAP:Success",
      )
      assertRetransformed(logcat, "android.app.ActivityThread", "dalvik.system.DexPathList\$Element")
    } else {
      assertRetransformed(
        logcat,
        "android.app.ApplicationLoaders",
        "android.app.ActivityThread",
        "dalvik.system.DexPathList\$Element",
        "dalvik.system.DexPathList",
        "android.app.ResourcesManager",
      )
    }
  }

  @Test
  @Throws(IOException::class)
  fun checkFailingMkDir() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val file = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code.apk")

    // Set device to fail on any attempt to mkdir
    device.shell.addCommand(FailingMkdir())
    val args = arrayOf("codeswap", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    val retcode = runner.run(args)

    if (device.api < 26) {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26.ordinal.toLong(), retcode.toLong())
      assertMetrics(runner.metrics) // No metrics
    } else if (device.api < 30) {
      Assert.assertEquals(DeployerException.Error.DUMP_FAILED.ordinal.toLong(), retcode.toLong())
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(),
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
        "su root " + AdbInstallerTest.CHOWN_DIR,
        AdbInstallerTest.RM_DIR,
        AdbInstallerTest.MK_DIR,
      )
    } else {
      // TODO: Add R+ tests.
    }
  }

  @Test
  @ApiLevel.InRange(min = 31)
  @Throws(java.lang.Exception::class)
  fun testHiddenAPISuppression() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    val oldApk = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val newApk = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code.apk")

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)

    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", oldApk.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", oldApk)

    val cmd = "am start -n com.example.simpleapp/.MainActivity -a android.intent.action.MAIN"
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    device.shell.clearHistory()

    // This swap is just to put the agent in place.
    args = arrayOf("codeswap", "com.example.simpleapp", newApk.toString(), "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())

    // Stop the app so we can restart it and attach a startup agent.
    device.stopApp("com.example.simpleapp")
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    val logcat = getLogcatContent(device)
    assertHiddenAPISilencer(logcat, "Suppressing", "Restoring")
  }

  @Test
  @Throws(java.lang.Exception::class)
  fun testApkNotRecognized() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    // Install the base apk:
    Assert.assertTrue(device.apps.isEmpty())
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    var runner = DeployerRunner(cacheDb, dexDB, service)
    var file = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    var args = arrayOf("install", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")
    var retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())
    assertInstalled("com.example.simpleapp", file)

    if (device.api < 24) {
      assertMetrics(runner.metrics, "DELTAINSTALL:API_NOT_SUPPORTED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    } else {
      assertMetrics(runner.metrics, "DELTAINSTALL:DUMP_UNKNOWN_PACKAGE", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    }

    file = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code.apk")
    args = arrayOf("codeswap", "com.example.simpleapp", file.toString(), "--installers-path=$installersPath")

    // We create a empty database. This simulate an installed APK not found in the database.
    dexDB = SqlApkFileDatabase(File.createTempFile("test_db_empty", ".bin"), null)
    device.shell.clearHistory()
    runner = DeployerRunner(cacheDb, dexDB, service)
    retcode = runner.run(args)
    if (device.supportsJvmti()) {
      // TODO WIP. This is WRONG, this is where optimistic swap should fail because of
      // OverlayID mismatch.
      if (device.api < 30) {
        Assert.assertEquals(DeployerException.Error.REMOTE_APK_NOT_FOUND_IN_DB.ordinal.toLong(), retcode.toLong())
      }
    } else {
      Assert.assertEquals(DeployerException.Error.CANNOT_SWAP_BEFORE_API_26.ordinal.toLong(), retcode.toLong())
    }
    if (device.api < 26) {
      Assert.assertTrue(runner.metrics.isEmpty())
      assertHistory(device, "getprop")
    } else if (device.api < 30) {
      val packageCommand = if (device.api < 28) "dump" else "path"
      assertMetrics(
        runner.metrics,
        "DELTAPREINSTALL_WRITE",
        ":Success",
        ":Success",
        ":Success",
        "DUMP:Success",
        "DIFF:Success",
        "PREINSTALL:Success",
        "VERIFY:Success",
        "COMPARE:Failed",
      )
      assertHistory(
        device,
        "getprop",
        getInstallerInvocation(), // dump com.example.simpleapp
        "/system/bin/run-as com.example.simpleapp id -u",
        "id -u",
        String.format("/system/bin/cmd package %s com.example.simpleapp", packageCommand),
        getInstallerInvocation(), // deltapreinstall
        "/system/bin/cmd package install-create -t -r --dont-kill",
        "cmd package install-write -S \${size:com.example.simpleapp} 2 base.apk",
        "cmd package install-abandon 2",
      )
    }
  }

  @Test
  @ApiLevel.InRange(min = 30)
  @Throws(java.lang.Exception::class)
  fun testAgentTransformCache() {
    AssumeUtil.assumeNotWindows() // This test runs the installer on the host

    val oldApk = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
    val newApk = TestUtils.resolveWorkspacePath(BASE + "apks/simple+code.apk")

    Assert.assertTrue(device.apps.isEmpty())
    val runner = DeployerRunner(cacheDb, dexDB, service)

    val installersPath = DeployerTestUtils.prepareInstaller().toPath()

    var args = arrayOf("install", "com.example.simpleapp", oldApk.toString(), "--force-full-install", "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())
    assertInstalled("com.example.simpleapp", oldApk)

    val cmd = "am start -n com.example.simpleapp/.MainActivity -a android.intent.action.MAIN"
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    device.shell.clearHistory()

    // This swap is just to put the agent in place.
    args = arrayOf("codeswap", "com.example.simpleapp", newApk.toString(), "--installers-path=$installersPath")

    Assert.assertEquals(0, runner.run(args).toLong())

    // Stop the app so we can restart it and attach a startup agent.
    device.stopApp("com.example.simpleapp")
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    // The second time we restart it, it should use cached instrumentation.
    device.stopApp("com.example.simpleapp")
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    // The third time we restart it, it should use cached instrumentation.
    device.stopApp("com.example.simpleapp")
    Assert.assertEquals(0, device.executeScript(cmd, byteArrayOf()).value.toLong())

    val logcat = getLogcatContent(device)

    // We currently determine if the agent is using transform caching by inspecting JVMTI
    // invocations. The agent uses RetransformClasses + RedefineClasses when cached classes are
    // not available, and only uses RedefineClasses when cached classes are present.

    // Should only have one retransform of each of these classes.
    assertRetransformed(logcat, "android.app.ApplicationLoaders", "java.lang.Thread", "dalvik.system.DexPathList", "android.app.LoadedApk")
    // Should have redefined each of these classes three times, once per restart.
    assertRedefined(
      logcat,
      "android.app.ApplicationLoaders",
      "java.lang.Thread",
      "dalvik.system.DexPathList",
      "android.app.LoadedApk",
      "android.app.ApplicationLoaders",
      "java.lang.Thread",
      "dalvik.system.DexPathList",
      "android.app.LoadedApk",
      "android.app.ApplicationLoaders",
      "java.lang.Thread",
      "dalvik.system.DexPathList",
      "android.app.LoadedApk",
    )
  }
}
