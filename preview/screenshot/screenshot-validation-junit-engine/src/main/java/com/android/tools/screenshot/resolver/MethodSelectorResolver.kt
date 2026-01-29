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

package com.android.tools.screenshot.resolver

import com.android.tools.screenshot.PreviewTest
import com.android.tools.screenshot.descriptor.PreviewMethodDescriptor
import java.util.Optional
import org.junit.platform.commons.util.AnnotationUtils.isAnnotated
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.discovery.MethodSelector
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution

class MethodSelectorResolver : SelectorResolver {
  override fun resolve(selector: MethodSelector, context: SelectorResolver.Context): Resolution {
    if (!isAnnotated(selector.javaMethod, PreviewTest::class.java)) {
      return Resolution.unresolved()
    }
    return context
      .addToParent(
        { DiscoverySelectors.selectClass(selector.className) },
        { parent -> Optional.of(PreviewMethodDescriptor(parent.uniqueId, selector.className, selector.methodName)) },
      )
      .map { Resolution.match(Match.exact(it)) }
      .orElse(Resolution.unresolved())
  }
}
