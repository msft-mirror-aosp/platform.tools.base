/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.tools.lint.checks.infrastructure.TestFiles.getLintClassPath
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.useFirUast

class UElementAsPsiDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector? {
    return UElementAsPsiDetector()
  }

  fun testDocumentationExample() {
    lint()
        .files(
            java(
                    """
                    /* Copyright (C) 2025 The Android Open Source Project */
                    package test.pkg;
                    import com.intellij.psi.PsiClass;
                    import com.intellij.psi.PsiCallExpression;
                    import com.intellij.psi.PsiExpression;
                    import com.intellij.psi.PsiField;
                    import com.intellij.psi.PsiMethod;
                    import com.intellij.psi.util.PsiTreeUtil;
                    import com.android.tools.lint.detector.api.Detector;
                    import org.jetbrains.uast.UFile;
                    import org.jetbrains.uast.UMethod;
                    import org.jetbrains.uast.UField;
                    import com.android.tools.lint.detector.api.Category;
                    import com.android.tools.lint.detector.api.Detector;
                    import com.android.tools.lint.detector.api.Implementation;
                    import com.android.tools.lint.detector.api.Issue;
                    import com.android.tools.lint.detector.api.JavaContext;
                    import com.android.tools.lint.detector.api.Scope;
                    import com.android.tools.lint.detector.api.Severity;
                    import org.jetbrains.uast.UCallExpression;
                    import java.util.EnumSet;

                    @SuppressWarnings({"MethodMayBeStatic", "ClassNameDiffersFromFileName", "StatementWithEmptyBody", "deprecation"})
                    public class MyJavaLintDetector extends Detector {
                        public static final Issue ISSUE =
                                Issue.create(
                                        "com.android.namespaced.lint.check.FooDetector",
                                        "Wrong use of <LinearLayout>",
                                        "As described in "
                                            + "https://code.google.com/p/android/issues/detail?id=65351 blah blah blah.",
                                        Category.A11Y,
                                        3,
                                        Severity.WARNING,
                                        new Implementation(MyJavaLintDetector.class, EnumSet.of(Scope.RESOURCE_FILE, Scope.JAVA_FILE)));

                        public void testGetContainingClass(UMethod method, UField field) {
                            method.getContainingClass(); // ERROR 1
                            field.getContainingClass(); // ERROR 2
                        }
                        public void testGetContainingClass(PsiMethod method, PsiField field) {
                            method.getContainingClass(); // OK
                            field.getContainingClass(); // OK
                        }
                        public void testParents(PsiField field, UMethod method) {
                            PsiElement parent = field.getParent(); // OK
                            PsiElement parent = method.getParent(); // ERROR 3
                            PsiTreeUtil.getParentOfType(field, PsiClass.class); // OK
                            PsiTreeUtil.getParentOfType(method, PsiClass.class); // ERROR 4
                        }
                    }
          """
                )
                .indented(),
            kotlin(
                    """
                    /* Copyright (C) 2025 The Android Open Source Project */
                    package test.pkg
                    import com.intellij.psi.PsiClass
                    import com.intellij.psi.PsiCallExpression
                    import com.intellij.psi.PsiExpression
                    import com.intellij.psi.PsiField
                    import com.intellij.psi.PsiMethod
                    import com.intellij.psi.util.PsiTreeUtil
                    import com.android.tools.lint.detector.api.Category
                    import com.android.tools.lint.detector.api.Detector
                    import com.android.tools.lint.detector.api.Implementation
                    import com.android.tools.lint.detector.api.Issue
                    import com.android.tools.lint.detector.api.JavaContext
                    import com.android.tools.lint.detector.api.Scope
                    import com.android.tools.lint.detector.api.Severity
                    import org.jetbrains.uast.UMethod
                    import org.jetbrains.uast.UField

                    class MyKotlinLintDetector : Detector() {
                        fun testGetContainingClass(method: UMethod, field: UField) {
                          method.getContainingClass() // ERROR 5
                          field.getContainingClass() // ERROR 6
                        }

                        fun testGetContainingClass(method: PsiMethod, field: PsiField) {
                          method.getContainingClass() // OK
                          field.getContainingClass() // OK
                        }

                        fun testParents(field: PsiField, method: UMethod) {
                            val parent1 = field.parent // OK
                            val parent2 = method.parent // ERROR 7
                            PsiTreeUtil.getParentOfType(field, PsiClass::class.java) // OK
                            PsiTreeUtil.getParentOfType(method, PsiClass::class.java) // ERROR 8
                        }

                        companion object {
                            private val IMPLEMENTATION =
                                Implementation(
                                    MyKotlinLintDetector::class.java,
                                    Scope.JAVA_FILE_SCOPE
                                )

                            val ISSUE =
                                Issue.create(
                                    id = "badlyCapitalized id",
                                    briefDescription = "checks MyLintDetector.",
                                    explanation = ""${'"'}
                                        Some description here.
                                        Here's a call: foo.bar.baz(args).
                                        This line continuation is okay. \
                                        But this one is missing a space.\
                                        Okay?
                                        ""${'"'}.trimIndent(),
                                    category = Category.INTEROPERABILITY_KOTLIN,
                                    priority = 4,
                                    severity = Severity.WARNING,
                                    implementation = IMPLEMENTATION
                                )
                        }
                    }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/test/pkg/MyJavaLintDetector.java:37: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        method.getContainingClass(); // ERROR 1
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/test/pkg/MyJavaLintDetector.java:38: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        field.getContainingClass(); // ERROR 2
        ~~~~~~~~~~~~~~~~~~~~~~~~~~
src/test/pkg/MyJavaLintDetector.java:46: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        PsiElement parent = method.getParent(); // ERROR 3
                            ~~~~~~~~~~~~~~~~~~
src/test/pkg/MyJavaLintDetector.java:48: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        PsiTreeUtil.getParentOfType(method, PsiClass.class); // ERROR 4
                                    ~~~~~~
src/test/pkg/MyKotlinLintDetector.kt:21: Warning: Do not use UElement as PsiElement [UElementAsPsi]
      method.getContainingClass() // ERROR 5
      ~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/test/pkg/MyKotlinLintDetector.kt:22: Warning: Do not use UElement as PsiElement [UElementAsPsi]
      field.getContainingClass() // ERROR 6
      ~~~~~~~~~~~~~~~~~~~~~~~~~~
src/test/pkg/MyKotlinLintDetector.kt:32: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        val parent2 = method.parent // ERROR 7
                      ~~~~~~~~~~~~~
src/test/pkg/MyKotlinLintDetector.kt:34: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        PsiTreeUtil.getParentOfType(method, PsiClass::class.java) // ERROR 8
                                    ~~~~~~
0 errors, 8 warnings
        """
        )
  }

  fun testArgumentPass() {
    lint()
        .files(
            java(
                    """
            package pkg.j;

            import org.jetbrains.uast.UClass;
            import com.intellij.psi.PsiClass;

            class JClass {
                void processUClass(UClass uClass) {
                    processPsiClass(uClass); // ERROR 1
                }

                void processPsiClass(PsiClass psiClass) {
                }
            }
          """
                )
                .indented(),
            kotlin(
                    """
            package pkg.k

            import org.jetbrains.uast.UClass
            import com.intellij.psi.PsiClass

            fun processUClass(uClass: UClass) {
              processPsiClass(uClass) // ERROR 2
            }

            fun processPsiClass(psiClass: PsiClass) { }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/pkg/j/JClass.java:8: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        processPsiClass(uClass); // ERROR 1
                        ~~~~~~
src/pkg/k/test.kt:7: Warning: Do not use UElement as PsiElement [UElementAsPsi]
  processPsiClass(uClass) // ERROR 2
                  ~~~~~~
0 errors, 2 warnings
        """
        )
  }

  fun testAssignment() {
    lint()
        .files(
            java(
                    """
            package pkg.j;

            import org.jetbrains.uast.UClass;
            import com.intellij.psi.PsiClass;

            class JClass {
                PsiClass psiClass = null;

                void foo(UClass uClass) {
                    psiClass = uClass; // ERROR 1
                    PsiClass local = uClass; // ERROR 2
                }
            }
          """
                )
                .indented(),
            kotlin(
                    """
            package pkg.k

            import org.jetbrains.uast.UClass
            import com.intellij.psi.PsiClass

            class Klass {
              var psiClass: PsiClass? = null

              fun foo(uClass: UClass) {
                psiClass = uClass // ERROR 3
                val local: PsiClass = uClass // ERROR 4
              }
            }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/pkg/j/JClass.java:10: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        psiClass = uClass; // ERROR 1
                   ~~~~~~
src/pkg/j/JClass.java:11: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        PsiClass local = uClass; // ERROR 2
                         ~~~~~~
src/pkg/k/Klass.kt:10: Warning: Do not use UElement as PsiElement [UElementAsPsi]
    psiClass = uClass // ERROR 3
               ~~~~~~
src/pkg/k/Klass.kt:11: Warning: Do not use UElement as PsiElement [UElementAsPsi]
    val local: PsiClass = uClass // ERROR 4
                          ~~~~~~
0 errors, 4 warnings
        """
        )
  }

  fun testCast() {
    lint()
        .files(
            java(
                    """
            package pkg.j;

            import org.jetbrains.uast.UElement;
            import org.jetbrains.uast.UClass;
            import com.intellij.psi.PsiClass;

            class JClass {
                void foo(UElement uElement) {
                    if (uElement instanceof PsiClass) { // ERROR 1
                        PsiClass psiClass = (PsiClass) uElement; // ERROR 2
                    }
                    if (uElement instanceof UClass) {
                        UClass uClass = (UClass) uElement; // OK
                    }
                }
            }
          """
                )
                .indented(),
            kotlin(
                    """
            package pkg.k

            import org.jetbrains.uast.UElement
            import org.jetbrains.uast.UClass
            import com.intellij.psi.PsiClass

            fun foo(uElement: UElement) {
              when (uElement) {
                is UClass -> {
                  val uClass = uElement // OK
                }
                is PsiClass -> { // ERROR 3 // TODO: switch subject type v.s. type check
                  val psiClass = uElement // ERROR 4 // TODO: smart cast
                }
              }
              val anotherPsi = uElement as? PsiClass // ERROR 5
            }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/pkg/j/JClass.java:9: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        if (uElement instanceof PsiClass) { // ERROR 1
            ~~~~~~~~
src/pkg/j/JClass.java:10: Warning: Do not use UElement as PsiElement [UElementAsPsi]
            PsiClass psiClass = (PsiClass) uElement; // ERROR 2
                                           ~~~~~~~~
src/pkg/k/test.kt:16: Warning: Do not use UElement as PsiElement [UElementAsPsi]
  val anotherPsi = uElement as? PsiClass // ERROR 5
                   ~~~~~~~~
0 errors, 3 warnings
        """
        )
  }

  fun testCallParent() {
    lint()
        .files(
            java(
                    """
            package pkg.j;

            import org.jetbrains.uast.UClass;
            import org.jetbrains.uast.UMethod;
            import com.intellij.psi.PsiElement;
            import com.intellij.psi.PsiClass;

            class UClassImpl implements UClass {
                @Override
                public PsiElement getParent() { return null; }
            }

            class JClass {
                void foo(UClass uClass) {
                    uClass.getParent(); // ERROR 1
                    UClassImpl impl = new UClassImpl();
                    impl.getParent(); // ERROR 2
                }

                void bar(UMethod uMethod) {
                    PsiClass parent = uMethod.getContainingClass(); // ERROR 3
                }
            }
          """
                )
                .indented(),
            kotlin(
                    """
            package pkg.k

            import org.jetbrains.uast.UClass
            import org.jetbrains.uast.UMethod
            import com.intellij.psi.PsiElement
            import com.intellij.psi.PsiClass

            class UClassImpl : UClass {
              override fun getParent(): PsiElement? = null
            }

            fun foo(uClass: UClass) {
              uClass.getParent() // ERROR 4
              val impl = UClassImpl()
              impl.getParent() // ERROR 5
            }

            fun bar(uMethod: UMethod) {
              val parent = uMethod.getContainingClass() // ERROR 6
            }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/pkg/j/UClassImpl.java:15: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        uClass.getParent(); // ERROR 1
        ~~~~~~~~~~~~~~~~~~
src/pkg/j/UClassImpl.java:17: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        impl.getParent(); // ERROR 2
        ~~~~~~~~~~~~~~~~
src/pkg/j/UClassImpl.java:21: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        PsiClass parent = uMethod.getContainingClass(); // ERROR 3
                          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/pkg/k/UClassImpl.kt:13: Warning: Do not use UElement as PsiElement [UElementAsPsi]
  uClass.getParent() // ERROR 4
  ~~~~~~~~~~~~~~~~~~
src/pkg/k/UClassImpl.kt:15: Warning: Do not use UElement as PsiElement [UElementAsPsi]
  impl.getParent() // ERROR 5
  ~~~~~~~~~~~~~~~~
src/pkg/k/UClassImpl.kt:19: Warning: Do not use UElement as PsiElement [UElementAsPsi]
  val parent = uMethod.getContainingClass() // ERROR 6
               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
0 errors, 6 warnings
        """
        )
  }

  fun testArrays() {
    lint()
        .files(
            java(
                    """
            package pkg.j;

            import org.jetbrains.uast.UClass;
            import com.intellij.psi.PsiClass;

            class JClass {
                UClass[] getClasses() { return null; }

                void foo() {
                    PsiClass[] classes = getClasses(); // ERROR 1
                }
            }
          """
                )
                .indented(),
            kotlin(
                    """
            package pkg.k

            import org.jetbrains.uast.UClass
            import com.intellij.psi.PsiClass

            fun getClasses(): Array<UClass> {
              return emptyArrayOf<UClass>()
            }

            fun foo() {
              val classes: Array<PsiClass> = getClasses() // ERROR 2
            }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/pkg/j/JClass.java:10: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        PsiClass[] classes = getClasses(); // ERROR 1
                             ~~~~~~~~~~~~
src/pkg/k/test.kt:11: Warning: Do not use UElement as PsiElement [UElementAsPsi]
  val classes: Array<PsiClass> = getClasses() // ERROR 2
                                 ~~~~~~~~~~~~
0 errors, 2 warnings
        """
        )
  }

  fun testReturn() {
    lint()
        .files(
            java(
                    """
            package pkg.j;

            import org.jetbrains.uast.UClass;
            import com.intellij.psi.PsiClass;

            class JClass {
                PsiClass foo(UClass uClass) {
                    return uClass; // ERROR 1
                }
            }
          """
                )
                .indented(),
            kotlin(
                    """
            package pkg.k

            import org.jetbrains.uast.UClass
            import com.intellij.psi.PsiClass

            fun foo(uClass: UClass): PsiClass {
              return uClass // ERROR 2
            }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/pkg/j/JClass.java:8: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        return uClass; // ERROR 1
               ~~~~~~
src/pkg/k/test.kt:7: Warning: Do not use UElement as PsiElement [UElementAsPsi]
  return uClass // ERROR 2
         ~~~~~~
0 errors, 2 warnings
        """
        )
  }

  fun testNestedMethods() {
    lint()
        .files(
            java(
                    """
            package pkg.j;

            import org.jetbrains.uast.UClass;
            import com.intellij.psi.PsiClass;

            class JClass {
              void method(UClass uClass) {
                new Runnable() {
                  public void run() {
                    new Runnable() {
                      public void run() {
                        new Runnable() {
                          public void run() {
                            uClass.getParent(); // ERROR 1
                          }
                        };
                      }
                    };
                  }
                };
              }
            }
          """
                )
                .indented(),
            kotlin(
                    """
            package pkg.k

            import org.jetbrains.uast.UClass
            import com.intellij.psi.PsiClass

            fun foo(uClass: UClass) {
              Runnable {
                Runnable {
                  Runnable {
                    uClass.getParent() // ERROR 2
                  }
                }
              }
            }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expect(
            """
src/pkg/j/JClass.java:14: Warning: Do not use UElement as PsiElement [UElementAsPsi]
                uClass.getParent(); // ERROR 1
                ~~~~~~~~~~~~~~~~~~
src/pkg/k/test.kt:10: Warning: Do not use UElement as PsiElement [UElementAsPsi]
        uClass.getParent() // ERROR 2
        ~~~~~~~~~~~~~~~~~~
0 errors, 2 warnings
        """
        )
  }

  fun testModifierListOwner() {
    lint()
        .files(
            kotlin(
                    """
            import org.jetbrains.uast.UClass
            import com.intellij.psi.PsiModifierListOwner
            import com.intellij.psi.PsiClass

            // E.g., isOpen, isInfix, isVararg, isExpect, etc.
            fun hasModifier(owner: PsiModifierListOwner?, keyword: String): Boolean = TODO()

            fun testU(uClass: UClass) {
              hasModifier(uClass)
              hasModifier(uClass.javaPsi)
            }

            fun testP(psiClass: PsiClass) {
              hasModifier(psiClass)
            }
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expectClean()
  }

  fun testHasAnnotationOnUDeclaration() {
    // http://yaqs/2860404788426702848
    lint()
        .files(
            kotlin(
                    """
            import org.jetbrains.uast.UDeclaration

            // UDeclaration overrides hasAnnotation
            fun UDeclaration.hasInjectAnnotation() =
              hasAnnotation("javax.inject.Inject") || this.hasAnnotation("com.google.apps.framework.request.Rpc")
          """
                )
                .indented(),
            *getLintClassPath(),
        )
        .run()
        .expectClean()
  }

  fun testContractLambda() {
    // b/463436546
    // TODO: after https://youtrack.jetbrains.com/issue/KT-82846
    if (useFirUast()) {
      return
    }
    lint()
        .files(
            kotlin(
                    """
            import kotlin.contracts.ExperimentalContracts
            import kotlin.contracts.InvocationKind
            import kotlin.contracts.contract

            interface MyDeferred<T> {
              suspend fun await(): T
            }

            abstract class MyException : Exception() {
              abstract fun isInternal(): Boolean
            }

            @OptIn(ExperimentalContracts::class)
            suspend fun <T> MyDeferred<T>.safeAwait(
              fallbackOnAbort: suspend () -> T,
              onCancelled: suspend (MyException) -> T = { throw it },
            ) : T {
              contract {
                callsInPlace(fallbackOnAbort, InvocationKind.AT_MOST_ONCE)
                callsInPlace(onCancelled, InvocationKind.AT_MOST_ONCE)
              }
              return try {
                await()
              } catch (e: MyException) {
                if (e.isInternal()) {
                  fallbackOnAbort()
                } else {
                  onCancelled(e)
                }
              }
            }
          """
                )
                .indented()
        )
        .run()
        .expectClean()
  }
}
