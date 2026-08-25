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

/**
 * Encapsulates raw preview annotations and parameter metadata collected during bytecode inspection of a method.
 *
 * @property isComposable Whether the method is annotated with `@Composable`.
 * @property previewConfigurations Discovered `@Preview` attributes for each preview configuration.
 * @property previewParameterConfigs Discovered attribute maps for each `@PreviewParameter` annotation on parameters.
 * @property previewWrapperFqns Fully qualified names of discovered `@PreviewWrapper` annotations.
 */
data class MethodDiscoveryContext(
  var isComposable: Boolean = false,
  // @Preview Annotations
  val previewConfigurations: MutableList<Map<String, String>> = mutableListOf(),
  // @PreviewParameter Annotations
  val previewParameterConfigs: MutableList<Map<String, String>> = mutableListOf(),
  // @PreviewWrapper Annotations
  val previewWrapperFqns: MutableList<String> = mutableListOf(),
)
