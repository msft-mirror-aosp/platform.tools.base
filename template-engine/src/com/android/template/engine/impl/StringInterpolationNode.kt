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
package com.android.template.engine.impl

internal sealed class StringInterpolationNode {
  abstract val startToken: StringInterpolationToken
  abstract val endToken: StringInterpolationToken
}

internal data class TextNode(
  override val startToken: StringInterpolationToken,
  override val endToken: StringInterpolationToken,
  val text: String,
) : StringInterpolationNode()

internal data class StringLiteralNode(
  override val startToken: StringInterpolationToken,
  override val endToken: StringInterpolationToken,
  val text: String,
) : StringInterpolationNode()

internal data class IdentifierNode(
  override val startToken: StringInterpolationToken,
  override val endToken: StringInterpolationToken,
  val name: String,
) : StringInterpolationNode()

internal data class MethodCallNode(
  override val startToken: StringInterpolationToken,
  override val endToken: StringInterpolationToken,
  val methodName: String,
  val arguments: List<StringInterpolationNode>,
) : StringInterpolationNode()

internal data class InterpolationNode(
  override val startToken: StringInterpolationToken,
  override val endToken: StringInterpolationToken,
  val identifier: IdentifierNode,
  val methodCalls: List<MethodCallNode>,
) : StringInterpolationNode()

internal data class TemplateNode(
  override val startToken: StringInterpolationToken,
  override val endToken: StringInterpolationToken,
  val parts: List<StringInterpolationNode>,
) : StringInterpolationNode()
