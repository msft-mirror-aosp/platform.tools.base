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

package com.android.tools.backup

/** Annotation to identify a setup method to be run before a backup is generated. */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class BeforeBackup()

/**
 * Annotation to identify a setup method to be run after a backup is generated and before the application is restored from the generated
 * backup file.
 */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class BetweenBackupAndRestore()

/** Annotation to identify a setup method to be run before the application is restored from an existing backup file. */
@Retention(AnnotationRetention.RUNTIME) @Target(AnnotationTarget.FUNCTION) annotation class BeforeRestore()
