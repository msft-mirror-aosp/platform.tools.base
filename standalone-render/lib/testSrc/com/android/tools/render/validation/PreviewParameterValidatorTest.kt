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

package com.android.tools.render.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewParameterValidatorTest {

  @Test
  fun testValidPreviewParameterPassesValidation() {
    val issues = PreviewParameterValidator.validate(mapOf("provider" to "com.example.MyProvider", "limit" to "5"))
    assertTrue("Valid @PreviewParameter should produce no issues", issues.isEmpty())
  }

  @Test
  fun testUnexpectedAttributeProducesError() {
    val issues = PreviewParameterValidator.validate(mapOf("provider" to "com.example.MyProvider", "unknownAttr" to "value"))
    assertEquals(1, issues.size)
    assertEquals(ValidationSeverity.ERROR, issues[0].severity)
    assertEquals("unknownAttr", issues[0].target)
  }

  @Test
  fun testInvalidLimitProducesError() {
    val invalidLimits = listOf("0", "-1", "abc")
    for (invalidLimit in invalidLimits) {
      val issues = PreviewParameterValidator.validate(mapOf("provider" to "com.example.MyProvider", "limit" to invalidLimit))
      assertEquals("Expected error for limit: $invalidLimit", 1, issues.size)
      assertEquals("limit", issues[0].target)
      assertEquals(ValidationSeverity.ERROR, issues[0].severity)
    }
  }

  @Test
  fun testBlankProviderProducesError() {
    val issues = PreviewParameterValidator.validate(mapOf("provider" to "   "))
    assertEquals(1, issues.size)
    assertEquals("provider", issues[0].target)
    assertEquals(ValidationSeverity.ERROR, issues[0].severity)
  }

  @Test
  fun testMissingProviderProducesError() {
    val issues = PreviewParameterValidator.validate(mapOf("limit" to "5"))
    assertEquals(1, issues.size)
    assertEquals("provider", issues[0].target)
    assertEquals(ValidationSeverity.ERROR, issues[0].severity)
  }

  @Test
  fun testEmptyMapProducesMissingProviderError() {
    val issues = PreviewParameterValidator.validate(emptyMap())
    assertEquals(1, issues.size)
    assertEquals("provider", issues[0].target)
    assertEquals(ValidationSeverity.ERROR, issues[0].severity)
  }
}
