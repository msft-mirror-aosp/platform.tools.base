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
    val project1 =
      project()
        .files(
          java(
              """
            package my.pkg;

            public interface MyView {
              void setBackgroundColor(int rgb);
            }
          """
            )
            .indented()
        )
    val project2 =
      project()
        .files(
          kotlin(
              """
            package another.pkg

            import my.pkg.MyView

            @JvmInline
            value class MyColor(val rgb: int) {
              companion object {
                val White = MyColor(0xffffffff.toInt())
              }
            }

            fun MyView.setBackgroundColor(c: MyColor) = TODO()

            fun colorWhite() = MyColor.White
          """
            )
            .indented()
        )
        .dependsOn(project1)
    val project3 =
      project()
        .files(
          kotlin(
              """
            package yet.another.pkg

            import another.pkg.MyColor
            import another.pkg.colorWhite
            import another.pkg.setBackgroundColor
            import my.pkg.MyView

            fun test(v: MyView, c: MyColor) {
              v.setBackgroundColor(42) // Member
              v.setBackgroundColor(c) // Extension
              v.setBackgroundColor(colorWhite()) // Extension
            }
          """
            )
            .indented()
        )
        .dependsOn(project1)
        .dependsOn(project2)
    lint().projects(project1, project2, project3).run().expectClean()
  }

  fun testValueClass_binary() {
    // b/427808171
    val project1 =
      project()
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
          )
        )
    val project2 =
      project()
        .files(
          bytecode(
            "libs/ui.jar",
            kotlin(
                """
              package another.pkg

              import my.pkg.MyView

              @JvmInline
              value class MyColor(val rgb: Int) {
                companion object {
                  val White = MyColor(0xffffffff.toInt())
                }
              }

              fun MyView.setBackgroundColor(c: MyColor) = this.setBackgroundColor(c.rgb)

              fun colorWhite() = MyColor.White
            """
              )
              .indented(),
            0x63c9e678,
            """
                META-INF/main.kotlin_module:
                H4sIAAAAAAAA/2NgYGBmYGBgBGJOBijg4uJiEGILSS0u8S7hkuDiTszLL8lI
                LdIryE4X4vStdM7PyS/yLlFi0GIAAHsJ/lI+AAAA
                """,
            """
                another/pkg/MyColor＄Companion.class:
                H4sIAAAAAAAA/5VSTU8TURQ97820MwyVlk8p8iFaFVCYQthhTLDGpEnRBEld
                sDCv0ycMnc6Qea9Ed40L/4eu3bCSuDBN3fmjjHemRY0hJi7mfpz7zr0398z3
                H1++AtjGOsOCCCN9LGP3tHXk7r2tREEUlypR+1SEfhRaYAyFE3Em3ECER+7z
                xon0tAWDIfvQD339iMFYWa3nkEHWgQmLwdTHvmJYqv2z8w71PZL65bGv5Xqg
                tg8auyrtVWWYvIJp4RrDhPA8qVTpkljyTnPII+dgDAWGzZVaK9KBH7onZ23X
                D7WMQxG4T+Rr0Ql0JQqVjjuejuI9EbdkvLNad8CTlSdL3u/iq3ZaZdj4v24M
                45eEPalFU2hBGG+fGXRrlpiRxICBtQh/4ydZmaLmJkOz151y+Cx3eKHXdbjN
                B4lt2P33xmyvu8XL7LFl8/7HLC/w/fmCMcfL5rcL1uuSYfQlJYfo1pxpZwrZ
                /jueJ37Rydj9D4tlRvFyMmuLJRtk0vMxTF8lEm3NSIWRX1oxOMPSRkuTwJWo
                Sdx8zQ/ls067IeMD0QgImahFngjqIvaTfAjmqmEo40oglJKksPMi6sSefOon
                teJ+J9R+W9Z95dPj3ZCWEZomKmySMmZyLhgU0d9FW9+jzE3uRz6z9hn2OQUc
                K2SzKWhhlWxu8AAjcMiPYzRFEvLGkGxeYPzTX1z7D6454NIvlcHEkLtOng8H
                T56nOiaEmQE4HJZEU5immoE1ypyUNIY7KOJ+OvAuHpCvED5Db68fwqhitopi
                FXO4QSHmq1jA4iGYwhJuHsJWcBSWFbIKtxRuK4wq5BRKPwH+rztqxgMAAA==
                """,
            """
                another/pkg/MyColor.class:
                H4sIAAAAAAAA/31VS3PTVhT+rvySZSUoDoRYCeWVgpMADimllEAaMKUoOKFN
                aGhIX7IjHMWy5Epyhu4y3bS/oItu6HTTDYtS2iQDM50Udv1NnU7PlRQ7dTyd
                8dxz77nn8Z3vHF3/9c+LPwBcgsPQr9uOv2a4hUatWpj7uuhYjpsCY1DW9Q29
                YOl2tXCvvG5U/BRiDMmq4S9Uywyx/KhGq8v3TJORgpiGgDRD3F8zPYYjpS6R
                pxh6fGfRd027et6sNyyyy2ujpXau8I7sjnbqbjZNa9UgcIcIxjXTNv3pAMaS
                jD5kJSjoZ5CjRHkCdl3EETLVGw3DXmU4nz+Y5mDmKMuUjKMY5EFzDMPdIO43
                HOKGw9yw+P+Gb3DD4wziHgkMh/NdypdxEqe47WniU3erEzJ60CsRwWeIwTXd
                Wys6q0bEYJzgUS/62lE02zeqnKoxSrVnLeMcRiWM47yMPN8JKDBkjK+auuVF
                oQbyWqmz71OjDxmkpl12HgdWMt5CkntfYkgEHWbIHvQi5sPQvMXdgsq4iEke
                52pYwpKEOG+hUnFsz3ebFd9xI1jiXm6GQd6LbpPFp+AaD3eDBnKDJmFfYROE
                NK9pvBChcZEvk4RZr1QMzxuhiX6wZvrGSKVBZsFWhhaO8yzlvlaxomE71iXv
                SNGpN3TbdOwU5hgu5ks1xyeHwvpGvWBSH1xbtwq3jEd60/KL7crmdLdGQxFO
                7z0J8/iQId0KxnC8W5XtbFTvAhY5xvsypsN2LjGcLjlutbBu+GVXN22voNsU
                RPfJwSvMO/5807KoMwP7Qc5u1DWbDgZd9O1dzBm+vqr7OumE+kaMXgvGlzRf
                QATXSP/Y5CfiVlglTl/sbp6ShEFBEpTdTYl+giJKgpggmSGZJNlLMia++nZm
                cHfzRP+kMMGusv6b2WxSEVRhIvZ6h+1uvvopGRfjSmJWVURSpidFRVLjg2yC
                3Xn9fSy4zSjyrKL0cBfSsUDXSx6Kcoh0SkvXp2QX+sLQdBYJkxoXk0rq1XdM
                CHN9I8QJUo5XQANBdUkRzxdqPsPQQtP2zbqh2RumZ5Yt40abTBpZ/k0xHCoR
                c/PNetlw7+tkw78Fp6JbS7pr8nOk7Fn09UptTm9EZ1mzbcMtWrrnGRRMWnSa
                bsW4bfK7XJR36UBW+mIE+kiAGHK8/YS4SqckyU9IZvkrTFLl40uyl0YrEdyu
                0anA+0YyMfYbpKe0EWBGznxdp1UODZChHTWbvzmR8ztkze9yL6Esb+NwdmAL
                qrqFY8roFk5sYeSXYC7aQXJ4M8DA+EsWBTkTIRA5gh2c7fQRW4npfYp8Tu+h
                Vndw4WmHQ6KV5FyrzI4kE50+7ST0/EQ+96g6Psvq+J8QfkAi9nR8F8IW3r6u
                Dj/hx3jIV43WFIT03+gNQw6QkvMdwuC7y0QVB3AF70bBec+4VZoDGt/BVBtR
                6J6OEPFd4K4I/C2L3Kcjd2lsG9fHhn6H9Kxr78JYUiuWFAwDvSn8dYhinYi4
                EdROVoRwdJQc3sNMZH2WOOF36ZcQltVt3OzsVxrFwKmP/yN09mtvyliXycrh
                Ft7vqC+jDv2IVPxnxGNtshNE9sx+rjK4HVGdwQe8PkJ8p5V8OMgCxH/F3TB3
                m6UEWZfoEwktx4N+A/0vMb/MtvHRc3ws7ODBc9x99h/HDLnFYAWEMnqlBfru
                cqgHCB/BJlmj3TLJh5RiZQUxDZ9q+EzD5/iCtvhSg47yCpiHClZXcNiD7MHw
                kArWaQ+jHhIekh6uBJrL9Jl7mPRwzkPew8lA2eOh18PCvzEalxy6CQAA
                """,
            """
                another/pkg/MyColorKt.class:
                H4sIAAAAAAAA/31UW08TQRT+ZktvS4ECcmkRqlKlVGELoojVByAh2VjQCKkx
                PJjpdtIu3e6SnS3KG/Gf6LMv+mDQB0PwzR9lPFMqIqCb7Jxzvjnzzbnt/vj5
                9RuABTxmGOKuF9SFb+w2asb6/qrneP6TIArGkNzhe9xwuFsznlZ2hEVoiCEt
                RbDCrUbN91pute0/05CLi0tLLxkGc6XmfoeqbIvXRXO6zDBZ8vyasSOCis9t
                VxrcpTt5YHukb3jBRstxigyj2aBuy+xF+hhiDBMNL3Bs19jZaxq2Gwjf5Y5h
                uoFPhLYlo9ApF6surEaH8Rn3eVOQI8NUrnQ+leIZZFOR1IrT5QQS6NHRjV6G
                nr/yiCLJMHAxNIaunKkODmCwG/24Qsn+K41XrfnqaaH6ztWJgVn0mgy6pZxf
                1O1AMIRy0wQNXtKjKK4yxFe95i53qZAMmdIlXtlTh2ICE8jEMY5rDOP/dY3i
                BjW/JoJ2EDOOXNiqLMsEshjTMYmbDP2lTjfWRcCrPOAUv9bcC9FQMbXE1QLK
                p6EUjTbf2EorkFadY7CODob1owNdG9V0LUZvUuvooXRv8uggrRXY90N2dHD8
                PkJ76UwyRFBXXiuE5yPJSGf71KUrFk3Gjt9q3USQ0sOx43cTBaaumqeZuKws
                FC5Fp3es2UZAfVz1qkL1xXbFRqtZEf4WrziEDJQ8iztl7tvK7oDZ5y03sJvC
                dPdsaRN0Om3Lf2abIWG6rvBXHS6lIFPf9Fq+JdZsRZHqUJRPCM6cwxw0dKkK
                kkwhjAjJ+2StkNRI9uYH4ofoy499wZCGj22/RVojlFSEZvgB6cMnniRH2ky9
                GCUuhiXVHo2UaBtOIY0xshR9hqR6op9w/TNufWi38DevYgjhIUmdrEmaowk6
                XGzffQ+PSK4RPkXR5rYRMjFtIm/iNu6YmMGsCQOFbTCV2/w24pJ+I7grMSbp
                q8KCxIjEqERYIvILKZtzMZ0EAAA=
                """,
          )
        )
    val project3 =
      project()
        .files(
          kotlin(
              """
            import another.pkg.MyColor
            import another.pkg.colorWhite
            import another.pkg.setBackgroundColor
            import my.pkg.MyView

            fun test(v: MyView, c: MyColor) {
              v.setBackgroundColor(42) // Member
              v.setBackgroundColor(c) // Extension
              v.setBackgroundColor(colorWhite()) // Extension
            }
          """
            )
            .indented()
        )
        .dependsOn(project1)
        .dependsOn(project2)
    lint()
      .projects(project1, project2, project3)
      // TODO(b/430184413)
      .allowCompilationErrors()
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
