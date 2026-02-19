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
import com.android.ide.common.gradle.Version
import com.android.ide.common.pagealign.AlignmentProblem
import com.android.ide.common.pagealign.hasElfMagicNumber
import com.android.ide.common.pagealign.readElfAlignmentProblems
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope.Companion.GRADLE_AND_TOML_SCOPE
import com.android.tools.lint.detector.api.Scope.Companion.GRADLE_SCOPE
import com.android.tools.lint.detector.api.Scope.Companion.TOML_SCOPE
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.model.LintModelAndroidLibrary
import com.android.tools.lint.model.LintModelMavenName
import java.io.File

/** Looks for problems with transitive libraries not being properly 16 KB aligned. */
class PageAlignmentDetector : DependencyDetector<PageAlignmentDetector.PageAlignmentIssue>() {
  /** Represents a specific problematic global option at a specified file */
  class PageAlignmentIssue(val coordinates: LintModelMavenName, val sharedLibrary: File) : DependencyIssue() {
    override fun toLintIncident(): Incident {
      val location = Location.create(sharedLibrary)
      val libraryName = "${sharedLibrary.parentFile?.name}/${sharedLibrary.name}"
      val message = "The native library `$libraryName` (from `$coordinates`) is not 16 KB aligned"
      val incident = Incident(ISSUE, location, message)
      return incident
    }
  }

  /**
   * Returns whether the given group+artifact+version Google maven artifact is known to be safe (e.g. does not have a 16 KB alignment
   * problem).
   *
   * This is based on scanning all libraries on gmaven across all versions and looking for alignment problems.
   *
   * This of course only knows about the libraries up until the time of scanning, but the assumption is that as of now, all future libraries
   * are correctly compiled, so this just avoids doing a lot of unnecessary I/O on packages in the gmaven names space unless they're for
   * known older versions.
   *
   * Note that this method returning false doesn't mean that the library is known to be incompatible; in that case, the lint check should
   * look. (For example, the incompatibility could be in one of the ABIs that are filtered out by this app --
   * https://developer.android.com/build/configure-apk-splits#configure-abi-split
   */
  override fun isDependencyKnownSafe(group: String, artifact: String, version: String): Boolean {
    val lastBroken =
      when (group) {
        "androidx.appsearch" -> if (artifact == "appsearch-local-storage") "1.1.0-alpha03" else null
        "androidx.datastore" -> if (artifact == "datastore-core-android") "1.1.0-beta01" else null
        "androidx.graphics" -> {
          when (artifact) {
            "graphics-core" -> "1.0.0-beta01"
            "graphics-path" -> "1.0.0-beta02"
            else -> null
          }
        }
        "androidx.tracing" -> if (artifact == "tracing-perfetto-binary") "1.0.0" else null
        "com.crashlytics.sdk.android" -> if (artifact == "crashlytics-ndk") "2.1.1" else null
        "com.google.ai.edge.litert" -> {
          when (artifact) {
            "litert-gpu" -> "1.0.1"
            "litert" -> "1.0.1"
            else -> null
          }
        }
        "com.google.android.games" -> if (artifact == "memory-advice") "0.24" else null
        "com.google.android.gms" -> {
          when (artifact) {
            "play-services-tflite-java" -> "16.2.0-beta02"
            "play-services-vision-face-contour-internal" -> "16.1.0"
            "play-services-vision-image-labeling-internal" -> "16.1.0"
            else -> null
          }
        }
        "com.google.android.libraries.navigation" -> if (artifact == "navigation") "5.2.5" else null
        "com.google.ar" -> if (artifact == "core") "1.42.0" else null
        "com.google.ar.sceneform" -> {
          when (artifact) {
            "animation" -> "1.17.1"
            "assets" -> "1.17.1"
            "core" -> "1.17.1"
            "filament-android" -> "1.17.1"
            else -> null
          }
        }
        "com.google.firebase" -> {
          when (artifact) {
            "firebase-crashlytics-ndk" -> "19.0.1"
            "firebase-ml-natural-language-language-id-model" -> "20.0.8"
            "firebase-ml-natural-language-smart-reply-model" -> "20.0.8"
            "firebase-ml-natural-language-translate-model" -> "20.0.9"
            "firebase-ml-vision-barcode-model" -> "16.1.2"
            "firebase-ml-vision-internal-vkp" -> "17.0.2"
            else -> null
          }
        }
        "com.google.mediapipe" -> {
          when (artifact) {
            "solution-core" -> "0.10.20"
            "tasks-audio" -> "0.20230731"
            "tasks-genai" -> "0.10.20"
            "tasks-text" -> "0.20230731"
            "tasks-vision-image-generator" -> "0.10.20"
            "tasks-vision" -> "0.20230731"
            else -> null
          }
        }
        "com.google.mlkit" -> {
          when (artifact) {
            "barcode-scanning" -> "17.2.0"
            "digital-ink-recognition" -> "18.1.0"
            "entity-extraction" -> "16.0.0-beta5"
            "language-id" -> "17.0.5"
            "mediapipe-internal" -> "17.0.0-beta9"
            "smart-reply" -> "17.0.3"
            "text-recognition-bundled-common" -> "16.0.0"
            "translate" -> "17.0.2"
            "vision-internal-vkp" -> "18.2.2"
            else -> null
          }
        }
        "org.chromium.net" -> if (artifact == "cronet-embedded") "119.6045.31" else null
        else -> null
      }
    if (lastBroken == null) {
      // The above list includes all cases of maven.google.com libraries that contain
      // JNI libraries where at least one version has code that is not 16 KB aligned.
      // That means that if we're presented with any OTHER package from AndroidX,
      // it's safe, and we don't have to go look on disk.
      // (If we wanted a really accurate list we can use GoogleMavenRepository,
      // which stores all the relevant group id's in the master-index file, but
      // it's not required; this is just an optimization path to avoid I/O for *most*
      // libraries.)
      return group.startsWith("androidx.") ||
        group.startsWith("com.google.") ||
        group.startsWith("com.android.") ||
        group == "org.chromium.net" ||
        group.startsWith("com.crashlytics.")
    } else {
      val parsedVersion = Version.parse(version)
      val parsedLastBrokenVersion = Version.parse(lastBroken)
      return parsedVersion > parsedLastBrokenVersion
    }
  }

  override val dependencyIssueCache: HashMap<LintModelMavenName, List<DependencyIssue>>
    get() = _dependencyIssueCache

  override fun getIncidentsFromAndroidLibrary(library: LintModelAndroidLibrary): List<DependencyIssue> {
    val folder = library.folder
    val jniFolder = File(folder, FD_JNI)
    if (jniFolder.isDirectory) {
      abiFolderLoop@ for (abiFolder in jniFolder.listFiles().sorted()) {
        for (sharedLibrary in abiFolder.listFiles().sorted()) {
          if (sharedLibrary.isFile) {
            val input = sharedLibrary.inputStream().buffered()
            input.use {
              if (hasElfMagicNumber(input)) {
                val alignmentProblems = readElfAlignmentProblems(input)
                if (alignmentProblems != null && alignmentProblems.any { it is AlignmentProblem.LoadSectionNotAligned }) {
                  // TODO: consider reporting multiple
                  return listOf(PageAlignmentIssue(library.resolvedCoordinates, sharedLibrary = sharedLibrary))
                }
              }
            }
          }
        }
      }
    }
    return emptyList()
  }

  companion object {
    // each subclass declares its own separate static cache
    private val _dependencyIssueCache = HashMap<LintModelMavenName, List<DependencyIssue>>()

    @JvmField
    val ISSUE =
      Issue.create(
        id = "Aligned16KB",
        briefDescription = "Native library dependency not 16 KB aligned",
        explanation =
          """
          Android has traditionally used 4 KB memory page sizes. However, to support \
          future devices that only work with 16 KB aligned libraries apps containing \
          native libraries need to be built with 16 KB alignment.

          Apps with 4 KB aligned native libraries may not work correctly on devices \
          requiring 16 KB alignment. To ensure compatibility and future-proof your \
          app, it is strongly recommended that your native libraries are aligned to 16 \
          KB boundaries.

          If your app uses any NDK libraries, directly or indirectly through an SDK, \
          you should rebuild your app to meet this recommendation. Make sure all \
          native libraries within your application, including those from dependencies, \
          are built with 16 KB page alignment.

          This lint check looks at all native libraries that your app depends on. If \
          any are found to be aligned to 4 KB instead of 16 KB, you will need to \
          address this.

          When a library is flagged, first try to update to a newer version that \
          supports 16 KB alignment. If an updated version is not available, contact \
          the library vendor to ask about their plans for 16 KB support and request a \
          compatible version. Updating your libraries proactively will help ensure \
          your app works properly on a wider range of devices.
          """,
        category = Category.CORRECTNESS,
        priority = 2,
        severity = Severity.WARNING,
        implementation = Implementation(PageAlignmentDetector::class.java, GRADLE_AND_TOML_SCOPE, GRADLE_SCOPE, TOML_SCOPE),
        androidSpecific = true,
        moreInfo = "https://developer.android.com/guide/practices/page-sizes",
      )
  }
}
