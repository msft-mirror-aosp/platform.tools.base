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
package com.android.tools.render.common

/**
 * Returns the theme reference for the `theme` preview parameter: references such as `@style/Theme.MyApp` or `@android:style/Theme.Material`
 * are kept, and a plain style name such as `Theme.MyApp` refers to the project's style of that name.
 */
internal fun toThemeReference(theme: String): String = theme.trim().let { if (it.startsWith("@")) it else "@style/$it" }
