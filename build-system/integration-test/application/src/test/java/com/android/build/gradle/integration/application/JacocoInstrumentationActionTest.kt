/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.internal.tasks.JacocoTask
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JacocoInstrumentationActionTest {

    @Test
    fun testExpectedInstrumentationAction() {
        val expectedFileAndAction = mapOf(
            "com/example/R.class" to JacocoTask.Action.COPY,
            "com/example/R\$color.class" to JacocoTask.Action.COPY,
            "com/example/Manifest.class" to JacocoTask.Action.COPY,
            "com/example/Manifest\$details.class" to JacocoTask.Action.COPY,
            "com/example/BuildConfig.class" to JacocoTask.Action.COPY,
            "com/example/APiratesFavouriteLetterIsR.class" to JacocoTask.Action.INSTRUMENT,
            "SomeClass.class" to JacocoTask.Action.INSTRUMENT,
            "com/example/AnotherClass.class" to JacocoTask.Action.INSTRUMENT,
            "README.md" to JacocoTask.Action.IGNORE
        )
        expectedFileAndAction.forEach {
            assertThat(JacocoTask.calculateAction(it.key)).isEqualTo(it.value)
        }
    }
}
