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

package com.android.tools.render.discovery

import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.validation.ValidationResult

/**
 * Holds discovered Compose preview screenshots and method-level validation results for a single method overload.
 *
 * When a class declares multiple overloads for the same method name (e.g. `MyPreview()` and
 * `MyPreview(@PreviewParameter(UserProvider::class) user: User)`), each preview-annotated overload is evaluated and isolated into its own
 * [DiscoveredMethodPreviews] instance. This allows valid overloads to render successfully even if another overload fails validation.
 *
 * @property previews Discovered [ComposeScreenshot]s for this method overload.
 * @property methodValidationResult Validation issues (e.g. missing `@Composable`) specific to this method overload.
 */
data class DiscoveredMethodPreviews(
  val previews: List<ComposeScreenshot> = emptyList(),
  val methodValidationResult: ValidationResult = ValidationResult.OK,
)
