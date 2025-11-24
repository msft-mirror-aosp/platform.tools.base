/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.build.gradle.integration.common.truth.GradleTaskSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.truth.TaskStateList
import com.google.common.base.Preconditions
import com.google.common.base.Throwables
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.tasks.TaskExecutionException
import org.gradle.internal.serialize.ContextualPlaceholderException
import org.gradle.internal.serialize.PlaceholderException
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.events.ProgressEvent
import java.io.File
import java.util.Scanner

/**
 * An enum to represent which files mentioned in the build output should be copied.
 */
enum class CopyFilesMode(val extensions: Set<String>) {
    ALL(emptySet()),

    TEXT_ONLY(setOf("csv", "htm", "html", "json", "log", "md", "properties", "txt", "xml"));

    /** Returns true if the given [file] should be included in this mode. */
    fun includes(file: File): Boolean {
        return extensions.isEmpty() || extensions.any { file.extension.equals(it, ignoreCase = true) }
    }
}

/**
 * The result from running a build.
 * See [GradleTestProject.executor] and [GradleTaskExecutor].
 *
 * @property exception The exception from the build, null if the build succeeded.
 */
class GradleBuildResult(
    private val stdoutFile: File,
    private val stderrFile: File,
    private val taskEvents: List<ProgressEvent>,
    val problemEvents: List<ProgressEvent>,
    val exception: GradleConnectionException?,
) {
    @JvmOverloads
    fun assertTask(
        name: String,
        withInfo: String? = null
    ): GradleTaskSubject = taskStateList.assertTask(name, withInfo)

    fun assertFailureMessage(): StringSubject {
        return Truth.assertThat(failureMessage)
    }

    /**
     * Returns a new [Scanner] for the stderr messages. This instance MUST be closed when done.
     */
    @Deprecated("Use assertStdErr")
    val stderr
        get() = Scanner(stderrFile)

    @Suppress("unused") // Keep this property as it is useful for debugging
    val stderrAsText: String by lazy { stderr.asText() }

    /**
     * Returns a new [Scanner] for the stdout messages. This instance MUST be closed when done.
     */
    @Deprecated("Use assertStdOut")
    val stdout
        get() = Scanner(stdoutFile)

    @Suppress("unused") // Keep this property as it is useful for debugging
    @Deprecated(
        "This property is used for debugging only," +
                " do not actually use it in tests because stdout is often large" +
                " and can cause memory issues if loaded as a string" +
                " (stderr is fine)"
    )
    val stdoutAsTextForDebug: String by lazy { stdout.asText() }

    /**
     * Returns the short (single-line) message that Gradle would print out in the console, without
     * `--stacktrace`. If the build succeeded, returns null.
     */
    @Deprecated("Use assertFailureMessage")
    val failureMessage: String?
        get() = exception?.let {
            val causalChain = Throwables.getCausalChain(exception)
            // Try the common scenarios: configuration or task failure.
            for (throwable in causalChain) {
                // Because of different class loaders involved, we are forced to do stringly-typed
                // programming.
                val throwableType = throwable.javaClass.name
                if (throwableType == ProjectConfigurationException::class.java.name) {
                    return throwable.cause?.message ?: throw AssertionError(
                        "Exception had unexpected structure.",
                        exception
                    )
                } else if (isPlaceholderEx(throwableType)) {
                    if (throwable.toString().startsWith(TaskExecutionException::class.java.name)) {
                        var cause = throwable
                        // there can be several levels of PlaceholderException when dealing with
                        // Worker API failures.
                        while (isPlaceholderEx(throwableType) && cause.cause != null) {
                            cause = cause.cause
                        }
                        return cause.message
                    }
                }
            }

            // Look for any BuildException, for other cases.
            for (throwable in causalChain) {
                val throwableType = throwable.javaClass.name
                if (throwableType == BuildException::class.java.name) {
                    return throwable.cause?.message ?: throw AssertionError(
                        "Exception had unexpected structure.",
                        exception
                    )
                }
            }

            throw AssertionError("Failed to determine the failure message.", exception)
        }

    val tasks: List<String>
        get() = taskStateList.tasks

    val taskStates: Map<String, TaskStateList.ExecutionState>
        get() = taskStateList.taskStates

    val upToDateTasks: Set<String>
        get() = taskStateList.upToDateTasks

    val fromCacheTasks: Set<String>
        get() = taskStateList.fromCacheTasks

    val didWorkTasks: Set<String>
        get() = taskStateList.didWorkTasks

    val skippedTasks: Set<String>
        get() = taskStateList.skippedTasks

    val failedTasks: Set<String>
        get() = taskStateList.failedTasks

    /**
     * Returns the task info given the task name, or null if the task is not found (if it is not in
     * the task execution plan).
     *
     * @see getTask
     */
    fun findTask(name: String): TaskStateList.TaskInfo? {
        return taskStateList.findTask(name)
    }

    /**
     * Returns the task info given the task name. The task must exist (it must be in the task
     * execution plan).
     *
     * @see findTask
     */
    fun getTask(name: String): TaskStateList.TaskInfo {
        Preconditions.checkArgument(name.startsWith(":"), "Task name must start with :")
        return taskStateList.getTask(name)
    }

    private fun isPlaceholderEx(throwableType: String) =
        throwableType == PlaceholderException::class.java.name
                || throwableType == ContextualPlaceholderException::class.java.name

    @Suppress("DEPRECATION")
    fun assertOutputContains(text: String) {
        stdout.use {
            ScannerSubject.assertThat(it, name = "stdout").contains(text)
        }
    }

    @Suppress("DEPRECATION")
    fun assertErrorContains(text: String) {
        stderr.use {
            ScannerSubject.assertThat(it, name = "stderr").contains(text)
        }
    }

    @Suppress("DEPRECATION")
    fun assertOutputDoesNotContain(text: String) {
        stdout.use {
            ScannerSubject.assertThat(it, name = "stdout").doesNotContain(text)
        }
    }

    @Suppress("DEPRECATION")
    fun assertErrorDoesNotContain(text: String) {
        stderr.use {
            ScannerSubject.assertThat(it, name = "stderr").doesNotContain(text)
        }
    }

    private fun Scanner.processEachLine(processLine: (String) -> Unit) {
        while (this.hasNextLine()) {
            val line = this.nextLine()
            processLine(line)
        }
    }

    /**
     * Processes each line of the stdout using the provided [processLine] function.
     *
     * @param processLine A function that consumes each line of the stdout.
     */
    @Suppress("DEPRECATION")
    fun processOutput(processLine: (String) -> Unit) {
        stdout.use { it.processEachLine(processLine) }
    }

    /**
     * Processes each line of the stderr using the provided [processLine] function.
     *
     * @param processLine A function that consumes each line of the stderr.
     */
    @Suppress("DEPRECATION")
    fun processError(processLine: (String) -> Unit) {
        stderr.use { it.processEachLine(processLine) }
    }

    /** Checks that the [GradleBuildResult] hit the configuration cache */
    fun assertConfigurationCacheHit() {
        assertOutputContains("Reusing configuration cache")
        assertOutputDoesNotContain("Calculating task graph")
    }

    /** Checks that the [GradleBuildResult] did not hit the configuration cache */
    fun assertConfigurationCacheMiss() {
        assertOutputContains("Calculating task graph")
        assertOutputDoesNotContain("Reusing configuration cache")
    }

    /**
     * Finds file paths mentioned in stdout and stderr and copies them to the given [outputDir].
     *
     * If any of the mentioned files are HTML reports, this method will also perform simple text
     * matching to find linked local CSS, JavaScript, and other HTML files, copying them as well
     * to preserve the report's structure and functionality. Remote files are ignored.
     *
     * - For files located under the parent of [outputDir], the path relative to that parent is
     *   preserved within [outputDir].
     * - For all other files, their full absolute path (minus the root) is recreated inside
     *   [outputDir].
     *
     * @param outputDir the directory to copy files to.
     * @param mode which category of files to copy.
     */
    fun copyMentionedFilesTo(outputDir: File, mode: CopyFilesMode = CopyFilesMode.TEXT_ONLY) {
        val filesToCopy = mutableSetOf<File>()
        val processedFiles = mutableSetOf<File>()

        val fileMentionRegex = Regex("""file:(?://)?((?:[a-zA-Z]:)?[^'"\s]+)""")
        val linkRegex = Regex("""<link[^>]+href=["'](?!https?://)([^"']+\.(css))["']""")
        val scriptRegex = Regex("""<script[^>]+src=["'](?!https?://)([^"']+\.(js))["']""")
        val hyperlinkRegex = Regex("""<a[^>]+href=["'](?!https?://)([^"']+\.html?)["']""")

        fun findAndProcessFiles(file: File) {
            if (!file.exists() || file in processedFiles) return
            processedFiles.add(file)

            val extension = file.extension.lowercase()
            if (extension == "html" || extension == "htm") {
                file.useLines { lines ->
                    lines.forEach { line ->
                        (linkRegex.findAll(line) +
                                scriptRegex.findAll(line) +
                                hyperlinkRegex.findAll(line)).forEach { matchResult ->
                            val relativePath = matchResult.groupValues[1]
                            val referencedFile = file.resolveSibling(relativePath).normalize()
                            if (referencedFile !in filesToCopy) {
                                filesToCopy.add(referencedFile)
                                findAndProcessFiles(referencedFile)
                            }
                        }
                    }
                }
            }
        }

        val initialFiles = mutableListOf<File>()
        val collectInitialFiles = { lines: Sequence<String> ->
            lines.forEach { line ->
                fileMentionRegex.findAll(line).forEach { matchResult ->
                    val filePath = matchResult.groupValues.last()
                    val sourceFile = File(filePath)
                    if (sourceFile.exists() && mode.includes(sourceFile)) {
                        if (sourceFile !in filesToCopy) {
                            filesToCopy.add(sourceFile)
                            initialFiles.add(sourceFile)
                        }
                    }
                }
            }
        }

        stdoutFile.useLines(block = collectInitialFiles)
        stderrFile.useLines(block = collectInitialFiles)

        initialFiles.forEach(::findAndProcessFiles)

        filesToCopy.forEach { sourceFile ->
            val sourcePath = sourceFile.toPath()
            val outputDirPath = outputDir.toPath()
            val destinationFile = if (sourcePath.startsWith(outputDirPath.parent)) {
                outputDirPath.resolve(sourcePath.subpath(outputDirPath.parent.nameCount, sourcePath.nameCount).toString()).toFile()
            } else {
                outputDir.resolve(sourceFile.toPath().root.relativize(sourceFile.toPath()).toString()).absoluteFile
            }
            destinationFile.parentFile.mkdirs()
            sourceFile.copyTo(destinationFile, overwrite = true)
        }
    }

    private fun Scanner.asText(): String = use {
        buildString {
            while (it.hasNextLine()) {
                appendLine(it.nextLine())
            }
        }
    }

    /**
     * Most tests don't examine the state of the build's tasks and [TaskStateList] is relatively
     * expensive to initialize, so this is done lazily.
     */
    private val taskStateList: TaskStateList by lazy {
        TaskStateList(taskEvents, this.stdout)
    }
}
