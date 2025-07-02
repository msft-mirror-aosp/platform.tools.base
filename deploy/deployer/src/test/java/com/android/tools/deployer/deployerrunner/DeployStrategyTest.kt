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
import com.android.tools.deployer.DeployerRunner
import com.android.tools.deployer.DeployerTestUtils
import com.android.tools.deployer.rules.ApiLevel
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(ApiLevel::class)
class DeployStrategyTest : DeployRunnerTestBase() {
    @Test
    @ApiLevel.InRange(min = 33)
    fun testBaselineInstall() {
        AssumeUtil.assumeNotWindows() // This test runs the installer on the host

        Assert.assertTrue(device.apps.isEmpty())
        val runner = DeployerRunner(cacheDb, dexDB, service)
        val json = TestUtils.resolveWorkspacePath(BASE + "apks/arch_filter.json")
        val installersPath = DeployerTestUtils.prepareInstaller().toPath()
        val args = arrayOf(
            "install",
            "--strategy=$json",
            "--force-full-install",
            "--installers-path=$installersPath"
        )
        val returnCode = runner.run(args)
        Assert.assertEquals(0, returnCode.toLong())
        Assert.assertEquals(1, device.apps.size.toLong())

        val apk0 = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")
        val apk1 = TestUtils.resolveWorkspacePath(BASE + "apks/split2.apk")

        assertInstalled("com.example.simpleapp", apk0, apk1)
    }

    @Test
    @ApiLevel.InRange(min = 33)
    @Throws(Exception::class)
    fun testRootPushInstall() {
        AssumeUtil.assumeNotWindows() // This test runs the installer on the host

        Assert.assertTrue(device.apps.isEmpty())
        val runner = DeployerRunner(cacheDb, dexDB, service)
        val json = TestUtils.resolveWorkspacePath(BASE + "apks/arch_filter.json")
        val installersPath = DeployerTestUtils.prepareInstaller().toPath()
        var args = arrayOf(
            "install",
            "--strategy=$json",
            "--force-full-install",
            "--installers-path=$installersPath",
            )
        var  returnCode = runner.run(args)
        Assert.assertEquals(0, returnCode.toLong())
        Assert.assertEquals(1, device.apps.size.toLong())

        val apk0 = TestUtils.resolveWorkspacePath(BASE + "apks/simple.apk")

        // NOTE that our FakeDevice are of arch "x86" (FakeDevice.java's ABI value)
        val apk1 = TestUtils.resolveWorkspacePath(BASE + "apks/split2.apk")

        args = arrayOf(
            "install",
            "--strategy=$json",
            "--force-full-install",
            "--installers-path=$installersPath",
            "--use-root-push-install",
            "--skip-post-install"
        )

        returnCode = runner.run(args)
        Assert.assertEquals(0, returnCode.toLong())
        Assert.assertEquals(1, device.apps.size.toLong())
        assertInstalled("com.example.simpleapp", apk0, apk1)
        assertMetrics(
            runner.metrics,
            ":Success",
            "ROOT_PUSH_INSTALL:Success"
        )
    }
}
