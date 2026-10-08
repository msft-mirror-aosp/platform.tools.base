package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Detector

class WearMaterialThemeDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return WearMaterialThemeDetector()
  }

  private val materialStub =
    kotlin(
        """
    package androidx.compose.material

    class MaterialTheme

    class Icon
    """
      )
      .indented()

  private val materialWearStub =
    kotlin(
        """
    package androidx.wear.compose.material

    class MaterialTheme
  """
      )
      .indented()

  private val material3WearStub =
    kotlin(
        """
    package androidx.wear.compose.material3

    class MaterialTheme
  """
      )
      .indented()

  private val material3Stub =
    kotlin(
        """
    package androidx.compose.material3

    class MaterialTheme

    class Icon
    """
      )
      .indented()

  fun testJava() {
    //noinspection all // Sample code
    lint()
      .files(
        materialStub,
        materialWearStub,
        manifest(
            """
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.pkg">
                            <uses-feature android:name="android.hardware.type.watch" />
                            <application
                                android:icon="@mipmap/ic_launcher"
                                android:label="@string/app_name">
                            </application>
                        </manifest>
                        """
          )
          .indented(),
        java(
            """
                package test.pkg;

                import androidx.compose.material.MaterialTheme; // ERROR
                import androidx.wear.compose.material.MaterialTheme; // OK

                public class BadImport {
                }
                """
          )
          .indented(),
      )
      .run()
      .expect(
        """
            src/test/pkg/BadImport.java:3: Error: Don't use androidx.compose.material.MaterialTheme in a Wear OS project; use androidx.wear.compose.material3.MaterialTheme instead [WearMaterialTheme]
            import androidx.compose.material.MaterialTheme; // ERROR
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
      )
  }

  fun testKotlin() {
    lint()
      .files(
        materialStub,
        materialWearStub,
        manifest(
            """
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.pkg">
                            <uses-feature android:name="android.hardware.type.watch" />
                            <application
                                android:icon="@mipmap/ic_launcher"
                                android:label="@string/app_name">
                            </application>
                        </manifest>
                        """
          )
          .indented(),
        kotlin(
            """
                import androidx.compose.material.MaterialTheme // ERROR
                import androidx.compose.material.Icon // OK
                """
          )
          .indented(),
      )
      .run()
      .expect(
        """
            src/test.kt:1: Error: Don't use androidx.compose.material.MaterialTheme in a Wear OS project; use androidx.wear.compose.material3.MaterialTheme instead [WearMaterialTheme]
            import androidx.compose.material.MaterialTheme // ERROR
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 errors, 0 warnings
            """
      )
  }

  fun testMaterial3ThemeImport() {
    lint()
      .files(
        material3Stub,
        material3WearStub,
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.pkg">
                <uses-feature android:name="android.hardware.type.watch" />
                <application
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name">
                </application>
            </manifest>
            """
          )
          .indented(),
        kotlin(
            """
            import androidx.compose.material3.MaterialTheme // ERROR
            import androidx.compose.material3.Icon // OK
            import androidx.wear.compose.material3.MaterialTheme // OK
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test.kt:1: Error: Don't use androidx.compose.material3.MaterialTheme in a Wear OS project; use androidx.wear.compose.material3.MaterialTheme instead [WearMaterialTheme]
        import androidx.compose.material3.MaterialTheme // ERROR
        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }
}
