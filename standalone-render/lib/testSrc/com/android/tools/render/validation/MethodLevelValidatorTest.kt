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

import com.android.tools.render.discovery.MethodDiscoveryContext
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
        discoveryContext =
          MethodDiscoveryContext(isComposable = true, previewConfigurations = mutableListOf(mapOf("name" to "Light Mode"))),
      )
    assertTrue("Composable preview method should be valid", result.isValid)
    assertFalse("Valid preview method should not have errors", result.hasErrors)
  }

  @Test
  fun testNonComposablePreviewMethodProducesError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result =
      validator.validate(
        methodFQN = methodFQN,
        discoveryContext =
          MethodDiscoveryContext(isComposable = false, previewConfigurations = mutableListOf(mapOf("name" to "Light Mode"))),
      )
    assertTrue("Non-composable preview method should have errors", result.hasErrors)
    assertEquals(1, result.errors.size)
    val error = result.errors.first()
    assertEquals("Method '$methodFQN' annotated with @Preview must be annotated with @Composable", error.message)
    assertEquals(ValidationSeverity.ERROR, error.severity)
    assertEquals(ValidationCategory.METHOD, error.category)
    assertEquals(methodFQN, error.target)
  }

  @Test
  fun testPreviewWrapperWithPreviewAndComposableIsValid() {
    val result =
      validator.validate(
        methodFQN = "com.example.MyScreenKt.MyPreview",
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = true,
            previewConfigurations = mutableListOf(mapOf("name" to "Preview")),
            previewWrapperFqns = mutableListOf("com.example.MyWrapper"),
          ),
      )
    assertTrue("Preview with wrapper and @Composable should be valid", result.isValid)
    assertFalse("Valid wrapped preview should not have errors", result.hasErrors)
  }

  @Test
  fun testMultiplePreviewWrappersProducesError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result =
      validator.validate(
        methodFQN = methodFQN,
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = true,
            previewConfigurations = mutableListOf(mapOf("name" to "Preview")),
            previewWrapperFqns = mutableListOf("com.example.Wrapper1", "com.example.Wrapper2"),
          ),
      )
    assertTrue("Multiple preview wrappers should produce errors", result.hasErrors)
    assertEquals(1, result.errors.size)
    assertEquals(
      "Multiple @PreviewWrapper annotations found for method '$methodFQN': com.example.Wrapper1, com.example.Wrapper2",
      result.errors.first().message,
    )
  }

  @Test
  fun testPreviewWrapperWithoutPreviewProducesError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result =
      validator.validate(
        methodFQN = methodFQN,
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = true,
            previewConfigurations = mutableListOf(),
            previewWrapperFqns = mutableListOf("com.example.MyWrapper"),
          ),
      )
    assertTrue("PreviewWrapper without @Preview should produce error", result.hasErrors)
    assertEquals(1, result.errors.size)
    assertEquals(
      "Method '$methodFQN' annotated with @PreviewWrapper must also be annotated with @Preview",
      result.errors.first().message,
    )
  }

  @Test
  fun testPreviewWrapperWithoutComposableProducesError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result =
      validator.validate(
        methodFQN = methodFQN,
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = false,
            previewConfigurations = mutableListOf(mapOf("name" to "Preview")),
            previewWrapperFqns = mutableListOf("com.example.MyWrapper"),
          ),
      )
    assertTrue("PreviewWrapper with @Preview but without @Composable should produce error", result.hasErrors)
    assertEquals(1, result.errors.size)
    assertEquals(
      "Method '$methodFQN' annotated with @Preview must be annotated with @Composable",
      result.errors.first().message,
    )
  }

  @Test
  fun testPreviewWrapperWithoutPreviewAndWithoutComposableProducesCombinedError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result =
      validator.validate(
        methodFQN = methodFQN,
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = false,
            previewConfigurations = mutableListOf(),
            previewWrapperFqns = mutableListOf("com.example.MyWrapper"),
          ),
      )
    assertTrue("PreviewWrapper without @Preview and @Composable should produce error", result.hasErrors)
    assertEquals(1, result.errors.size)
    assertEquals(
      "Method '$methodFQN' annotated with @PreviewWrapper must be annotated with @Preview and @Composable",
      result.errors.first().message,
    )
  }

  @Test
  fun testNonPreviewMethodDoesNotProduceErrorsEvenIfNotComposable() {
    val result =
      validator.validate(
        methodFQN = "com.example.MyScreenKt.helperFunction",
        discoveryContext = MethodDiscoveryContext(isComposable = false),
      )
    assertTrue("Method without preview annotations should not produce errors", result.isValid)
  }

  @Test
  fun testValidateMissingClassNotFound() {
    val result =
      validator.validateMissingMethodOrPreview(
        methodFQN = "com.example.MissingClass.myMethod",
        className = "com.example.MissingClass",
        methodName = "myMethod",
        classFound = false,
        methodFound = false,
      )
    assertTrue(result.hasErrors)
    assertEquals("Class 'com.example.MissingClass' could not be found on the classpath", result.errors.first().message)
  }

  @Test
  fun testValidateMissingMethodNotFound() {
    val result =
      validator.validateMissingMethodOrPreview(
        methodFQN = "com.example.MyClass.missingMethod",
        className = "com.example.MyClass",
        methodName = "missingMethod",
        classFound = true,
        methodFound = false,
      )
    assertTrue(result.hasErrors)
    assertEquals("Method 'missingMethod' not found in class 'com.example.MyClass'", result.errors.first().message)
  }

  @Test
  fun testComposablePreviewWithoutPreviewParameterPassesValidation() {
    val result =
      validator.validate(
        methodFQN = "com.example.MyScreenKt.MyPreview",
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = true,
            previewConfigurations = mutableListOf(mapOf("name" to "Preview")),
            previewParameterConfigs = mutableListOf(),
          ),
      )
    assertTrue("Method without @PreviewParameter should be valid", result.isValid)
    assertFalse(result.hasErrors)
  }

  @Test
  fun testSinglePreviewParameterPassesValidation() {
    val result =
      validator.validate(
        methodFQN = "com.example.MyScreenKt.MyPreview",
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = true,
            previewConfigurations = mutableListOf(mapOf("name" to "Preview")),
            previewParameterConfigs = mutableListOf(mapOf("provider" to "com.example.MyStringProvider", "limit" to "5")),
          ),
      )
    assertTrue("Single valid @PreviewParameter should be valid", result.isValid)
    assertFalse(result.hasErrors)
  }

  @Test
  fun testMultiplePreviewParametersProducesError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result =
      validator.validate(
        methodFQN = methodFQN,
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = true,
            previewConfigurations = mutableListOf(mapOf("name" to "Preview")),
            previewParameterConfigs =
              mutableListOf(
                mapOf("provider" to "com.example.Provider1"),
                mapOf("provider" to "com.example.Provider2"),
              ),
          ),
      )
    assertTrue("Multiple @PreviewParameter annotations should produce error", result.hasErrors)
    assertEquals(1, result.errors.size)
    assertEquals("Composable preview functions can have at most one @PreviewParameter", result.errors.first().message)
  }

  @Test
  fun testInvalidPreviewParameterLimitProducesError() {
    val methodFQN = "com.example.MyScreenKt.MyPreview"
    val result =
      validator.validate(
        methodFQN = methodFQN,
        discoveryContext =
          MethodDiscoveryContext(
            isComposable = true,
            previewConfigurations = mutableListOf(mapOf("name" to "Preview")),
            previewParameterConfigs = mutableListOf(mapOf("provider" to "com.example.MyStringProvider", "limit" to "-5")),
          ),
      )
    assertTrue("Invalid limit in @PreviewParameter should produce error", result.hasErrors)
    assertEquals(1, result.errors.size)
    assertEquals("Parameter 'limit' on @PreviewParameter must be a positive integer, got: '-5'", result.errors.first().message)
  }

  @Test
  fun testValidateMissingNoPreviewsFound() {
    val result =
      validator.validateMissingMethodOrPreview(
        methodFQN = "com.example.MyClass.nonPreviewMethod",
        className = "com.example.MyClass",
        methodName = "nonPreviewMethod",
        classFound = true,
        methodFound = true,
      )
    assertTrue(result.hasErrors)
    assertEquals("No @Preview annotations found on method 'com.example.MyClass.nonPreviewMethod'", result.errors.first().message)
  }
}
