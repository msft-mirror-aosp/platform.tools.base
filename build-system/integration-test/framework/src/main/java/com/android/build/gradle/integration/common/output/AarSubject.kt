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

import com.android.build.gradle.internal.tasks.AarMetadataReader
import com.android.build.gradle.internal.tasks.AarMetadataTask
import com.google.common.truth.FailureMetadata
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertAbout
import java.io.File
import java.nio.file.Path
import java.util.function.Consumer
import java.util.regex.Pattern
import kotlin.io.path.inputStream

private val PATTERN_LIBS_JAR = Pattern.compile("^libs/.+$")

/** A truth subject to validate the content of an AAR. */
@SubjectDsl
class AarSubject(metadata: FailureMetadata, actual: Zip) : AbstractAndroidArchiveSubject<AarSubject, Zip>(metadata, actual) {

  companion object {
    /** Runs the provided action on an [AarSubject] that is created for the zip at the provided path. */
    fun assertThat(path: Path, action: AarSubject.() -> Unit) {
      SimpleZip(path).use { action(assertAbout(aars()).that(it)) }
    }

    /** Runs the provided action on an [AarSubject] that is created for the zip at the provided path. */
    fun assertThat(file: File, action: AarSubject.() -> Unit) {
      assertThat(file.toPath(), action)
    }

    /** Runs the provided action on an [AarSubject] that is created for the zip at the provided path. */
    @JvmStatic
    fun assertThat(path: Path, action: Consumer<AarSubject>) {
      SimpleZip(path).use { action.accept(assertAbout(aars()).that(it)) }
    }

    /** Runs the provided action on an [AarSubject] that is created for the zip at the provided path. */
    @JvmStatic
    fun assertThat(file: File, action: Consumer<AarSubject>) {
      assertThat(file.toPath(), action)
    }

    internal fun assertThat(zip: Zip, action: AarSubject.() -> Unit) {
      action(assertAbout(aars()).that(zip))
    }

    internal fun aars(): Factory<AarSubject, Zip> {
      return Factory<AarSubject, Zip> { metadata, actual -> AarSubject(metadata, actual) }
    }
  }

  override fun classes(): ClassesSubject {
    exists()
    return codeJarSubject
  }

  /**
   * Cached instance of [ClassesSubject] around all the code jars in the AAR.
   *
   * Computing entry list over several jars is costly so it's better cached.
   */
  private val codeJarSubject: ClassesSubject by lazy {
    check("classes()").about(JarWithClassesSubject.jars()).that(MultiZipView(computeAllJars(), "codeJars"))
  }

  /**
   * Returns a [ResourcesSubject] for all java resources in the AAR.
   *
   * This pulls the resources from classes.jar and the jars in the lib/ folder.
   */
  override fun javaResources(): ResourcesSubject {
    exists()
    return javaResourcesSubject
  }

  /**
   * Cached instance of [ResourcesSubject] around all the code jars in the AAR.
   *
   * Computing entry list over several jars is costly so it's better cached.
   */
  private val javaResourcesSubject: ResourcesSubject by lazy {
    check("javaResources()").about(JarWithJavaResourcesSubject.jars()).that(MultiZipView(computeAllJars(), "javaResources"))
  }

  /** returns a [JarSubject] for the main jar of the AAR (classes.jar) */
  fun mainJar(): JarSubject = jar("classes.jar", methodName = "mainJar()")

  /** creates a [JarSubject] for the main jar of the AAR (classes.jar), and configures it via the given action. */
  fun mainJar(action: JarSubject.() -> Unit) {
    action(mainJar())
  }

  /** creates a [JarSubject] for the main jar of the AAR (classes.jar), and configures it via the given action. */
  fun mainJar(action: Consumer<JarSubject>) {
    mainJar { action.accept(this) }
  }

  /**
   * returns a [ClassesSubject] for the api jar of the AAR (api.jar).
   *
   * While this is a Jar, there's no resources in there (it's not used at runtime, and during compilation the Java resources are not used),
   * so [ClassesSubject] is better.
   */
  fun apiJar(): ClassesSubject {
    val path = "api.jar"
    contains(path)

    // this can be null when we're testing the fixture. In normal operation, the call
    // to contains above guarantees that it's not null
    // It's ok to not close this empty zip as it's not using a real file.
    // Inner zips are automatically closed when the enclosing zip is closed.
    val jar = actual().innerZip(path) ?: SimpleZip(null)

    return check("apiJar()").about(JarWithClassesSubject.jars()).that(jar)
  }

  /** creates a [ClassesSubject] for the api jar of the AAR (api.jar), and configures it via the given action. */
  fun apiJar(action: ClassesSubject.() -> Unit) {
    action(apiJar())
  }

  /** creates a [ClassesSubject] for the api jar of the AAR (api.jar), and configures it via the given action. */
  fun apiJar(action: Consumer<ClassesSubject>) {
    apiJar { action.accept(this) }
  }

  fun hasNoSecondaryJars() {
    exists()
    val view = ZipFolderView(actual(), "libs")
    check("secondaryJars").that(view.getEntries()).isEmpty()
  }

  fun hasSecondaryJars(vararg jars: String) {
    exists()
    val view = ZipFolderView(actual(), "libs")

    check("secondaryJars()").that(view.getEntries()).containsExactly(*jars)
  }

  /** Returns all the classes from any secondary jars as a single [JarSubject]. */
  fun secondaryJars(): JarSubject {
    exists()

    // Inner zips are automatically closed when the enclosing zip is closed.
    val secondaryJars = actual().getEntries(PATTERN_LIBS_JAR).mapNotNull { actual().innerZip(it) }

    val zipView = MultiZipView(secondaryJars, "secondaryJars")
    return JarSubject(
      code = check("secondaryJars()").about(JarWithClassesSubject.jars()).that(zipView),
      resources = check("secondaryJars()").about(JarWithJavaResourcesSubject.jars()).that(zipView),
    )
  }

  /** Creates a [JarSubject] representing all the classes from the secondary jars, and configure it with the given action */
  fun secondaryJars(action: JarSubject.() -> Unit) {
    action(secondaryJars())
  }

  /** Creates a [JarSubject] representing all the classes from the secondary jars, and configure it with the given action */
  fun secondaryJars(action: Consumer<JarSubject>) {
    secondaryJars { action.accept(this) }
  }

  /** returns a [StringSubject] for the Android Manifest of the AAR. */
  override fun manifest(): StringSubject {
    contains("AndroidManifest.xml")
    return check("manifest()").that(actual().textFile("AndroidManifest.xml"))
  }

  /** returns a [StringSubject] for the text symbold file (R.txt) */
  fun textSymbolFile(): StringSubject {
    contains("R.txt")
    return check("textSymbolFile()").that(actual().textFile("R.txt"))
  }

  /** returns a [StringSubject] for the public resources file (public.txt) */
  fun publicResFile(): StringSubject {
    contains("public.txt")
    return check("publicResFile()").that(actual().textFile("public.txt"))
  }

  /** returns a [JarSubject] for the lint jar of the AAR (lint.jar). */
  fun lintJar(): JarSubject = jar("lint.jar", methodName = "lintJar()")

  /** creates a [JarSubject] for the lint jar of the AAR (lint.jar), and configures it via the given action. */
  fun lintJar(action: JarSubject.() -> Unit) {
    action(lintJar())
  }

  /** creates a [JarSubject] for the lint jar of the AAR (lint.jar), and configures it via the given action. */
  fun lintJar(action: Consumer<JarSubject>) {
    lintJar { action.accept(this) }
  }

  override fun jniLibs(): JniSubject {
    exists()
    return check("jniLibs()").about(JniSubjectImpl.libs()).that(ZipFolderView(actual(), "jni"))
  }

  /** returns a [AarMetadataSubject] for the metadata of this AAR */
  fun aarMetadata(): AarMetadataSubject {
    contains(AarMetadataTask.AAR_METADATA_ENTRY_PATH)

    val path = actual().getEntry(AarMetadataTask.AAR_METADATA_ENTRY_PATH)

    // this can be null when we're testing the fixture. In normal operation, the call
    // to contains above guarantees that it's not null
    val aarMetadata = path?.let { AarMetadataReader.load(it.inputStream()) } ?: AarMetadataReader.load("".byteInputStream())

    return check("aarMetadata()").about(AarMetadataSubject.aarmetadatas()).that(aarMetadata)
  }

  /** Creates a [AarMetadataSubject] for the metadata of this AAR, and runs the given action on it. */
  fun aarMetadata(action: AarMetadataSubject.() -> Unit) {
    action(aarMetadata())
  }

  private fun jar(path: String, methodName: String = "jar($path)"): JarSubject {
    contains(path)

    // this can be null when we're testing the fixture. In normal operation, the call
    // to contains above guarantees that it's not null
    // It's ok to not close this empty zip as it's not using a real file.
    // Inner zips are automatically closed when the enclosing zip is closed.
    val jar = actual().innerZip(path) ?: SimpleZip(null)

    return JarSubject(
      code = check(methodName).about(JarWithClassesSubject.jars()).that(jar),
      resources = check(methodName).about(JarWithJavaResourcesSubject.jars()).that(jar),
    )
  }

  /** Returns the list of "code" jar in the AAR. This is mainly classes.jar + the jars under lib/ */
  private fun computeAllJars(): List<Zip> {
    // Inner zips are automatically closed when the enclosing zip is closed.
    val mainJar = actual().innerZip("classes.jar")
    val secondaryJars = actual().getEntries(PATTERN_LIBS_JAR).mapNotNull { actual().innerZip(it) }

    return if (mainJar != null) {
      buildList {
        // we want to keep this one first
        add(mainJar)
        addAll(secondaryJars)
      }
    } else secondaryJars
  }
}
