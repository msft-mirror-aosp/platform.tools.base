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

import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.TextFormat
import com.android.tools.lint.useFirUast

class MemberExtensionConflictDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return MemberExtensionConflictDetector()
  }

  fun testDocumentationExample() {
    // Collecting multiple applicable candidates only work for K2 AA
    if (!useFirUast()) {
      return
    }
    lint()
      .files(
        kotlin(
            """
            package my.cool.lib
            interface MyList {
              val magicCount: Int
              fun removeMiddle()
            }
          """
          )
          .indented(),
        kotlin(
            """
            package users.own

            import my.cool.lib.MyList

            val MyList.magicCount: Int
              get() = 42

            fun MyList.removeMiddle() {}
          """
          )
          .indented(),
        kotlin(
            """
            import my.cool.lib.MyList
            import users.own.magicCount
            import users.own.removeMiddle

            class ListWrapper(
              private val base: MyList
            ) : MyList by base

            fun test(l : ListWrapper) {
              val x = l.magicCount // WARNING 1
              l.removeMiddle() // WARNING 2
            }
          """
          )
          .indented(),
      )
      // Some test modes change the function signature of interest
      .skipTestModes(TestMode.JVM_OVERLOADS, TestMode.TYPE_ALIAS)
      .textFormat(TextFormat.RAW)
      .run()
      .expect(
        """
src/ListWrapper.kt:10: Warning: `magicCount` is defined both as a member in class `ListWrapper` and an extension in package `users.own`. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  val x = l.magicCount // WARNING 1
            ~~~~~~~~~~
src/ListWrapper.kt:11: Warning: `removeMiddle` is defined both as a member in class `ListWrapper` and an extension in package `users.own`. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  l.removeMiddle() // WARNING 2
  ~~~~~~~~~~~~~~~~
0 errors, 2 warnings
        """
      )
  }

  fun testConflictsFromBinary() {
    // Collecting multiple applicable candidates only work for K2 AA
    if (!useFirUast()) {
      return
    }
    lint()
      .files(
        bytecode(
          "libs/lib1.jar",
          kotlin(
              """
            package my.cool.lib
            interface MyList {
              val magicCount: Int
              fun removeMiddle()
            }
            """
            )
            .indented(),
          0x30ac8af8,
          """
                META-INF/main.kotlin_module:
                H4sIAAAAAAAA/2NgYGBmYGBgBGJOBijgEuRiEOL1ySwuCS9KLChILfIu4RLm
                4iwtTi0q1ssvzxNiC0ktLvEuUWLQYgAASi63EEAAAAA=
                """,
          """
                my/cool/lib/MyList.class:
                H4sIAAAAAAAA/2VPzU7CQBic3Za2VNCCiMADGL1YJN48GYyxhsYEE2LCqbQr
                WelPwhYiN57Fgw/hwRCOPpTxK15MTDYzs/N92dn5+v74BHCJDkM9WblhlsVu
                LCeuvxpIlZtgDM5LsAzcOEin7sPkRYTkagzVqcj9YCrDfrZIcwbt9MxjqMxF
                ki2FL6MoFjtzxFAbzLI8lqnrizyIgjy4YuDJUqNgVkC5ADCwGfmvsrh1SUUX
                DDebddPmLW5zZ7O26XDHsrmlEXPrubVZ93iX3VuO0eFd8+5kWHc4Ke1p+65v
                3wyjo1u6Uyre6jE0Bv8L0k8o2E7+FCn/Ts5npO3HbDEPxa0syrSHNJeJGEkl
                J7G4TtMsD3KZpcqgBOhFCXCdoQQDIDZhFQ5aOzxGm7hPcWXasMfQPOx5qHio
                Yp8kDjw4qI3BFOo4HMNSaCgcKTR3WFIwFEzSP4O1Xny1AQAA
                """,
        ),
        bytecode(
          "libs/lib2.jar",
          kotlin(
              """
            package users.own

            import my.cool.lib.MyList

            val MyList.magicCount: Int
              get() = 42

            fun MyList.removeMiddle() {}
            """
            )
            .indented(),
          0x4bf50ed4,
          """
                META-INF/main.kotlin_module:
                H4sIAAAAAAAA/2NgYGBmYGBgBGJOBijgEuRiEOL1ySwuCS9KLChILfIu4RLm
                4iwtTi0q1ssvzxNiC0ktLvEuUWLQYgAASi63EEAAAAA=
                """,
          """
                users/own/TestKt.class:
                H4sIAAAAAAAA/3VRTW/TQBB966R2alLqhJa2AQq0gbY54BQ4IAUhUKVKFk6L
                aJVLTxtnlW7iD8m7CfTW38KZCzfEAVUc+VGI2SaCUECWZ97MzryZt/v9x5ev
                AJ6iweCNlMiVn71L/WOh9GvtgFF2wMfcj3na9w+7AxFRtsCw0Be6zfsy2stG
                qWZY2Q6TMz/KstiPZddvn4VS6dZOwLAZZnnfHwjdzblMlc/TNNNcy4zwQaYP
                RnHcYrCf61OpXpRQYlgfZjqWqT8YJ75MtchTHvtBqnNql5Fy4DIsR6ciGk77
                3/CcJ4IKGba2w6v7tmYyR4ak39rplFHGgotruE4K62Z2PZmRs/QvNQzlXCTZ
                WLRlrxeL/4ruMFQnlH+WV8KpsLbQvMc1J0YrGRfo/pkx88aAgQ0NsOjwvTSo
                Sai3y9C6OK+6F+eu5ZVca9VyrVKBsFVzvbma1bQbVtPaWPYuzilgJni2/+2D
                bdeKpYJXNBSPGdxZkTTK0fTQj4YUFPeyHu24GMpUHIySrsiPeddsXQ2ziMcd
                nksTT5P1t8QgExGkY6kkpX49wavfz0vjjrJRHol9aXrWpj2dScdMIXZhoWjE
                k1/DHGzydYqekGfmZhrV+c9Y9BofL0sekLXpwKbvIeHypAgeKuS36HeYEUdg
                DVXcmLLtTtmcCdunK1ylGS4HS39zWdi+tJvYIf+Sssu0680TFAKsBFgNaFot
                wC3cDnAH6ydgCndx7wSOwn2FDYWKwpyCrVCl8CdD0glRewMAAA==
                """,
        ),
        kotlin(
            """
            import my.cool.lib.MyList
            import users.own.magicCount
            import users.own.removeMiddle

            class ListWrapper(
              private val base: MyList
            ) : MyList by base

            fun test(l : ListWrapper) {
              val x = l.magicCount // WARNING 1
              l.removeMiddle() // WARNING 2
            }
          """
          )
          .indented(),
      )
      .textFormat(TextFormat.RAW)
      .run()
      .expect(
        """
src/ListWrapper.kt:10: Warning: `magicCount` is defined both as a member in class `ListWrapper` and an extension in package `users.own`. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  val x = l.magicCount // WARNING 1
            ~~~~~~~~~~
src/ListWrapper.kt:11: Warning: `removeMiddle` is defined both as a member in class `ListWrapper` and an extension in package `users.own`. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  l.removeMiddle() // WARNING 2
  ~~~~~~~~~~~~~~~~
0 errors, 2 warnings
        """
      )
  }

  fun testOnlyMultipleExtensions() {
    lint()
      .files(
        kotlin(
            """
            package my.cool.lib
            interface MyList {
              fun removeFirst()
            }
          """
          )
          .indented(),
        kotlin(
            """
            package another.cool.lib

            import my.cool.lib.MyList

            fun MyList.removeMiddle() {}
          """
          )
          .indented(),
        kotlin(
            """
          package users.own

          import my.cool.lib.MyList

          fun MyList.removeMiddle() {}
          """
          )
          .indented(),
        kotlin(
            """
            import my.cool.lib.MyList
            import users.own.removeMiddle // explicit

            class ListWrapper(
              private val base: MyList
            ) : MyList by base

            fun test(l : ListWrapper) {
              l.removeMiddle() // OK
            }
          """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testNullableExtensionReceiver() {
    // Collecting multiple applicable candidates only work for K2 AA
    if (!useFirUast()) {
      return
    }
    // b/406935594
    lint()
      .files(
        kotlin(
            """
            package another.pkg

            import test.pkg.Foo

            fun Foo?.bar() { this?.baz() }
          """
          )
          .indented(),
        kotlin(
            """
            package test.pkg

            import another.pkg.bar

            class Foo {
              fun bar() {}
              fun baz() {}
            }

            fun test(foo: Foo) {
              foo.bar() // Member
              (foo as? Foo?).bar() // Extension
              (foo as Foo?)?.bar() // Member
            }
          """
          )
          .indented(),
      )
      // Some test modes change the function signature of interest
      .skipTestModes(TestMode.JVM_OVERLOADS, TestMode.TYPE_ALIAS)
      .textFormat(TextFormat.RAW)
      .run()
      .expect(
        """
src/test/pkg/Foo.kt:11: Warning: `bar` is defined both as a member in class `test.pkg.Foo` and an extension in package `another.pkg`. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  foo.bar() // Member
  ~~~~~~~~~
src/test/pkg/Foo.kt:13: Warning: `bar` is defined both as a member in class `test.pkg.Foo` and an extension in package `another.pkg`. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  (foo as Foo?)?.bar() // Member
  ~~~~~~~~~~~~~~~~~~~~
0 errors, 2 warnings
        """
      )
  }

  fun testNullableToString() {
    // b/406935594
    lint()
      .files(
        kotlin(
            """
            fun test() {
              42.toString()
              // Any?.toString() extension
              (0 as Int?).toString()
              (0 as Int?)?.toString()
            }
          """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testStringBuilder() {
    // b/406991279
    lint()
      .files(
        kotlin(
            """
            fun test(p: List<Any>): String {
              val sb = StringBuilder()
              for (item in p) {
                sb.append(" | ")
                sb.append(item)
              }
              return sb.toString()
            }
          """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testUserLib_implicitImport() {
    // Collecting multiple applicable candidates only work for K2 AA
    if (!useFirUast()) {
      return
    }
    // b/427761232
    lint()
      .files(
        kotlin(
            "src/my/cool/lib/MyList.kt",
            """
            package my.cool.lib

            interface MyList {
              val magicCount: Int
              fun removeMiddle()
            }
          """,
          )
          .indented(),
        kotlin(
            "src/my/cool/lib/Utils.kt",
            """
            package my.cool.lib

            fun MyList.removeMiddle() {}
          """,
          )
          .indented(),
        kotlin(
            "src/my/cool/lib/test.kt",
            """
            package my.cool.lib
            // same package, hence implicitly imported

            private fun test(l: MyList) {
              l.removeMiddle() // WARNING
            }
          """,
          )
          .indented(),
      )
      .run()
      .expect(
        """
src/my/cool/lib/test.kt:5: Warning: removeMiddle is defined both as a member in class my.cool.lib.MyList and an extension in package my.cool.lib. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  l.removeMiddle() // WARNING
  ~~~~~~~~~~~~~~~~
0 errors, 1 warning
        """
      )
  }

  fun testKotlinCollection_implicitImport() {
    // b/427761232
    lint()
      .files(
        kotlin(
            """
            fun test() {
              val set = mutableSetOf<String>()
              set.add("hi")
              set.remove("hi") // Member
            }
          """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testKotlinCollection_randomImport() {
    // b/427761232
    lint()
      .files(
        kotlin(
            """
            package another.pkg

            class Foo

            fun Foo?.bar() { this?.baz() }
          """
          )
          .indented(),
        kotlin(
            """
            import another.pkg.bar // random import

            fun test() {
              val set = mutableSetOf<String>()
              set.add("hi")
              set.remove("hi") // Member
            }
          """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testKotlinCollection_explicitImport() {
    // Collecting multiple applicable candidates only work for K2 AA
    if (!useFirUast()) {
      return
    }
    // b/427761232
    lint()
      .files(
        kotlin(
            """
            import kotlin.collections.remove // technically unused yet explicit import

            fun test() {
              val set = mutableSetOf<String>()
              set.add("hi")
              set.remove("hi") // Member
            }
          """
          )
          .indented()
      )
      .run()
      .expect(
        """
src/test.kt:6: Warning: remove is defined both as a member in class kotlin.collections.MutableSet and an extension in package kotlin.collections. The defined behavior for this is to use the member, but since the extension is explicitly imported into this file, there's a chance that this was not expected. (One common way this happens is for members to be added to a class after code was already written to use an extension). [MemberExtensionConflict]
  set.remove("hi") // Member
  ~~~~~~~~~~~~~~~~
0 errors, 1 warning
        """
      )
  }

  fun testKotlinCollection_explicitImportAlias() {
    // b/427761232
    lint()
      .files(
        kotlin(
            """
            import kotlin.collections.remove as extRemove

            fun test() {
              val set = mutableSetOf<String>()
              set.add("hi")
              set.extRemove("hi") // Extension
            }
          """
          )
          .indented()
      )
      .run()
      .expectClean()
  }
}
