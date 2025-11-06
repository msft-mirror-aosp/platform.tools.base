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

import com.android.SdkConstants.FD_GRADLE
import com.android.SdkConstants.FN_VERSION_CATALOG
import com.android.ide.common.gradle.Dependency
import com.android.tools.lint.checks.GradleDetector.Companion.getNamedDependency
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.client.api.LintTomlDocument
import com.android.tools.lint.client.api.LintTomlMapValue
import com.android.tools.lint.client.api.TomlContext
import com.android.tools.lint.client.api.TomlScanner
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.GradleContext.Companion.getStringLiteralValue
import com.android.tools.lint.detector.api.GradleScanner
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.findGradleRootDir
import com.android.tools.lint.detector.api.guessGradleLocation
import com.android.tools.lint.model.LintModelAndroidLibrary
import com.android.tools.lint.model.LintModelJavaLibrary
import com.android.tools.lint.model.LintModelMavenName
import java.io.File

/**
 * Base class for looking for problems with dependencies and transitive dependencies.
 *
 * NOTE: currently only supports AARs
 */
abstract class DependencyDetector<T : DependencyDetector.DependencyIssue> :
  Detector(), GradleScanner, TomlScanner {
  /**
   * Incidents found by looking at transitive dependencies; we'll look at Gradle and TOML version
   * declaration to see if they're in this collection such that we can associate the error with a
   * user source file; for the ones we can't find (if they for example belong to a transitive
   * dependency that you don't reference directly in a source file) we'll just report them after the
   * fact in [afterCheckRootProject] pointing to the broken .so file itself as the error location.
   */
  private var reportCoordinates: MutableMap<LintModelMavenName, Incident>? = null

  abstract class DependencyIssue() {
    abstract fun toLintIncident(): Incident
  }

  /**
   * Return true from this function to enable skipping dependency analysis for known-safe
   * groups/libraries/versions for performance.
   */
  abstract fun isDependencyKnownSafe(group: String, artifact: String, version: String): Boolean

  /** Declare a cache in a companion object and provide it via this method. */
  abstract val dependencyIssueCache: HashMap<LintModelMavenName, List<DependencyIssue>>

  override fun beforeCheckRootProject(context: Context) {
    // This lint check has two separate paths; one for running in the IDE,
    // and the other for running in batch mode. The below is for the batch
    // mode, where we collect all dependencies from the dependency graph;
    // in the IDE we'll only look up dependencies we come across in the same
    // file.
    if (context.driver.isIsolated()) {
      return
    }
    val project = context.project
    val variant = project.buildVariant
    if (variant != null) {
      val artifact = variant.artifact
      val dependencies = artifact.dependencies
      for (androidLibrary in dependencies.getAll()) {
        if (androidLibrary is LintModelJavaLibrary) {
          // TODO: detect disallowed rules in jars - may need to parse jar resources
        } else if (androidLibrary is LintModelAndroidLibrary) {
          getIncidentsFromAndroidLibraryCached(androidLibrary).forEach { optionIncident ->
            recordIncident(androidLibrary.resolvedCoordinates, optionIncident.toLintIncident())
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
    targetList[coordinate] = incident
  }

  override fun afterCheckRootProject(context: Context) {
    // Handle any incidents we didn't find dependency declarations for
    var targets = reportCoordinates ?: return
    if (targets.isEmpty()) {
      return
    }

    if (LintClient.isStudio) {
      // We have dependencies we weren't able to map to a build file dependency
      // declaration. We normally just point to the actual shared library
      // file. However, inside the IDE, reporting an error on a file outside
      // the project root means it gets filtered out of the Inspections view.
      // Therefore, try a little harder to look up a suitable location for the
      // errors here. In the worst case, we'll just point to the project root
      // directory.
      val client = context.client
      var location: Location? = null
      val gradleRoot = findGradleRootDir(context.project.dir)
      if (gradleRoot != null) {
        val catalog = File(gradleRoot, "$FD_GRADLE/$FN_VERSION_CATALOG")
        if (catalog.isFile) {
          location = Location.create(catalog)

          // Also try looking in the TOML file; in the IDE, we may encounter the TOML
          // file *before* having recorded dependencies, since the IDE sync machinery
          // creates a root project holding the TOML file, without a dependency
          // graph.
          val contents = client.readFile(catalog)
          val document = client.getTomlParser().parse(catalog, contents)
          checkTomlDocument(context, document)
          targets = reportCoordinates ?: return
        }
      }
      if (location == null) {
        location = guessGradleLocation(context.project)
      }
      for ((_, incident) in targets) {
        incident.location = location
      }
    }

    val iterator = targets.iterator()
    while (iterator.hasNext()) {
      val (_, incident) = iterator.next()
      context.report(incident)
      iterator.remove()
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
    val targets = reportCoordinates
    if (!context.driver.isIsolated() && targets == null) {
      return
    }
    if (parent == "dependencies" || parent == "declarativeDependencies") {
      val dependencyString = getStringLiteralValue(value, valueCookie) ?: getNamedDependency(value)
      if (dependencyString != null) {
        val dependency = Dependency.parse(dependencyString)
        val groupId = dependency.group ?: return
        val artifactId = dependency.name
        if (targets == null) {
          // isolated: check in IDE dependencies
          checkArtifactReference(context, groupId, dependency.name) {
            context.getLocation(valueCookie)
          }
        } else {
          for ((coordinate, incident) in targets) {
            if (coordinate.groupId == groupId && coordinate.artifactId == artifactId) {
              incident.location = context.getLocation(valueCookie)
              context.report(incident)
              targets.remove(coordinate)
              return
            }
          }
        }
      }
    }
  }

  override fun visitTomlDocument(context: TomlContext, document: LintTomlDocument) {
    checkTomlDocument(context, document)
  }

  private fun checkTomlDocument(context: Context, document: LintTomlDocument) {
    val targets = reportCoordinates
    if (!context.driver.isIsolated() && targets == null) {
      return
    }
    val libraries = document.getValue(VC_LIBRARIES) as? LintTomlMapValue
    if (libraries != null) {
      val versions = document.getValue(VC_VERSIONS) as? LintTomlMapValue
      // Batch: we've recorded problems in [reportCoordinates]; try to
      // map these to dependencies we find anywhere, including transitive
      // dependencies
      for ((_, library) in libraries.getMappedValues()) {
        val (coordinate, _) = getLibraryFromTomlEntry(versions, library) ?: continue
        val dependency = Dependency.parse(coordinate)
        val groupId = dependency.group ?: continue
        val artifactId = dependency.name
        if (targets != null) {
          for ((coordinate, incident) in targets) {
            if (coordinate.groupId == groupId && coordinate.artifactId == artifactId) {
              incident.location = context.getLocation(library)
              context.report(incident)
              targets.remove(coordinate)
              return
            }
          }
        } else {
          // In the IDE: Just check dependencies directly
          checkArtifactReference(context, groupId, artifactId) { context.getLocation(library) }
        }
      }
    }
  }

  private fun checkArtifactReference(
    context: Context,
    groupId: String?,
    artifactId: String,
    locationProvider: () -> Location,
  ) {
    groupId ?: return

    val full = context.isGlobalAnalysis()
    val project = if (full) context.mainProject else context.project

    val androidLibrary = project.buildVariant?.artifact?.findCompileDependency(groupId, artifactId)
    if (androidLibrary is LintModelAndroidLibrary) {
      getIncidentsFromAndroidLibraryCached(androidLibrary).forEach { optionIncident ->
        val incident = optionIncident.toLintIncident()
        incident.location = locationProvider()
        context.report(incident)
      }
    }
  }

  abstract fun getIncidentsFromAndroidLibrary(
    library: LintModelAndroidLibrary
  ): List<DependencyIssue>

  /**
   * Given a [library] definition, returns a list of [DependencyIssue]s found in the library, using
   * the cache if running from Studio.
   */
  private fun getIncidentsFromAndroidLibraryCached(
    library: LintModelAndroidLibrary
  ): List<DependencyIssue> {
    val coordinate: LintModelMavenName = library.resolvedCoordinates

    val group = coordinate.groupId
    val artifact = coordinate.artifactId
    val version = coordinate.version
    if (isDependencyKnownSafe(group, artifact, version)) {
      return emptyList()
    }

    // We only cache in the IDE (for on-the-fly analysis as the user is editing;
    // don't do this from AGP since this is not thread safe.)
    val useCache = LintClient.isStudio
    if (useCache) {
      val cached = dependencyIssueCache[coordinate]
      if (cached != null) {
        return cached
      }
      dependencyIssueCache[coordinate] = emptyList()
    }

    val errors = getIncidentsFromAndroidLibrary(library)
    if (useCache) {
      dependencyIssueCache[coordinate] = errors
    }
    return errors
  }
}
