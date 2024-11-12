/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.lint.checks

import com.android.testutils.TestUtils.resolveWorkspacePath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastReceiverUtilsTest {
  @Test
  fun testBasics() {
    assertTrue(
      BroadcastReceiverUtils.isProtectedBroadcast("android.accounts.action.ACCOUNT_REMOVED")
    )
    assertFalse(BroadcastReceiverUtils.isProtectedBroadcast("not.a.permission"))
  }

  @Test
  @Suppress("LocalVariableName")
  fun testDbUpToDate() {
    // Set to true to generate an updated switch table directly in this repository (for
    // BroadcastReceiverUtils.kt)
    val UPDATE_IN_PLACE = false
    if (!UPDATE_IN_PLACE) {
      return
    }
    val generator = PermissionDataGenerator()
    val permissions = generator.getProtectedBroadcasts()
    if (permissions.isEmpty()) {
      return
    }

    val sb = StringBuilder()
    val prefix =
      "  fun isProtectedBroadcast(actionName: String): Boolean {\n" +
        "    return when (actionName) {\n"
    val suffix = "      else -> false\n" + "    }"
    sb.append(prefix)
    for (name in permissions.sorted()) {
      sb.append("      \"$name\",\n")
    }
    sb.setLength(sb.length - 2)
    sb.append(" -> true\n")

    sb.append(suffix)
    val replacement = sb.toString()

    // Replace existing
    val utilsRelativePath =
      "tools/base/lint/libs/lint-checks/src/main/java/com/android/tools/lint/checks/BroadcastReceiverUtils.kt"
    val utilsFile = resolveWorkspacePath(utilsRelativePath).toFile()

    val t = utilsFile.readText()
    val index = t.indexOf(prefix)
    val end = t.indexOf("}", index + 1)
    if (index == -1 || end == -1)
      error(
        "Couldn't find existing switch; has the code formatting changed? Compare to the `prefix` and `suffix` variables above!"
      )
    val replaced = t.substring(0, index) + replacement.toString() + t.substring(end + 1)
    utilsFile.writeText(replaced)
    println("Updated the switch table in $utilsFile")
  }
}
