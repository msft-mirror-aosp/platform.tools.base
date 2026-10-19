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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.build.gradle.integration.common.utils.SdkHelper
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.util.concurrent.TimeUnit
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Connected integration test running against an emulator via a TCP serial containing a colon. */
class TcpConnectedTest {
  companion object {
    private const val TCP_SERIAL = "127.0.0.1:5555"

    @JvmField @ClassRule val emulator: ExternalResource = getEmulator()
  }

  @get:Rule val rule = GradleRule.fromProject(BasicSpec())

  @Test
  @Throws(Exception::class)
  fun androidTest() {
    val adb = SdkHelper.getAdb().absolutePath
    val connectProcess = ProcessBuilder(adb, "connect", TCP_SERIAL).start()
    connectProcess.waitFor(30, TimeUnit.SECONDS)

    try {
      val build = rule.build
      build.executor.withEnvironmentVariables(mapOf("ANDROID_SERIAL" to TCP_SERIAL)).run(":app:connectedAndroidTest")

      val reportDir = build.directory.resolve("app/build/reports/androidTests/connected").toFile()
      val indexHtml = reportDir.walkTopDown().maxDepth(3).find { it.name == "index.html" }
      assertThat(indexHtml).isNotNull()

      val resultsDir = build.directory.resolve("app/build/outputs/androidTest-results/connected").toFile()
      val pbFile = resultsDir.walkTopDown().maxDepth(4).find { it.name == "test-result.pb" }
      assertThat(pbFile).isNotNull()
      val testSuiteResult = pbFile!!.inputStream().use { TestSuiteResult.parseFrom(it) }
      assertThat(testSuiteResult.testResultCount).isAtLeast(1)
      assertThat(testSuiteResult.testResultList.any { it.testCase.testMethod == "testBuildConfig" }).isTrue()
    } finally {
      ProcessBuilder(adb, "disconnect", TCP_SERIAL).start().waitFor(10, TimeUnit.SECONDS)
    }
  }
}
