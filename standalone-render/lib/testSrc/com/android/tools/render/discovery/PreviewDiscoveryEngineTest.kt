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
import com.android.testutils.TestUtils
import com.android.tools.render.RenderEnvironmentBootstrapper
import kotlin.io.path.absolutePathString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom non-Compose preview annotation to verify strict descriptor matching. */
annotation class CustomNonComposePreview(val name: String = "")

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

  fun sampleMethodWithoutAnnotation() {}

  @CustomNonComposePreview(name = "Non Compose Preview") fun sampleMethodWithNonComposeAnnotation() {}

  class NestedTarget {
    @Preview(name = "Nested Preview", widthDp = 150, heightDp = 300) fun nestedPreviewMethod() {}
  }

  companion object {
    @Preview(name = "Companion Preview", fontScale = 2.0f) fun companionPreviewMethod() {}
  }
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
}
