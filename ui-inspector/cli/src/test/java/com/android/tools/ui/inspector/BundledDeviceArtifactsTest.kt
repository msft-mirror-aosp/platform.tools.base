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

package com.android.tools.ui.inspector

import com.android.tools.ui.inspector.deploy.SUPPORTED_ABIS
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Checks that the standalone CLI bundles every device artifact at the classpath path [UiInspector] reads it from by default. */
class BundledDeviceArtifactsTest {

  @Test
  fun testEveryDeviceArtifactIsBundledWhereUiInspectorReadsItByDefault() {
    // The default paths of InjectionManager and InspectorRegistry, which are relative classpath resource paths.
    val paths =
      listOf(
        "tools/base/ui-inspector/agent/inspectors/view/view-inspector.jar",
        "tools/base/ui-inspector/agent/payload/lib_ui_inspector_payload.jar",
        "tools/base/ui-inspector/art-tooling/java/libarttooling.jar",
      ) + SUPPORTED_ABIS.map { abi -> "tools/base/ui-inspector/art-tooling/native/libarttooling_agent.so/$abi/libarttooling_agent.so" }

    for (path in paths) {
      assertWithMessage(path).that(UiInspector::class.java.getResource("/$path")).isNotNull()
    }
  }
}
