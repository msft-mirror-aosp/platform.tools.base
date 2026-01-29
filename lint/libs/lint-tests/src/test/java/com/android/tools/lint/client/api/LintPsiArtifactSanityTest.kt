/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.lint.client.api

import com.android.testutils.TestUtils
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.util.jar.JarInputStream
import org.junit.Assume
import org.junit.Test

class LintPsiArtifactSanityTest {
  @Test
  fun testNoDuplicateClassDeclarations() {
    // TODO: how to read prebuilt lint-psi artifacts in CI with bazel?
    //  For now, we're manually running this test in IDE.
    Assume.assumeFalse(TestUtils.runningFromBazel())

    val kt = TestUtils.resolveWorkspacePath(KOTLIN_COMPILER_JAR_PATH).toFile()
    val ij = TestUtils.resolveWorkspacePath(INTELLIJ_CORE_JAR_PATH).toFile()
    val ktFiles = fileNamesInJar(kt)
    val ijFiles = fileNamesInJar(ij)
    val both =
        ktFiles.intersect(ijFiles).filterNot { fileName ->
          fileName.contains("org/jetbrains/annotations") ||
              // https://github.com/JetBrains/kotlin/commit/4b2c54d5e6bc1aae5a1d7cc5df56006c11f26d7e
              fileName.contains("com/intellij/openapi/util/Object") ||
              fileName.contains("kotlinx/collections") ||
              fileName.contains("kotlinx-collections")
        }
    assertThat(both).isEmpty()
  }

  companion object {
    private const val LINT_PSI = "prebuilts/tools/common/lint-psi"
    private const val KOTLIN_COMPILER_JAR_PATH = "$LINT_PSI/kotlin-compiler/kotlin-compiler.jar"
    private const val INTELLIJ_CORE_JAR_PATH = "$LINT_PSI/intellij-core/intellij-core.jar"

    private fun fileNamesInJar(jar: File): Set<String> = buildSet {
      JarInputStream(ByteArrayInputStream(jar.readBytes())).use { jis ->
        var entry = jis.nextJarEntry
        while (entry != null) {
          if (!entry.isDirectory) {
            add(entry.name)
          }
          entry = jis.nextJarEntry
        }
      }
    }
  }
}
