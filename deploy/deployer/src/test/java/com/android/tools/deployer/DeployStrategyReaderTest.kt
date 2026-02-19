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
package com.android.tools.deployer

import com.android.tools.deployer.model.App
import java.nio.file.Path
import org.junit.Assert
import org.junit.Test

const val BASE = "tools/base/deploy/deployer/src/test/resource/apks/"

class DeployStrategyReaderTest {
  @Test
  fun abiFilter() {
    val app = App.fromStrategy(Path.of(BASE + "arch_filter.json"), TestLogger())
    Assert.assertEquals("com.example.simpleapp", app.appId)
    val strategies = app.allStrategies
    Assert.assertEquals(3, strategies.size)
    val targetArm64 = app.getApksForPackageManager("arm64-v8a")
    Assert.assertEquals(2, targetArm64.size)
    Assert.assertTrue(targetArm64.any { it.path.endsWith("split.apk") })
    Assert.assertTrue(targetArm64.any { it.path.endsWith("simple.apk") })
    Assert.assertFalse(targetArm64.any { it.path.endsWith("split2.apk") })
  }
}
