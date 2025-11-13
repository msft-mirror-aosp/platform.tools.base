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

package com.android.build.gradle.integration.common.utils

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption

@Deprecated("Do not use this method. Try to migrate the test to built-in Kotlin instead (b/385745419).")
fun GradleTestProjectBuilder.disableBuiltInKotlin(): GradleTestProjectBuilder {
    addGradleProperty(BooleanOption.BUILT_IN_KOTLIN, false)
    addGradleProperties("${StringOption.SUPPRESS_UNSUPPORTED_OPTION_WARNINGS.propertyName}=${StringOption.SUPPRESS_UNSUPPORTED_OPTION_WARNINGS.propertyName},${BooleanOption.BUILT_IN_KOTLIN.propertyName}")
    return this
}
