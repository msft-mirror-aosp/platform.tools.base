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
import com.android.tools.lint.useFirUast

class MemberExtensionConflictDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector? {
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
      .run()
      .expect(
        """
src/ListWrapper.kt:10: Warning: Conflict applicable candidates of member and extension: members {override val magicCount: kotlin.Int}, extensions {val my.cool.lib.MyList.magicCount: kotlin.Int
  get()} [MemberExtensionConflict]
  val x = l.magicCount // WARNING 1
            ~~~~~~~~~~
src/ListWrapper.kt:11: Warning: Conflict applicable candidates of member and extension: members {override fun removeMiddle()}, extensions {fun my.cool.lib.MyList.removeMiddle()} [MemberExtensionConflict]
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
      .run()
      .expect(
        """
src/ListWrapper.kt:10: Warning: Conflict applicable candidates of member and extension: members {override val magicCount: kotlin.Int}, extensions {val my.cool.lib.MyList.magicCount: kotlin.Int
  get()} [MemberExtensionConflict]
  val x = l.magicCount // WARNING 1
            ~~~~~~~~~~
src/ListWrapper.kt:11: Warning: Conflict applicable candidates of member and extension: members {override fun removeMiddle()}, extensions {fun my.cool.lib.MyList.removeMiddle()} [MemberExtensionConflict]
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
}
