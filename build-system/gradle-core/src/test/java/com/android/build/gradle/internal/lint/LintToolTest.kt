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

package com.android.build.gradle.internal.lint

import com.android.build.gradle.internal.fixtures.FakeObjectFactory
import com.android.testutils.truth.PathSubject.assertThat
import java.nio.file.Files
import org.gradle.api.Action
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.process.JavaForkOptions
import org.gradle.workers.ProcessWorkerSpec
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LintToolTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun initializeLintCacheDir() {
    val cacheDir = temporaryFolder.newFolder("lint-cache").toPath()

    val exampleCacheFile = cacheDir.resolve("exampleCacheFile.txt").also { Files.write(it, "content1".toByteArray()) }

    val lintTool = FakeObjectFactory.factory.newInstance(LintTool::class.java)

    lintTool.versionKey.set("version 1")
    lintTool.lintCacheDirectory.fileValue(cacheDir.toFile())

    assertThat(exampleCacheFile).hasContents("content1")
    // Check that initialization with no version present clears the directory
    lintTool.initializeLintCacheDir()
    assertThat(exampleCacheFile).doesNotExist()

    Files.write(exampleCacheFile, "content2".toByteArray())

    // Check that initializing with the same version doesn't clear the directory
    assertThat(exampleCacheFile).hasContents("content2")
    lintTool.initializeLintCacheDir()
    assertThat(exampleCacheFile).hasContents("content2")

    // Check that initializing with a different version clears the directory
    lintTool.versionKey.set("version 2")
    lintTool.initializeLintCacheDir()
    assertThat(exampleCacheFile).doesNotExist()
  }

  @Test
  fun submitProcessIsolation_jdk24Plus_passesJvmArgs() {
    val lintTool = FakeObjectFactory.factory.newInstance(LintTool::class.java)
    val forkOptions = mock<JavaForkOptions>()
    configureLintTool(lintTool, 24, forkOptions)

    verify(forkOptions).jvmArgs("--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED")
  }

  @Test
  fun submitProcessIsolation_lessThanJdk24_doesNotPassJvmArgs() {
    val lintTool = FakeObjectFactory.factory.newInstance(LintTool::class.java)
    val forkOptions = mock<JavaForkOptions>()
    configureLintTool(lintTool, 23, forkOptions)

    verify(forkOptions, never()).jvmArgs(anyVararg())
  }

  private fun configureLintTool(lintTool: LintTool, javaVersion: Int, forkOptions: JavaForkOptions): JavaForkOptions {
    lintTool.runInProcess.set(false)
    lintTool.javaMajorVersion.set(JavaLanguageVersion.of(javaVersion))

    val workerSpec = mock<ProcessWorkerSpec>()
    whenever(workerSpec.classpath).thenReturn(FakeObjectFactory.factory.fileCollection())
    whenever(workerSpec.forkOptions).thenReturn(forkOptions)

    val workQueue = mock<WorkQueue>()
    val workerExecutor = mock<WorkerExecutor>()
    whenever(workerExecutor.processIsolation(any<Action<in ProcessWorkerSpec>>())).doAnswer { invocation ->
      val action = invocation.getArgument<Action<in ProcessWorkerSpec>>(0)
      action.execute(workerSpec)
      workQueue
    }

    lintTool.submit(
      workerExecutor = workerExecutor,
      mainClass = "com.android.tools.lint.Main",
      arguments = emptyList(),
      lintMode = LintMode.ANALYSIS,
      useK2Uast = false,
    )
    return forkOptions
  }
}
