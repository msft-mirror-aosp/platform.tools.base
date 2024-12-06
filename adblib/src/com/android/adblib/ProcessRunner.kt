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
package com.android.adblib

import java.nio.file.Path

interface ProcessRunner {

    /**
     * Executes a command and waits for it to complete.
     *
     * @param executable The absolute path to the executable.
     * @param args  A list of arguments to pass to the executable.
     * @param envVars  A map of environment variables to set for the process.
     */
    suspend fun runProcess(executable: Path, args: List<String>, envVars: Map<String, String>)
}
