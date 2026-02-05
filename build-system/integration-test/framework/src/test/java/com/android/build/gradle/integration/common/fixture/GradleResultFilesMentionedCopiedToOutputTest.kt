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

package com.android.build.gradle.integration.common.fixture

import com.google.common.truth.Truth
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradleResultFilesMentionedCopiedToOutputTest {

  @get:Rule val temp = TemporaryFolder()

  private lateinit var outputDestination: File

  @Before
  fun setup() {
    outputDestination = temp.newFolder("output_folder")
  }

  @Test
  fun copyMentionedFilesTo_allFileTypes() {
    val testResultsFolder = temp.newFolder("test_results", "folder1")
    val htmlFile = File(testResultsFolder, "a.html").apply { writeText("content of a.html") }
    val zipFile = temp.newFile("c.zip").apply { writeText("content of c.zip") }
    val stdOut = getStdOutWithFilePaths(htmlFile, zipFile)
    val result = createGradleBuildResult(stdOut)

    result.copyMentionedFilesTo(outputDestination, CopyFilesMode.ALL)

    val (relativePaths, filesCopied) = getNormalizedRelativePathsOfCopiedFiles()
    val filesContent = filesCopied.map { it.readText() }

    Truth.assertThat(relativePaths).containsExactly("c.zip", "test_results/folder1/a.html")
    Truth.assertThat(filesContent).containsExactly("content of c.zip", "content of a.html")
  }

  @Test
  fun copyMentionedFilesTo_onlyHtmlByDefault() {
    val testResultsFolder = temp.newFolder("test_results", "folder1")
    val htmlFile = File(testResultsFolder, "a.html").apply { writeText("content of a.html") }
    val zipFile = temp.newFile("c.zip").apply { writeText("content of c.zip") }
    val stdOut = getStdOutWithFilePaths(htmlFile, zipFile)
    val result = createGradleBuildResult(stdOut)

    result.copyMentionedFilesTo(outputDestination)

    val (relativePaths, filesCopied) = getNormalizedRelativePathsOfCopiedFiles()
    val filesContent = filesCopied.map { it.readText() }

    Truth.assertThat(relativePaths).containsExactly("test_results/folder1/a.html")
    Truth.assertThat(filesContent).containsExactly("content of a.html")
  }

  @Test
  fun copyMentionedFilesTo_nonExistentFileIsIgnored() {
    val existingFile = temp.newFile("existing.txt").apply { writeText("File content") }
    val nonExistentFile = File(temp.root, "non_existent.txt")
    val stdOut = getStdOutWithMultipleFilePaths(listOf(existingFile, nonExistentFile))
    val result = createGradleBuildResult(stdOut)

    result.copyMentionedFilesTo(outputDestination)

    val (relativePaths, filesCopied) = getNormalizedRelativePathsOfCopiedFiles()
    Truth.assertThat(filesCopied).hasSize(1)
    val relativePath = relativePaths.first()
    val content = filesCopied.first().readText()
    Truth.assertThat(relativePath).isEqualTo("existing.txt")
    Truth.assertThat(content).isEqualTo("File content")
  }

  @Test
  fun copyMentionedFilesTo_copiesLocallyReferencedWebFiles() {
    val testResultsFolder = temp.newFolder("test_results", "folder1")
    val cssFolder = File(testResultsFolder, "css").apply { mkdir() }
    val jsFolder = File(testResultsFolder, "js").apply { mkdir() }
    val mainHtmlContent =
      """
      <!DOCTYPE html>
      <html>
      <head>
          <title>Test Report</title>
          <link rel="stylesheet" type="text/css" href="css/style.css">
          <link rel="stylesheet" href="//fonts.googleapis.com/css?family=ab">
          <link rel="stylesheet" href="http://fonts.googleapis.com/css?family=cd">
          <link rel="stylesheet" href="https://fonts.googleapis.com/css?family=ef">
      </head>
      <body>
          <h1>Main Report</h1>
          <p>This is the main test report. See <a href="details.html">details</a>.</p>
          <script src="js/script.js"></script>
      </body>
      </html>
      """
        .trimIndent()
    val htmlFile = File(testResultsFolder, "a.html").apply { writeText(mainHtmlContent) }
    File(testResultsFolder, "details.html").apply { writeText("content of details.html") }
    File(cssFolder, "style.css").apply { writeText("body { color: blue; }") }
    File(jsFolder, "script.js").apply { writeText("console.log('hello');") }
    val zipFile = temp.newFile("c.zip").apply { writeText("content of c.zip") }
    val stdOut = getStdOutWithFilePaths(htmlFile, zipFile)
    val result = createGradleBuildResult(stdOut)

    result.copyMentionedFilesTo(outputDestination, CopyFilesMode.ALL)

    val (relativePaths, _) = getNormalizedRelativePathsOfCopiedFiles()

    Truth.assertThat(relativePaths.toSet())
      .containsExactly(
        "c.zip",
        "test_results/folder1/a.html",
        "test_results/folder1/details.html",
        "test_results/folder1/css/style.css",
        "test_results/folder1/js/script.js",
      )
  }

  private fun getNormalizedRelativePathsOfCopiedFiles(): Pair<List<String>, List<File>> {
    val filesCopied = outputDestination.walk().filter { it.isFile }.toList()
    val relativePaths = filesCopied.map { it.relativeTo(outputDestination).path.replace(File.separatorChar, '/') }
    return Pair(relativePaths, filesCopied)
  }

  private fun createGradleBuildResult(stdOut: String): GradleBuildResult {
    val stdoutFile = temp.newFile().apply { writeText(stdOut) }
    val stderrFile = temp.newFile()
    return GradleBuildResult(stdoutFile, stderrFile, taskEvents = emptyList(), problemEvents = emptyList(), exception = null)
  }

  private fun getStdOutWithFilePaths(htmlFile: File, zipFile: File): String =
    """
            org.gradle.tooling.BuildException: Could not execute build using connection to Gradle distribution 'file:${zipFile.absolutePath}'.
                    at org.gradle.tooling.internal.consumer.ConnectionExceptionTransformer.transform(ConnectionExceptionTransformer.java:69)
                    at org.gradle.tooling.internal.consumer.ResultHandlerAdapter.onFailure(ResultHandlerAdapter.java:42)
                    at org.gradle.tooling.internal.consumer.async.DefaultAsyncConsumerActionExecutor$1$1.run(DefaultAsyncConsumerActionExecutor.java:68)
                    at org.gradle.internal.concurrent.ExecutorPolicy${'$'}CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
                    at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:47)
                    at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(Unknown Source)
                    at java.base/java.util.concurrent.ThreadPoolExecutor${'$'}Worker.run(Unknown Source)
                    at java.base/java.lang.Thread.run(Unknown Source)
            Caused by: org.gradle.api.GradleException: There were failing tests. See the report at: file://${htmlFile.absolutePath}
                    at com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask.run(DeviceProviderInstrumentTestTask.java:439)
                    at com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask.doTaskAction(DeviceProviderInstrumentTestTask.java:303)
                    at com.android.build.gradle.internal.tasks.NonIncrementalTask${'$'}taskAction$${'$'}inlined${'$'}recordTaskAction$1.invoke(BaseTask.kt:79)
                    at com.android.build.gradle.internal.tasks.Blocks.recordSpan(Blocks.java:51)
                    at com.android.build.gradle.internal.tasks.NonIncrementalTask.taskAction(NonIncrementalTask.kt:78)
                    at org.gradle.internal.reflect.JavaMethod.invoke(JavaMethod.java:125)
        """
      .trimIndent()

  private fun getStdOutWithMultipleFilePaths(files: List<File>): String {
    return files.joinToString(separator = "\n") { "Some line with a file path file://${it.absolutePath}" }
  }
}
