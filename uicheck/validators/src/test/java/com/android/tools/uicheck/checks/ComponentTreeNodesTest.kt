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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentTreeNodesTest {

  /** Verifies button, text field, view node classification, and ID-suffixed display names. */
  @Test
  fun classifiesButtonsTextFieldsAndViewNodes() {
    assertTrue(node("Button", bounds(0, 0, 100, 48)).isButtonComponent())
    assertTrue(node("IconButton", bounds(0, 0, 48, 48)).isButtonComponent())
    assertTrue(node("android.widget.CheckBox", bounds(0, 0, 100, 48)).isButtonComponent())
    assertTrue(node("CustomButton", bounds(0, 0, 100, 48), semantics = mapOf("role" to "Button")).isButtonComponent())
    assertFalse(node("Row", bounds(0, 0, 100, 48), semantics = mapOf("role" to "Button")).isButtonComponent())
    assertFalse(node("Box", bounds(0, 0, 100, 48)).isButtonComponent())

    assertTrue(node("TextField", bounds(0, 0, 200, 56)).isTextFieldComponent())
    assertTrue(node("com.google.android.material.textfield.MaterialAutoCompleteTextView", bounds(0, 0, 200, 56)).isTextFieldComponent())
    assertTrue(node("CustomInput", bounds(0, 0, 200, 56), semantics = mapOf("actions" to "SetText")).isTextFieldComponent())

    assertTrue(node("android.widget.Button", bounds(0, 0, 100, 48)).isViewNode())
    assertFalse(node("Button", bounds(0, 0, 100, 48)).isViewNode())

    assertEquals("Text", node("Text", bounds(0, 0, 100, 20)).nameWithId())
    assertEquals("Text (label)", node("Text", bounds(0, 0, 100, 20), semantics = mapOf("id" to "label")).nameWithId())
    assertEquals("Button (submit_btn)", node("Button", bounds(0, 0, 100, 48), parameters = mapOf("id" to "submit_btn")).nameWithId())
  }
}
