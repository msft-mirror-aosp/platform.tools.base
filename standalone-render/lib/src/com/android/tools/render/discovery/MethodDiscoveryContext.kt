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

import org.objectweb.asm.Type

/**
 * A parameter declared in the source of a method.
 *
 * @property type Fully qualified name of the parameter type, e.g. `java.lang.String`, `int` or `com.example.User`.
 * @property hasPreviewParameter Whether the parameter is annotated with `@PreviewParameter`.
 */
data class DiscoveredParameter(val type: String, var hasPreviewParameter: Boolean = false)

/**
 * Encapsulates raw preview annotations and parameter metadata collected during bytecode inspection of a method.
 *
 * @property isComposable Whether the method is annotated with `@Composable`.
 * @property parameters The parameters declared in source, in declaration order; empty unless the method was compiled by the Compose
 *   compiler, see [fromDescriptor].
 * @property mayHaveDefaultParameters Whether a declared parameter may have a default value, see [fromDescriptor].
 * @property previewConfigurations Discovered `@Preview` attributes for each preview configuration.
 * @property previewParameterConfigs Discovered attribute maps for each `@PreviewParameter` annotation on parameters.
 * @property previewWrapperFqns Fully qualified names of discovered `@PreviewWrapper` annotations.
 */
data class MethodDiscoveryContext(
  var isComposable: Boolean = false,
  // Parameters declared in source
  val parameters: MutableList<DiscoveredParameter> = mutableListOf(),
  val mayHaveDefaultParameters: Boolean = false,
  // @Preview Annotations
  val previewConfigurations: MutableList<Map<String, String>> = mutableListOf(),
  // @PreviewParameter Annotations
  val previewParameterConfigs: MutableList<Map<String, String>> = mutableListOf(),
  // @PreviewWrapper Annotations
  val previewWrapperFqns: MutableList<String> = mutableListOf(),
) {
  companion object {
    private const val COMPOSER_DESC = "Landroidx/compose/runtime/Composer;"

    /** Number of declared parameters encoded by each `$changed` mask, `SLOTS_PER_INT` in the Compose compiler. */
    private const val PARAMETERS_PER_CHANGED_MASK = 10

    /**
     * Creates the context of a method with the parameters declared in its source, recovered from its JVM [descriptor].
     *
     * The Compose compiler appends parameters to every `@Composable` function: a `Composer`, one `int` `$changed` mask per 10 declared
     * parameters (at least one) and, only when a parameter has a default value, `int` `$default` masks. `fun Card(user: User, modifier:
     * Modifier = Modifier)` therefore compiles to `Card(User, Modifier, Composer, int, int)`: the parameters before the `Composer` are the
     * declared ones, and masks beyond the `$changed` ones reveal default values without telling which parameter has one.
     *
     * A method without this shape, because it has no `Composer` parameter, fewer masks than its `$changed` ones or something else than
     * `int` masks after the `Composer`, was not compiled by the Compose compiler, so the renderer cannot invoke it anyway: no parameter is
     * recorded, and the checks relying on them do not run. A change in the convention can thus only disable those checks, never fail a
     * valid preview.
     */
    fun fromDescriptor(descriptor: String): MethodDiscoveryContext {
      // Most methods of a class are not composable: skip them without parsing the descriptor.
      if (COMPOSER_DESC !in descriptor) return MethodDiscoveryContext()
      val arguments = Type.getArgumentTypes(descriptor).toList()
      // The substring may also be the return type.
      val composerIndex = arguments.indexOfLast { it.descriptor == COMPOSER_DESC }
      if (composerIndex < 0) return MethodDiscoveryContext()

      val declared = arguments.take(composerIndex)
      val masks = arguments.drop(composerIndex + 1)
      val changedMasks = maxOf(1, (declared.size + PARAMETERS_PER_CHANGED_MASK - 1) / PARAMETERS_PER_CHANGED_MASK)
      if (masks.size < changedMasks || masks.any { it.sort != Type.INT }) return MethodDiscoveryContext()

      return MethodDiscoveryContext(
        parameters = declared.mapTo(mutableListOf()) { DiscoveredParameter(it.className) },
        mayHaveDefaultParameters = masks.size > changedMasks,
      )
    }
  }
}
