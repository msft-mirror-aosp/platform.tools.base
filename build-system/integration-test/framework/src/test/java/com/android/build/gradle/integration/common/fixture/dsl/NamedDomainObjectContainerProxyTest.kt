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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.google.common.truth.Truth
import org.gradle.api.NamedDomainObjectContainer
import org.junit.Test

class NamedDomainObjectContainerProxyTest {

  private val dslRecorder = DefaultDslRecorder()

  @Test
  fun allAction() {
    dslRecorder.runNestedBlock("root", listOf(), ObjectWithContainer::class.java) { container.all { it.foo = 1 } }

    val groovy = GroovyBuildWriter()
    dslRecorder.writeContent(groovy)
    Truth.assertThat(groovy.toString())
      .isEqualTo(
        """
        root {
          container.all {
            foo = 1
          }
        }

        """
          .trimIndent()
      )
  }

  @Test
  fun allActionChained() {
    dslRecorder.runNestedBlock("root", listOf(), EnclosingObject::class.java) { objectWithContainer.container.all { it.foo = 1 } }

    val groovy = GroovyBuildWriter()
    dslRecorder.writeContent(groovy)
    Truth.assertThat(groovy.toString())
      .isEqualTo(
        """
        root {
          objectWithContainer.container.all {
            foo = 1
          }
        }

        """
          .trimIndent()
      )
  }

  @Test
  fun namedAndConfigure() {
    dslRecorder.runNestedBlock("root", listOf(), ObjectWithContainer::class.java) { container.named("foo") { it.foo = 1 } }

    val groovy = GroovyBuildWriter()
    dslRecorder.writeContent(groovy)
    Truth.assertThat(groovy.toString())
      .isEqualTo(
        """
        root {
          container.named('foo') {
            foo = 1
          }
        }

        """
          .trimIndent()
      )
  }

  @Test
  fun namedAndConfigureChained() {
    dslRecorder.runNestedBlock("root", listOf(), EnclosingObject::class.java) { objectWithContainer.container.named("foo") { it.foo = 1 } }

    val groovy = GroovyBuildWriter()
    dslRecorder.writeContent(groovy)
    Truth.assertThat(groovy.toString())
      .isEqualTo(
        """
        root {
          objectWithContainer.container.named('foo') {
            foo = 1
          }
        }

        """
          .trimIndent()
      )
  }
}

interface DataObject {
  var foo: Int
}

interface ObjectWithContainer {
  val container: NamedDomainObjectContainer<DataObject>

  fun container(action: NamedDomainObjectContainer<DataObject>.() -> Unit)
}

interface EnclosingObject {
  val objectWithContainer: ObjectWithContainer
}
