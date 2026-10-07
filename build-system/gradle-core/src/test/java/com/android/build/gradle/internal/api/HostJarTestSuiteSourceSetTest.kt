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

package com.android.build.gradle.internal.api

import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteDependencies
import com.android.build.api.variant.TestSuiteSourceSet
import com.android.build.gradle.internal.dsl.AgpTestSuiteImpl
import com.android.build.gradle.internal.dsl.TestSuiteHostJarSpecImpl
import com.android.build.gradle.internal.services.VariantServices
import com.android.build.gradle.internal.services.VariantServicesImpl
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.internal.testsuites.validateTestSuiteSourceSetNames
import com.google.common.truth.Truth
import kotlin.test.assertFailsWith
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.quality.Strictness

class HostJarTestSuiteSourceSetTest {

  @get:Rule val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  @get:Rule val tmpFolder: TemporaryFolder = TemporaryFolder()

  @Mock lateinit var dependencies: AgpTestSuiteDependencies

  lateinit var variantServices: VariantServices
  lateinit var project: Project

  @Before
  fun init() {
    project = ProjectBuilder.builder().withProjectDir(tmpFolder.newFolder()).build()
    variantServices = VariantServicesImpl(createProjectServices(project), true)
    includeAndroidResources = project.objects.property(Boolean::class.java).also { it.set(false) }
  }

  private lateinit var includeAndroidResources: Property<Boolean>

  @Test
  fun testKotlinDisabled() {
    val sourceSet =
      HostJarTestSuiteSourceSet(
        sourceSetName = "test",
        testSuiteName = "test",
        isMixed = false,
        variantServices = variantServices,
        userAddedSourceSets = emptyList(),
        javaEnabled = true,
        kotlinEnabled = false,
        includeAndroidResources = includeAndroidResources,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.kotlin).isNull()
  }

  @Test
  fun testJavaDisabled() {
    val sourceSet =
      HostJarTestSuiteSourceSet(
        sourceSetName = "test",
        testSuiteName = "test",
        isMixed = false,
        variantServices = variantServices,
        userAddedSourceSets = emptyList(),
        javaEnabled = false,
        kotlinEnabled = true,
        includeAndroidResources = includeAndroidResources,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.java).isNull()
  }

  @Test
  fun testDefaultCase() {
    val sourceSet =
      HostJarTestSuiteSourceSet(
        sourceSetName = "test",
        testSuiteName = "test",
        isMixed = false,
        variantServices = variantServices,
        userAddedSourceSets = emptyList(),
        javaEnabled = true,
        kotlinEnabled = true,
        includeAndroidResources = includeAndroidResources,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.kotlin).isNotNull()
    Truth.assertThat(sourceSet.java).isNotNull()
  }

  @Test
  fun testDefaultSourceSet() {
    val sourceSet =
      HostJarTestSuiteSourceSet(
        sourceSetName = "test",
        testSuiteName = "test",
        isMixed = false,
        variantServices = variantServices,
        userAddedSourceSets = emptyList(),
        javaEnabled = true,
        kotlinEnabled = true,
        includeAndroidResources = includeAndroidResources,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.kotlin?.all?.get()).containsExactly(project.layout.projectDirectory.dir("src/test/kotlin"))
    Truth.assertThat(sourceSet.java?.all?.get()).containsExactly(project.layout.projectDirectory.dir("src/test/java"))
    Truth.assertThat(sourceSet.resources.all.get()).containsExactly(project.layout.projectDirectory.dir("src/test/resources"))
  }

  @Test
  fun testIsMixedSourceSet() {
    val sourceSet =
      HostJarTestSuiteSourceSet(
        sourceSetName = "backupTestTest",
        testSuiteName = "backupTest",
        isMixed = true,
        variantServices = variantServices,
        userAddedSourceSets = emptyList(),
        javaEnabled = true,
        kotlinEnabled = true,
        includeAndroidResources = includeAndroidResources,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.kotlin?.all?.get()).containsExactly(project.layout.projectDirectory.dir("src/backupTest/test/kotlin"))
    Truth.assertThat(sourceSet.java?.all?.get()).containsExactly(project.layout.projectDirectory.dir("src/backupTest/test/java"))
    Truth.assertThat(sourceSet.resources.all.get()).containsExactly(project.layout.projectDirectory.dir("src/backupTest/test/resources"))
    Truth.assertThat(sourceSet.manifestFileCandidate)
      .isEqualTo(project.layout.projectDirectory.file("src/backupTest/test/AndroidManifest.xml").asFile)
  }

  @Test
  fun testSpecCreatesExpectedSourceSetForSingleAndMixedSuites() {
    val spec =
      TestSuiteHostJarSpecImpl(
        objects = project.objects,
        name = "first",
        projectDirectory = project.layout.projectDirectory,
        buildDirectory = project.layout.buildDirectory,
      )

    val singleSourceSet =
      spec.createTestSuiteSourceSet(
        variantServices = variantServices,
        javaEnabled = true,
        kotlinEnabled = true,
        isMixed = false,
      ) as TestSuiteSourceSet.HostJar
    Truth.assertThat(singleSourceSet.name).isEqualTo("first")
    Truth.assertThat(singleSourceSet.java?.all?.get()).containsExactly(project.layout.projectDirectory.dir("src/first/java"))

    val mixedSourceSet =
      spec.createTestSuiteSourceSet(
        variantServices = variantServices,
        javaEnabled = true,
        kotlinEnabled = true,
        isMixed = true,
      ) as TestSuiteSourceSet.HostJar
    Truth.assertThat(mixedSourceSet.name).isEqualTo("firstTest")
    Truth.assertThat(mixedSourceSet.java?.all?.get()).containsExactly(project.layout.projectDirectory.dir("src/first/test/java"))
  }

  @Test
  fun testValidateTestSuiteSourceSetNamesThrowsOnCollisions() {
    val dslServices = createDslServices(createProjectServices(project))
    fun suitesContainer(configure: org.gradle.api.NamedDomainObjectContainer<AgpTestSuite>.() -> Unit) =
      dslServices
        .polymorphicDomainObjectContainer(AgpTestSuite::class.java)
        .apply {
          registerFactory(AgpTestSuite::class.java) { name ->
            dslServices.newInstance(AgpTestSuiteImpl::class.java, name, dslServices, false)
          }
        }
        .apply(configure)

    // Non-colliding suites (empty, single, multiple with mixed) should succeed without error
    validateTestSuiteSourceSetNames(suitesContainer {})
    validateTestSuiteSourceSetNames(
      suitesContainer {
        create("onlySuite") {
          it.hostJar {}
          it.assets {}
        }
      }
    )
    validateTestSuiteSourceSetNames(
      suitesContainer {
        create("first") { it.hostJar {} }
        create("second") { it.testApk {} }
        create("mixed") {
          it.hostJar {}
          it.testApk {}
          it.assets {}
        }
      }
    )

    // Single-source hostJar colliding with mixed suite's hostJar source set (regardless of declaration order)
    val expectedHostJarMsg =
      "Test suite 'testSuiteTest' collides with source set 'testSuiteTest' of mixed test suite 'testSuite'. " +
        "Please rename one of the test suites to avoid source set and task name collisions."
    val hostJarCollision1 =
      assertFailsWith<InvalidUserDataException> {
        validateTestSuiteSourceSetNames(
          suitesContainer {
            create("testSuiteTest") { it.hostJar {} }
            create("testSuite") {
              it.hostJar {}
              it.assets {}
            }
          }
        )
      }
    val hostJarCollision2 =
      assertFailsWith<InvalidUserDataException> {
        validateTestSuiteSourceSetNames(
          suitesContainer {
            create("testSuite") {
              it.hostJar {}
              it.assets {}
            }
            create("testSuiteTest") { it.hostJar {} }
          }
        )
      }
    Truth.assertThat(hostJarCollision1.message).isEqualTo(expectedHostJarMsg)
    Truth.assertThat(hostJarCollision2.message).isEqualTo(expectedHostJarMsg)

    // Single-source testApk colliding with mixed suite's testApk source set
    val testApkCollision =
      assertFailsWith<InvalidUserDataException> {
        validateTestSuiteSourceSetNames(
          suitesContainer {
            create("testSuiteAndroidTest") { it.testApk {} }
            create("testSuite") {
              it.hostJar {}
              it.testApk {}
            }
          }
        )
      }
    Truth.assertThat(testApkCollision.message)
      .isEqualTo(
        "Test suite 'testSuiteAndroidTest' collides with source set 'testSuiteAndroidTest' of mixed test suite 'testSuite'. " +
          "Please rename one of the test suites to avoid source set and task name collisions."
      )

    // Single-source assets (including implicit default assets when no source block is specified) colliding with mixed suite's assets source
    // set
    val assetsCollision =
      assertFailsWith<InvalidUserDataException> {
        validateTestSuiteSourceSetNames(
          suitesContainer {
            create("testSuiteAssets")
            create("testSuite") {
              it.hostJar {}
              it.assets {}
            }
          }
        )
      }
    Truth.assertThat(assetsCollision.message)
      .isEqualTo(
        "Test suite 'testSuiteAssets' collides with source set 'testSuiteAssets' of mixed test suite 'testSuite'. " +
          "Please rename one of the test suites to avoid source set and task name collisions."
      )

    // Cross-type collision (single-source testApk 'crossSuiteTest' colliding with mixed 'crossSuite' hostJar 'crossSuiteTest')
    val crossTypeCollision =
      assertFailsWith<InvalidUserDataException> {
        validateTestSuiteSourceSetNames(
          suitesContainer {
            create("crossSuiteTest") { it.testApk {} }
            create("crossSuite") {
              it.hostJar {}
              it.assets {}
            }
          }
        )
      }
    Truth.assertThat(crossTypeCollision.message)
      .isEqualTo(
        "Test suite 'crossSuiteTest' collides with source set 'crossSuiteTest' of mixed test suite 'crossSuite'. " +
          "Please rename one of the test suites to avoid source set and task name collisions."
      )

    // Mixed-to-mixed source set collision ('fooTest' testApk -> 'fooTestAndroidTest' collides with 'fooTestAndroid' hostJar ->
    // 'fooTestAndroidTest')
    val mixedToMixedCollision =
      assertFailsWith<InvalidUserDataException> {
        validateTestSuiteSourceSetNames(
          suitesContainer {
            create("fooTestAndroid") {
              it.hostJar {}
              it.assets {}
            }
            create("fooTest") {
              it.testApk {}
              it.assets {}
            }
          }
        )
      }
    Truth.assertThat(mixedToMixedCollision.message)
      .isEqualTo(
        "Source set 'fooTestAndroidTest' of mixed test suite 'fooTestAndroid' collides with " +
          "source set 'fooTestAndroidTest' of mixed test suite 'fooTest'. " +
          "Please rename one of the test suites to avoid source set and task name collisions."
      )

    // Lazy NamedDomainObjectContainer (customSuites.register + create)
    var lazyRealized = false
    val lazyContainer = suitesContainer {
      register("lazySuite") {
        lazyRealized = true
        it.hostJar {}
        it.assets {}
      }
      create("lazySuiteTest") { it.hostJar {} }
    }
    Truth.assertThat(lazyRealized).isFalse()

    val lazyCollision =
      assertFailsWith<InvalidUserDataException> {
        validateTestSuiteSourceSetNames(lazyContainer)
      }
    Truth.assertThat(lazyRealized).isTrue()
    Truth.assertThat(lazyCollision.message)
      .isEqualTo(
        "Test suite 'lazySuiteTest' collides with source set 'lazySuiteTest' of mixed test suite 'lazySuite'. " +
          "Please rename one of the test suites to avoid source set and task name collisions."
      )
  }

  @Test
  fun testUserAddedSourceSet() {
    val userAddedSourceSet = tmpFolder.newFolder("userAdded")
    val dir = project.layout.projectDirectory.dir(userAddedSourceSet.absolutePath)
    val sourceSet =
      HostJarTestSuiteSourceSet(
        sourceSetName = "test",
        testSuiteName = "test",
        isMixed = false,
        variantServices = variantServices,
        userAddedSourceSets = listOf(dir),
        javaEnabled = true,
        kotlinEnabled = true,
        includeAndroidResources = includeAndroidResources,
        dependencies = dependencies,
      )
    Truth.assertThat(sourceSet.kotlin?.all?.get())
      .containsExactly(project.layout.projectDirectory.dir("src/test/kotlin"), dir.dir("kotlin"))
    Truth.assertThat(sourceSet.java?.all?.get()).containsExactly(project.layout.projectDirectory.dir("src/test/java"), dir.dir("java"))
    Truth.assertThat(sourceSet.resources.all.get())
      .containsExactly(project.layout.projectDirectory.dir("src/test/resources"), dir.dir("resources"))
  }
}
