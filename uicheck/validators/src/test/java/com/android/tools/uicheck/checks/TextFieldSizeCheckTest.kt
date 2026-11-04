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

package com.android.tools.uicheck.checks

import com.android.tools.visuallint.VisualLintErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFieldSizeCheckTest {

  /** Verifies that a Compose OutlinedTextField wider than 488dp is reported once even when wrapping an inset CoreTextField. */
  @Test
  fun reportsTextFieldWiderThan488dpOnceWithoutInnerCoreTextFieldDuplicate() {
    // 900px at 240dpi is 600dp; inner CoreTextField is 868px (579dp) due to decoration padding.
    val field =
      node(
        "OutlinedTextField",
        bounds(20, 100, 920, 184),
        children =
          listOf(
            node(
              "BasicTextField",
              bounds(36, 116, 904, 168),
              children = listOf(node("CoreTextField", bounds(36, 116, 904, 168), semantics = mapOf("actions" to "SetText"))),
            )
          ),
      )
    val issue = TextFieldSizeCheck.check(context(field, densityDpi = 240)).single()

    assertEquals(VisualLintErrorType.TEXT_FIELD_SIZE, issue.errorType)
    assertEquals("The text field OutlinedTextField is too wide", issue.message)
  }

  /** Verifies that text fields at or below the 488dp maximum width are not reported. */
  @Test
  fun ignoresTextFieldsUpTo488dp() {
    assertTrue(TextFieldSizeCheck.check(context(node("TextField", bounds(0, 0, 732, 84)), densityDpi = 240)).isEmpty())
  }

  /** Verifies that `SecureTextField` and `BasicSecureTextField` are checked by name, even without a `SetText` action. */
  @Test
  fun checksSecureTextFieldsWithoutSetTextAction() {
    for (name in listOf("SecureTextField", "BasicSecureTextField")) {
      assertEquals(name, 1, TextFieldSizeCheck.check(context(node(name, bounds(0, 0, 900, 84)), densityDpi = 240)).size)
    }
  }

  /** Verifies that custom editable nodes exposing the SetText accessibility action are checked. */
  @Test
  fun treatsNodesWithSetTextActionAsTextFields() {
    val field = node("CustomSearchInput", bounds(0, 0, 900, 84), semantics = mapOf("actions" to "OnClick, SetText"))

    assertEquals(1, TextFieldSizeCheck.check(context(field, densityDpi = 240)).size)
  }

  /** Verifies that XML EditText subclasses including TextInputEditText and MaterialAutoCompleteTextView are reported. */
  @Test
  fun checksXmlEditTextSubclasses() {
    val root =
      node(
        "android.widget.LinearLayout",
        bounds(0, 0, 1000, 400),
        children =
          listOf(
            node("com.google.android.material.textfield.TextInputEditText", bounds(0, 0, 900, 84), parameters = mapOf("id" to "email")),
            node("com.google.android.material.textfield.MaterialAutoCompleteTextView", bounds(0, 100, 900, 184)),
          ),
      )

    assertEquals(
      listOf(
        "The text field TextInputEditText (email) is too wide",
        "The text field MaterialAutoCompleteTextView is too wide",
      ),
      TextFieldSizeCheck.check(context(root, densityDpi = 240)).map { it.message },
    )
  }
}
