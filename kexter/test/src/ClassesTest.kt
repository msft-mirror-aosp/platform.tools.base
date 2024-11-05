import org.junit.Assert
import org.junit.Test

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
class ClassesTest {

  @Test
  fun testClassesRetrievalNonExistent() {
    Assert.assertFalse(DexUtils.allClasses().isEmpty())
    Assert.assertThrows(java.lang.IllegalStateException::class.java) {
      DexUtils.retrieveClass("Foo")
    }
  }

  @Test
  fun testClassRetrievalByName() {
    DexUtils.retrieveClass("LAddClass;")
  }

  @Test
  fun testClassRetrievalByNameWithPackage() {
    DexUtils.retrieveClass("Lcom/pkg/ClassInPackage;")
  }

  @Test
  fun testMethodsRetrieval() {
    val clazz = DexUtils.retrieveClass("Lcom/pkg/ClassInPackage;")
    Assert.assertFalse(clazz.methods.isEmpty())
    val expected = listOf("<init>(V)", "f(FF)", "i(II)", "l(JJ)", "d(DD)", "o(LLLL)").sorted()
    val actual = clazz.methods.keys.sorted()
    Assert.assertEquals(expected, actual)
  }

  @Test
  fun testMethodParamsComplexTypes() {
    val clazz = DexUtils.retrieveClass("Lcom/pkg/ClassInPackage;")
    Assert.assertFalse(clazz.methods.isEmpty())
    Assert.assertTrue(clazz.methods.keys.contains("o(LLLL)"))

    val m = clazz.methods["o(LLLL)"]!!
    val p = m.params
    Assert.assertEquals(m.returnType, "Ljava/lang/Object;")
    Assert.assertTrue(
      p.sorted()
        .containsAll(
          listOf("Ljava/lang/Object;", "Ljava/lang/String;", "Lcom/pkg/ClassInPackage;").sorted()
        )
    )
  }

  @Test
  fun testMethodParamsPrimitiveTypes() {
    val clazz = DexUtils.retrieveClass("Lcom/pkg/ClassInPackage;")
    Assert.assertFalse(clazz.methods.isEmpty())
    Assert.assertTrue(clazz.methods.keys.contains("i(II)"))

    val m = clazz.methods["i(II)"]!!
    val p = m.params
    Assert.assertTrue(p.contains("I"))
  }
}
