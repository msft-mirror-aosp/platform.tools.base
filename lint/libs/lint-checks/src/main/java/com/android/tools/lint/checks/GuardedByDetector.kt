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

package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifierListOwner
import org.jetbrains.uast.UMethod

/** Check to enforce go/errorprone's [com.google.errorprone.bugpatterns.threadsafety.GuardedByChecker] check. */
class GuardedByDetector :
  AbstractGuardedByDetector(
    guardedBySimpleName = "GuardedBy",
    lockName = "lock",
    unsupportedLockTypes = UNSUPPORTED_LOCK_TYPES,
  ) {

  override val issue: Issue
    get() = ISSUE

  override fun analyzeMethod(node: UMethod, context: JavaContext) {
    // Constructors and field, instance, and class initializers are free to mutate guarded state
    // without holding the necessary locks. It is assumed that all objects (and classes) are
    // thread-local during initialization.
    // Synchronized instance methods hold the 'this' lock. Synchronized static methods hold
    // the class lock for the enclosing class.
    if (node.isConstructor) return

    val lockSet = mutableSetOf<LockReference>()
    if (node.javaPsi.hasModifier(JvmModifier.SYNCHRONIZED) || node.javaPsi.hasAnnotation("kotlin.jvm.Synchronized")) {
      node.javaPsi.containingClass?.let { containingClass ->
        lockSet.add(LockReference(containingClass, isStatic = node.isStatic))
      }
    }

    lockSet.addAll(
      node.javaPsi.findGuardedByAnnotations().mapNotNull { anno ->
        anno.value<String>("value")?.let {
          LockReference.createLockReferenceFromString(it, node, context)
        }
      }
    )

    node.accept(GuardedByLockVisitor(context, lockSet.toMutableSet(), issue, guardedBySimpleName))
  }

  @Suppress("ExternalAnnotations")
  // Like the errorprone check, support all of the various annotations named GuardedBy
  override fun PsiModifierListOwner.findGuardedByAnnotations(): List<PsiAnnotation> {
    return annotations.filter { it.simpleName == guardedBySimpleName }
  }

  @Suppress("ExternalAnnotations")
  override fun PsiModifierListOwner.hasGuardedByAnnotation(): Boolean {
    return annotations.any { it.simpleName == guardedBySimpleName }
  }

  override fun createGuardRefFromString(
    string: String,
    clazz: PsiClass,
    annotatedMember: PsiModifierListOwner,
    context: JavaContext,
  ): GuardReference? {
    return GuardedByReference.createGuardedByRefFromString(string, clazz, annotatedMember, context)
  }

  companion object {
    private val IMPLEMENTATION = Implementation(GuardedByDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    @Suppress("LintImplTextFormat", "LintImplUnexpectedDomain")
    val ISSUE: Issue =
      Issue.create(
        id = "GuardedBy",
        briefDescription = "Unguarded accesses to fields and methods with @GuardedBy annotations",
        explanation =
          """
          The GuardedBy analysis checks that fields or methods annotated with `@GuardedBy(lock)` are only \
          accessed when the specified lock is held.
          """,
        category = Category.CORRECTNESS,
        priority = 6,
        enabledByDefault = false,
        severity = Severity.ERROR,
        moreInfo = "https://errorprone.info/bugpattern/GuardedBy",
        implementation = IMPLEMENTATION,
      )

    internal val UNSUPPORTED_LOCK_TYPES =
      listOf(
        UnsupportedLock(qualifiedType = "java.util.concurrent.locks.ReadWriteLock"),
        UnsupportedLock(
          qualifiedType = "kotlinx.coroutines.sync.Mutex",
          reportMessage = "To guard on Mutexes, '@GuardedByMutex' should be used instead.",
        ),
      )
  }
}
