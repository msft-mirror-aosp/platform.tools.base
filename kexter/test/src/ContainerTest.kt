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

class ContainerTest {
  companion object {
    const val NUMBER_OF_METHODS_IN_CLASS = 65500
  }

  @Test
  fun testAllIdsArePresent() {
    val container = ContainerDexArchive.container
    Assert.assertEquals(2, container.dexFiles.size)

    val retrievedNames = mutableSetOf<String>()
    for (dex in container.dexFiles) {
      val dex = dex as DexImpl
      for (id in 0u..dex.methodIds.numElements()) {
        val name = dex.retrieveMethod(id)?.name ?: continue
        retrievedNames.add(name)
      }
    }

    val expectedNames = buildExpectedSet()
    Assert.assertEquals(expectedNames, retrievedNames)
  }

  fun buildExpectedSet(): Set<String> {
    return buildSet {
      add("<init>")
      for (i in 1..NUMBER_OF_METHODS_IN_CLASS) {
        add("bar$i")
        add("foo$i")
      }
    }
  }
}
