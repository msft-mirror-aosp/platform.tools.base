/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.profgen

import com.android.testutils.TestUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val STRING = "Ljava/lang/String;"
private const val STRING_ARRAY = "[Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

class DexFileParserTest {

  @Test
  fun test() {
    val apk = Apk(TestUtils.resolveWorkspacePath(Path).toFile())
    assertThat(apk.dexes).hasSize(1)
    val dex = apk.dexes[0]

    // only one class `Hello` was defined in classes.jar
    assertThat(dex.classDefPool).hasLength(1)
    assertThat(dex.typePool[dex.classDefPool[0]]).isEqualTo("LHello;")

    val voidMethod = DexMethod("LHello;", "voidMethod", DexPrototype("V", emptyList()))
    val intMethod = DexMethod("LHello;", "method", DexPrototype("I", listOf(STRING)))
    val strLength = DexMethod(STRING, "length", DexPrototype("I", emptyList()))
    val objInit = DexMethod(OBJECT, "<init>", DexPrototype("V", emptyList()))
    val helloInit = DexMethod("LHello;", "<init>", DexPrototype("V", emptyList()))

    assertThat(dex.methodPool).isEqualTo(listOf(helloInit, intMethod, voidMethod, objInit, strLength))
  }

  @Test
  fun testParseParams() {
    assertThat(splitParameters("ILa/B;ZILa/C;J")).isEqualTo(listOf("I", "La/B;", "Z", "I", "La/C;", "J"))
    assertThat(splitParameters("")).isEqualTo(emptyList<String>())
    assertThat(splitParameters("IJ")).isEqualTo(listOf("I", "J"))
    assertThat(splitParameters("La/C;")).isEqualTo(listOf("La/C;"))
    assertThat(splitParameters("LB;La/C;")).isEqualTo(listOf("LB;", "La/C;"))

    assertThat(splitParameters("[I[[La/B;IJ[B")).isEqualTo(listOf("[I", "[[La/B;", "I", "J", "[B"))
  }

  @Test
  fun test040Format() {
    val apk = Apk(TestUtils.resolveWorkspacePath(Dex040Path).toFile())
    assertThat(apk.dexes).hasSize(1)
    val dex = apk.dexes[0]

    assertThat(dex.classDefPool).hasLength(1)
    assertThat(dex.classDefPool[0]).isEqualTo(0)
    assertThat(dex.typePool)
      .isEqualTo(
        listOf(
          "LMain;",
          "Ljava/io/PrintStream;",
          "Ljava/lang/Object;",
          "Ljava/lang/String;",
          "Ljava/lang/System;",
          "V",
          "[Ljava/lang/String;",
        )
      )

    val methodName =
      ("method_with_spaces_" +
        "20 " +
        "a0\u00a0" +
        "1680\u1680" +
        "2000\u2000" +
        "2001\u2001" +
        "2002\u2002" +
        "2003\u2003" +
        "2004\u2004" +
        "2005\u2005" +
        "2006\u2006" +
        "2007\u2007" +
        "2008\u2008" +
        "2009\u2009" +
        "200a\u200a" +
        "202f\u202f" +
        "205f\u205f" +
        "3000\u3000")

    val mainMain = DexMethod("LMain;", "main", DexPrototype("V", listOf(STRING_ARRAY)))
    val mainMethodWithSpaces = DexMethod("LMain;", methodName, DexPrototype("V", emptyList<String>()))
    val printStreamPrintln = DexMethod("Ljava/io/PrintStream;", "println", DexPrototype("V", listOf(STRING)))

    assertThat(dex.methodPool).isEqualTo(listOf(mainMain, mainMethodWithSpaces, printStreamPrintln))
  }

  companion object {
    private const val Path = "tools/base/profgen/profgen/testData/hello.apk"
    private const val Dex040Path = "tools/base/profgen/profgen/testData/dex040.apk"
  }
}
