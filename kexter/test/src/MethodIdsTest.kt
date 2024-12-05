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

import kexter.DexMethod
import org.junit.Assert
import org.junit.Test

class MethodIdsTest {
  private data class MethodInfo(
    val id: UInt,
    val name: String,
    val owner: String,
    val signature: String,
  )

  @Test
  fun testAllIdsArePresent() {
    // Here are methods from 'MultipleClassesInFile.kt'
    val expectedMethodInfos =
      setOf(
        MethodInfo(id = 0u, name = "<init>", owner = "LA;", signature = "()V"),
        MethodInfo(id = 1u, name = "a", owner = "LA;", signature = "()I"),
        MethodInfo(id = 7u, name = "<init>", owner = "LBase;", signature = "()V"),
        MethodInfo(id = 8u, name = "base", owner = "LBase;", signature = "()I"),
        MethodInfo(id = 12u, name = "i1", owner = "LI1\$DefaultImpls;", signature = "(LI1;)I"),
        MethodInfo(id = 13u, name = "access\$i1\$jd", owner = "LI1;", signature = "(LI1;)I"),
        MethodInfo(id = 14u, name = "i1", owner = "LI1;", signature = "()I"),
        MethodInfo(id = 15u, name = "i1", owner = "LI2\$DefaultImpls;", signature = "(LI2;)I"),
        MethodInfo(id = 16u, name = "i2", owner = "LI2\$DefaultImpls;", signature = "(LI2;)I"),
        MethodInfo(id = 17u, name = "access\$i1\$jd", owner = "LI2;", signature = "(LI2;)I"),
        MethodInfo(id = 18u, name = "access\$i2\$jd", owner = "LI2;", signature = "(LI2;)I"),
        MethodInfo(id = 19u, name = "i1", owner = "LI2;", signature = "()I"),
        MethodInfo(id = 20u, name = "i2", owner = "LI2;", signature = "()I"),
        MethodInfo(id = 21u, name = "i1", owner = "LI3\$DefaultImpls;", signature = "(LI3;)I"),
        MethodInfo(id = 22u, name = "i2", owner = "LI3\$DefaultImpls;", signature = "(LI3;)I"),
        MethodInfo(id = 23u, name = "i3", owner = "LI3\$DefaultImpls;", signature = "(LI3;)I"),
        MethodInfo(id = 24u, name = "access\$i1\$jd", owner = "LI3;", signature = "(LI3;)I"),
        MethodInfo(id = 25u, name = "access\$i2\$jd", owner = "LI3;", signature = "(LI3;)I"),
        MethodInfo(id = 26u, name = "access\$i3\$jd", owner = "LI3;", signature = "(LI3;)I"),
        MethodInfo(id = 27u, name = "i1", owner = "LI3;", signature = "()I"),
        MethodInfo(id = 28u, name = "i2", owner = "LI3;", signature = "()I"),
        MethodInfo(id = 29u, name = "i3", owner = "LI3;", signature = "()I"),
        MethodInfo(id = 30u, name = "main", owner = "LMultipleClassesInFileKt;", signature = "()V"),
        MethodInfo(
          id = 31u,
          name = "main",
          owner = "LMultipleClassesInFileKt;",
          signature = "([Ljava/lang/String;)V",
        ),
        MethodInfo(id = 45u, name = "<init>", owner = "LX;", signature = "()V"),
        MethodInfo(id = 46u, name = "x", owner = "LX;", signature = "()I"),
      )

    for (info in expectedMethodInfos) {
      val id = info.id
      val retrievedInfo = DexArchive.dex.retrieveMethod(id)?.toMethodInfo(id)
      Assert.assertEquals(info, retrievedInfo)
    }
  }

  private fun DexMethod.toMethodInfo(id: UInt): MethodInfo {
    val signature = params.joinToString(separator = "", prefix = "(", postfix = ")") + returnType
    return MethodInfo(id, name, type, signature)
  }
}
