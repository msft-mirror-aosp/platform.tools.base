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

package com.android.tools.ui.inspector.device

import com.android.adblib.DeviceSelector
import com.android.adblib.testing.FakeAdbSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

class ProcessPackagesTest {

  private val fakeSession = FakeAdbSession()
  private val selector = DeviceSelector.fromSerialNumber("serial")

  @Test
  fun testParseProcessPackages_realApi35SharedUidExcerpt() {
    // Verbatim `dumpsys activity processes` excerpt from an API 35 emulator running two sharedUserId siblings.
    val result =
      parseProcessPackages(
        """
        |  *APP* UID 10212 ProcessRecord{b827c26 15156:com.example.uida/u0a212}
        |    user #0 uid=10212 gids={50212, 20212, 9997}
        |    mRequiredAbi=arm64-v8a instructionSet=null
        |    dir=/data/app/~~6zRCfow_uyI2bf7ZkCVMgw==/com.example.uida-oW4_28099sb08utp_GOjMg==/base.apk publicDir=/data/app/~~6zRCfow_uyI2bf7ZkCVMgw==/com.example.uida-oW4_28099sb08utp_GOjMg==/base.apk data=/data/user/0/com.example.uida
        |    packageList={com.example.uida}
        |    compat={420dpi}
        |  *APP* UID 10212 ProcessRecord{47aa6b1 15052:com.example.uidb/u0a212}
        |    user #0 uid=10212 gids={50212, 20212, 9997}
        |    packageList={com.example.uidb}
        """
          .trimMargin()
      )

    assertThat(result)
      .containsExactly(
        ProcessPackages(pid = "15156", packageNames = setOf("com.example.uida")),
        ProcessPackages(pid = "15052", packageNames = setOf("com.example.uidb")),
      )
      .inOrder()
  }

  @Test
  fun testParseProcessPackages_api29Shape() {
    // API 29 block shape per AOSP android10-release: AMS prints the `*APP* UID <uid> <ProcessRecord.toString()>` header,
    // ProcessRecord.dump prints `packageList={...}` directly.
    val result =
      parseProcessPackages(
        """
        |  *APP* UID 10123 ProcessRecord{af6f277 3487:com.example.app/u0a123}
        |    user #0 uid=10123 gids={50123, 20123, 9997}
        |    requiredAbi=arm64-v8a instructionSet=null
        |    class=com.example.app.App
        |    dir=/data/app/com.example.app-1/base.apk publicDir=/data/app/com.example.app-1/base.apk data=/data/user/0/com.example.app
        |    packageList={com.example.app}
        |    compat={2.625 420dpi}
        """
          .trimMargin()
      )

    assertThat(result).containsExactly(ProcessPackages(pid = "3487", packageNames = setOf("com.example.app")))
  }

  @Test
  fun testParseProcessPackages_severalPackagesInOneProcess() {
    val result =
      parseProcessPackages(
        """
        |  *APP* UID 10212 ProcessRecord{47aa6b1 15052:com.example.shared/u0a212}
        |    packageList={com.example.uida, com.example.uidb}
        """
          .trimMargin()
      )

    assertThat(result).containsExactly(ProcessPackages(pid = "15052", packageNames = setOf("com.example.uida", "com.example.uidb")))
  }

  @Test
  fun testParseProcessPackages_fullyQualifiedProcessNameAndPersistentProcess() {
    val result =
      parseProcessPackages(
        """
        |  *PERS* UID 1001 ProcessRecord{123abc 800:com.android.phone/1001}
        |    packageList={com.android.phone}
        |  *APP* UID 10123 ProcessRecord{456def 900:fully.qualified.name/u0a123}
        |    packageList={com.example.app}
        """
          .trimMargin()
      )

    assertThat(result)
      .containsExactly(
        ProcessPackages(pid = "800", packageNames = setOf("com.android.phone")),
        ProcessPackages(pid = "900", packageNames = setOf("com.example.app")),
      )
      .inOrder()
  }

  @Test
  fun testParseProcessPackages_packageDependenciesAreNotOwnership() {
    val result =
      parseProcessPackages(
        """
        |  *APP* UID 10123 ProcessRecord{456def 900:com.example.app/u0a123}
        |    packageDependencies={com.example.dependency}
        |    packageList={com.example.app}
        """
          .trimMargin()
      )

    assertThat(result).containsExactly(ProcessPackages(pid = "900", packageNames = setOf("com.example.app")))
  }

  @Test
  fun testParseProcessPackages_interveningBlockHeaderUnbindsPackageList() {
    // The packageList after the unparseable second header must not bind to the first block's pid.
    val result =
      parseProcessPackages(
        """
        |  *APP* UID 10123 ProcessRecord{456def 900:com.example.app/u0a123}
        |  *APP* UID 10124 ProcessRecord{malformed}
        |    packageList={com.example.other}
        """
          .trimMargin()
      )

    assertThat(result).isEmpty()
  }

  @Test
  fun testParseProcessPackages_packageListWithoutHeaderIsIgnored() {
    val result = parseProcessPackages("    packageList={com.example.app}\n")

    assertThat(result).isEmpty()
  }

  @Test
  fun testParseProcessPackages_emptyPackageListIsIgnored() {
    val result =
      parseProcessPackages(
        """
        |  *APP* UID 10123 ProcessRecord{456def 900:com.example.app/u0a123}
        |    packageList={}
        """
          .trimMargin()
      )

    assertThat(result).isEmpty()
  }

  @Test
  fun testParseProcessPackages_duplicatePidKeepsFirstAttribution() {
    val result =
      parseProcessPackages(
        """
        |  *APP* UID 10123 ProcessRecord{456def 900:com.example.app/u0a123}
        |    packageList={com.example.app}
        |  *APP* UID 10123 ProcessRecord{456def 900:com.example.app/u0a123}
        |    packageList={com.example.stale}
        """
          .trimMargin()
      )

    assertThat(result).containsExactly(ProcessPackages(pid = "900", packageNames = setOf("com.example.app")))
  }

  @Test
  fun testQueryProcessPackages_runsExactCommand() = runTest {
    fakeSession.deviceServices.configureShellCommand(
      selector,
      "dumpsys activity processes",
      "  *APP* UID 10123 ProcessRecord{456def 900:com.example.app/u0a123}\n    packageList={com.example.app}\n",
    )

    val result = fakeSession.deviceServices.getPackagesByPid(selector)

    assertThat(result).containsExactly(ProcessPackages(pid = "900", packageNames = setOf("com.example.app")))
  }

  @Test
  fun testQueryProcessPackages_nonZeroExitThrows() = runTest {
    fakeSession.deviceServices.configureShellCommand(selector, "dumpsys activity processes", "irrelevant", exitCode = 1)

    try {
      fakeSession.deviceServices.getPackagesByPid(selector)
      fail("Expected the non-zero dumpsys exit to propagate")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("dumpsys activity processes")
    }
  }
}
