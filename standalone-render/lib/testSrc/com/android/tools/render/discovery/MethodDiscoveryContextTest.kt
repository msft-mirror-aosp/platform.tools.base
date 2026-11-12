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

package com.android.tools.render.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The descriptors below are the ones the Compose compiler emits, e.g. for `fun Card(user: User, modifier: Modifier = Modifier)`. */
class MethodDiscoveryContextTest {

  private val composer = "Landroidx/compose/runtime/Composer;"
  private val elevenInts = "I".repeat(11)

  private fun types(descriptor: String) = MethodDiscoveryContext.fromDescriptor(descriptor).parameters.map { it.type }

  private fun mayHaveDefaults(descriptor: String) = MethodDiscoveryContext.fromDescriptor(descriptor).mayHaveDefaultParameters

  @Test
  fun testDeclaredParametersPrecedeTheComposer() {
    assertTrue(types("(${composer}I)V").isEmpty())
    assertEquals(listOf("com.example.User", "int", "java.lang.String[]"), types("(Lcom/example/User;I[Ljava/lang/String;${composer}I)V"))
    // A receiver and a composable lambda are parameters the renderer has to supply too.
    assertEquals(
      listOf("java.lang.String", "kotlin.jvm.functions.Function2"),
      types("(Ljava/lang/String;Lkotlin/jvm/functions/Function2;${composer}I)V"),
    )
  }

  @Test
  fun testDefaultMaskIsDetected() {
    assertFalse(mayHaveDefaults("(${composer}I)V"))
    assertFalse(mayHaveDefaults("(Lcom/example/User;${composer}I)V"))
    assertTrue(mayHaveDefaults("(Lcom/example/User;Landroidx/compose/ui/Modifier;${composer}II)V"))
  }

  @Test
  fun testSecondChangedMaskIsNotMistakenForDefaults() {
    // 11 parameters need two $changed masks (10 parameters per mask).
    assertEquals(11, types("($elevenInts${composer}II)V").size)
    assertFalse(mayHaveDefaults("($elevenInts${composer}II)V"))
    assertTrue(mayHaveDefaults("($elevenInts${composer}III)V"))
  }

  @Test
  fun testMethodNotCompiledByComposeHasNoDeclaredParameters() {
    // The renderer cannot invoke such a method, so nothing is validated: plain Kotlin functions have no Composer tail, and a Composer
    // followed by something else than masks, or by fewer masks than the $changed ones, is not the Compose compiler's shape either.
    val descriptors =
      listOf(
        "(Ljava/lang/String;I)V",
        "()V",
        "()$composer",
        "(${composer}Ljava/lang/String;)V",
        "(I${composer}IZ)V",
        "(${composer})V",
        "(Ljava/lang/String;${composer})V",
        "($elevenInts${composer}I)V",
      )
    for (descriptor in descriptors) {
      assertTrue(descriptor, types(descriptor).isEmpty())
      assertFalse(descriptor, mayHaveDefaults(descriptor))
    }
  }
}
