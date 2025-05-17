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

package com.android.build.api.dsl

import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.quality.Strictness
import com.google.common.truth.Truth
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyCollector
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testing.base.TestSuite
import org.gradle.testing.base.TestSuiteTarget
import org.junit.Before
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

@Suppress("UnstableApiUsage")
class TestOptionsTest {
    @get:Rule
    val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

    @get:Rule
    var temporaryFolder = TemporaryFolder()

    @Mock
    private lateinit var testOptions: TestOptions

    private lateinit var project: Project

    open class AgpTestSuiteImplForTest(private val name: String) : AgpTestSuite {
        override fun getName(): String = name

        override fun getTargets(): ExtensiblePolymorphicDomainObjectContainer<out TestSuiteTarget> {
            throw RuntimeException("Unexpected call to `getTargets()`")
        }

        private val jUnitEngineSpec = JunitEngineSpecForTest()

        override val useJunitEngine: JUnitEngineSpec = jUnitEngineSpec
        override val targetProductFlavors: MutableList<Pair<String, String>> = mutableListOf<Pair<String, String>>()
        override val targetVariants: MutableList<String> = mutableListOf<String>()
        override fun useJunitEngine(action: JUnitEngineSpec.() -> Unit) {
            throw RuntimeException("Unexpected call")
        }
        override fun assets(action: TestSuiteAssetsSpec.() -> Unit) {
            throw RuntimeException("Unexpected call")
        }
        override fun hostJar(action: TestSuiteHostJarSpec.() -> Unit) {
            throw RuntimeException("Unexpected call")
        }
        override fun testApk(action: TestSuiteTestApkSpec.() -> Unit) {
            throw RuntimeException("Unexpected call")
        }
    }

    @Before
    fun setup() {
        project = ProjectBuilder.builder()
            .withProjectDir(temporaryFolder.newFolder())
            .build()

        val suites = project.objects.polymorphicDomainObjectContainer(AgpTestSuite::class.java)

        suites.registerBinding(AgpTestSuite::class.java, AgpTestSuiteImplForTest::class.java)
        Mockito.`when`(testOptions.suites).thenReturn(suites)
    }

    @Test
    fun testNewKnownTestSuite() {
        testOptions.suites.create("generic", AgpTestSuite::class.java)
        Truth.assertThat(testOptions.suites.size).isEqualTo(1)
        Truth.assertThat(testOptions.suites.single().name).isEqualTo("generic")
    }

    interface RandomTestSuite : AgpTestSuite {
        var instructions: String
    }

    /**
     * Test that new unknown [TestSuite] to AGP can be registered and
     * configured through the [TestOptions] interface.
     */
    @Test
    fun testNewUnknownTestSuite() {
        open class RandomTestSuiteImpl(name: String) : AgpTestSuiteImplForTest(name), RandomTestSuite {
            override var instructions: String = ""
            override val useJunitEngine: JUnitEngineSpec = JunitEngineSpecForTest()

            init {
                DefaultInputsForAgpTestSuites.JOURNEYS_TEST.initialize(this.useJunitEngine)
            }
        }
        testOptions.suites.registerBinding(
            RandomTestSuite::class.java,
            RandomTestSuiteImpl::class.java
        )

        testOptions.suites.create("random", RandomTestSuite::class.java)
        Truth.assertThat(testOptions.suites.size).isEqualTo(1)
        (testOptions.suites.getByName("random") as RandomTestSuite)
            .instructions = "some instructions"
    }

    @Test
    fun testExtraProperties() {
        // add a required test input parameter.
        testOptions.suites.create("journeysTest") {
            it.useJunitEngine.let { junitEngine ->
                DefaultInputsForAgpTestSuites.JOURNEYS_TEST.initialize(junitEngine)
                junitEngine.inputs.add(AgpTestSuiteInputParameters.TESTING_APK)
            }
        }
        val testSuite = testOptions.suites.getByName("journeysTest")
        Truth.assertThat(testSuite).isNotNull()
        Truth.assertThat(testSuite.useJunitEngine.inputs).containsExactlyElementsIn(
            DefaultInputsForAgpTestSuites.JOURNEYS_TEST.supportedProperties
                .plus(AgpTestSuiteInputParameters.TESTING_APK)
        )
    }

    private class JunitEngineSpecForTest: JUnitEngineSpec {
        override val includeEngines = mutableSetOf<String>()
        override val inputs = mutableListOf<AgpTestSuiteInputParameters>()
        override fun addInputProperty(propertyName: String, propertyValue: String) { }
        override fun addInputProperty(
            propertyName: String,
            propertyValue: Provider<String>
        ) { }

        override val enginesDependencies: DependencyCollector
            get() = throw RuntimeException("Unexpected call")
    }
}
