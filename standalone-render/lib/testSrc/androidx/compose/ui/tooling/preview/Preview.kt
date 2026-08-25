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

/** Mock Jetpack Compose @Preview annotation for testing PreviewDiscoveryEngine and Renderer bytecode discovery. */
@Repeatable
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Preview(
  val name: String = "",
  val group: String = "",
  val apiLevel: Int = -1,
  val widthDp: Int = -1,
  val heightDp: Int = -1,
  val locale: String = "",
  val fontScale: Float = 1f,
  val showBackground: Boolean = false,
  val backgroundColor: Long = 0,
  val showSystemUi: Boolean = false,
  val device: String = "",
  val uiMode: Int = 0,
  val wallpaper: Int = 0,
)
