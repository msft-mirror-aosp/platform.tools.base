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
package com.android.sdklib.testing

import com.android.sdklib.repository.AndroidSdkHandler
import org.junit.rules.ExternalResource

/** Rule for ensuring tests using AndroidSdkHandler are hermetic. */
class AndroidSdkHandlerRule : ExternalResource() {
  private var isRuleInstalled = false

  override fun before() {
    isRuleInstalled = true
    after()
  }

  var instanceProvider: AndroidSdkHandler.InstanceProvider
    get() = AndroidSdkHandler.instanceProvider
    set(value) {
      check(isRuleInstalled) { "Rule is not installed in the test" }
      AndroidSdkHandler.instanceProvider = value
    }

  override fun after() {
    AndroidSdkHandler.reset()
    AndroidSdkHandler.instanceProvider = AndroidSdkHandler.DefaultInstanceProvider
  }
}
