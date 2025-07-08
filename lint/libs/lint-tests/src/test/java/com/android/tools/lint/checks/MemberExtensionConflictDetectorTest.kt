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

  fun testValueClass_source() {
    // b/427808171
    lint()
      .files(
        java(
            """
            package my.pkg;

            public interface MyView {
              void setBackgroundColor(int rgb);
            }
          """
          )
          .indented(),
        kotlin(
            """
            package another.pkg

            import my.pkg.MyView

            @JvmInline
            value class MyColor(val rgb: Int)

            fun MyView.setBackgroundColor(c: MyColor) = this.setBackgroundColor(c.rgb)
          """
          )
          .indented(),
        kotlin(
            """
            package another.pkg

            import my.pkg.MyView

            fun test(v: MyView, c: MyColor) {
              v.setBackgroundColor(42) // Member
              v.setBackgroundColor(c) // Extension
            }
          """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testValueClass_binary() {
    // b/427808171
    lint()
      .files(
        bytecode(
          "libs/view.jar",
          java(
              """
              package my.pkg;

              public interface MyView {
                void setBackgroundColor(int rgb);
              }
            """
            )
            .indented(),
          0xe4b0da78,
          """
                my/pkg/MyView.class:
                H4sIAAAAAAAA/zv1b9c+BgYGWwZOdgYmRgbe3Er9gux0fd/KsMzUcnYGFkYG
                gazEskT9nMS8dH3/pKzU5BJGBqHi1BKnxOTs9KL80rwU5/yc/CJGBhYNT80w
                Rgau4PzSouRUt8ycVEYGbog5eiAj2BgZGBmYGUCAEWgsKwMbiMXADiSZGDgA
                Eo20k4gAAAA=
                """,
        ),
        bytecode(
          "libs/ui.jar",
          kotlin(
              """
              package another.pkg

              import my.pkg.MyView

              @JvmInline
              value class MyColor(val rgb: Int)

              fun MyView.setBackgroundColor(c: MyColor) = this.setBackgroundColor(c.rgb)
            """
            )
            .indented(),
          0xd9a3302f,
          """
                META-INF/main.kotlin_module:
                H4sIAAAAAAAA/2NgYGBmYGBgBGJOBijg4uJiEGILSS0u8S7hkuDiTszLL8lI
                LdIryE4X4vStdM7PyS/yLlFi0GIAAHsJ/lI+AAAA
                """,
          """
                another/pkg/MyColor.class:
                H4sIAAAAAAAA/31U3VMbVRT/3ZtNstkssAktJQtqP7RN+GhSrLVKQQq1djEU
                hYpSfFnCTlhIdmN2w9Q3xhf9C3zwRccXX3ioMxYYO+NQ+ubf5Dieu9kkTMg4
                s3Pvueeej9/5nXP373///AvAbXzNMGg6rr9t1fO13XJ+6dsFt+LW42AM2o65
                Z+YrplPOL2/uWCU/jghDrGz5K+VNhkg2Z9BaFzIzVMQhJ8CRYJD8bdtjuFjs
                EXmaoc93V/267ZQn7WqtQnZZI1fs5Grekd2lbt18w65sWQRugGDcsx3bnw1g
                rKlIIa1AwyCDGibKErAZGRfJ1KzVLGeLYTJ7Ps35zGGWaRWXMCyCZhhGe0E8
                azgiDEeF4cL/G74pDN9ikFskMFzI9ihfxRVcFbbXiE+zXi6o6EO/QgRfJwa3
                TW97wd2yQgYlgke9SHWiGI5vlQVVY5SqZa1iAjkF45hUkRUSR54haX3TMCte
                GGooaxS7+z6de8qgNJxN91lgpeJdxIT3bYZo0GGG9HkvYr4ZWrS4V1AVtzAl
                4nzYLGFNgSRaqJVcx/PrjZLv1kNYcis3w7DoRa/JElNwT4S7TwO5R5NwprAC
                Ic0ahiiE126JZYpqLe66fsV28jt71fziXtVw6GAR7lTrYsnyzS3TN0nHq3sR
                ejJMLAmxgLLskv6ZLU6UgG9R4Bcn+1cVPswVrp3sK/RxTVa4HKU9SXuM9n7a
                uXz6/dzwyf4UL7D5dDqmcZ0XIq+P2cn+6a8xSZa06KKuyaRMTMmaokvDrMAe
                vf4xEtwmNXVR0/qEC+lYoOsnD00bIJ3W1qW09EqqGZrOMsHRJTmmxU9/YLyZ
                6zsuEZqMAE+EUElKyObNXZ+aIqaGYaBItDxuVDet+hNzs2KJbrsls7Jm1m1x
                DpV9q75Z2l0ya+FZWXUb9ZL10BaHzErD8e2qtWZ7Nt3ed6iBpm9To2kIOPVd
                JE+LnwhJopNRxEizRqe8YJr26NgfUA5I4PiS1ligjOGrwCEwQJIkao94KqHz
                +2Qt7jIvoa0f4UJ66BC6fog3tNwhLh/i7edB5k6QDN4JMDDxAMMg10MEskBw
                jBvdPnI7MT2r0OdaC7V+jJsHXQ7RdpKJdpldSQrdPp0k9GpCn2WqTkyfPv4K
                /CdEIwfjJ+CHeG9GH/1ZHKUmX+u0xsET/6C/GXKIlOQWwhDSHaJKALiLD8Lg
                oi/CKiEAjR9juoOo6Z4IEQkpcNe4eIKh+2zorowdYWZs5AWU33v2rhlLacdS
                giFgFHO2TeblkBuud7PCmyOjZfAR5kLrG8SJuEu8BF/XjzDf3a8EFgKnlPiR
                dferNWWsx2Rl8AAfd9WX1Ed+QVz6DVKkQ3aUyJ47y1USD0Oqk/gkqI/jaWD+
                BTZo3ybpEe0GuS5uIGLgUwNFA0t4TCKWDXyGzzfAPKxgdQODHlQPTzzEg3XW
                Q85D1EPMw91Ac4felYcpDxMesh6uBMo+D/3/AZO+jOv7BwAA
                """,
          """
                another/pkg/MyColorKt.class:
                H4sIAAAAAAAA/3VSXU8TQRQ9M/1k+SoFpBQFlSqlCluICSF9UUhMNpZixDQx
                PJjpdlKmu901u9Mqb43/RP+Bb+iDafDNH2W8WxpE0Ie598yde8+dc2d+/vr2
                HcATmAzzwvP1iQzMd07LPDjd910/eKFTYAyZtugJ0xVeyzxstKVN0RhDPpR6
                T9hOK/C7XnOYv+GEOzu7u28YZovVzumIqq7k+4q1XmdYrfpBy2xL3QiE8kJT
                eNRTaOUTrvm61nXdCkOuoE9UWLhJn0aaYdnxtas8s93rmMrTMvCEa1qeDohQ
                2WEKBmmxT6TtjBhfikB0JCUyrBWr16VUrkSOIpJWZb0+gQlMGhjHFMPkXzpS
                yDBkb16NIV60osIsZscxgzkS+z8Zb7vbzctBTV+bEwOzaVkMM9WR0gOpRVNo
                QWe804vRg7HIjEUGlOtEgNPhBxWhMqHmFsPhoD9nDPoGz3CD5/gQkuNpnl/J
                DPp5XmYlXubbyUyMcPzHGRv0ybDzz8l4OpFJnn/k40Yiff5pucyoajGi3WZR
                x/nqP/4K3c4YwU1H0zz2/aaM9ClP1rqdhgxei4ZLkWzVt4VbF4GK9qNg4VXX
                06ojLa+nQkWhy1d79uePUIcjvxvY8rmKahZHNfWLiiuJ2AJHHBfzWUQCSdqv
                026PPCc/VcqOnWG6tPQV8xxfovmhRDZJ+Umk8YjwrYtM8gtDpinkiIvhcTR7
                TiA1DHNsDG0Rm+SfUjxPDZeOEbNw28IdC8tYsXAX9yzcx+oxWIgCHhwjFdJH
                wsMQCyFyIdZCJEIkfwOO1BR6kwMAAA==
                """,
        ),
        kotlin(
            """
            import another.pkg.MyColor
            import another.pkg.setBackgroundColor
            import my.pkg.MyView

            fun test(v: MyView, c: MyColor) {
              v.setBackgroundColor(42) // Member
              v.setBackgroundColor(c) // Extension
            }
          """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testFunctionValueParameter() {
    // b/429730003
    lint()
      .files(
        kotlin(
            """
            @JvmInline
            value class Color(val rgb: Int) {
              companion object {
                fun argb(a: Int, r: Int, g: Int, b: Int) {}
              }
            }

            inline val Int.alpha: Int
              get() = (this shr 24) and 0xff

            inline val Int.red: Int
              get() = (this shr 16) and 0xff

            inline val Int.green: Int
              get() = (this shr 8) and 0xff

            inline val Int.blue: Int
              get() = this and 0xff

            inline fun Int.replaceAlpha(alpha: Int) =
              Color.argb(alpha, red, green, blue)
          """
          )
          .indented()
      )
      .run()
      .expectClean()
  }
}
