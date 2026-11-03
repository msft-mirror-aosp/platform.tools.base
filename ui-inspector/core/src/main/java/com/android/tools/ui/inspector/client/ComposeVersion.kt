/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.ui.inspector.client

/**
 * A Compose release since 1.0, as published on Google Maven: `MAJOR.MINOR.PATCH`, optionally followed by `-alphaNN`, `-betaNN` or `-rcNN`.
 * Ordered by release: the alphas, then the betas, then the release candidates of a version come before the version itself.
 */
internal data class ComposeVersion(val major: Int, val minor: Int, val patch: Int, val stage: Stage, val stageNumber: Int) :
  Comparable<ComposeVersion> {

  /** The release stage. The order of the entries is the release order. */
  enum class Stage {
    ALPHA,
    BETA,
    RC,
    STABLE,
  }

  override fun compareTo(other: ComposeVersion): Int = COMPARATOR.compare(this, other)

  /** The version as published, e.g. "1.11.0-beta01". */
  override fun toString(): String =
    if (stage == Stage.STABLE) "$major.$minor.$patch"
    else "$major.$minor.$patch-${stage.name.lowercase()}${stageNumber.toString().padStart(2, '0')}"

  companion object {
    private val COMPARATOR = compareBy<ComposeVersion>({ it.major }, { it.minor }, { it.patch }, { it.stage }, { it.stageNumber })

    private val REGEX = Regex("""(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)(\d+))?""")

    /** Parses [version], or returns null when it is not a Compose release, e.g. a locally built version. */
    fun parse(version: String): ComposeVersion? {
      val match = REGEX.matchEntire(version) ?: return null
      val (major, minor, patch, stage, stageNumber) = match.destructured
      return ComposeVersion(
        major = major.toIntOrNull() ?: return null,
        minor = minor.toIntOrNull() ?: return null,
        patch = patch.toIntOrNull() ?: return null,
        stage = if (stage.isEmpty()) Stage.STABLE else Stage.valueOf(stage.uppercase()),
        stageNumber = if (stageNumber.isEmpty()) 0 else stageNumber.toIntOrNull() ?: return null,
      )
    }

    /** Parses [version], a Compose release. */
    fun of(version: String): ComposeVersion = checkNotNull(parse(version)) { "Not a Compose release: $version" }
  }
}
