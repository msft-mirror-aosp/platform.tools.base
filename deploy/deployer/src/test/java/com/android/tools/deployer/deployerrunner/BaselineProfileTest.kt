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

import com.android.testutils.TestUtils
import com.android.tools.deployer.DeployerRunner
import com.android.tools.deployer.DeployerTestUtils
import com.android.tools.deployer.rules.ApiLevel
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(ApiLevel::class)
class BaselineProfileTest : DeployRunnerTestBase() {
  @Test
  @ApiLevel.InRange(min = 31)
  @Throws(Exception::class)
  fun testBaselineInstall() {
    Assert.assertTrue(device.apps.isEmpty())
    val runner: DeployerRunner = DeployerRunner(cacheDb, dexDB, service)
    val apk = TestUtils.resolveWorkspacePath(BASE + "sample.apk")
    val baseline = TestUtils.resolveWorkspacePath(BASE + "sample.dm")
    val installersPath = DeployerTestUtils.prepareInstaller().toPath()
    val args =
      arrayOf(
        "install",
        "com.example.helloworld",
        apk.toString(),
        baseline.toString(),
        "--force-full-install",
        "--installers-path=$installersPath",
      )
    val retcode = runner.run(args)
    Assert.assertEquals(0, retcode.toLong())
    Assert.assertEquals(1, device.apps.size.toLong())
    assertInstalled("com.example.helloworld", apk)
    assertMetrics(runner.metrics, "DELTAINSTALL:DISABLED", "INSTALL:OK", "DDMLIB_UPLOAD", "DDMLIB_INSTALL")
    Assert.assertFalse(device.hasFile("/data/local/tmp/sample.apk"))
  }

  fun retrieveEditMapping(beforeEditOffset: Int): Int? {
    return null
  }
}
