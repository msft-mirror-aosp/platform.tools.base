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

package com.android.tools.screenshot.descriptor

import java.util.Optional
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestSource
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource

class ClassDescriptor(parentId: UniqueId, className: String) : AbstractTestDescriptor(parentId.append(SEGMENT_TYPE, className), className) {
  companion object {
    const val SEGMENT_TYPE: String = "class"
  }

  private val source: ClassSource = ClassSource.from(className)

  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

  override fun getSource(): Optional<TestSource> {
    return Optional.of(source)
  }
}
