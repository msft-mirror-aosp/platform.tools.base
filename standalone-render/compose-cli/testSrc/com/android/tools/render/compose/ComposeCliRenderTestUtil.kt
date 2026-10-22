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

package com.android.tools.render.compose

import com.android.testutils.TestUtils
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/** We want to filter out the error that we are aware of and that does not affect rendering. */
private val ALLOWED_ERRORS =
  listOf(
    "WARNING: A terminally deprecated method in java.lang.System has been called",
    "WARNING: System::setSecurityManager has been called by",
    "WARNING: Please consider reporting this to the maintainers",
    "WARNING: System::setSecurityManager will be removed in a future release",
    "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called",
    "WARNING: sun.misc.Unsafe::objectFieldOffset has been called by",
    "WARNING: sun.misc.Unsafe::objectFieldOffset will be removed in a future release",
    "WARNING: A restricted method in java.lang.System has been called",
    "WARNING: java.lang.System::load has been called by",
    "WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning",
    "WARNING: Restricted methods will be blocked in a future release",
    "Tracing Skia with Perfetto is not supported in this environment (host build?)",
  )

/**
 * Runs the CLI with [args] and returns its stdout.
 *
 * When [expectRenderErrors] is `false`, fails if the CLI exits with an error or writes anything to stderr other than [ALLOWED_ERRORS]. When
 * it is `true`, fails unless the CLI reports that rendering failed with errors (exit code 1), which it does when any screenshot has an
 * error. The caller is then expected to check the results file.
 */
fun runComposeCliRender(
  args: List<String>,
  expectRenderErrors: Boolean = false,
  jvmArgs: List<String> = emptyList(),
): String {
  val javaHome = System.getProperty("java.home")
  val composeCliRenderFolder = TestUtils.resolveWorkspacePath("tools/base/standalone-render/compose-cli")
  val deployJar = TestUtils.resolveWorkspacePath("tools/base/standalone-render/compose-cli/compose-preview-renderer-cli_deploy.jar")
  val command =
    listOf("$javaHome/bin/java", "-Dlayoutlib.thread.profile.timeoutms=10000") +
      jvmArgs +
      listOf("-jar", deployJar.absolutePathString()) +
      args
  val commandStr = command.joinToString(" ")
  // Redirect stdout and stderr to temp files so a full OS pipe buffer cannot deadlock the CLI process.
  val stdoutFile = Files.createTempFile("compose-cli-stdout", ".log")
  val stderrFile = Files.createTempFile("compose-cli-stderr", ".log")
  val stdout: String
  val error: String
  val exitCode: Int
  try {
    val procBuilder =
      ProcessBuilder(command)
        .directory(composeCliRenderFolder.toFile())
        .redirectOutput(stdoutFile.toFile())
        .redirectError(stderrFile.toFile())
    // We have to specify JAVA_HOME
    procBuilder.environment()["JAVA_HOME"] = javaHome
    val proc = procBuilder.start()
    if (!proc.waitFor(5, TimeUnit.MINUTES)) {
      proc.destroyForcibly()
      proc.waitFor(10, TimeUnit.SECONDS)
      val partialStderr = runCatching { Files.readString(stderrFile) }.getOrDefault("")
      throw AssertionError("CLI command timed out after 5 minutes: \"$commandStr\"\nPartial stderr:\n$partialStderr")
    }
    stdout = Files.readString(stdoutFile)
    error = Files.readAllLines(stderrFile).filter { line -> ALLOWED_ERRORS.none { line.trim().startsWith(it) } }.joinToString("\n")
    exitCode = proc.exitValue()
  } finally {
    Files.deleteIfExists(stdoutFile)
    Files.deleteIfExists(stderrFile)
  }
  val failureReason =
    if (expectRenderErrors) {
      // When expectRenderErrors is true, the CLI writes "Error: Rendering failed with errors" to stderr (so stderr is non-empty),
      // while per-screenshot errors are recorded in the results JSON rather than logged at WARNING/SEVERE. Checking for WARNING/SEVERE
      // still catches unexpected internal renderer or reflection failures.
      when {
        exitCode != 1 -> "expected exit code 1 when expectRenderErrors=true, but got $exitCode"
        !error.contains("Error: Rendering failed with errors") -> "expected stderr to contain \"Error: Rendering failed with errors\""
        error.contains("Global error:") -> "unexpected \"Global error:\" in stderr"
        error.contains("SEVERE:") -> "unexpected \"SEVERE:\" log line in stderr"
        error.contains("WARNING:") -> "unexpected \"WARNING:\" log line in stderr"
        else -> null
      }
    } else {
      when {
        exitCode != 0 -> "expected exit code 0, but got $exitCode"
        error.isNotEmpty() -> "expected empty stderr, but found unexpected output"
        else -> null
      }
    }
  if (failureReason != null) {
    throw AssertionError("Error while executing CLI command \"$commandStr\" ($failureReason, exit code $exitCode):\n$error")
  }
  return stdout
}

fun runComposeCliRender(
  settingsFile: File,
  expectRenderErrors: Boolean = false,
  jvmArgs: List<String> = emptyList(),
): String {
  return runComposeCliRender(listOf(settingsFile.absolutePath), expectRenderErrors, jvmArgs)
}
