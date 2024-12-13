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
package com.android.tools.lint.checks

import com.android.SdkConstants.FD_JNI
import com.android.ide.common.gradle.Dependency
import com.android.ide.common.pagealign.hasElfMagicNumber
import com.android.ide.common.pagealign.is16kAligned
import com.android.ide.common.pagealign.readElfMinimumLoadSectionAlignment
import com.android.tools.lint.checks.GradleDetector.Companion.getNamedDependency
import com.android.tools.lint.client.api.LintTomlDocument
import com.android.tools.lint.client.api.LintTomlMapValue
import com.android.tools.lint.client.api.TomlContext
import com.android.tools.lint.client.api.TomlScanner
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.GradleContext.Companion.getStringLiteralValue
import com.android.tools.lint.detector.api.GradleScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope.Companion.GRADLE_AND_TOML_SCOPE
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.model.LintModelAndroidLibrary
import com.android.tools.lint.model.LintModelMavenName
import java.io.File

/** Looks for problems with transitive libraries not being properly 16 KB aligned. */
class PageAlignmentDetector : Detector(), GradleScanner, TomlScanner {
  /**
   * Incidents found by looking at transitive dependencies; we'll look at Gradle and TOML version
   * declaration to see if they're in this collection such that we can associate the error with a
   * user source file; for the ones we can't find (if they for example belong to a transitive
   * dependency that you don't reference directly in a source file) we'll just report them after the
   * fact in [afterCheckRootProject] pointing to the broken .so file itself as the error location.
   */
  private var reportCoordinates: MutableMap<LintModelMavenName, Incident>? = null

  override fun beforeCheckRootProject(context: Context) {
    // This is an expensive lint check; don't do this on the fly in the IDE
    if (context.driver.isIsolated()) {
      return
    }
    val project = context.project
    val variant = project.buildVariant
    if (variant != null) {
      val artifact = variant.artifact
      val dependencies = artifact.dependencies
      for (androidLibrary in dependencies.getAll()) {
        if (androidLibrary is LintModelAndroidLibrary) {
          val folder = androidLibrary.folder
          val jni = File(folder, FD_JNI)
          if (jni.isDirectory) {
            checkLibraryJniFolder(androidLibrary, jni)
          }
        }
      }
    }
  }

  private fun checkLibraryJniFolder(androidLibrary: LintModelAndroidLibrary, jniFolder: File) {
    for (abiFolder in jniFolder.listFiles().sorted()) {
      for (sharedLibrary in abiFolder.listFiles().sorted()) {
        if (sharedLibrary.isFile) {
          val input = sharedLibrary.inputStream().buffered()
          input.use {
            if (hasElfMagicNumber(input)) {
              val minimumLoadSectionAlignment = readElfMinimumLoadSectionAlignment(input)
              if (minimumLoadSectionAlignment != -1L) {
                if (!is16kAligned(minimumLoadSectionAlignment)) {
                  // In theory, we could search from the dependency roots down to the
                  // individual library to figure out the dependency path. Unfortunately,
                  // currently, the variant compile dependencies are a flat list so this is
                  // pointless.
                  val location = Location.create(sharedLibrary)
                  val libraryName = "${sharedLibrary.parentFile?.name}/${sharedLibrary.name}"
                  val coordinates = androidLibrary.resolvedCoordinates
                  val message =
                    "The native library `$libraryName` (from `$coordinates`) is not 16 KB aligned"
                  val incident = Incident(ISSUE, location, message)
                  recordIncident(coordinates, incident)
                  // Only flag one native library for this artifact, so break/return
                  // out of the jni loop
                  return
                }
              }
            }
          }
        }
      }
    }
  }

  private fun recordIncident(coordinate: LintModelMavenName, incident: Incident) {
    // We don't report the incident right away; instead, we record the
    // coordinate and look for it in dependency declarations in gradle and
    // version catalogs, and if it matches, we'll report the error there (and
    // remove it from this list). Any unreported errors at the end are reported
    // from afterCheckRootProject.
    val targetList =
      reportCoordinates
        ?: mutableMapOf<LintModelMavenName, Incident>().also { reportCoordinates = it }
    targetList.put(coordinate, incident)
  }

  override fun afterCheckRootProject(context: Context) {
    // Handle any incidents we didn't find dependency declarations for
    val targets = reportCoordinates ?: return
    for ((coordinate, incident) in targets) {
      context.report(incident)
      targets.remove(coordinate)
    }
    reportCoordinates = null
  }

  override fun checkDslPropertyAssignment(
    context: GradleContext,
    property: String,
    value: String,
    parent: String,
    parentParent: String?,
    propertyCookie: Any,
    valueCookie: Any,
    statementCookie: Any,
  ) {
    val targets = reportCoordinates ?: return
    if (parent == "dependencies" || parent == "declarativeDependencies") {
      val dependencyString = getStringLiteralValue(value, valueCookie) ?: getNamedDependency(value)
      if (dependencyString != null) {
        val dependency = Dependency.parse(dependencyString)
        for ((coordinate, incident) in targets) {
          if (coordinate.groupId == dependency.group && coordinate.artifactId == dependency.name) {
            incident.location = context.getLocation(valueCookie)
            context.report(incident)
            targets.remove(coordinate)
            return
          }
        }
      }
    }
  }

  override fun visitTomlDocument(context: TomlContext, document: LintTomlDocument) {
    val targets = reportCoordinates ?: return
    val libraries = document.getValue(VC_LIBRARIES) as? LintTomlMapValue
    if (libraries != null) {
      val versions = document.getValue(VC_VERSIONS) as? LintTomlMapValue
      for ((_, library) in libraries.getMappedValues()) {
        val (coordinate, _) = getLibraryFromTomlEntry(versions, library) ?: continue
        val dependency = Dependency.parse(coordinate)
        for ((coordinate, incident) in targets) {
          if (coordinate.groupId == dependency.group && coordinate.artifactId == dependency.name) {
            incident.location = context.getLocation(library)
            context.report(incident)
            targets.remove(coordinate)
            return
          }
        }
      }
    }
  }

  companion object {
    @JvmField
    val ISSUE =
      Issue.create(
        id = "Aligned16KB",
        briefDescription = "Native library dependency not 16 KB aligned",
        explanation =
          """
          Historically, Android has aligned memory using 4 KB memory page sizes, \
          which optimized system memory performance for the average amount \
          of total memory that Android devices have typically had.

          To support devices that only support 16 KB aligned libraries in the future, \
          the Google Play Store will soon require all apps to be compiled with 16 KB \
          aligned libraries.

          An app compiled with 4 KB aligned libraries will not work correctly \
          on these devices. To ensure compatibility with these devices and to \
          future-proof your app, the Play Store will require native libraries to \
          be aligned to 16 KB boundaries.

          If your app uses any NDK libraries, either directly or indirectly \
          through an SDK, you'll need to rebuild your app to meet this new \
          requirement. This means ensuring that all native libraries within \
          your app, including those from any dependencies, are built with 16 \
          KB page alignment.

          This lint check helps identify potential issues by inspecting all \
          transitive libraries your app depends on. If any nested native \
          libraries are found to be aligned only to 4 KB, you'll need to \
          take action.

          If lint flags a library, try updating to a newer version that \
          supports 16 KB alignment. If no updated version is available, \
          reach out to the library vendor for assistance.
          """,
        category = Category.CORRECTNESS,
        priority = 2,
        severity = Severity.WARNING,
        implementation = Implementation(PageAlignmentDetector::class.java, GRADLE_AND_TOML_SCOPE),
        androidSpecific = true,
        moreInfo = "https://developer.android.com/guide/practices/page-sizes",
        // Not yet enabled. Consider tying this to a StudioFlag.
        enabledByDefault = false,
      )
  }
}
