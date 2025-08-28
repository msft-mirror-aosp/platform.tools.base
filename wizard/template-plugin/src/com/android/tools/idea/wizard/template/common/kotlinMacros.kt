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
package com.android.tools.idea.wizard.template.common

import com.android.ide.common.repository.AgpVersion

/**
 * Starting with this AGP version, the Kotlin plugin is present by default and should not be
 * explicitly added (see b/259523353).
 */
val AGP_VERSION_WITH_BUILT_IN_KOTLIN = AgpVersion.parse("9.0.0-alpha03")
