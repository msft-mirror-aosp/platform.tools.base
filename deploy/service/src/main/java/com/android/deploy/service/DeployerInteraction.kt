/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.deploy.service

import com.android.tools.deployer.UIService
import java.util.LinkedList
import java.util.Queue

/**
 * The deployer requires a UIService to be provided. This UIService acts as a way for the Deployer to communicate with the user. An example
 * is prompting the user to re-install the APK when the APK versions do not match.
 *
 * Before [com.android.tools.deployer.DeployerRunner.run] is called, [setPromptResponses] should be set to clear previous run results and
 * setup prompt responses. If no prompt responses are set the default response will be false.
 */
class DeployerInteraction : UIService {

  val prompts = mutableListOf<String>()
  private val myResponses: Queue<Boolean> = LinkedList()
  val messages = mutableListOf<String>()

  fun clear() {
    myResponses.clear()
    prompts.clear()
    messages.clear()
  }

  fun setPromptResponses(responses: MutableList<Boolean>) {
    clear()
    myResponses.addAll(responses)
  }

  override fun prompt(result: String): Boolean {
    prompts.add(result)
    if (!myResponses.isEmpty()) {
      return myResponses.poll()!!
    }
    return false
  }

  override fun message(message: String) {
    messages.add(message)
  }
}
