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

import kexter.core.DexImpl
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MethodIdsTest(archive: DexArchiveBase) : MultipleDexVersionTestBase(archive) {
  @Test
  fun testAllIdsArePresent() {
    val expectedMethodInfos =
      setOf(
        MethodInfo(name = "<init>", owner = "LA;", signature = "()V"),
        MethodInfo(name = "a", owner = "LA;", signature = "()I"),
        MethodInfo(name = "<init>", owner = "LBase;", signature = "()V"),
        MethodInfo(name = "base", owner = "LBase;", signature = "()I"),
        MethodInfo(name = "i1", owner = "LI1\$DefaultImpls;", signature = "(LI1;)I"),
        MethodInfo(name = "access\$i1\$jd", owner = "LI1;", signature = "(LI1;)I"),
        MethodInfo(name = "i1", owner = "LI1;", signature = "()I"),
        MethodInfo(name = "i1", owner = "LI2\$DefaultImpls;", signature = "(LI2;)I"),
        MethodInfo(name = "i2", owner = "LI2\$DefaultImpls;", signature = "(LI2;)I"),
        MethodInfo(name = "access\$i1\$jd", owner = "LI2;", signature = "(LI2;)I"),
        MethodInfo(name = "access\$i2\$jd", owner = "LI2;", signature = "(LI2;)I"),
        MethodInfo(name = "i1", owner = "LI2;", signature = "()I"),
        MethodInfo(name = "i2", owner = "LI2;", signature = "()I"),
        MethodInfo(name = "i1", owner = "LI3\$DefaultImpls;", signature = "(LI3;)I"),
        MethodInfo(name = "i2", owner = "LI3\$DefaultImpls;", signature = "(LI3;)I"),
        MethodInfo(name = "i3", owner = "LI3\$DefaultImpls;", signature = "(LI3;)I"),
        MethodInfo(name = "access\$i1\$jd", owner = "LI3;", signature = "(LI3;)I"),
        MethodInfo(name = "access\$i2\$jd", owner = "LI3;", signature = "(LI3;)I"),
        MethodInfo(name = "access\$i3\$jd", owner = "LI3;", signature = "(LI3;)I"),
        MethodInfo(name = "i1", owner = "LI3;", signature = "()I"),
        MethodInfo(name = "i2", owner = "LI3;", signature = "()I"),
        MethodInfo(name = "i3", owner = "LI3;", signature = "()I"),
        MethodInfo(name = "main", owner = "LMethodIdsKt;", signature = "()V"),
        MethodInfo(name = "main", owner = "LMethodIdsKt;", signature = "([Ljava/lang/String;)V"),
        MethodInfo(name = "<init>", owner = "LX;", signature = "()V"),
        MethodInfo(name = "x", owner = "LX;", signature = "()I"),
      )

    val retrievedInfos = mutableSetOf<MethodInfo>()
    for (dex in archive.container.dexFiles) {
      val dex = dex as DexImpl
      for (id in 0u..dex.methodIds.numElements()) {
        val methodInfo = dex.retrieveMethod(id)?.toMethodInfo() ?: continue
        retrievedInfos.add(methodInfo)
      }
    }
    for (info in expectedMethodInfos) {
      Assert.assertTrue(info in retrievedInfos)
    }
  }
}
