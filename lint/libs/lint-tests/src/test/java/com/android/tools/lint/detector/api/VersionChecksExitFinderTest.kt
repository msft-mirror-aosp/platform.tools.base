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
package com.android.tools.lint.detector.api

import com.android.testutils.TestUtils
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.parseFirst
import com.intellij.openapi.util.Disposer
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.visitor.AbstractUastVisitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [VersionChecks.findPrecedingVersionCheckExitConstraints] and [VersionChecks.isPrecededByVersionCheckExit], calling the
 * methods directly on parsed UAST. (Unlike [VersionChecksTest], which drives the analysis through the ApiDetector.)
 *
 * Each test parses a source file containing a `reachedHere()` call and computes the exit constraints for that call.
 */
class VersionChecksExitFinderTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testLowerAndUpperBoundExits() {
    check(
      kotlin(
          """
          package test.pkg

          import android.os.Build

          class ExitTest {
              fun target() {
                  if (Build.VERSION.SDK_INT < 21) return
                  if (Build.VERSION.SDK_INT >= 30) return
                  reachedHere()
              }

              fun reachedHere() {}
          }
          """
        )
        .indented()
    ) { context, element ->
      val constraint = VersionChecks.findPrecedingVersionCheckExitConstraints(context, element)
      assertEquals(ApiConstraint.range(21, 0, 30, 0).toString(), constraint.toString())
    }
  }

  @Test
  fun testExtensionVersionCheckExit() {
    // The preceding exit is guarded by an extension SDK check, not a plain SDK_INT check.
    // reachedHere() is reachable (whenever the R extension version is at least 4), so the
    // returned constraint must not be the empty constraint: an empty result vacuously
    // satisfies any isAtLeast() bounds check downstream.
    check(
      kotlin(
          """
          package test.pkg

          import android.os.Build
          import android.os.ext.SdkExtensions

          class ExitTest {
              fun target() {
                  if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) < 4) return
                  reachedHere()
              }

              fun reachedHere() {}
          }
          """
        )
        .indented()
    ) { context, element ->
      val constraint = VersionChecks.findPrecedingVersionCheckExitConstraints(context, element)
      assertFalse(
        "reachedHere() is reachable when the R extension version is >= 4, but the constraint claims it is unreachable at every API level",
        constraint.isEmpty(),
      )
    }
  }

  @Test
  fun testConditionalVersionCheckExit() {
    // The version check is and-ed with an unrelated condition, so the exit is not
    // unconditional for any API level: nothing can be concluded about the possible API
    // levels at reachedHere(), and the correct answer is ALL. In particular the result
    // must not be the empty constraint, which would vacuously satisfy any isAtLeast()
    // bounds check downstream.
    check(
      kotlin(
          """
          package test.pkg

          import android.os.Build

          class ExitTest {
              fun target(userOptedIn: Boolean) {
                  if (Build.VERSION.SDK_INT < 21 && !userOptedIn) return
                  reachedHere()
              }

              fun reachedHere() {}
          }
          """
        )
        .indented()
    ) { context, element ->
      val constraint = VersionChecks.findPrecedingVersionCheckExitConstraints(context, element)
      assertEquals(ApiConstraint.ALL.toString(), constraint.toString())
    }
  }

  @Test
  fun testBothBranchesReturn() {
    // When both the then branch and the else branch return, the program has definitely
    // exited, so in particular it has exited whenever SDK_INT < 25. The else branch must
    // not clobber the positive answer already established by the then branch.
    check(
      kotlin(
          """
          package test.pkg

          import android.os.Build

          class ExitTest {
              fun target() {
                  if (Build.VERSION.SDK_INT < 25) return else return
                  reachedHere()
              }

              fun reachedHere() {}
          }
          """
        )
        .indented()
    ) { context, element ->
      assertTrue(VersionChecks.isPrecededByVersionCheckExit(context, element, ApiConstraint.get(25)))
    }
  }

  private fun check(testFile: TestFile, assertions: (JavaContext, UCallExpression) -> Unit) {
    val (context, disposable) =
      parseFirst(temporaryFolder = temporaryFolder, sdkHome = TestUtils.getSdk().toFile(), testFiles = arrayOf(testFile))
    try {
      var call: UCallExpression? = null
      context.uastFile?.accept(
        object : AbstractUastVisitor() {
          override fun visitCallExpression(node: UCallExpression): Boolean {
            if (node.methodName == "reachedHere") {
              call = node
            }
            return super.visitCallExpression(node)
          }
        }
      )
      assertions(context, call ?: error("Couldn't find reachedHere() call in test file"))
    } finally {
      Disposer.dispose(disposable)
    }
  }
}
