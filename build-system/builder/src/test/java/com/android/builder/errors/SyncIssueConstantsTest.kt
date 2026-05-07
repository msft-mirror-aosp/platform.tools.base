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

package com.android.builder.errors

import com.android.builder.model.SyncIssue as SyncIssueV1
import com.android.builder.model.v2.ide.SyncIssue as SyncIssueV2
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class SyncIssueConstantsTest {

  @Test
  fun testConstantsMatch() {
    val v1Class = SyncIssueV1::class.java
    val v2Class = SyncIssueV2::class.java

    val v1Fields =
      v1Class.declaredFields.filter {
        Modifier.isPublic(it.modifiers) &&
          Modifier.isStatic(it.modifiers) &&
          Modifier.isFinal(it.modifiers) &&
          it.type == Int::class.javaPrimitiveType
      }

    assertTrue("Expected to find constants in v1 SyncIssue", v1Fields.isNotEmpty())

    for (field in v1Fields) {
      val name = field.name
      // Skip companion object instance if present
      if (name == "Companion") continue
      val v1Value = field.get(null) as Int

      val v2Field =
        try {
          v2Class.getDeclaredField(name)
        } catch (e: NoSuchFieldException) {
          fail("Constant $name present in v1 SyncIssue but missing in v2 SyncIssue")
          return
        }

      val v2Value = v2Field.get(null) as Int
      assertEquals("Value mismatch for constant $name", v1Value, v2Value)
    }
  }
}
