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
import org.junit.Test

class UidResolverTest {

  private val fakeSession = FakeAdbSession()
  private val selector = DeviceSelector.fromSerialNumber("serial")

  @Test
  fun testParsePackageUids_api30Output() {
    val result = parsePackageUids("package:com.example.qaviews uid:10123\n")

    assertThat(result).containsExactly(PackageUid("com.example.qaviews", 10123))
  }

  @Test
  fun testParsePackageUids_multiplePackages() {
    val result = parsePackageUids("package:com.first uid:10123\npackage:com.second uid:10456\n")

    assertThat(result).containsExactly(PackageUid("com.first", 10123), PackageUid("com.second", 10456)).inOrder()
  }

  @Test
  fun testParsePackageUids_ignoresMalformedAndDiagnosticLines() {
    val result =
      parsePackageUids(
        """
        package:com.valid uid:10123
        warning: package manager diagnostic
        package:com.missing.uid
        package:com.bad uid:not-a-number
        package:com.extra uid:10124 trailing

        """
          .trimIndent()
      )

    assertThat(result).containsExactly(PackageUid("com.valid", 10123))
  }

  @Test
  fun testParsePidsForUid_filtersOnUidAndSkipsHeader() {
    val result =
      parsePidsForUid(
        """
        PID   UID NAME
          1     0 init
        321 10123 com.example.uiprocess
        654 10456 com.other
        987 10123 com.example.qaviews:worker
        """
          .trimIndent(),
        10123,
      )

    assertThat(result).containsExactly("321", "987").inOrder()
  }

  @Test
  fun testParsePidsForUid_skipsMalformedRowsAndDeduplicates() {
    val result =
      parsePidsForUid(
        """
        PID   UID NAME
        orphan-line
        abc 10123 com.bad.pid
        321 not-a-uid com.bad.uid
        321 10123 com.example
        321 10123 com.example
        """
          .trimIndent(),
        10123,
      )

    assertThat(result).containsExactly("321")
  }

  @Test
  fun testQueryPackageUid_requiresExactPackageMatch() = runTest {
    fakeSession.deviceServices.configureShellCommand(
      selector,
      "pm list packages -U --user 0 com.example",
      "package:com.example.other uid:10123\n",
    )

    assertThat(UidResolver(fakeSession, selector).packageUid("com.example")).isNull()
  }

  @Test
  fun testQueryProcessUid_parsesHeaderlessOutput() = runTest {
    fakeSession.deviceServices.configureShellCommand(selector, "ps -o UID= -p 1234", "  10123 \n")

    assertThat(UidResolver(fakeSession, selector).processUid("1234")).isEqualTo(10123)
  }

  @Test
  fun testQueryProcessUid_returnsNullForDisappearedProcess() = runTest {
    fakeSession.deviceServices.configureShellCommand(selector, "ps -o UID= -p 1234", "", exitCode = 1)

    assertThat(UidResolver(fakeSession, selector).processUid("1234")).isNull()
  }
}
