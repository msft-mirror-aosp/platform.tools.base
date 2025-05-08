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
import com.android.tools.journeys.testengine.descriptor.PromptDescriptor
import com.android.tools.journeys.testengine.robo.RoboConverter
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.DirectorySelector
import org.junit.platform.engine.discovery.DiscoverySelectors.selectDirectory
import org.junit.platform.engine.discovery.DiscoverySelectors.selectFile
import org.junit.platform.engine.discovery.FileSelector
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution.selectors
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory

class JourneysFileSelectorResolver : SelectorResolver {
    override fun resolve(
        selector: ClassSelector,
        context: SelectorResolver.Context
    ): Resolution {
        // Gradle's Test task only supports class-selector. To work around the limitation,
        // we use the "JourneysEntryPoint" class an entry point.
        return if (selector.className == "JourneysEntryPoint"
            && JourneysTestEngineInput.testDeviceId.isNotEmpty()
        ) {
            selectors(setOf(selectDirectory(JourneysTestEngineInput.journeysInputDir)))
        } else {
            Resolution.unresolved()
        }
    }

    override fun resolve(
        selector: DirectorySelector,
        context: SelectorResolver.Context
    ): Resolution {
        val files = selector.directory.listFiles()
        return if (!files.isNullOrEmpty()) {
            selectors(files.mapTo(mutableSetOf()) { selectFile(it) })
        } else {
            Resolution.unresolved()
        }
    }

    override fun resolve(
        selector: FileSelector,
        context: SelectorResolver.Context
    ): Resolution {
        val file = selector.file

        if (file.extension.lowercase() != "xml") {
            return Resolution.unresolved()
        }

        val filter = JourneysTestEngineInput.journeysFilter
        if (filter.isNotEmpty() && file.name !in filter) {
            return Resolution.unresolved()
        }
        val journeyName = file.inputStream().use { input ->
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

        return context.addToParent { parent ->
            val journeysFileDescriptor =
                JourneyFileDescriptor(
                    parent.uniqueId,
                    selector.file,
                    journeyName
                )

            RoboConverter.getRoboElements(selector.file.inputStream())
                .forEachIndexed { index, element ->
                    val promptText = element.textContent.trim()
                    // TODO(b/414570953): Remove once editor issue is fixed.
                    if (promptText.isBlank()) {
                        return@forEachIndexed
                    }
                    val promptDescriptor =
                        PromptDescriptor(
                            journeysFileDescriptor.uniqueId,
                            promptText,
                            index
                        )
                    journeysFileDescriptor.addChild(promptDescriptor)
                }
            Optional.of(journeysFileDescriptor)
        }.map { journeyFileDesc ->
            val match = Match.exact(journeyFileDesc)
            Resolution.matches(setOf(match))
        }.orElseGet {
            Resolution.unresolved()
        }
    }
}
