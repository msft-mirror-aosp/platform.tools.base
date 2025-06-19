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
package com.android.tools.deployer.model

import com.android.ddmlib.IDevice
import com.android.tools.deployer.ApplicationDumper.Dump

/**
 * ┌─────────────────┐                    ┌───────────────┐
 * │    Project      │                    │    Device     │
 * └────────┬────────┘                    └───────┬───────┘
 *          │                                     │
 *    [Build System]                          [ DUMP ]
 *          │                                     │
 *          ▼                                     ▼
 *    ┌───────────────────┐              ┌───────────────────┐
 *    │       App         │              │    App State      │
 *    │  ┌───────────────────────┐       │   (Dump Info)     │
 *    │  │ Deployment Strategy A │       └─────────┬─────────┘
 *    │  │  -APK                 │                 │
 *    │  │  -APK                 │                 │
 *    │  └───────────────────────┘                 │
 *    │  ┌───────────────────────┐                 │
 *    │  │ Deployment Strategy B │                 │
 *    │  │  -APK                 │                 │
 *    │  └───────────────────────┘                 │
 *    └─────────┬─────────┘                        │
 *              │                                  │
 *              │                                  │
 *              ▼                                  ▼
 *    ┌─────────────────────────────────────────────┐
 *    │           Deployment Plan                   │
 *    │              -App                           │
 *    │              -AppState                      │
 *    └─────────────────────────────────────────────┘
 *
 * How and what we deploy to the device depends on two inputs.
 *
 *   1. An App created by the Build System
 *       - Each App has artifacts such as APKs, Baseline Profiles, etc.
 *       - Each artifact has an associated Deployment Strategy. It signifies how each artifact
 *         should be deployed to the device. For example, a RootPushArtifact APK should be installed
 *         via the 'rootpush' method (when possible).
 *
 *   2. App State
 *       - From the App model and the deployment strategy, the deployer will formulate a plan of
 *         action by choosing to fetch certain information from the device via DUMP.
 *         (NOTE: We currently have not migrated all the DUMP into part of AppState)
 *
 *       - It is important to note that App State should be completely fetched before the deployment
 *         plan is executed. That means the deployer will make (close to) zero queries to the
 *         device after deployment started. The absence of large number of round trips limits
 *         the probability of having hangs and timeout when workstation-to-device connection is
 *         unreliable.
 */
abstract class DeploymentStrategy<T>(val artifact: T, val filters: Map<String, String>) {
    /**
     * The artifact, likely an APK or Baseline profile, should be fed to the package manager.
     *
     * Note that it does not mean it will always be *installed* by the package manager since things
     * like IWI exists.
     */
    open fun shouldSendToPackageManager() = false
    open fun shouldRootPush() = false

    /**
     * Returns whether this artifact should be deployed to the device given the device's most
     * preferable ABI
     */
    fun shouldTargetAbi(abi: String) = filters["ABI"]?.equals(abi) ?: true
    open fun isApk() = false
}
abstract class ApkArtifact(apk: Apk, filters: Map<String, String> = emptyMap())
    : DeploymentStrategy<Apk>(apk, filters) {
    override fun isApk() = true
}
class PackageManagerApk(apk: Apk, filters: Map<String, String> = emptyMap())
    : ApkArtifact(apk, filters) {
    override fun shouldSendToPackageManager() = true
}

class RootPushApk(apk: Apk, filters: Map<String, String> = emptyMap())
    : ApkArtifact(apk, filters) {
    override fun shouldRootPush() = true
}
