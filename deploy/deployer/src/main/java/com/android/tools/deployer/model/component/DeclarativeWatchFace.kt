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
package com.android.tools.deployer.model.component

import com.android.tools.deployer.model.activate.ActivationCommand
import com.android.tools.deployer.model.activate.ActivationCommands
import com.android.tools.deployer.model.activate.BroadcastResultChecker
import java.util.function.Consumer

class DeclarativeWatchFace {
  companion object {
    private const val SET_DECLARATIVE_WATCH_FACE =
      "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId"

    @JvmStatic
    fun getActivationCommands(appId: String): ActivationCommands {
      val setCommand = "$SET_DECLARATIVE_WATCH_FACE $appId"
      val showCommand = WatchFace.ShellCommand.SHOW_WATCH_FACE

      val commands = mutableListOf<ActivationCommand>()
      commands.add(
        ActivationCommand(
          setCommand,
          "Setting Declarative Watch Face for $appId",
          BroadcastResultChecker(onError = Consumer { msg -> println("Warning: $msg") }),
        )
      )
      commands.add(
        ActivationCommand(showCommand, "Showing Watch Face", BroadcastResultChecker(onError = Consumer { msg -> println("Warning: $msg") }))
      )
      return ActivationCommands(commands)
    }
  }
}
