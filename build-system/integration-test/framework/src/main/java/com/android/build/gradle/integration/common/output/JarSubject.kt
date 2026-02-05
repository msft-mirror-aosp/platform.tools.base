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

package com.android.build.gradle.integration.common.output

import java.io.File
import java.nio.file.Path
import java.util.function.Consumer

/**
 * Truth subject for a zip archive as a jar.
 *
 * This provides custom content validation, in a different way than [ZipSubject] does.
 *
 * Note that this is actually not a Truth [javax.security.auth.Subject]. This combines 2 actual subjects ([ClassesSubject] and
 * [ResourcesSubject]) in a single class to represent a Jar.
 */
@SubjectDsl
class JarSubject internal constructor(private val code: ClassesSubject, private val resources: ResourcesSubject) {

  companion object {
    fun assertThat(path: Path, action: JarSubject.() -> Unit) {
      SimpleZip(path).use { zip -> action(JarSubject(JarWithClassesSubject.assertThat(zip), JarWithJavaResourcesSubject.assertThat(zip))) }
    }

    fun assertThat(file: File, action: JarSubject.() -> Unit) {
      assertThat(file.toPath(), action)
    }
  }

  /** Returns a [ClassesSubject] for the classes inside this jar */
  fun classes(): ClassesSubject = code

  /** Applies the action to the [ClassesSubject] returned by [classes] */
  fun classes(action: ClassesSubject.() -> Unit) {
    action(classes())
  }

  /** Applies the action to the [ClassesSubject] returned by [classes] */
  fun classes(action: Consumer<ClassesSubject>) {
    action.accept(classes())
  }

  /** Returns a [ResourcesSubject] for the resources inside this jar */
  fun resources(): ResourcesSubject = resources

  /** Applies the action to the [ResourcesSubject] returned by [resources] */
  fun resources(action: ResourcesSubject.() -> Unit) {
    action(resources())
  }

  /** Applies the action to the [ResourcesSubject] returned by [resources] */
  fun resources(action: Consumer<ResourcesSubject>) {
    action.accept(resources())
  }
}
