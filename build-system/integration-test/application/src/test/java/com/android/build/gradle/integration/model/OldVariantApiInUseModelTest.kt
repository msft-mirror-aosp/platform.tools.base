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

package com.android.build.gradle.integration.model

import com.android.build.gradle.integration.common.fixture.model.ReferenceModelComparator
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import org.gradle.api.Project
import org.junit.Test

class OldVariantApiInUseModelTest : ReferenceModelComparator(
    referenceConfig = {
        androidApplication {
        }
        gradleProperties {
            add(BooleanOption.ENABLE_PROFILE_JSON, true)
        }
    },
    deltaConfig = {
        androidApplication {
            pluginCallbacks += OldVariantApiCallback::class.java
        }
        gradleProperties {
            add(BooleanOption.USE_NEW_DSL, false)
        }
    },
    syncOptions = {
        ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
    }
) {
    @Test
    fun `test AndroidProject model`() {
        compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
    }

    class OldVariantApiCallback : LegacyApplicationCallback {
        override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
            // Accessing applicationVariants triggers the use of the old API. The block can be empty.
            extension.applicationVariants.configureEach {}
        }
    }
}
