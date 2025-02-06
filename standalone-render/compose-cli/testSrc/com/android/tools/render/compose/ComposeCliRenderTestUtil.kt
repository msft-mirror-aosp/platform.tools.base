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
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/** We want to filter out the error that we are aware of and that does not affect rendering. */
private val ALLOWED_ERRORS = listOf(
    "WARNING: A terminally deprecated method in java.lang.System has been called",
    "WARNING: System::setSecurityManager has been called by com.android.tools.rendering.security.RenderSecurityManager",
    "WARNING: Please consider reporting this to the maintainers of com.android.tools.rendering.security.RenderSecurityManager",
    "WARNING: System::setSecurityManager will be removed in a future release",
    "Tracing Skia with Perfetto is not supported in this environment (host build?)",
)

fun runComposeCliRender(settingsFile: File): String {
    val javaHome = System.getProperty("java.home")
    val layoutlibJar = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib/data/layoutlib-mvn.jar")
    val composeCliRenderFolder = TestUtils.resolveWorkspacePath("tools/base/standalone-render/compose-cli")
    val command = listOf("$javaHome/bin/java", "-Dlayoutlib.thread.profile.timeoutms=10000", "-Djava.security.manager=allow", "-cp", "compose-preview-renderer.jar:${layoutlibJar.absolutePathString()}", "com.android.tools.render.common.MainKt", settingsFile.absolutePath)
    val procBuilder = ProcessBuilder(command)
        .directory(composeCliRenderFolder.toFile())
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
    // We have to specify JAVA_HOME
    procBuilder.environment()["JAVA_HOME"] = javaHome
    val proc = procBuilder.start()
    proc.waitFor(5, TimeUnit.MINUTES)
    val error = proc
        .errorStream
        .bufferedReader()
        .readLines()
        .filter { line -> ALLOWED_ERRORS.none { line.startsWith(it) } }
        .joinToString("\n")
    if (error.isNotEmpty()) {
        val commandStr = command.joinToString(" ")
        throw AssertionError("Error while rendering Compose previews \"$commandStr\":\n$error")
    }
    return proc.inputStream.bufferedReader().readText()
}
