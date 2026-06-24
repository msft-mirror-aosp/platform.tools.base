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

package com.android.tools.backup

/**
 * Interface representing a delegated action/payload to be run inside the application sandbox on the target Android device.
 *
 * Implementing classes must have a public no-arg constructor, enabling them to be instantiated dynamically by the test runner on the
 * device.
 */
interface DeviceAction {
  /**
   * Executes the custom payload.
   *
   * @param args The input arguments passed from the host orchestrator.
   * @return A Map containing the output results to be serialized back to the host JVM.
   */
  fun execute(args: Map<String, String>): Map<String, String>
}

/** Interface for actions that populate or seed data inside the application sandbox before a backup. */
interface DataPopulationAction : DeviceAction

/** Interface for actions that verify or assert data inside the application sandbox after a restore. */
interface DataVerificationAction : DeviceAction
