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
package com.android.template.engine.impl.methodhandlers

import com.android.template.engine.impl.MethodCallNode
import com.android.template.engine.impl.StringInterpolationToken
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToJavaClassNameTest {

  @Test
  fun testEvaluate() {
    val handler = ToJavaClassName()
    assertThat(handler.name).isEqualTo("toJavaClassName")
    assertThat(handler.argCount).isEqualTo(0)

    val dummyToken = StringInterpolationToken(StringInterpolationToken.TokenType.IDENTIFIER, "toJavaClassName", 0, 0)
    val methodCall = MethodCallNode(dummyToken, dummyToken, "toJavaClassName", emptyList())
    val result = handler.evaluate("My-Android Package 123!", methodCall, evaluateArgument = { "" })
    assertThat(result).isEqualTo("MyAndroidPackage123")
  }

  @Test
  fun testStringToJavaClassName() {
    assertThat(ToJavaClassName.stringToJavaClassName("My-Package")).isEqualTo("MyPackage")
    assertThat(ToJavaClassName.stringToJavaClassName("My Application")).isEqualTo("MyApplication")
    assertThat(ToJavaClassName.stringToJavaClassName("123abcDEF_")).isEqualTo("_123abcDEF")
    assertThat(ToJavaClassName.stringToJavaClassName("Hello-World! @#")).isEqualTo("HelloWorld")
    assertThat(ToJavaClassName.stringToJavaClassName("1-2-3")).isEqualTo("_123")
    assertThat(ToJavaClassName.stringToJavaClassName("space case")).isEqualTo("SpaceCase")
    assertThat(ToJavaClassName.stringToJavaClassName("mixed-CASE_123")).isEqualTo("MixedCASE123")
    assertThat(ToJavaClassName.stringToJavaClassName("Project ADT")).isEqualTo("ProjectADT")
  }
}
