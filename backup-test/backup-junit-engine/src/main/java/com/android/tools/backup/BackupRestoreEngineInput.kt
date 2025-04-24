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

object BackupRestoreEngineInput {
    val appId: String = getSystemProperty("appId")
    val resultDirPath: String = getSystemProperty("resultsDirPath")

    object ReportEntrySetting {
        // Redirect ReportEntry to stdout when enabled.
        // This is a short-term workaround until Gradle supports ReportEntry.
        // https://github.com/gradle/gradle/issues/4605
        val redirectToStdout: Boolean = getSystemProperty("ReportEntrySetting.redirectToStdout").toBoolean()
    }
}

private fun getSystemProperty(propertyName: String): String {
    return System.getProperty("BackupRestoreTestEngineInput.$propertyName", "")
}
