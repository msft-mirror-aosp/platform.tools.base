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
import com.android.tools.screenshot.descriptor.ClassDescriptor
import java.util.Optional
import org.junit.platform.commons.support.HierarchyTraversalMode
import org.junit.platform.commons.support.ReflectionSupport.findMethods
import org.junit.platform.commons.util.AnnotationUtils.isAnnotated
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution

class ClassSelectorResolver : SelectorResolver {
  override fun resolve(selector: ClassSelector, context: SelectorResolver.Context): Resolution {
    return context
      .addToParent { parent -> Optional.of(ClassDescriptor(parent.uniqueId, selector.className)) }
      .map { classContainerDescriptor ->
        Resolution.match(
          Match.exact(classContainerDescriptor) {
            findMethods(selector.javaClass, { isAnnotated(it, PreviewTest::class.java) }, HierarchyTraversalMode.TOP_DOWN)
              .asSequence()
              .map { DiscoverySelectors.selectMethod(selector.javaClass, it) }
              .toMutableSet()
          }
        )
      }
      .orElse(Resolution.unresolved())
  }
}
