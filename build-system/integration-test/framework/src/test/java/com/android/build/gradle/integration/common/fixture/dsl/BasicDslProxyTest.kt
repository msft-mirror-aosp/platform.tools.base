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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.api.dsl.Address
import com.android.build.api.dsl.California
import com.android.build.api.dsl.Person
import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.KtsBuildWriter
import com.google.common.truth.Truth
import org.junit.Test

// this should really test the event content of the holder instead of relying on
// the BuildWriter but it's so much more convenient...

class BasicDslProxyTest: ExtensionAwareDefinition{
    private val contentHolder = DefaultDslContentHolder()

    @Test
    fun basicTest() {
        contentHolder.runNestedBlock("address", listOf(), Address::class.java) {
            street = "1600 Amphitheatre Parkway"
            city = "Mountain View"
            zipCode = 94043
        }

        val writer = GroovyBuildWriter()
        contentHolder.writeContent(writer)
        Truth.assertThat(writer.toString()).isEqualTo("""
            address {
              street = '1600 Amphitheatre Parkway'
              city = 'Mountain View'
              zipCode = 94043
            }

        """.trimIndent())
    }

    @Test
    fun nestedTest() {
        contentHolder.runNestedBlock("person", listOf(), Person::class.java) {
            name = "BugDroid"
            surname = null
            age = 17
            isRobot = true
            address {
                street = "1600 Amphitheatre Parkway"
                city = "Mountain View"
                zipCode = 94043
            }
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).named("Groovy version").isEqualTo("""
            person {
              name = 'BugDroid'
              surname = null
              age = 17
              robot = true
              address {
                street = '1600 Amphitheatre Parkway'
                city = 'Mountain View'
                zipCode = 94043
              }
            }

        """.trimIndent())

        val kts = KtsBuildWriter()
        contentHolder.writeContent(kts)
        Truth.assertThat(kts.toString()).named("KTS version").isEqualTo("""
            person {
              name = "BugDroid"
              surname = null
              age = 17
              isRobot = true
              address {
                street = "1600 Amphitheatre Parkway"
                city = "Mountain View"
                zipCode = 94043
              }
            }

        """.trimIndent())

    }

    @Test
    fun chainedBlockUsage() {
        contentHolder.runNestedBlock("california", listOf(), California::class.java) {
            mountainView.mayor {
                name = "bob"
                address.street = "1600 Amphitheatre Parkway"
            }
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            california {
              mountainView.mayor {
                name = 'bob'
                address.street = '1600 Amphitheatre Parkway'
              }
            }

        """.trimIndent())
    }

    @Test
    fun methodCall() {
        contentHolder.runNestedBlock("person", listOf(), Person::class.java) {
            name = "bob"
            sendMessage("Hello!")
            sendMessage(null)
            something("one", "two")
            something(12, "one", "two")
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).isEqualTo("""
            person {
              name = 'bob'
              sendMessage('Hello!')
              sendMessage(null)
              something('one', 'two')
              something(12, 'one', 'two')
            }

        """.trimIndent())
    }

    @Test
    fun extension() {
        // This tests an extension. Person has `address` but not `workAddress`.
        // We'll us the same type for both
        contentHolder.runNestedBlock("person", listOf(), Person::class.java) {
            name = "BugDroid"
            surname = null
            address {
                street = "1600 Amphitheatre Parkway"
                city = "Mountain View"
                zipCode = 94043
            }
            viaExtension("workAddress", Address::class) {
                street = "901 Cherry Ave." //youtube
                city = "San Bruno"
                zipCode = 94066
            }
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).named("Groovy version").isEqualTo("""
            person {
              name = 'BugDroid'
              surname = null
              address {
                street = '1600 Amphitheatre Parkway'
                city = 'Mountain View'
                zipCode = 94043
              }
              workAddress {
                street = '901 Cherry Ave.'
                city = 'San Bruno'
                zipCode = 94066
              }
            }

        """.trimIndent())
    }

    @Test
    fun nestedExtension() {
        // this tests a nested extension to make sure it gets written inside the right block
        contentHolder.runNestedBlock("person", listOf(), Person::class.java) {
            name = "BugDroid"
            address {
                street = "1600 Amphitheatre Parkway"
                city = "Mountain View"
                zipCode = 94043
                viaExtension("landlord", Person::class) {
                    name = "Sundar"
                    surname = "Pichai"
                }
            }
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).named("Groovy version").isEqualTo("""
            person {
              name = 'BugDroid'
              address {
                street = '1600 Amphitheatre Parkway'
                city = 'Mountain View'
                zipCode = 94043
                landlord {
                  name = 'Sundar'
                  surname = 'Pichai'
                }
              }
            }

        """.trimIndent())
    }

    @Test
    fun chainedExtension() {
        // this tests a nested extension via a chained call, to make sure it gets written inside
        // the right block
        contentHolder.runNestedBlock("person", listOf(), Person::class.java) {
            name = "BugDroid"
            address.viaExtension("landlord", Person::class) {
                name = "Sundar"
                surname = "Pichai"
            }
        }

        val groovy = GroovyBuildWriter()
        contentHolder.writeContent(groovy)
        Truth.assertThat(groovy.toString()).named("Groovy version").isEqualTo("""
            person {
              name = 'BugDroid'
              address.landlord {
                name = 'Sundar'
                surname = 'Pichai'
              }
            }

        """.trimIndent())
    }

}
