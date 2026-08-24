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

package com.android.tools.render.discovery

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.tooling.preview.PreviewWrapper
import androidx.compose.ui.tooling.preview.PreviewWrapperProvider
import com.android.testutils.TestUtils
import com.android.tools.render.RenderEnvironmentBootstrapper
import kotlin.io.path.absolutePathString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom non-Compose preview annotation to verify strict descriptor matching. */
annotation class CustomNonComposePreview(val name: String = "")

/** MultiPreview annotation class with two previews. */
@Preview(name = "Phone Light", widthDp = 360, heightDp = 640)
@Preview(name = "Phone Dark", widthDp = 360, heightDp = 640, uiMode = 32)
annotation class DeviceThemePreviews

/** Single preview MultiPreview annotation class. */
@Preview(name = "Tablet Landscape", widthDp = 1280, heightDp = 800) annotation class TabletPreviews

/** Nested MultiPreview annotation class combining DeviceThemePreviews and TabletPreviews. */
@DeviceThemePreviews @TabletPreviews annotation class CombinedDevicePreviews

/** MultiPreview classes with circular annotations to verify cycle termination. */
@CyclicPreviewB annotation class CyclicPreviewA

@CyclicPreviewA annotation class CyclicPreviewB

/** Mock PreviewWrapperProvider for theme wrapping. */
class SampleThemeWrapper : PreviewWrapperProvider

/** Second mock PreviewWrapperProvider for multiple wrapper testing. */
class AnotherThemeWrapper : PreviewWrapperProvider

/** MultiPreview annotation class with @PreviewWrapper attached. */
@PreviewWrapper(SampleThemeWrapper::class)
@Preview(name = "Wrapped Phone Light", widthDp = 360, heightDp = 640)
@Preview(name = "Wrapped Phone Dark", widthDp = 360, heightDp = 640, uiMode = 32)
annotation class WrappedThemePreviews

/** MultiPreview annotation class with AnotherThemeWrapper attached. */
@PreviewWrapper(AnotherThemeWrapper::class)
@Preview(name = "Another Wrapped Preview", widthDp = 400)
annotation class AnotherWrappedThemePreviews

/** Base preview for NestedMultiPreviewWithDuplicateDescendants testing. */
@Preview(name = "Base Theme Preview", widthDp = 200, heightDp = 200) annotation class BaseThemePreview

/** MultiPreview branch A referencing BaseThemePreview. */
@BaseThemePreview @Preview(name = "Branch A Preview", widthDp = 300, heightDp = 300) annotation class BranchThemePreviewA

/** MultiPreview branch B referencing BaseThemePreview. */
@BaseThemePreview @Preview(name = "Branch B Preview", widthDp = 400, heightDp = 400) annotation class BranchThemePreviewB

/** MultiPreview combining BranchThemePreviewA and BranchThemePreviewB (which both reference BaseThemePreview). */
@BranchThemePreviewA @BranchThemePreviewB annotation class NestedMultiPreviewWithDuplicateDescendants

/** Mock PreviewParameterProvider for user names. */
class SampleUserProvider : PreviewParameterProvider<String> {
  override val values = sequenceOf("Alice", "Bob")
}

/** Sample class containing composable preview methods for testing discovery. */
class SamplePreviewTarget {

  @Composable
  @Preview(
    name = "Dark Mode Preview",
    group = "Sample Group",
    apiLevel = 33,
    widthDp = 300,
    heightDp = 600,
    locale = "es",
    fontScale = 1.5f,
    showBackground = true,
    backgroundColor = 4282532676L,
    device = "spec:width=411dp,height=891dp",
    uiMode = 32,
  )
  fun SampleAnnotatedPreview() {}

  @Composable
  @Preview(name = "Light Mode", widthDp = 320, heightDp = 640)
  @Preview(name = "Dark Mode", widthDp = 360, heightDp = 720, uiMode = 32)
  fun SampleMultiPreviewMethod() {}

  @Composable @Preview(name = "Invalid Preview", widthDp = -50) fun SampleInvalidPreviewMethod() {}

  @Composable
  @Preview(name = "Valid Preview", widthDp = 300, heightDp = 600)
  @Preview(name = "Invalid Dimension Preview", widthDp = -50)
  fun SampleMixedMultiPreviewMethod() {}

  @Composable @DeviceThemePreviews fun SampleMultiPreviewAnnotatedMethod() {}

  @Composable @CombinedDevicePreviews fun SampleNestedMultiPreviewAnnotatedMethod() {}

  @Composable @DeviceThemePreviews @Preview(name = "Direct Override", widthDp = 500) fun SampleMixedDirectAndMultiPreviewMethod() {}

  @Composable @CyclicPreviewA fun SampleCyclicMultiPreviewMethod() {}

  @Composable @NestedMultiPreviewWithDuplicateDescendants fun SampleNestedMultiPreviewWithDuplicateDescendants() {}

  @Composable @BaseThemePreview @BranchThemePreviewA fun SampleDirectAndNestedSharedMultiPreviewMethod() {}

  @Composable
  @Preview(name = "Single Param Preview")
  fun SampleMethodWithPreviewParameter(@PreviewParameter(provider = SampleUserProvider::class, limit = 5) user: String) {}

  @Composable
  @Preview(name = "Default Param Preview")
  fun SampleMethodWithDefaultPreviewParameter(@PreviewParameter(provider = SampleUserProvider::class) user: String) {}

  @Composable @Preview(name = "Direct Wrapped Preview") @PreviewWrapper(SampleThemeWrapper::class) fun SampleDirectWrappedPreviewMethod() {}

  @Composable @WrappedThemePreviews fun SampleMultiPreviewWithWrapperMethod() {}

  @Composable @WrappedThemePreviews @AnotherWrappedThemePreviews fun SampleMethodWithMultipleWrappersViaMultiPreview() {}

  @Composable @PreviewWrapper(SampleThemeWrapper::class) @WrappedThemePreviews fun SampleMethodWithDirectAndMultiPreviewWrapper() {}

  @Composable @PreviewWrapper(SampleThemeWrapper::class) fun SampleComposableWrapperWithoutPreviewMethod() {}

  @Preview(name = "Non-Composable Preview") fun SampleNonComposablePreviewMethod() {}

  fun sampleMethodWithoutAnnotation() {}

  @CustomNonComposePreview(name = "Non Compose Preview") fun sampleMethodWithNonComposeAnnotation() {}

  class NestedTarget {
    @Composable @Preview(name = "Nested Preview", widthDp = 150, heightDp = 300) fun NestedPreviewMethod() {}
  }

  companion object {
    @Composable @Preview(name = "Companion Preview", fontScale = 2.0f) fun CompanionPreviewMethod() {}
  }
}

/** Sample class containing overloaded methods (one with no parameters, one with @PreviewParameter). */
class OverloadedPreviewParameterTarget {
  @Composable @Preview(name = "Overload No Param", widthDp = 100) fun OverloadedMethod() {}

  @Composable
  @Preview(name = "Overload With PreviewParameter", widthDp = 200)
  fun OverloadedMethod(@PreviewParameter(provider = SampleUserProvider::class, limit = 2) user: String) {}
}

/** Sample class containing overloaded methods where one is a valid @Composable preview and the other lacks @Composable. */
class OverloadedMixedValidityTarget {
  @Composable @Preview(name = "Valid Composable Overload", widthDp = 100) fun OverloadedPreview() {}

  @Preview(name = "Invalid Non-Composable Overload", widthDp = 200) fun OverloadedPreview(count: Int) {}
}

class PreviewDiscoveryEngineTest {

  private fun createDiscoveryEngine(block: (PreviewDiscoveryEngine) -> Unit) {
    val layoutlibPath = TestUtils.resolveWorkspacePath("prebuilts/studio/layoutlib")
    val bootstrapper =
      RenderEnvironmentBootstrapper(
        fontsPath = null,
        resourceApkPath = null,
        namespace = "com.android.tools.render.test",
        classPath = emptyList(),
        projectClassPath = emptyList(),
        layoutlibPath = layoutlibPath.absolutePathString(),
      )

    bootstrapper.bootstrap().use { renderer ->
      val engine = PreviewDiscoveryEngine(renderer.module)
      block(engine)
    }
  }

  @Test
  fun testDiscoverSingleDirectPreview() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleAnnotatedPreview"

      val result = engine.discoverAllPreviews(methodFQN).single()
      assertTrue("Validation result should be valid", result.methodValidationResult.isValid)
      val discovered = result.previews.firstOrNull()
      assertNotNull("Preview should be discovered on annotated method", discovered)
      assertEquals(methodFQN, discovered!!.methodFQN)

      val params = discovered.previewParams
      assertEquals("Dark Mode Preview", params["name"])
      assertEquals("Sample Group", params["group"])
      assertEquals("33", params["apiLevel"])
      assertEquals("300", params["widthDp"])
      assertEquals("600", params["heightDp"])
      assertEquals("es", params["locale"])
      assertEquals("1.5", params["fontScale"])
      assertEquals("true", params["showBackground"])
      assertEquals("4282532676", params["backgroundColor"])
      assertEquals("spec:width=411dp,height=891dp", params["device"])
      assertEquals("32", params["uiMode"])
    }
  }

  @Test
  fun testDiscoverNestedClassPreview() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget.NestedTarget::class.java.name}.NestedPreviewMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      assertTrue("Validation result should be valid", result.methodValidationResult.isValid)
      val discovered = result.previews.firstOrNull()
      assertNotNull("Preview should be discovered on nested class method", discovered)
      assertEquals(methodFQN, discovered!!.methodFQN)

      val params = discovered.previewParams
      assertEquals("Nested Preview", params["name"])
      assertEquals("150", params["widthDp"])
      assertEquals("300", params["heightDp"])
    }
  }

  @Test
  fun testDiscoverCompanionObjectPreview() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget.Companion::class.java.name}.CompanionPreviewMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      assertTrue("Validation result should be valid", result.methodValidationResult.isValid)
      val discovered = result.previews.firstOrNull()
      assertNotNull("Preview should be discovered on companion object method", discovered)
      assertEquals(methodFQN, discovered!!.methodFQN)

      val params = discovered.previewParams
      assertEquals("Companion Preview", params["name"])
      assertEquals("2.0", params["fontScale"])
    }
  }

  @Test
  fun testDiscoverMethodWithNonComposeAnnotationProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithNonComposeAnnotation"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Methods annotated with non-Compose annotations should produce empty previews", result.previews.isEmpty())
      assertTrue(result.methodValidationResult.hasErrors)
      assertEquals("No @Preview annotations found on method '$methodFQN'", result.methodValidationResult.errors.first().message)
    }
  }

  @Test
  fun testDiscoverMethodWithoutPreviewProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithoutAnnotation"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Methods without @Preview annotation should produce empty previews", result.previews.isEmpty())
      assertTrue(result.methodValidationResult.hasErrors)
      assertEquals("No @Preview annotations found on method '$methodFQN'", result.methodValidationResult.errors.first().message)
    }
  }

  @Test
  fun testDiscoverNonExistentMethodProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.nonExistentMethod"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Non-existent method should produce empty previews", result.previews.isEmpty())
      assertTrue(result.methodValidationResult.hasErrors)
      assertEquals(
        "Method 'nonExistentMethod' not found in class '${SamplePreviewTarget::class.java.name}'",
        result.methodValidationResult.errors.first().message,
      )
    }
  }

  @Test
  fun testDiscoverNonExistentClassProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "com.example.NonExistentClass.someMethod"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Non-existent class should produce empty previews", result.previews.isEmpty())
      assertTrue(result.methodValidationResult.hasErrors)
      assertEquals(
        "Class 'com.example.NonExistentClass' could not be found on the classpath",
        result.methodValidationResult.errors.first().message,
      )
    }
  }

  @Test
  fun testDiscoverAllMultiPreviews() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMultiPreviewMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val allDiscovered = result.previews
      assertEquals("Should discover all 2 @Preview annotations on method", 2, allDiscovered.size)

      val p0 = allDiscovered[0]
      assertEquals(methodFQN, p0.methodFQN)
      assertEquals("Light Mode", p0.previewParams["name"])
      assertEquals("320", p0.previewParams["widthDp"])
      assertEquals("640", p0.previewParams["heightDp"])

      val p1 = allDiscovered[1]
      assertEquals(methodFQN, p1.methodFQN)
      assertEquals("Dark Mode", p1.previewParams["name"])
      assertEquals("360", p1.previewParams["widthDp"])
      assertEquals("720", p1.previewParams["heightDp"])
      assertEquals("32", p1.previewParams["uiMode"])
    }
  }

  @Test
  fun testDiscoverCustomMultiPreviewClassAnnotation() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMultiPreviewAnnotatedMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals("Should discover 2 previews from @DeviceThemePreviews", 2, discovered.size)

      val p0 = discovered[0]
      assertEquals("Phone Light", p0.previewParams["name"])
      assertEquals("360", p0.previewParams["widthDp"])
      assertEquals("640", p0.previewParams["heightDp"])

      val p1 = discovered[1]
      assertEquals("Phone Dark", p1.previewParams["name"])
      assertEquals("360", p1.previewParams["widthDp"])
      assertEquals("640", p1.previewParams["heightDp"])
      assertEquals("32", p1.previewParams["uiMode"])
    }
  }

  @Test
  fun testDiscoverNestedMultiPreviewClassAnnotation() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleNestedMultiPreviewAnnotatedMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals("Should discover 3 previews from nested @CombinedDevicePreviews", 3, discovered.size)

      assertEquals("Phone Light", discovered[0].previewParams["name"])
      assertEquals("Phone Dark", discovered[1].previewParams["name"])
      assertEquals("Tablet Landscape", discovered[2].previewParams["name"])
      assertEquals("1280", discovered[2].previewParams["widthDp"])
    }
  }

  @Test
  fun testDiscoverMixedDirectAndMultiPreview() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMixedDirectAndMultiPreviewMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals("Should discover 3 previews (2 from MultiPreview + 1 direct @Preview)", 3, discovered.size)

      assertEquals("Phone Light", discovered[0].previewParams["name"])
      assertEquals("Phone Dark", discovered[1].previewParams["name"])
      assertEquals("Direct Override", discovered[2].previewParams["name"])
      assertEquals("500", discovered[2].previewParams["widthDp"])
    }
  }

  @Test
  fun testCyclicMultiPreviewTerminatesSafely() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleCyclicMultiPreviewMethod"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Cyclic annotations without @Preview should return empty previews", result.previews.isEmpty())
      assertTrue(result.methodValidationResult.hasErrors)
      assertEquals("No @Preview annotations found on method '$methodFQN'", result.methodValidationResult.errors.first().message)
    }
  }

  @Test
  fun testDiscoverNestedMultiPreviewWithDuplicateDescendants() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleNestedMultiPreviewWithDuplicateDescendants"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals("Should discover all 4 previews from nested MultiPreview with duplicate descendants", 4, discovered.size)

      val names = discovered.map { it.previewParams["name"] }
      assertTrue("Should contain Branch A Preview", names.contains("Branch A Preview"))
      assertTrue("Should contain Branch B Preview", names.contains("Branch B Preview"))
      assertTrue("Should contain Base Theme Preview", names.contains("Base Theme Preview"))
    }
  }

  @Test
  fun testDiscoverDirectAndNestedSharedMultiPreview() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleDirectAndNestedSharedMultiPreviewMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals("Should discover 3 previews from direct and nested shared MultiPreview", 3, discovered.size)

      val names = discovered.map { it.previewParams["name"] }
      assertEquals(2, names.count { it == "Base Theme Preview" })
      assertEquals(1, names.count { it == "Branch A Preview" })
    }
  }

  @Test
  fun testDiscoverPreviewParameterSingleArgument() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMethodWithPreviewParameter"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals(1, discovered.size)

      val screenshot = discovered[0]
      assertEquals(1, screenshot.methodParams.size)
      val param0 = screenshot.methodParams[0]
      assertEquals(SampleUserProvider::class.java.name, param0["provider"])
      assertEquals("5", param0["limit"])
    }
  }

  @Test
  fun testDiscoverPreviewParameterWithDefaultLimit() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMethodWithDefaultPreviewParameter"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals(1, discovered.size)

      val screenshot = discovered[0]
      assertEquals(1, screenshot.methodParams.size)

      val param0 = screenshot.methodParams[0]
      assertEquals(SampleUserProvider::class.java.name, param0["provider"])
      assertEquals(null, param0["limit"])
    }
  }

  @Test
  fun testDiscoverMethodWithoutPreviewParameterHasEmptyMethodParams() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleAnnotatedPreview"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals(1, discovered.size)
      assertTrue("Method without @PreviewParameter should have empty methodParams", discovered[0].methodParams.isEmpty())
      assertEquals(null, discovered[0].previewWrapperFqn)
    }
  }

  @Test
  fun testDiscoverDirectPreviewWrapper() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleDirectWrappedPreviewMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals(1, discovered.size)

      val screenshot = discovered[0]
      assertEquals("Direct Wrapped Preview", screenshot.previewParams["name"])
      assertEquals(SampleThemeWrapper::class.java.name, screenshot.previewWrapperFqn)
    }
  }

  @Test
  fun testDiscoverMultiPreviewWithWrapper() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMultiPreviewWithWrapperMethod"

      val result = engine.discoverAllPreviews(methodFQN).single()
      val discovered = result.previews
      assertEquals(2, discovered.size)

      assertEquals("Wrapped Phone Light", discovered[0].previewParams["name"])
      assertEquals(SampleThemeWrapper::class.java.name, discovered[0].previewWrapperFqn)

      assertEquals("Wrapped Phone Dark", discovered[1].previewParams["name"])
      assertEquals(SampleThemeWrapper::class.java.name, discovered[1].previewWrapperFqn)
    }
  }

  @Test
  fun testDiscoverOverloadedMethodsWithAndWithoutPreviewParameter() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${OverloadedPreviewParameterTarget::class.java.name}.OverloadedMethod"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals("Should discover 2 method overloads", 2, results.size)

      val noParamResult = results[0]
      assertEquals(1, noParamResult.previews.size)
      val noParamPreview = noParamResult.previews[0]
      assertEquals("Overload No Param", noParamPreview.previewParams["name"])
      assertTrue("No-param overload should have empty methodParams", noParamPreview.methodParams.isEmpty())
      assertEquals("100", noParamPreview.previewParams["widthDp"])

      val withParamResult = results[1]
      assertEquals(1, withParamResult.previews.size)
      val withParamPreview = withParamResult.previews[0]
      assertEquals("Overload With PreviewParameter", withParamPreview.previewParams["name"])
      assertEquals("With-param overload should have 1 methodParam", 1, withParamPreview.methodParams.size)
      assertEquals(SampleUserProvider::class.java.name, withParamPreview.methodParams[0]["provider"])
      assertEquals("2", withParamPreview.methodParams[0]["limit"])
      assertEquals("200", withParamPreview.previewParams["widthDp"])
    }
  }

  @Test
  fun testMultiplePreviewWrappersViaMultiPreviewProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMethodWithMultipleWrappersViaMultiPreview"
      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Method with multiple wrappers should have validation errors", result.methodValidationResult.hasErrors)
      assertTrue("Discovered previews should be empty for method with multiple wrappers", result.previews.isEmpty())
      val error = result.methodValidationResult.errors.first()
      assertTrue("Error should mention multiple wrappers", error.message.contains("Multiple @PreviewWrapper annotations found"))
    }
  }

  @Test
  fun testDirectAndMultiPreviewWrapperProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleMethodWithDirectAndMultiPreviewWrapper"
      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Method with direct and multi-preview wrappers should have validation errors", result.methodValidationResult.hasErrors)
      assertTrue("Discovered previews should be empty", result.previews.isEmpty())
      val error = result.methodValidationResult.errors.first()
      assertTrue("Error should mention multiple wrappers", error.message.contains("Multiple @PreviewWrapper annotations found"))
    }
  }

  @Test
  fun testPreviewWrapperWithoutPreviewProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleComposableWrapperWithoutPreviewMethod"
      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results.first()
      assertTrue("Method with wrapper but without preview should have validation errors", result.methodValidationResult.hasErrors)
      assertTrue("Discovered previews should be empty", result.previews.isEmpty())
      val error = result.methodValidationResult.errors.first()
      assertEquals("Method '$methodFQN' annotated with @PreviewWrapper must also be annotated with @Preview", error.message)
    }
  }

  @Test
  fun testDiscoverPreviewWithoutComposableProducesValidationError() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.SampleNonComposablePreviewMethod"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, results.size)
      val result = results[0]
      assertTrue("Discovered previews should be empty for non-composable preview", result.previews.isEmpty())
      assertTrue("Validation result should have errors", result.methodValidationResult.hasErrors)

      val error = result.methodValidationResult.errors.first()
      assertEquals("Method '$methodFQN' annotated with @Preview must be annotated with @Composable", error.message)
    }
  }

  @Test
  fun testDiscoverOverloadedMethodsWithMixedValidity() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${OverloadedMixedValidityTarget::class.java.name}.OverloadedPreview"

      val results = engine.discoverAllPreviews(methodFQN)
      assertEquals("Should return 2 PreviewDiscoveryResults for the two overloads", 2, results.size)

      // First overload is valid
      val validResult = results[0]
      assertEquals("Should discover the valid preview from valid overload", 1, validResult.previews.size)
      assertEquals("Valid Composable Overload", validResult.previews[0].previewParams["name"])
      assertTrue("Valid overload should have no validation errors", validResult.methodValidationResult.isValid)

      // Second overload is invalid (missing @Composable)
      val invalidResult = results[1]
      assertTrue("Invalid overload should have empty previews", invalidResult.previews.isEmpty())
      assertTrue("Invalid overload should record validation errors", invalidResult.methodValidationResult.hasErrors)
      assertEquals(1, invalidResult.methodValidationResult.errors.size)
      assertEquals(
        "Method '$methodFQN' annotated with @Preview must be annotated with @Composable",
        invalidResult.methodValidationResult.errors[0].message,
      )
    }
  }
}
