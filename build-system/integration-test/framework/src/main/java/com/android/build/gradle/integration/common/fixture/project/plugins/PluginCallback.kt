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

package com.android.build.gradle.integration.common.fixture.project.plugins

/** Base interface for all plugin callbacks. */
interface PluginCallback {

  /**
   * Indicates that this callbacks requires the old DSL.
   *
   * This is meant to be used by the fixture callback interfaces, and not changed by the tests themselves
   */
  val requiresOldVariantApi
    get() = false

  /**
   * Indicates that the callback would like to run on the old DSL but does not actually requires it, meaning that it can run with both which
   * is useful for parameterized tests on old/new DSL.
   *
   * A test should return true here to indicate that running with the old DSL is fine, and should not trigger an error.
   */
  val useWithOldDsl: Boolean
    get() = false
}
