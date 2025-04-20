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

package com.android.journeys.testengine.resolver

import com.android.journeys.testengine.JourneysTestEngineInput
import com.android.journeys.testengine.descriptor.JourneyFileDescriptor
import com.android.journeys.testengine.selector.DeviceSelector
import org.junit.platform.engine.discovery.DirectorySelector
import org.junit.platform.engine.discovery.DiscoverySelectors.selectFile
import org.junit.platform.engine.discovery.FileSelector
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution.selectors
import java.util.Optional
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.jvm.optionals.getOrNull

class JourneysFileSelectorResolver : SelectorResolver {
    override fun resolve(
        selector: DirectorySelector,
        context: SelectorResolver.Context
    ): Resolution {
        val files = selector.directory.listFiles()
        return if (files.isNotEmpty()) {
            selectors(files.mapTo(mutableSetOf()) { selectFile(it) })
        } else {
            Resolution.unresolved()
        }
    }

    override fun resolve(
        selector: FileSelector,
        context: SelectorResolver.Context
    ): Resolution {
        if (selector.file.extension.lowercase() != "xml") {
            return Resolution.unresolved()
        }
        val journeyName = selector.file.inputStream().use { input ->
            try {
                val document =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
                if (document.documentElement.tagName.lowercase() != "journey") {
                    return Resolution.unresolved()
                }
                document.documentElement.getAttribute("name")
            } catch (_: org.xml.sax.SAXException) {
                return Resolution.unresolved()
            }
        }

        val matches = JourneysTestEngineInput.testDeviceIds.mapNotNullTo(mutableSetOf()) { deviceId ->
            context.addToParent({ DeviceSelector(deviceId) }) { parent ->
                Optional.of(JourneyFileDescriptor(parent.uniqueId, selector.file, journeyName))
            }.map {
                Match.exact(it)
            }.getOrNull()
        }
        return if (matches.isNotEmpty()) {
            Resolution.matches(matches)
        } else {
            Resolution.unresolved()
        }
    }
}
