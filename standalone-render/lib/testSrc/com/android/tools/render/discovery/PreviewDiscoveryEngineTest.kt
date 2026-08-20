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
import org.junit.Assert.assertNull
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
      val previewId = "sample_preview_0"

      val discovered = engine.discover(methodFQN, previewId)
      assertNotNull("Preview should be discovered on annotated method", discovered)
      assertEquals(methodFQN, discovered!!.methodFQN)
      assertEquals(previewId, discovered.previewId)

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
      val previewId = "nested_preview"

      val discovered = engine.discover(methodFQN, previewId)
      assertNotNull("Preview should be discovered on nested class method", discovered)
      assertEquals(methodFQN, discovered!!.methodFQN)
      assertEquals(previewId, discovered.previewId)

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
      val previewId = "companion_preview"

      val discovered = engine.discover(methodFQN, previewId)
      assertNotNull("Preview should be discovered on companion object method", discovered)
      assertEquals(methodFQN, discovered!!.methodFQN)
      assertEquals(previewId, discovered.previewId)

      val params = discovered.previewParams
      assertEquals("Companion Preview", params["name"])
      assertEquals("2.0", params["fontScale"])
    }
  }

  @Test
  fun testDiscoverMethodWithNonComposeAnnotationReturnsNull() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithNonComposeAnnotation"
      val previewId = "non_compose"

      val discovered = engine.discover(methodFQN, previewId)
      assertNull("Methods annotated with non-Compose annotations should return null", discovered)
    }
  }

  @Test
  fun testDiscoverMethodWithoutPreviewReturnsNull() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.sampleMethodWithoutAnnotation"
      val previewId = "unannotated"

      val discovered = engine.discover(methodFQN, previewId)
      assertNull("Methods without @Preview annotation should return null", discovered)
    }
  }

  @Test
  fun testDiscoverNonExistentMethodReturnsNull() {
    createDiscoveryEngine { engine ->
      val methodFQN = "${SamplePreviewTarget::class.java.name}.nonExistentMethod"
      val previewId = "missing"

      val discovered = engine.discover(methodFQN, previewId)
      assertNull("Non-existent method should return null", discovered)
    }
  }

  @Test
  fun testDiscoverNonExistentClassReturnsNull() {
    createDiscoveryEngine { engine ->
      val methodFQN = "com.android.tools.render.NonExistentClass.preview"
      val previewId = "missing_class"

      val discovered = engine.discover(methodFQN, previewId)
      assertNull("Non-existent class should return null", discovered)
    }
  }
}
