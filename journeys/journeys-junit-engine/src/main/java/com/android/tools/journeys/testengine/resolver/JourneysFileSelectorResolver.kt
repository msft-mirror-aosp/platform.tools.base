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

package com.android.tools.journeys.testengine.resolver

import com.android.tools.journeys.testengine.JourneysTestEngineInput
import com.android.tools.journeys.testengine.descriptor.JourneyFileDescriptor
import com.android.tools.journeys.testengine.selector.DeviceSpecificDirectorySelector
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.stream.Collectors
import kotlin.io.path.name
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution

class JourneysFileSelectorResolver : SelectorResolver {

  override fun resolve(selector: DiscoverySelector, context: SelectorResolver.Context): Resolution {
    return if (selector is DeviceSpecificDirectorySelector) {
      resolve(selector, context)
    } else {
      super.resolve(selector, context)
    }
  }

  private fun resolve(selector: DeviceSpecificDirectorySelector, context: SelectorResolver.Context): Resolution {
    val journeyFiles = findJourneyFiles(selector.directory.toPath(), JourneysTestEngineInput.journeysFilter)

    val matches =
      journeyFiles.mapNotNull { file ->
        // We resolve files directly using `context.addToParent` to ensure they are
        // correctly nested under their device-specific parent descriptor.
        // Creating and resolving a new `FileSelector` here would cause the parent
        // to revert to the root test descriptor. This is explained by
        // JUnit 5's `context.addToParent` documentation, the parent is reset to the
        // engine descriptor unless the selector being resolved is the result of
        // expanding a `SelectorResolver.Match`. A new `FileSelector` would not
        // satisfy this condition.
        context
          .addToParent { parent ->
            val relativePath = file.relativeTo(selector.directory).toString()
            Optional.of(JourneyFileDescriptor(parent.uniqueId, file, relativePath))
          }
          .map { Match.exact(it) }
          .orElse(null)
      }

    return if (matches.isNotEmpty()) {
      Resolution.matches(matches.toSet())
    } else {
      Resolution.unresolved()
    }
  }

  /**
   * Finds journey files, handling OS separators and special characters.
   *
   * @param baseDir The root directory to start the search from (e.g., "app/src/journeysTest").
   * @param filter The list of filters (e.g., "auth", "auth/login.journey.xml", "basic.journey.xml").
   * @return A list of files matching any of the filters.
   */
  private fun findJourneyFiles(baseDir: Path, filterList: List<String>): List<File> {
    val allJourneyFiles =
      Files.walk(baseDir, FileVisitOption.FOLLOW_LINKS)
        .filter { Files.isRegularFile(it) }
        .filter { it.name.endsWith(".journey.xml", ignoreCase = true) }
        .map { it.toFile() }
        .collect(Collectors.toList())

    if (filterList.isEmpty()) {
      return allJourneyFiles
    }

    val matchers =
      filterList.map { filter ->
        // Keep as is if users have provided wildcards or path to a journey file.
        if (containsWildcard(filter) || filter.endsWith(".journey.xml", ignoreCase = true)) {
          FileSystems.getDefault().getPathMatcher("glob:$filter")
        } else {
          // Append /** for directory matching when users did not provide that.
          FileSystems.getDefault().getPathMatcher("glob:$filter/**")
        }
      }

    return allJourneyFiles.filter { file ->
      val relativePath = baseDir.relativize(file.toPath())
      matchers.any { it.matches(relativePath) }
    }
  }

  private fun containsWildcard(filter: String): Boolean {
    return filter.contains('*') || filter.contains('?') || filter.contains('[')
  }
}
