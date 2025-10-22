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
package com.android.adblib.ddmlibcompatibility.testutils

import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource

/**
 * This test rule initializes AndroidDebugBridge.
 *
 * This rule is needed if the test directly or indirectly relies
 * on a call to `AndroidDebugBridge.createBridge`.
 *
 *  Use `portSuppier` to feed a server port from an outer rule
 *  like `FakeAdbServerProviderRule`.
 */
class InitAndroidDebugBridgeRule(
    private val alsoCreateBridge: Boolean = false,
    private val portSuppier: () -> Int
) : ExternalResource() {

    public override fun before() {
        AndroidDebugBridge.enableFakeAdbServerMode(portSuppier())
        AndroidDebugBridge.init(AdbInitOptions.DEFAULT)
        if (alsoCreateBridge) {
            AndroidDebugBridge.createBridge(10, TimeUnit.SECONDS)
                ?: error("InitAndroidDebugBridgeRule could not create ADB bridge ")
        }
    }

    override fun after() {
        AndroidDebugBridge.disconnectBridge(10, TimeUnit.SECONDS)
        AndroidDebugBridge.terminate()
        AndroidDebugBridge.disableFakeAdbServerMode()
    }
}
