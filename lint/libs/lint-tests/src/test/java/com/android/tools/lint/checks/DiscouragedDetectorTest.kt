/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.rClass
import com.android.tools.lint.detector.api.Detector

class DiscouragedDetectorTest : AbstractCheckTest() {

  private val discouragedAnnotationStub =
    kotlin(
        "src/androidx/annotation/Discouraged.kt",
        """
        /* HIDE-FROM-DOCUMENTATION */
        package androidx.annotation
        @Retention(AnnotationRetention.SOURCE)
        @Target(
            AnnotationTarget.CONSTRUCTOR,
            AnnotationTarget.FIELD,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.VALUE_PARAMETER,
            AnnotationTarget.ANNOTATION_CLASS,
            AnnotationTarget.CLASS
        )
        annotation class Discouraged(
            val message: String
        )
        """,
      )
      .indented()

  private val resourcesStub =
    java(
        "src/android/content/res/Resources.java",
        """
            /* HIDE-FROM-DOCUMENTATION */
            package android.content.res;

            import android.util.TypedValue;
            import androidx.annotation.Discouraged;

            public class Resources {

                @Discouraged(message="Use of this function is discouraged. It is more efficient "
                                   + "to retrieve resources by identifier than by name.\n"
                                   + "See `getValue(int id, TypedValue outValue, boolean "
                                   + "resolveRefs)`.")
                public int getValue(String name, TypedValue outValue, boolean resolveRefs) { }

                public int getValue(int id, TypedValue outValue, boolean resolveRefs) { }
            }
        """,
      )
      .indented()

  fun testDocumentationExample() {
    val expected =
      """
            src/test/pkg/Test1.java:9: Warning: Use of this function is discouraged. It is more efficient to retrieve resources by identifier than by name.
            See getValue(int id, TypedValue outValue, boolean resolveRefs). [DiscouragedApi]
                    Resources.getValue("name", testValue, false);
                              ~~~~~~~~
            0 errors, 1 warnings
            """
    lint()
      .files(
        java(
            """
                package test.pkg;

                import android.content.res.Resources;
                import android.util.TypedValue;

                public class Test1 {
                    public void setValue() {
                        TypedValue testValue;
                        Resources.getValue("name", testValue, false);
                        Resources.getValue(0, testValue, false);
                    }
                }
                """
          )
          .indented(),
        resourcesStub,
        discouragedAnnotationStub,
      )
      .run()
      .expect(expected)
  }

  fun test205800560() {
    lint()
      .files(
        kotlin(
            """
                package test.pkg

                import android.app.Activity
                import android.os.Bundle
                import android.widget.TextView
                import androidx.annotation.Discouraged
                import java.util.UUID

                class MainActivity : Activity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                        findViewById<TextView>(R.id.text)?.text = getSomeString()
                    }

                    companion object {
                        @Discouraged(message = "don't use this")
                        fun getSomeString(): String {
                            return UUID.randomUUID().toString()
                        }
                    }
                }
                """
          )
          .indented(),
        rClass("test.pkg", "@layout/activity_main", "@id/text"),
        discouragedAnnotationStub,
      )
      .allowDuplicates()
      .run()
      .expect(
        """
            src/test/pkg/MainActivity.kt:13: Warning: don't use this [DiscouragedApi]
                    findViewById<TextView>(R.id.text)?.text = getSomeString()
                                                              ~~~~~~~~~~~~~
            0 errors, 1 warnings
            """
      )
  }

  fun testDiscouragedAttributes() {
    lint()
      .files(
        manifest(
            """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="test.pkg">
                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                        <activity
                            android:maxAspectRatio="1.0"
                            android:screenOrientation="portrait"
                            android:resizeableActivity="false" >
                        </activity>
                        <activity
                            android:resizeableActivity="true" >
                        </activity>
                    </application>
                </manifest>
          """
          )
          .indented()
      )
      .allowDuplicates()
      .run()
      .expect(
        """
        AndroidManifest.xml:7: Warning: Minimum and maximum aspect ratios will be ignored in most cases, starting from Android 16. Android is moving toward a model where apps are expected to adapt to various orientations, display sizes, and aspect ratios. [DiscouragedApi]
                    android:maxAspectRatio="1.0"
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        AndroidManifest.xml:8: Warning: Fixed screen orientations will be ignored in most cases, starting from Android 16. Android is moving toward a model where apps are expected to adapt to various orientations, display sizes, and aspect ratios. [DiscouragedApi]
                    android:screenOrientation="portrait"
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        AndroidManifest.xml:9: Warning: Setting resizeableActivity to false will be ignored in most cases, starting from Android 16. Android is moving toward a model where apps are expected to adapt to various orientations, display sizes, and aspect ratios. [DiscouragedApi]
                    android:resizeableActivity="false" >
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 3 warnings
            """
      )
  }

  fun testScheduleAtFixedRate() {
    lint()
      .files(
        kotlin(
            """
            package com.pkg

            import java.time.Instant
            import java.util.Date
            import java.util.Timer
            import java.util.TimerTask
            import java.util.concurrent.ScheduledExecutorService
            import java.util.concurrent.TimeUnit

            class Main {
              fun bar(): TimerTask {
                TODO()
              }

              fun foo(executor: ScheduledExecutorService, timer: Timer) {
                executor.scheduleAtFixedRate({}, 10, 30, TimeUnit.SECONDS)
                timer.scheduleAtFixedRate(bar(), 10, 30)
                timer.scheduleAtFixedRate(bar(), Date.from(Instant.EPOCH), 30)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/pkg/Main.kt:16: Warning: Use of scheduleAtFixedRate is strongly discouraged because it can lead to unexpected behavior when Android processes become cached (tasks may unexpectedly execute hundreds or thousands of times in quick succession when a process changes from cached to uncached); prefer using scheduleWithFixedDelay [DiscouragedApi]
            executor.scheduleAtFixedRate({}, 10, 30, TimeUnit.SECONDS)
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/pkg/Main.kt:17: Warning: Use of scheduleAtFixedRate is strongly discouraged because it can lead to unexpected behavior when Android processes become cached (tasks may unexpectedly execute hundreds or thousands of times in quick succession when a process changes from cached to uncached); prefer using schedule [DiscouragedApi]
            timer.scheduleAtFixedRate(bar(), 10, 30)
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/pkg/Main.kt:18: Warning: Use of scheduleAtFixedRate is strongly discouraged because it can lead to unexpected behavior when Android processes become cached (tasks may unexpectedly execute hundreds or thousands of times in quick succession when a process changes from cached to uncached); prefer using schedule [DiscouragedApi]
            timer.scheduleAtFixedRate(bar(), Date.from(Instant.EPOCH), 30)
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 3 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/pkg/Main.kt line 16: Replace with scheduleWithFixedDelay:
        @@ -16 +16 @@
        -    executor.scheduleAtFixedRate({}, 10, 30, TimeUnit.SECONDS)
        +    executor.scheduleWithFixedDelay({}, 10, 30, TimeUnit.SECONDS)
        Fix for src/com/pkg/Main.kt line 17: Replace with schedule:
        @@ -17 +17 @@
        -    timer.scheduleAtFixedRate(bar(), 10, 30)
        +    timer.schedule(bar(), 10, 30)
        """
      )
  }

  fun testDiscouragedClassXmlReference() {
    lint()
      .files(
        kotlin(
            "src/com/pkg/Button.kt",
            """
            package com.pkg
            import androidx.annotation.Discouraged
            @Discouraged(message="Don't use this class")
            open class Button
            open class ToggleButton : Button // WARN 1: Referencing discouraged super class
            """,
          )
          .indented(),
        xml(
            "res/layout/activity_main.xml",
            """
            <merge>
                <com.pkg.Button/> <!-- WARN 2: Directly annotated -->
                <com.pkg.ToggleButton/> <!-- WARN 3: Superclass annotated -->
            </merge>
            """,
          )
          .indented(),
        discouragedAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/pkg/Button.kt:5: Warning: Don't use this class [DiscouragedApi]
        open class ToggleButton : Button // WARN 1: Referencing discouraged super class
                                  ~~~~~~
        res/layout/activity_main.xml:2: Warning: Don't use this class [DiscouragedApi]
            <com.pkg.Button/> <!-- WARN 2: Directly annotated -->
             ~~~~~~~~~~~~~~
        res/layout/activity_main.xml:3: Warning: Don't use this class [DiscouragedApi]
            <com.pkg.ToggleButton/> <!-- WARN 3: Superclass annotated -->
             ~~~~~~~~~~~~~~~~~~~~
        0 errors, 3 warnings
        """
      )
  }

  fun testNested() {
    // Referencing a class that has an outer class that is discouraged
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.app.Activity
            import androidx.annotation.Discouraged

            @Discouraged(message="Don't use this")
            open class Private {
                class MyActivity : Activity()
            }
            open class OkActivity : Activity()
            """
          )
          .indented(),
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="test.pkg" >
                <application>
                    <activity android:name="test.pkg.OkActivity"/>              <!-- OK -->
                    <activity android:name="test.pkg.Private＄MyActivity"/>     <!-- WARN -->
                </application>
            </manifest>
            """
          )
          .indented(),
        discouragedAnnotationStub,
      )
      .run()
      .expect(
        """
        AndroidManifest.xml:5: Warning: Don't use this [DiscouragedApi]
                <activity android:name="test.pkg.Private＄MyActivity"/>     <!-- WARN -->
                                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testDiscouragedManifestClassReference() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import android.app.Activity;

            import androidx.annotation.Discouraged;

            public class Private {
                @Discouraged(message="Don't use this")
                public static class MyActivity extends Activity {
                }
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;

            public class MyInheritedActivity extends Private.MyActivity { // WARN 5
            }
            """
          )
          .indented(),
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <!-- package="test.pkg" -->
                <application>
                    <activity android:name="test.pkg.OkActivity"/>              <!-- OK -->
                    <activity android:name="test.pkg.Private＄MyActivity"/>     <!-- WARN 1 -->
                    <activity android:name="test.pkg.MyInheritedActivity"/>     <!-- WARN 2 -->
                    <activity android:name=".Private＄MyActivity"/>             <!-- WARN 3 -->
                    <activity android:name=".MyInheritedActivity"/>             <!-- WARN 4 -->
                </application>
            </manifest>
            """
          )
          .indented(),
        kts(
            """
              android {
                  namespace = "test.pkg"
              }
              """
          )
          .indented(),
        // Using Gradle, so we need to move stub to the right source set, src/main/java rather
        // than
        // src/
        gradleSourceSet(discouragedAnnotationStub),
      )
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:5: Warning: Don't use this [DiscouragedApi]
                <activity android:name="test.pkg.Private＄MyActivity"/>     <!-- WARN 1 -->
                                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/main/AndroidManifest.xml:6: Warning: Don't use this [DiscouragedApi]
                <activity android:name="test.pkg.MyInheritedActivity"/>     <!-- WARN 2 -->
                                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/main/AndroidManifest.xml:7: Warning: Don't use this [DiscouragedApi]
                <activity android:name=".Private＄MyActivity"/>             <!-- WARN 3 -->
                                        ~~~~~~~~~~~~~~~~~~~
        src/main/AndroidManifest.xml:8: Warning: Don't use this [DiscouragedApi]
                <activity android:name=".MyInheritedActivity"/>             <!-- WARN 4 -->
                                        ~~~~~~~~~~~~~~~~~~~~
        src/main/java/test/pkg/MyInheritedActivity.java:3: Warning: Don't use this [DiscouragedApi]
        public class MyInheritedActivity extends Private.MyActivity { // WARN 5
                                                 ~~~~~~~~~~~~~~~~~~
        0 errors, 5 warnings
        """
      )
  }

  fun testAnnotationOnObjectFromJar() {
    // Regression test for https://issuetracker.google.com/484887319.
    // Annotations on Kotlin objects from jars were not getting recognized.
    lint()
      .files(
        bytecode(
          "libs/library.jar",
          kotlin(
              """
              package androidx.annotation

              @Retention(AnnotationRetention.BINARY)
              @Target(
                  AnnotationTarget.CONSTRUCTOR,
                  AnnotationTarget.FIELD,
                  AnnotationTarget.FUNCTION,
                  AnnotationTarget.PROPERTY_GETTER,
                  AnnotationTarget.PROPERTY_SETTER,
                  AnnotationTarget.VALUE_PARAMETER,
                  AnnotationTarget.ANNOTATION_CLASS,
                  AnnotationTarget.CLASS,
              )
              annotation class Discouraged(
                val message: String,
              )

              abstract class FeatureFlag {
                fun featureFlagMethod(): Boolean = true

                companion object {
                  operator fun FeatureFlag.invoke(): Boolean = true
                }
              }

              @Discouraged("Do not use!")
              data object BadFeatureFlag : FeatureFlag() {
                fun badFeatureFlagMethod(): Boolean = false
              }
              """
            )
            .indented(),
          0x16d208e7,
          """
          META-INF/main.kotlin_module:
          H4sIAAAAAAAA/2NgYGBmYGBggmIw4BLkYk/Oz9VLLCgQYgtJLS7xLlFi0GIA
          ADPfzNQrAAAA
          """,
          """
          androidx/annotation/BadFeatureFlag.class:
          H4sIAAAAAAAA/4VTTW8TSRB93TO2x+MQJgkQE9glAQP5gIwTWFgJtBIfG+HI
          GERQEOTUtlv2JPYMTLcDx9z4Bxz2yIFTDqA9IIGEwqK97I9abfV4VrH5lGa6
          XtfUq1dd1fPPv+8+ALiIXxlOirAZR0HzmS/CMNJCB1HoXxfNFSl0L5YrHdHK
          gTGc+FrcUJDFkL0ahIH+jcGanVsfQQZZFzZyDLZuB4qhVP2x2hWGQ/Uhz22p
          21EzSfqIwdHRmo6DsEVxs3PVTbEt/I4IW37fS/RT1Shu+ZtS12MRhGpASvm1
          SNd6nQ5FjQ7LOvAod1uo9o2oKROxijUR/H2ZTiWf9ESHyj88OyB3p74pG/qK
          Kan0PUFSE/WOJMVMpNsyZhj/MgtJX210kua54KZjTqW2dv9a7cbvIzgON0/O
          nxhmvtq/m4FqRL1YtGSTEuW6UinCDIWb0TQFTfeUnGEYq25FmiR8aqdoCi0o
          lne3LboI3CxgYFsG0LD5s8CgMqHmEsPTvZ2Sy4u8/zqWy70CWcLe3o5LD/ec
          vhl1Pj13i3s7y7zMrucd/tfLrO1wz1rNevYUL2dWJ7ysscuOl5uyi6zs3Pr0
          wlrNe3nyuoQZ4QLhEYON/PK3Tj18Zaj60YE+LG5phmP3eqEOurISbgcqoBlc
          258LXcn+nA9Wg1DWet26jO+bOZnxRA3RWRdxYPap88CaFo2t2+Jxui99nvuu
          iEVXahkPibhrVFBDrgSGczTlrH9RDZZovnYyg6Nm3GTLtMuSPZhYbv4l2i3R
          zjeTIpuZfwvnjRkfltNgYAoXaB3pByBPqYAxFBKPIZ9Lydza/Yx5fIDJUyad
          G6O0M8yZlGmNj71OPu2HW2n4YM3UR0ykovvUQ7vfoNLPhSOpUoUsJzs5v/AK
          GXt34SP4H8hYuwt74A/sfuEXabXBc06S7EifkCYzqEidZPiFcM6ITxJwqDv/
          N3EyIQCF9+AP3+LYn/j5TeKwcCnJ7+My2XsUfpIaeWoDVgWl5DmNMxWcxWwF
          c5jfAFNYwLkNHFBwFc4rZBSyCgWFRYXDCsUEjCcrxSwmXSpQ3hP0TivM/AcG
          qurfjgUAAA==
          """,
          """
          androidx/annotation/Discouraged.class:
          H4sIAAAAAAAA/4VSy07bUBA913mZ0BJDaQuhlEcpjz4IRd21GxNMG8nEkeMg
          oSzQJbmKTBy7ip0Udtn1P7rqN3RRIZb9qKpzk0K8iIpknRnPnXPmofn95+cv
          AO+xx7DC/WY3cJuXBe77QcQjN/ALh27YCHpd3hLNDBiDdsH7vOBxv1Wwzi9E
          I8ogQdRxNMbV79wMUgyZjghDEmKY394xx4xq1HX91geGZbMdRJ7rxzVsEQlf
          evSe6nOvR/StCXnjWnFG+qBU1u1ThvwEisO7LRFR1gz3vOCraI4CIcPGfwvc
          8aaLVrnq2LWiY9nU3VHJMA8Z1KNaueiUrDJDrmJbFcN2Ts8+GY5j2PFI9TZy
          ops146yi2/qxMYxoerlsObqUOCuaerVK2v/sqjlx0fGRN+9JqQSe27gabnti
          4t1065PfDU90SMm5+iIoKemcVgzaM7X+2aLhp2Jz5GJzjNJmb/d6LCLe5BEn
          BaXTT9AFKhLAwNrSoUNTLl3p0V0qzXcMH68HWlZZULKKtpS9HpCZIaPefFMW
          rgf7yh47UOfSmpJX9hJ2bmRvvqfT+aSqaEmpsc+wZt5z4dQOtTATi+y2I4Zs
          lf4a4sj16PgW7R7tsSNO3NA998T4LMJNKoMkCaTlBFQ0A1VOht0hvkWB7A+k
          MEU5WYFpPMBDWa8OVSAHTcKshDkJjyTMS3gs4YmEp1gYcRfJzUu3jrTAEp5J
          mJWwLOG5BE3CClap4lodiRLWh98LbJTwEpslbGG7DhZiB6/qSIV4HeJNiMwQ
          1b+zr8K9GgQAAA==
          """,
          """
          androidx/annotation/FeatureFlag＄Companion.class:
          H4sIAAAAAAAA/5VTS08UQRD+umdfDK/l/VBBZJVdEGZBbxB8QIhrFjRiOMjB
          NLPt0uxsj5np3XDkxA/x7IWTxoMhHP1RxupleWhM0GTSVf11fV9VV9f8+Pnt
          O4DHeMRQELoShapy6AmtQyOMCrW3IYVpRHIjENXcWlj/KDShaTCG7IFoCi8Q
          uuq92juQvknDYUitKK3MKoOTL+x0IYmUiwTSDAmzr2KGufI/Z1kmNaWbYU0y
          5PI38ZYL7ximy2FU9Q6k2YuE0vG10NjbCs1WIwis6oqtZTWDHoaJWmgCpb2D
          Zt1T2shIi8AraRMRXflxGlmGIX9f+rU2/7WIRF1SIMNMvvxnD5avIdtWhOqi
          NvRjwEUfBhm6cjZ37uJeUzdei2ExX/5bkevyg2gEZo3uZqKGb8JoU0Q1GVFC
          F9y2fCDnXx2+r7dOGRb+T42h74KwKY2oCCMI4/WmQ3PD7QIGVrMODQU/VNYr
          kldZZHhyejTo8lHu8uzpkcszDjmdFsicHTujp0dLvMied2T42adUIsOzzsvu
          bGKcF5OzvJh6cXbMrcwSsxkmb+hTGnmGjsvZYehZV7EfNiJRlZWFmqEBXAsr
          1PHestJyq1Hfk9FbsRcQ0l8OfRHsiEjZfRvMvWloo+qypJsqVgRdvvuzq5mi
          5yxpLaO1QMSxpK27TRl9uaGsxFhbYudc4BoPi/RACds6OOTRT0I3fEg7z/aS
          bHL2CzIntsGYpzXVAoewQGvXeQA64JLtQychvEVeIcvJZub6e79iKPH5N34K
          wy3+8HlMm2+9YYzQuUd+2mbvhp35JEbbNc2T5e2axk5az32lk7zUSWIct+jM
          QZF2botUwCzG6LK2jjkskX1K+G2KvbMLp4SJ1jeJuyVM4V4J08jtgsW4jwe7
          SMdwY8zESMZIxehs+SO/AFpNJlKxBAAA
          """,
          """
          androidx/annotation/FeatureFlag.class:
          H4sIAAAAAAAA/4VRXU8TQRQ9s7vdlqXS0vpRQAUEka+4QExMhBi1hKSm1EQN
          ifI07Q5l2u2s2Z1teOxv8dkX4gOJJqbx0R9lvLtUIPpAsplz7917zv369fvb
          DwBPsM4wy5UXBtI7cblSgeZaBsrdE1zHodjzeTsLxlDs8D53fa7a7ptmR7R0
          FiaDvSOV1M8ZzOWVgzwysB1YyDJY+lhGDPP1a7S3GSaPLt19oY8DL5X7yJDb
          afkj/ZVrdBarQe8TVxTNYoJhc7neDTSR3U6/50qlRai47+6KIx77uhqoSIdx
          SwfhPg+7Itw+b77ooIBJhrELMYa16ya4rLydRxk3x2DgFsNCPQjbbkfoZsil
          iq6QI7cR6Ebs+8nsf9ukwbnHNaeY0eubdBojecDAuolBFzBOZGJtkOVtMjwd
          DkqOUTEcozgcOEbOJGPcGQ5yS5XhYM7eMjbYM2a/yv78bFs5o2i+tovWtLGR
          SehbLFGe2JVRK4hD3hbe465mmHkbKy17oqb6MpJNX7y8bJpOWg08wVCoSyUa
          ca8pwvecchhK9aDF/QMeysQfBfM1pURY9XkUCSI776hQS+zJ5N/UqM7Bf1Ws
          edqelc49lSyT8CF5NuEdQpMwk3pL5LnJdggzq2fInSYrw6NRMlDCMr358wSM
          wSGcxHgaScjrI7JhffmHWb7CNEbMHG5clK0gvQzy31H4wM5Q+orbp2nExAq9
          DuUViFmmEVZTjUWsEb6geIV6mTqEWcN0+s3gbg33cL+GWcwdgkWYx4NDZCM4
          ERYiZCLYEcZTu/wHp+w1sLQDAAA=
          """,
        ),
        kotlin(
            """
            package com.app

            import androidx.annotation.Discouraged
            import androidx.annotation.BadFeatureFlag
            import androidx.annotation.FeatureFlag
            import androidx.annotation.FeatureFlag.Companion.invoke

            class Example

            @Discouraged("Do not use!")
            data object LocalBadFeatureFlag : FeatureFlag() {
              fun badFeatureFlagMethod(): Boolean = false
            }

            fun foo() {
              BadFeatureFlag
              BadFeatureFlag.toString()
              BadFeatureFlag.featureFlagMethod()
              BadFeatureFlag.badFeatureFlagMethod()
              if (BadFeatureFlag.invoke()) Unit
              if (BadFeatureFlag()) Unit
              print(BadFeatureFlag)

              LocalBadFeatureFlag
              LocalBadFeatureFlag.toString()
              LocalBadFeatureFlag.featureFlagMethod()
              LocalBadFeatureFlag.badFeatureFlagMethod()
              if (LocalBadFeatureFlag.invoke()) Unit
              if (LocalBadFeatureFlag()) Unit
              print(LocalBadFeatureFlag)
            }
            """
          )
          .indented(),
        java(
            """
            package com.app;

            import androidx.annotation.BadFeatureFlag;
            import androidx.annotation.FeatureFlag;

            public class JavaClass {
              public void foo() {
                Object a = BadFeatureFlag.class;
                Object b = BadFeatureFlag.INSTANCE;
                Object c = BadFeatureFlag.INSTANCE.toString();
                Object d = BadFeatureFlag.INSTANCE.featureFlagMethod();
                Object e = BadFeatureFlag.INSTANCE.badFeatureFlagMethod();
                Object f = FeatureFlag.Companion.invoke(BadFeatureFlag.INSTANCE);
              }
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/com/app/Example.kt:16: Warning: Do not use! [DiscouragedApi]
          BadFeatureFlag
          ~~~~~~~~~~~~~~
        src/com/app/Example.kt:17: Warning: Do not use! [DiscouragedApi]
          BadFeatureFlag.toString()
          ~~~~~~~~~~~~~~
        src/com/app/Example.kt:17: Warning: Do not use! [DiscouragedApi]
          BadFeatureFlag.toString()
                         ~~~~~~~~
        src/com/app/Example.kt:18: Warning: Do not use! [DiscouragedApi]
          BadFeatureFlag.featureFlagMethod()
          ~~~~~~~~~~~~~~
        src/com/app/Example.kt:19: Warning: Do not use! [DiscouragedApi]
          BadFeatureFlag.badFeatureFlagMethod()
          ~~~~~~~~~~~~~~
        src/com/app/Example.kt:19: Warning: Do not use! [DiscouragedApi]
          BadFeatureFlag.badFeatureFlagMethod()
                         ~~~~~~~~~~~~~~~~~~~~
        src/com/app/Example.kt:20: Warning: Do not use! [DiscouragedApi]
          if (BadFeatureFlag.invoke()) Unit
              ~~~~~~~~~~~~~~
        src/com/app/Example.kt:22: Warning: Do not use! [DiscouragedApi]
          print(BadFeatureFlag)
                ~~~~~~~~~~~~~~
        src/com/app/Example.kt:24: Warning: Do not use! [DiscouragedApi]
          LocalBadFeatureFlag
          ~~~~~~~~~~~~~~~~~~~
        src/com/app/Example.kt:25: Warning: Do not use! [DiscouragedApi]
          LocalBadFeatureFlag.toString()
          ~~~~~~~~~~~~~~~~~~~
        src/com/app/Example.kt:25: Warning: Do not use! [DiscouragedApi]
          LocalBadFeatureFlag.toString()
                              ~~~~~~~~
        src/com/app/Example.kt:26: Warning: Do not use! [DiscouragedApi]
          LocalBadFeatureFlag.featureFlagMethod()
          ~~~~~~~~~~~~~~~~~~~
        src/com/app/Example.kt:27: Warning: Do not use! [DiscouragedApi]
          LocalBadFeatureFlag.badFeatureFlagMethod()
          ~~~~~~~~~~~~~~~~~~~
        src/com/app/Example.kt:27: Warning: Do not use! [DiscouragedApi]
          LocalBadFeatureFlag.badFeatureFlagMethod()
                              ~~~~~~~~~~~~~~~~~~~~
        src/com/app/Example.kt:28: Warning: Do not use! [DiscouragedApi]
          if (LocalBadFeatureFlag.invoke()) Unit
              ~~~~~~~~~~~~~~~~~~~
        src/com/app/Example.kt:30: Warning: Do not use! [DiscouragedApi]
          print(LocalBadFeatureFlag)
                ~~~~~~~~~~~~~~~~~~~
        src/com/app/JavaClass.java:8: Warning: Do not use! [DiscouragedApi]
            Object a = BadFeatureFlag.class;
                       ~~~~~~~~~~~~~~~~~~~~
        src/com/app/JavaClass.java:9: Warning: Do not use! [DiscouragedApi]
            Object b = BadFeatureFlag.INSTANCE;
                                      ~~~~~~~~
        src/com/app/JavaClass.java:10: Warning: Do not use! [DiscouragedApi]
            Object c = BadFeatureFlag.INSTANCE.toString();
                                      ~~~~~~~~
        src/com/app/JavaClass.java:10: Warning: Do not use! [DiscouragedApi]
            Object c = BadFeatureFlag.INSTANCE.toString();
                                               ~~~~~~~~
        src/com/app/JavaClass.java:11: Warning: Do not use! [DiscouragedApi]
            Object d = BadFeatureFlag.INSTANCE.featureFlagMethod();
                                      ~~~~~~~~
        src/com/app/JavaClass.java:12: Warning: Do not use! [DiscouragedApi]
            Object e = BadFeatureFlag.INSTANCE.badFeatureFlagMethod();
                                      ~~~~~~~~
        src/com/app/JavaClass.java:12: Warning: Do not use! [DiscouragedApi]
            Object e = BadFeatureFlag.INSTANCE.badFeatureFlagMethod();
                                               ~~~~~~~~~~~~~~~~~~~~
        src/com/app/JavaClass.java:13: Warning: Do not use! [DiscouragedApi]
            Object f = FeatureFlag.Companion.invoke(BadFeatureFlag.INSTANCE);
                                                                   ~~~~~~~~
        0 errors, 24 warnings
        """
      )
  }

  private fun gradleSourceSet(kotlinFile: TestFile): TestFile {
    return kotlin("src/main/java/" + kotlinFile.targetRelativePath.removePrefix("src/"), kotlinFile.contents.trimIndent())
  }

  override fun getDetector(): Detector {
    return DiscouragedDetector()
  }
}
