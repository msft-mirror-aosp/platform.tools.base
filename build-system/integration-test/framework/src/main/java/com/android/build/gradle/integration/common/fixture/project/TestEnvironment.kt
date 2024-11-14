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

package com.android.build.gradle.integration.common.fixture.project

import com.android.SdkConstants
import com.android.build.gradle.integration.BazelIntegrationTestsSuite
import com.android.testutils.TestUtils
import java.nio.file.Path

/**
 * Helper for environment related methods
 */
class TestEnvironment {
    companion object {
        fun getNdkPath(sdkDir: Path?, version: String?): Path? {
            return if (version != null) {
                if (TestUtils.runningFromBazel()) {
                    BazelIntegrationTestsSuite.NDK_SIDE_BY_SIDE_ROOT.resolve(version)
                } else {
                    sdkDir?.resolve("${SdkConstants.FD_NDK_SIDE_BY_SIDE}/$version")
                }
            } else {
                if (TestUtils.runningFromBazel()) {
                    BazelIntegrationTestsSuite.NDK_IN_TMP
                } else {
                    sdkDir?.resolve(SdkConstants.FD_NDK)
                }
            }
        }
    }
}
