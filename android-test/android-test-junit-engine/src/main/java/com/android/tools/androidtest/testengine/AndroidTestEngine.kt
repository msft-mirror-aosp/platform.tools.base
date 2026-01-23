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

package com.android.tools.androidtest.testengine

import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.hierarchical.ForkJoinPoolHierarchicalTestExecutorService
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine
import org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService

/**
 * A JUnit [HierarchicalTestEngine] for Android tests.
 *
 * This test engine orchestrates Android device tests by installing APKs and running
 * `am instrument`. It reports test results back to the JUnit Platform as dynamic tests.
 */
class AndroidTestEngine : HierarchicalTestEngine<AndroidTestExecutionContext>() {

    override fun getId(): String = "android-test-engine"

    override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
        // Discovery is performed on-device during execution.
        // We return a root descriptor that will emit dynamic tests.
        return AndroidTestEngineDescriptor(uniqueId)
    }

    override fun createExecutionContext(request: ExecutionRequest): AndroidTestExecutionContext {
        return AndroidTestExecutionContext(request)
    }

    override fun createExecutorService(request: ExecutionRequest): HierarchicalTestExecutorService {
        return ForkJoinPoolHierarchicalTestExecutorService(request.configurationParameters)
    }
}
