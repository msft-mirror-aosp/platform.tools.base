/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.deployer.model.activate

/**
 * An abstraction of what a single command needed to activate a component after deployment.
 *
 * This should be free of any implementation specific details executing the command itself so it is not tied to Studio or ddml/adblib.
 *
 * @param command: The adb shell command
 * @param status: The status to show user when this command is activating. For example "Setting up Watch Face".
 * @param checker: This consumer like listener will be sent lines of the commandline's output to determine the success status of the
 *   command. It allows for custom callbacks to be registered if the caller wants to handle errors / warning in any way they want.
 */
data class ActivationCommand(val command: String, val status: String? = null, val checker: ActivationCommandResultChecker) {}

class ActivationCommands(val commands: List<ActivationCommand>) : List<ActivationCommand> by commands {
  constructor(vararg commands: ActivationCommand) : this(commands.toList())
}
