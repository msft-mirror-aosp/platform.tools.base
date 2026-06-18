/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.tools.lint.checks.infrastructure

import org.junit.Test

class AnalysisApiFirServicesTest : AnalysisApiServicesTestBase() {
  @Test
  fun testDynamicType() {
    checkDynamicType()
  }

  @Test
  fun testInternalModifier() {
    checkInternalModifier()
  }

  @Test
  fun testSamType() {
    checkSamType()
  }

  @Test
  fun testExtensionLambda() {
    checkExtensionLambda()
  }

  @Test
  fun testAnnotationOnTypeParameter() {
    checkAnnotationOnTypeParameter()
  }

  @Test
  fun testParameterModifiers() {
    checkParameterModifiers()
  }

  @Test
  fun testCancellation() {
    checkCancellation()
  }

  @Test
  fun testAnalysisAPIOnPsiElement() {
    checkAnalysisAPIOnPsiElement(isK2 = true)
  }

  @Test
  fun testAnalysisAPIOnJava() {
    checkAnalysisAPIOnJava(isK2 = true)
  }
}
