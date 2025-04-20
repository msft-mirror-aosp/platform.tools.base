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
import com.android.journeys.testengine.descriptor.DeviceDescriptor
import com.android.journeys.testengine.selector.DeviceSelector
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.DiscoverySelectors.selectDirectory
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution.match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution.selectors
import java.util.Optional

class DeviceSelectorResolver : SelectorResolver {
    override fun resolve(
        selector: ClassSelector,
        context: SelectorResolver.Context
    ): Resolution {
        // Gradle's Test task only supports class-selector. To work around the limitation,
        // we use the "JourneysEntryPoint" class an entry point and delegate the resolution
        // to DeviceSelector.
        return if (selector.className == "JourneysEntryPoint"
            && JourneysTestEngineInput.testDeviceIds.isNotEmpty()) {
            selectors(
                JourneysTestEngineInput.testDeviceIds.mapTo(mutableSetOf()) { DeviceSelector(it) })
        } else {
            Resolution.unresolved()
        }
    }

    override fun resolve(
        selector: DiscoverySelector,
        context: SelectorResolver.Context
    ): Resolution {
        return if (selector is DeviceSelector) {
            resolve(selector, context)
        } else {
            super.resolve(selector, context)
        }
    }

    private fun resolve(
        selector: DeviceSelector,
        context: SelectorResolver.Context
    ): Resolution {
        return context.addToParent { parent ->
            Optional.of(DeviceDescriptor(parent.uniqueId, selector.deviceSerialId))
        }.map {
            match(Match.exact(it) {
                setOf(selectDirectory(JourneysTestEngineInput.journeysInputDir))
            })
        }.orElse(Resolution.unresolved())
    }
}
