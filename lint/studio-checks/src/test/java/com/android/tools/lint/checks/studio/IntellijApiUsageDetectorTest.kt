/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.lint.checks.studio

import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import org.junit.Test

class IntellijApiUsageDetectorTest {

  @Test
  fun testDeprecatedApiUsagesKotlin() {
    studioLint()
      .files(
        API_STATUS_ANNOTATION_STUB,
        kotlin(
            """
            package test.pkg

            import org.jetbrains.annotations.ApiStatus

            @ApiStatus.ScheduledForRemoval
            open class DeprecatedClass {
              fun foo() {}
            }

            open class DeprecatedMethod {
              @ApiStatus.ScheduledForRemoval
              open fun foo() {}

              @Deprecated
              fun deprecatedButNotForRemoval() {}
            }

            class DeprecatedMethodOverride : DeprecatedMethod {
              override fun foo() {} // ERROR
            }

            class DeprecatedKotlinProperty {
              @ApiStatus.ScheduledForRemoval
              val property: String = ""
            }

            class DeprecatedConstructor private constructor() {
              @ApiStatus.ScheduledForRemoval
              constructor(arg: String) { error(arg) }
            }

            class DeprecatedSuperClass : DeprecatedClass // ERROR

            val deprecatedType: DeprecatedClass? = null // ERROR

            class NotDeprecated {
              fun foo() {}
            }
            """
          )
          .indented(),
        kotlin(
            """
            package test.pkg

            fun test() {
              DeprecatedClass().foo() // ERROR
              DeprecatedMethod().foo() // ERROR
              DeprecatedMethod::foo // ERROR
              DeprecatedMethod().deprecatedButNotForRemoval() // OK
              DeprecatedKotlinProperty().property // ERROR
              DeprecatedConstructor("foo") // ERROR
              DeprecatedSuperClass().foo() // ERROR on foo()
              NotDeprecated().foo() // OK
            }
            """
          )
          .indented(),
      )
      .issues(IntellijApiUsageDetector.SCHEDULED_FOR_REMOVAL)
      .allowDuplicates() // For some reason, type references are visited twice sometimes.
      .run()
      .expect(
        """
        src/test/pkg/DeprecatedClass.kt:19: Warning: foo is @ScheduledForRemoval [ScheduledForRemoval]
          override fun foo() {} // ERROR
                       ~~~
        src/test/pkg/DeprecatedClass.kt:32: Warning: DeprecatedClass is @ScheduledForRemoval [ScheduledForRemoval]
        class DeprecatedSuperClass : DeprecatedClass // ERROR
                                     ~~~~~~~~~~~~~~~
        src/test/pkg/DeprecatedClass.kt:34: Warning: DeprecatedClass is @ScheduledForRemoval [ScheduledForRemoval]
        val deprecatedType: DeprecatedClass? = null // ERROR
                            ~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:4: Warning: Containing class DeprecatedClass is @ScheduledForRemoval [ScheduledForRemoval]
          DeprecatedClass().foo() // ERROR
                            ~~~
        src/test/pkg/test.kt:4: Warning: DeprecatedClass is @ScheduledForRemoval [ScheduledForRemoval]
          DeprecatedClass().foo() // ERROR
          ~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:5: Warning: foo is @ScheduledForRemoval [ScheduledForRemoval]
          DeprecatedMethod().foo() // ERROR
                             ~~~
        src/test/pkg/test.kt:6: Warning: foo is @ScheduledForRemoval [ScheduledForRemoval]
          DeprecatedMethod::foo // ERROR
          ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:8: Warning: property is @ScheduledForRemoval [ScheduledForRemoval]
          DeprecatedKotlinProperty().property // ERROR
                                     ~~~~~~~~
        src/test/pkg/test.kt:9: Warning: This constructor for DeprecatedConstructor is @ScheduledForRemoval [ScheduledForRemoval]
          DeprecatedConstructor("foo") // ERROR
          ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:10: Warning: Containing class DeprecatedClass is @ScheduledForRemoval [ScheduledForRemoval]
          DeprecatedSuperClass().foo() // ERROR on foo()
                                 ~~~
        0 errors, 10 warnings
        """
      )
  }

  @Test
  fun testDeprecatedApiUsagesJava() {
    studioLint()
      .files(
        API_STATUS_ANNOTATION_STUB,
        java(
            """
            package test.pkg;

            class DeprecatedJavaField {
              @Deprecated(forRemoval = true)
              String myField;

              @Deprecated
              void deprecatedButNotForRemoval() {}
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;

            class Test {
              void test() {
                new DeprecatedJavaField().myField; // ERROR
                new DeprecatedJavaField().deprecatedButNotForRemoval(); // OK
              }
            }
            """
          )
          .indented(),
      )
      .issues(IntellijApiUsageDetector.SCHEDULED_FOR_REMOVAL)
      .run()
      .expect(
        """
        src/test/pkg/Test.java:5: Warning: myField is @Deprecated(forRemoval=true) [ScheduledForRemoval]
            new DeprecatedJavaField().myField; // ERROR
                                      ~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  @Test
  fun testOverrideOfNonDeprecatedMethod() {
    studioLint()
      .files(
        API_STATUS_ANNOTATION_STUB,
        java(
            """
          package test.pkg;

          public abstract class LocalFileSystem {
            public void findFileByPath() {
              throw new IllegalStateException("stub");
            }
          }
          """
          )
          .indented(),
        java(
            """
          package test.pkg;

          @Deprecated(forRemoval = true)
          public abstract class LocalFileSystemBase extends LocalFileSystem {
            @Override
            public void findFileByPath() {
              throw new IllegalStateException("stub");
            }
            public void someDeprecatedMethod() {}
          }
          """
          )
          .indented(),
        java(
            """
          package test.pkg;

          @SuppressWarnings("ScheduledForRemoval")
          public class TempFileSystem extends LocalFileSystemBase {
            public static TempFileSystem getInstance() {
              throw new IllegalStateException("stub");
            }
          }
          """
          )
          .indented(),
        kotlin(
            """
          package test.pkg

          fun test() {
            var fs = TempFileSystem.getInstance()
            fs.someDeprecatedMethod() // ERROR.
            fs.findFileByPath() // OK, because the super method is not deprecated.
            (fs as LocalFileSystemBase).findFileByPath() // ERROR (for the downcast).
          }
          """
          )
          .indented(),
      )
      .issues(IntellijApiUsageDetector.SCHEDULED_FOR_REMOVAL)
      .run()
      .expect(
        """
        src/test/pkg/test.kt:5: Warning: Containing class LocalFileSystemBase is @Deprecated(forRemoval=true) [ScheduledForRemoval]
          fs.someDeprecatedMethod() // ERROR.
             ~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:7: Warning: LocalFileSystemBase is @Deprecated(forRemoval=true) [ScheduledForRemoval]
          (fs as LocalFileSystemBase).findFileByPath() // ERROR (for the downcast).
                 ~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
  }
}

private val API_STATUS_ANNOTATION_STUB =
  java(
      """
      package org.jetbrains.annotations;

      import java.lang.annotation.*;

      public final class ApiStatus {

        @Retention(RetentionPolicy.CLASS)
        @Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE })
        public @interface Experimental {}

        @Retention(RetentionPolicy.CLASS)
        @Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE })
        public @interface Internal {}

        @Retention(RetentionPolicy.CLASS)
        @Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE })
        public @interface Obsolete {
          String since() default "";
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE })
        public @interface ScheduledForRemoval {
          String inVersion() default "";
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE })
        public @interface AvailableSince {
          String value();
        }

        @Retention(RetentionPolicy.CLASS)
        @Target({ ElementType.TYPE, ElementType.METHOD })
        public @interface NonExtendable {}

        @Retention(RetentionPolicy.CLASS)
        @Target({ ElementType.TYPE, ElementType.METHOD })
        public @interface OverrideOnly {}
      }
      """
    )
    .indented()
