/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.android.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.deployer.model.component

import com.android.tools.deployer.model.activate.ActivationCommands
import com.android.tools.deployer.model.activate.BroadcastResultChecker
import org.junit.Assert
import org.junit.Test

class DeclarativeWatchFaceTest {

  @Test
  fun testGetActivationCommands() {
    val appId = "com.example.myApp"
    val commands: ActivationCommands = DeclarativeWatchFace.getActivationCommands(appId)

    Assert.assertEquals(2, commands.size)

    val expectedSetCommand =
      "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId com.example.myApp"
    val expectedShowCommand = "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation show-watchface"

    Assert.assertEquals(expectedSetCommand, commands[0].command)
    Assert.assertEquals(expectedShowCommand, commands[1].command)

    Assert.assertEquals("Setting Declarative Watch Face for com.example.myApp", commands[0].status)
    Assert.assertEquals("Showing Watch Face", commands[1].status)

    Assert.assertTrue(commands[0].checker is BroadcastResultChecker)
    Assert.assertTrue(commands[1].checker is BroadcastResultChecker)
  }
}
