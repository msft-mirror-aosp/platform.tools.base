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

import com.google.common.truth.FailureMetadata
import com.google.common.truth.StringSubject
import java.util.function.Consumer

/** Base class for truth subject for AAR and APK. */
abstract class AbstractAndroidArchiveSubject<S : AbstractAndroidArchiveSubject<S, T>, T : Zip>
internal constructor(metadata: FailureMetadata, actual: T) : AbstractZipSubject<S, T>(metadata, actual) {

  /** returns a [StringSubject] for the Android Manifest of the archive. */
  abstract fun manifest(): StringSubject

  /** Returns a [ClassesSubject] for all code in the archive */
  abstract fun classes(): ClassesSubject

  fun classes(action: ClassesSubject.() -> Unit) {
    action(classes())
  }

  fun classes(action: Consumer<ClassesSubject>) {
    action.accept(classes())
  }

  /**
   * returns [ResourcesSubject] for the Android resources
   *
   * The names of the files do NOT include the res folder.
   */
  fun androidResources(): ResourcesSubject {
    exists()
    return check("androidResources()").about(FullJarResourcesSubject.jars()).that(ZipFolderView(actual(), "res"))
  }

  /** Creates a [ResourcesSubject] representing all the android resources, and configure it with the given action */
  fun androidResources(action: ResourcesSubject.() -> Unit) {
    action(androidResources())
  }

  /** Creates a [ResourcesSubject] representing all the android resources, and configure it with the given action */
  fun androidResources(action: Consumer<ResourcesSubject>) {
    action.accept(androidResources())
  }

  /**
   * returns [ResourcesSubject] for the Android assets
   *
   * The names of the files do NOT include the assets folder.
   */
  fun assets(): ResourcesSubject {
    exists()
    return check("assets()").about(FullJarResourcesSubject.jars()).that(ZipFolderView(actual(), "assets"))
  }

  /** Creates a [ResourcesSubject] representing all the android assets, and configure it with the given action */
  fun assets(action: ResourcesSubject.() -> Unit) {
    action(assets())
  }

  /** Creates a [ResourcesSubject] representing all the android assets, and configure it with the given action */
  fun assets(action: Consumer<ResourcesSubject>) {
    action.accept(assets())
  }

  /**
   * returns [JniSubject] for the Android resources
   *
   * The names of the files do NOT include the res folder.
   */
  abstract fun jniLibs(): JniSubject

  /** Creates a [ZipSubject] representing all the android resources, and configure it with the given action */
  fun jniLibs(action: JniSubject.() -> Unit) {
    action(jniLibs())
  }

  /** Creates a [ZipSubject] representing all the android resources, and configure it with the given action */
  fun jniLibs(action: Consumer<JniSubject>) {
    action.accept(jniLibs())
  }

  /**
   * returns [ResourcesSubject] for the Java resources
   *
   * The names of the files do NOT include the res folder.
   */
  abstract fun javaResources(): ResourcesSubject

  /** Creates a [ZipSubject] representing all the android resources, and configure it with the given action */
  fun javaResources(action: ResourcesSubject.() -> Unit) {
    action(javaResources())
  }

  /** Creates a [ZipSubject] representing all the android resources, and configure it with the given action */
  fun javaResources(action: Consumer<ResourcesSubject>) {
    javaResources { action.accept(this) }
  }
}
