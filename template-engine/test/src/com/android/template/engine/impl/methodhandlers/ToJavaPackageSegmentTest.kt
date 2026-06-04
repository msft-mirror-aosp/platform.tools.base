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

class ToJavaPackageSegmentTest {

  @Test
  fun testEvaluate() {
    val handler = ToJavaPackageSegment()
    assertThat(handler.name).isEqualTo("toJavaPackageSegment")
    assertThat(handler.argCount).isEqualTo(0)

    val dummyToken = StringInterpolationToken(StringInterpolationToken.TokenType.IDENTIFIER, "toAndroidPackageSegment", 0, 0)
    val methodCall = MethodCallNode(dummyToken, dummyToken, "toAndroidPackageSegment", emptyList())
    val result = handler.evaluate("My-Android Package 123!", methodCall, evaluateArgument = { "" })
    assertThat(result).isEqualTo("my_androidpackage123")
  }

  @Test
  fun testStringToJavaPackageSegment() {
    assertThat(ToJavaPackageSegment.stringToJavaPackageSegment("My-Package")).isEqualTo("my_package")
    assertThat(ToJavaPackageSegment.stringToJavaPackageSegment("My Application")).isEqualTo("myapplication")
    assertThat(ToJavaPackageSegment.stringToJavaPackageSegment("123abcDEF_")).isEqualTo("123abcdef_")
    assertThat(ToJavaPackageSegment.stringToJavaPackageSegment("Hello-World!@#")).isEqualTo("hello_world")
    assertThat(ToJavaPackageSegment.stringToJavaPackageSegment("1-2-3")).isEqualTo("1_2_3")
    assertThat(ToJavaPackageSegment.stringToJavaPackageSegment("space case")).isEqualTo("spacecase")
    assertThat(ToJavaPackageSegment.stringToJavaPackageSegment("mixed-CASE_123")).isEqualTo("mixed_case_123")
  }
}
