/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.build.gradle.internal.ide.kmp

import com.android.build.gradle.internal.dsl.DependencySelectionImpl
import com.android.build.gradle.internal.dsl.KotlinMultiplatformAndroidLibraryExtensionImpl
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.services.createDslServices
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.Versions
import com.android.builder.model.v2.models.kmp.KotlinMultiplatformAndroidDependencySelection
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.gradle.api.Project
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class KotlinMultiplatformAndroidDependencySelectionModelBuilderTest {

  private val project: Project = ProjectFactory.project
  private val dslServices = createDslServices()

  @Test
  fun testCanBuild() {
    val builder = KotlinMultiplatformAndroidDependencySelectionModelBuilder(mock())

    assertThat(builder.canBuild(KotlinMultiplatformAndroidDependencySelection::class.java.name)).isTrue()
    assertThat(builder.canBuild(Versions::class.java.name)).isFalse()
    assertThat(builder.canBuild(BasicAndroidProject::class.java.name)).isFalse()
    assertThat(builder.canBuild("com.example.UnknownModel")).isFalse()
  }

  @Test
  fun testUnsupportedModelThrows() {
    val builder = KotlinMultiplatformAndroidDependencySelectionModelBuilder(mock())
    val exception = assertThrows(RuntimeException::class.java) { builder.buildAll("com.example.UnknownModel", project) }
    assertThat(exception).hasMessageThat().contains("Does not support model 'com.example.UnknownModel'")
  }

  @Test
  fun testBuildsLocalDependencySelection() {
    val dependencySelection = project.objects.newInstance(DependencySelectionImpl::class.java, dslServices, project.objects)
    dependencySelection.selectBuildTypeFrom.add("debug")
    dependencySelection.productFlavorDimension("type") { it.selectFrom.set(listOf("typeone", "typetwo")) }
    dependencySelection.productFlavorDimension("mode") { it.selectFrom.set(listOf("modetwo")) }

    val extension = mock<KotlinMultiplatformAndroidLibraryExtensionImpl>()
    whenever(extension.localDependencySelection).thenReturn(dependencySelection)

    val builder = KotlinMultiplatformAndroidDependencySelectionModelBuilder(extension)
    val model = builder.buildAll(KotlinMultiplatformAndroidDependencySelection::class.java.name, project)

    assertThat(model.selectBuildTypeFrom).containsExactly("release", "debug").inOrder()
    assertThat(model.productFlavorSelection).containsExactly("type", listOf("typeone", "typetwo"), "mode", listOf("modetwo"))

    // Verify Java serialization round-trip (used by Gradle Tooling API).
    val serialized =
      ByteArrayOutputStream().use { baos ->
        ObjectOutputStream(baos).use { it.writeObject(model) }
        baos.toByteArray()
      }
    val deserialized =
      ObjectInputStream(ByteArrayInputStream(serialized)).use { it.readObject() as KotlinMultiplatformAndroidDependencySelection }
    assertThat(deserialized).isEqualTo(model)
  }

  @Test
  fun testDefaultsWhenUnconfigured() {
    val dependencySelection = project.objects.newInstance(DependencySelectionImpl::class.java, dslServices, project.objects)
    val extension = mock<KotlinMultiplatformAndroidLibraryExtensionImpl>()
    whenever(extension.localDependencySelection).thenReturn(dependencySelection)

    val builder = KotlinMultiplatformAndroidDependencySelectionModelBuilder(extension)
    val model = builder.buildAll(KotlinMultiplatformAndroidDependencySelection::class.java.name, project)

    assertThat(model.selectBuildTypeFrom).containsExactly("release")
    assertThat(model.productFlavorSelection).isEmpty()
  }
}
