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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.instrumentation.ASM_API_VERSION
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor

class TestDiscoveryClassVisitorTest {

  @Test
  fun testNormalClass() {
    val visitor = TestDiscoveryClassVisitor()
    visitor.visit(52, 0, "com/example/MyClass", null, "java/lang/Object", null)
    assertFalse(visitor.isTestClass)
  }

  @Test
  fun testJunitTestCase() {
    val visitor = TestDiscoveryClassVisitor()
    visitor.visit(52, 0, "com/example/MyTest", null, "junit/framework/TestCase", null)
    assertTrue(visitor.isTestClass)
  }

  @Test
  fun testAndroidTestCase() {
    val visitor = TestDiscoveryClassVisitor()
    visitor.visit(52, 0, "com/example/MyAndroidTest", null, "android/test/AndroidTestCase", null)
    assertTrue(visitor.isTestClass)
  }

  @Test
  fun testRunWithAnnotation() {
    val visitor = TestDiscoveryClassVisitor()
    visitor.visit(52, 0, "com/example/MyTest", null, "java/lang/Object", null)
    visitor.visitAnnotation("Lorg/junit/runner/RunWith;", true)
    assertTrue(visitor.isTestClass)
  }

  @Test
  fun testTestAnnotationOnMethod() {
    val visitor = TestDiscoveryClassVisitor()
    visitor.visit(52, 0, "com/example/MyTest", null, "java/lang/Object", null)
    val methodVisitor = visitor.visitMethod(0, "myMethod", "()V", null, null)
    methodVisitor?.visitAnnotation("Lorg/junit/Test;", true)
    assertTrue(visitor.isTestClass)
  }

  @Test
  fun testVisitorChaining() {
    var methodVisited = false
    var annotationVisited = false

    val delegateMethodVisitor =
      object : MethodVisitor(ASM_API_VERSION) {
        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor? {
          if (descriptor == "Lorg/junit/Test;") {
            annotationVisited = true
          }
          return super.visitAnnotation(descriptor, visible)
        }
      }

    val delegateClassVisitor =
      object : ClassVisitor(ASM_API_VERSION) {
        override fun visitMethod(
          access: Int,
          name: String?,
          descriptor: String?,
          signature: String?,
          exceptions: Array<out String>?,
        ): MethodVisitor {
          methodVisited = true
          return delegateMethodVisitor
        }
      }

    val visitor = TestDiscoveryClassVisitor(delegateClassVisitor)
    val methodVisitor = visitor.visitMethod(0, "myMethod", "()V", null, null)
    methodVisitor?.visitAnnotation("Lorg/junit/Test;", true)

    assertTrue(visitor.isTestClass)
    assertTrue(methodVisited)
    assertTrue(annotationVisited)
  }
}
