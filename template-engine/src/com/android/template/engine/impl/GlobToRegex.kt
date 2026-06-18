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

internal object GlobToRegex {
  /**
   * Creates a [Regex] that can be used to match the glob [pattern] parameter.
   *
   * See [https://git-scm.com/docs/gitignore](https://git-scm.com/docs/gitignore) for a reference.
   */
  fun parseGlobPattern(pattern: String, caseSensitive: Boolean = true): Regex {
    var p = pattern.trim()

    var anchored = false
    if (p.startsWith("/")) {
      anchored = true
      p = p.substring(1)
    }

    val hasSlash = p.dropLastWhile { it == '/' }.contains('/')
    if (hasSlash && !p.startsWith("**/")) {
      anchored = true
    }

    if (p.startsWith("**/")) {
      anchored = false
      p = p.substring(3)
    }

    val sb = StringBuilder()
    sb.append("^")
    if (!anchored) {
      sb.append("(?:.*/)?")
    }

    var i = 0
    while (i < p.length) {
      when {
        p.startsWith("/**/", i) -> {
          sb.append("(?:/|/.+/)")
          i += 4
        }
        p.startsWith("/**", i) && i + 3 == p.length -> {
          sb.append("(?:/.*)?")
          i += 3
        }
        p.startsWith("**", i) -> {
          sb.append(".*")
          i += 2
        }
        p[i] == '*' -> {
          sb.append("[^/]*")
          i++
        }
        p[i] == '?' -> {
          sb.append("[^/]")
          i++
        }
        p[i] == '.' -> {
          sb.append("\\.")
          i++
        }
        p[i] == '{' -> {
          sb.append("(?:")
          i++
        }
        p[i] == '}' -> {
          sb.append(")")
          i++
        }
        p[i] == ',' -> {
          sb.append("|")
          i++
        }
        p[i] == '[' -> {
          sb.append("[")
          i++
          if (i < p.length && p[i] == '^') {
            sb.append("^")
            i++
          }
          var hasClose = false
          while (i < p.length) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length) {
              sb.append("\\").append(p[i + 1])
              i += 2
            } else if (c == ']') {
              sb.append("]")
              i++
              hasClose = true
              break
            } else {
              sb.append(c)
              i++
            }
          }
          if (!hasClose) {
            // If missing closing bracket, it wasn't a valid character class
            // We could throw an error or just let regex engine handle it.
            // Usually it's an error.
          }
        }
        p[i] == '\\' && i + 1 < p.length -> {
          val next = p[i + 1]
          if (next in listOf('*', '?', '[', ']', '\\')) {
            sb.append("\\").append(next)
          } else {
            // If escaping something else, just add the escape
            sb.append("\\\\").append(next)
          }
          i += 2
        }
        p[i] in listOf('\\', '+', '^', '$', '(', ')', '|', ']') -> {
          sb.append("\\").append(p[i])
          i++
        }
        else -> {
          sb.append(p[i])
          i++
        }
      }
    }

    sb.append("$")
    val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return sb.toString().toRegex(options)
  }
}
