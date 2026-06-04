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
package com.android.template.engine

import com.android.template.engine.impl.GlobToRegex
import com.android.template.engine.impl.StringInterpolationEvaluator
import com.android.template.engine.impl.StringInterpolationMethodHandler

/**
 * Context used across all transformations of all files of a [TemplateDefinition].
 *
 * Currently used as an in-memory cache of compiled glob pattern matchers (see [matchGlob])
 */
internal class TransformationContext(val registry: TransformationRegistry, private val templateArguments: Map<String, String>) {
  private val regexCache = mutableMapOf<String, Result<Regex>>()
  private val evaluatedExpressionsCache = mutableMapOf<String, Result<String>>()
  private val builtinMethodHandlers = StringInterpolationMethodHandler::findMethodHandler

  /**
   * Matches a [value] against a glob [pattern], throwing a [TemplateProcessingException] if the expression is invalid or if the template
   * arguments are invalid.
   */
  fun matchGlob(pattern: String, value: String): Boolean {
    return try {
      // Optimization: parse "globs" only once
      val regex = regexCache.getOrPut(pattern) { runCatching { GlobToRegex.parseGlobPattern(pattern) } }.getOrThrow()
      regex.matches(value)
    } catch (t: Throwable) {
      throw TemplateProcessingException("Error matching glob '$pattern' against '$value': ${t.concatMessages()}", t)
    }
  }

  /**
   * Evaluate a string interpolation expression, throwing a [TemplateProcessingException] if the expression is invalid or if the template
   * arguments are invalid.
   */
  fun evaluateExpression(expression: String): String {
    // Optimization: Evaluate expressions only once
    return evaluatedExpressionsCache
      .getOrPut(expression) {
        runCatching { StringInterpolationEvaluator(expression, templateArguments, builtinMethodHandlers).evaluate() }
      }
      .getOrThrow()
  }
}
