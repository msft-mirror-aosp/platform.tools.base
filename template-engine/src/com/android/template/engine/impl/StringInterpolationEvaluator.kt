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

/** Evaluates string interpolation expressions given variables bindings (i.e. name -> value) */
internal class StringInterpolationEvaluator(
  private val expression: String,
  private val variables: Map<String, String>,
  private val findMethodHandler: (String) -> StringInterpolationMethodHandler? = StringInterpolationMethodHandler::findMethodHandler,
) {

  /** Captures [evaluateArgument] in a field to be passed to [StringInterpolationMethodHandler.evaluate] without heap allocation. */
  private val evaluateArgumentMethod = this::evaluateArgument

  /** Evaluates the string interpolation [expression] with the current [variables] */
  fun evaluate(): String {
    val parser = StringInterpolationParser(expression)
    val ast = parser.parse()
    return evaluate(ast)
  }

  /** Evaluates the string interpolation [node] with the current [variables] */
  fun evaluate(node: StringInterpolationNode): String {
    return when (node) {
      is TemplateNode -> {
        node.parts.joinToString("") { evaluate(it) }
      }

      is TextNode -> {
        node.text
      }

      is InterpolationNode -> {
        var value =
          variables[node.identifier.name]
            ?: throw StringInterpolationException(
              expression,
              node.identifier.startToken.start,
              "Variable '${node.identifier.name}' not found",
            )

        for (methodCall in node.methodCalls) {
          value = applyMethodCall(value, methodCall)
        }
        value
      }

      else ->
        throw StringInterpolationException(
          expression,
          node.startToken.start,
          "Unexpected node type '${node::class.simpleName}' in evaluation",
        )
    }
  }

  private fun applyMethodCall(target: String, methodCall: MethodCallNode): String {
    val methodHandler =
      findMethodHandler(methodCall.methodName)
        ?: run { throw StringInterpolationException(expression, methodCall.startToken.start, "Unknown method '${methodCall.methodName}'") }

    val argCount = methodHandler.argCount
    if (argCount != methodCall.arguments.size) {
      throw StringInterpolationException(
        expression,
        methodCall.startToken.start,
        "Method '${methodCall.methodName}' expects $argCount arguments",
      )
    }
    return methodHandler.evaluate(target, methodCall, evaluateArgumentMethod)
  }

  private fun evaluateArgument(arg: StringInterpolationNode): String {
    return when (arg) {
      is StringLiteralNode -> arg.text
      is IdentifierNode ->
        variables[arg.name] ?: throw StringInterpolationException(expression, arg.startToken.start, "Variable '${arg.name}' not found")

      else -> throw StringInterpolationException(expression, arg.startToken.start, "Unsupported node type '${arg::class.simpleName}'")
    }
  }
}
