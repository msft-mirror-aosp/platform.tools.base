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
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution
import java.util.Optional

class JourneysFileSelectorResolver : SelectorResolver {

    override fun resolve(
        selector: DiscoverySelector,
        context: SelectorResolver.Context
    ): Resolution {
        return if (selector is DeviceSpecificDirectorySelector) {
            resolve(selector, context)
        } else {
            super.resolve(selector, context)
        }
    }

    private fun resolve(
        selector: DeviceSpecificDirectorySelector,
        context: SelectorResolver.Context
    ): Resolution {
        val files = selector.directory.listFiles() ?: emptyArray()
        val filter = JourneysTestEngineInput.journeysFilter

        val matches = files.mapNotNull { file ->
            if (!file.name.lowercase().endsWith(".journey.xml")) {
                return@mapNotNull null
            }

            if (filter.isNotEmpty() && file.name !in filter) {
                return@mapNotNull null
            }

            // We resolve files directly using `context.addToParent` to ensure they are
            // correctly nested under their device-specific parent descriptor.
            // Creating and resolving a new `FileSelector` here would cause the parent
            // to revert to the root test descriptor. This is explained by
            // JUnit 5's `context.addToParent` documentation, the parent is reset to the
            // engine descriptor unless the selector being resolved is the result of
            // expanding a `SelectorResolver.Match`. A new `FileSelector` would not
            // satisfy this condition.
            context.addToParent { parent ->
                Optional.of(JourneyFileDescriptor(parent.uniqueId, file))
            }.map {
                Match.exact(it)
            }.orElse(null)
        }

        return if (matches.isNotEmpty()) {
            Resolution.matches(matches.toSet())
        } else {
            Resolution.unresolved()
        }
    }
}
