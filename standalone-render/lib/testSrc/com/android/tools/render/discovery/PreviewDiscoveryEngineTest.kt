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

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
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
  fun sampleAnnotatedPreview() {}

  @Preview(name = "Light Mode", widthDp = 320, heightDp = 640)
  @Preview(name = "Dark Mode", widthDp = 360, heightDp = 720, uiMode = 32)
  fun sampleMultiPreviewMethod() {}

  @Preview(name = "Invalid Preview", widthDp = -50) fun sampleInvalidPreviewMethod() {}

  @Preview(name = "Valid Preview", widthDp = 300, heightDp = 600)
  @Preview(name = "Invalid Dimension Preview", widthDp = -50)
  fun sampleMixedMultiPreviewMethod() {}

  @DeviceThemePreviews fun sampleMultiPreviewAnnotatedMethod() {}

  @CombinedDevicePreviews fun sampleNestedMultiPreviewAnnotatedMethod() {}

  @DeviceThemePreviews @Preview(name = "Direct Override", widthDp = 500) fun sampleMixedDirectAndMultiPreviewMethod() {}

  @CyclicPreviewA fun sampleCyclicMultiPreviewMethod() {}

  @NestedMultiPreviewWithDuplicateDescendants fun sampleNestedMultiPreviewWithDuplicateDescendants() {}

  @BaseThemePreview @BranchThemePreviewA fun sampleDirectAndNestedSharedMultiPreviewMethod() {}

  @Preview(name = "Single Param Preview")
  fun sampleMethodWithPreviewParameter(@PreviewParameter(provider = SampleUserProvider::class, limit = 5) user: String) {}

  @Preview(name = "Default Param Preview")
  fun sampleMethodWithDefaultPreviewParameter(@PreviewParameter(provider = SampleUserProvider::class) user: String) {}

  fun sampleMethodWithoutAnnotation() {}

  @CustomNonComposePreview(name = "Non Compose Preview") fun sampleMethodWithNonComposeAnnotation() {}

  class NestedTarget {
    @Preview(name = "Nested Preview", widthDp = 150, heightDp = 300) fun nestedPreviewMethod() {}
  }

  companion object {
    @Preview(name = "Companion Preview", fontScale = 2.0f) fun companionPreviewMethod() {}
  }
}

/** Sample class containing overloaded methods (one with no parameters, one with @PreviewParameter). */
class OverloadedPreviewParameterTarget {
  @Preview(name = "Overload No Param", widthDp = 100) fun overloadedMethod() {}

  @Preview(name = "Overload With PreviewParameter", widthDp = 200)
  fun overloadedMethod(@PreviewParameter(provider = SampleUserProvider::class, limit = 2) user: String) {}
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
  fun testDiscoverSinglePreviewAnnotation() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleAnnotatedPreview"

      val discovered = engine.discoverAllPreviews(methodFQN).firstOrNull()
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
      val methodFQN = "${SamplePreviewTarget.NestedTarget::class.java.name}.nestedPreviewMethod"

      val discovered = engine.discoverAllPreviews(methodFQN).firstOrNull()
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
      val methodFQN = "${SamplePreviewTarget.Companion::class.java.name}.companionPreviewMethod"

      val discovered = engine.discoverAllPreviews(methodFQN).firstOrNull()
      assertNotNull("Preview should be discovered on companion object method", discovered)
      assertEquals(methodFQN, discovered!!.methodFQN)

      val params = discovered.previewParams
      assertEquals("Companion Preview", params["name"])
      assertEquals("2.0", params["fontScale"])
    }
  }

  @Test
  fun testDiscoverMethodWithNonComposeAnnotationReturnsEmpty() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithNonComposeAnnotation"

      val discovered = engine.discoverAllPreviews(methodFQN)
      assertTrue("Methods annotated with non-Compose annotations should return empty list", discovered.isEmpty())
    }
  }

  @Test
  fun testDiscoverMethodWithoutPreviewReturnsEmpty() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithoutAnnotation"

      val discovered = engine.discoverAllPreviews(methodFQN)
      assertTrue("Methods without @Preview annotation should return empty list", discovered.isEmpty())
    }
  }

  @Test
  fun testDiscoverNonExistentMethodReturnsEmpty() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.nonExistentMethod"

      val discovered = engine.discoverAllPreviews(methodFQN)
      assertTrue("Non-existent method should return empty list", discovered.isEmpty())
    }
  }

  @Test
  fun testDiscoverAllMultiPreviews() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMultiPreviewMethod"

      val allDiscovered = engine.discoverAllPreviews(methodFQN)
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
  fun testDiscoverNonExistentClassReturnsEmpty() {
    createDiscoveryEngine { engine ->
      val methodFQN = "com.android.tools.render.NonExistentClass.preview"

      val discovered = engine.discoverAllPreviews(methodFQN)
      assertTrue("Non-existent class should return empty list", discovered.isEmpty())
    }
  }

  @Test
  fun testDiscoverCustomMultiPreviewClassAnnotation() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMultiPreviewAnnotatedMethod"

      val discovered = engine.discoverAllPreviews(methodFQN)
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
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleNestedMultiPreviewAnnotatedMethod"

      val discovered = engine.discoverAllPreviews(methodFQN)
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
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMixedDirectAndMultiPreviewMethod"

      val discovered = engine.discoverAllPreviews(methodFQN)
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
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleCyclicMultiPreviewMethod"

      val discovered = engine.discoverAllPreviews(methodFQN)
      assertTrue("Cyclic annotations without @Preview should return empty list without crashing", discovered.isEmpty())
    }
  }

  @Test
  fun testDiscoverNestedMultiPreviewWithDuplicateDescendants() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleNestedMultiPreviewWithDuplicateDescendants"

      val discovered = engine.discoverAllPreviews(methodFQN)
      // Branch A: Branch A Preview + Base Theme Preview (2)
      // Branch B: Branch B Preview + Base Theme Preview (2)
      // Total: 4
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
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleDirectAndNestedSharedMultiPreviewMethod"

      val discovered = engine.discoverAllPreviews(methodFQN)
      // Direct @BaseThemePreview (1) + @BranchThemePreviewA (Branch A Preview + Base Theme Preview) (2) = 3
      assertEquals("Should discover 3 previews from direct and nested shared MultiPreview", 3, discovered.size)

      val names = discovered.map { it.previewParams["name"] }
      assertEquals(2, names.count { it == "Base Theme Preview" })
      assertEquals(1, names.count { it == "Branch A Preview" })
    }
  }

  @Test
  fun testDiscoverPreviewParameterSingleArgument() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithPreviewParameter"

      val discovered = engine.discoverAllPreviews(methodFQN)
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
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithDefaultPreviewParameter"

      val discovered = engine.discoverAllPreviews(methodFQN)
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
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleAnnotatedPreview"

      val discovered = engine.discoverAllPreviews(methodFQN)
      assertEquals(1, discovered.size)
      assertTrue("Method without @PreviewParameter should have empty methodParams", discovered[0].methodParams.isEmpty())
    }
  }

  @Test
  fun testDiscoverOverloadedMethodsWithAndWithoutPreviewParameter() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${OverloadedPreviewParameterTarget::class.java.name}.overloadedMethod"

      val discovered = engine.discoverAllPreviews(methodFQN)
      assertEquals("Should discover 2 previews across both overloads", 2, discovered.size)

      val noParamPreview = discovered.first { it.previewParams["name"] == "Overload No Param" }
      assertTrue("No-param overload should have empty methodParams", noParamPreview.methodParams.isEmpty())
      assertEquals("100", noParamPreview.previewParams["widthDp"])

      val withParamPreview = discovered.first { it.previewParams["name"] == "Overload With PreviewParameter" }
      assertEquals("With-param overload should have 1 methodParam", 1, withParamPreview.methodParams.size)
      assertEquals(SampleUserProvider::class.java.name, withParamPreview.methodParams[0]["provider"])
      assertEquals("2", withParamPreview.methodParams[0]["limit"])
      assertEquals("200", withParamPreview.previewParams["widthDp"])
    }
  }
}
