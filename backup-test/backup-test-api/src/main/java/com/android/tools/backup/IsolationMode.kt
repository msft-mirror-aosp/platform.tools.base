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

package com.android.tools.backup

/** Defines the cleanup policy used between individual test methods. */
enum class IsolationPolicy {
  AUTOMATIC,
  MANUAL,
}

/**
 * Annotation to override the default automatic clear data behavior of the framework.
 *
 * Apply this to a test class or test method to avoid calling `pm clear` automatically before each test, useful for optimizing runtime of
 * large test suites.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class IsolationMode(val value: IsolationPolicy = IsolationPolicy.AUTOMATIC)
