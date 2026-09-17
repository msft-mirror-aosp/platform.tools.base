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

package androidx.compose.ui.tooling.preview

import kotlin.reflect.KClass

/** Mock Jetpack Compose PreviewParameterProvider interface for testing PreviewDiscoveryEngine parameter discovery. */
interface PreviewParameterProvider<T> {
  val values: Sequence<T>
  val count: Int
    get() = values.count()
}

/** Mock Jetpack Compose @PreviewParameter annotation for testing PreviewDiscoveryEngine parameter discovery. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PreviewParameter(val provider: KClass<out PreviewParameterProvider<*>>, val limit: Int = Int.MAX_VALUE)
