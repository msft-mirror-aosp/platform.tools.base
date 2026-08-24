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

package com.android.tools.render.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodLevelValidatorTest {

  private val validator = MethodLevelValidator()

  @Test
  fun testComposablePreviewMethodIsValid() {
    val result =
      validator.validate(
        methodFQN = "com.example.MyScreenKt.MyPreview",
        isComposable = true,
        previewParamsList = listOf(mapOf("name" to "Light Mode")),
      )
    assertTrue("Composable preview method should be valid", result.isValid)
    assertFalse("Valid preview method should not have errors", result.hasErrors)
  }

  @Test
  fun testNonComposablePreviewMethodProducesError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result = validator.validate(methodFQN = methodFQN, isComposable = false, previewParamsList = listOf(mapOf("name" to "Light Mode")))
    assertTrue("Non-composable preview method should have errors", result.hasErrors)
    assertEquals(1, result.errors.size)
    val error = result.errors.first()
    assertEquals("Method '$methodFQN' annotated with @Preview must be annotated with @Composable", error.message)
    assertEquals(ValidationSeverity.ERROR, error.severity)
    assertEquals(ValidationCategory.METHOD, error.category)
    assertEquals(methodFQN, error.target)
  }

  @Test
  fun testNonPreviewMethodDoesNotProduceErrorsEvenIfNotComposable() {
    val result =
      validator.validate(methodFQN = "com.example.MyScreenKt.helperFunction", isComposable = false, previewParamsList = emptyList())
    assertTrue("Method without preview annotations should not produce errors", result.isValid)
  }
}
