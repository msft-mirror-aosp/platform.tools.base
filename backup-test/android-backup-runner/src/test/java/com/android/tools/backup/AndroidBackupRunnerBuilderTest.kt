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

import com.android.tools.backup.sampleTests.ExampleRestoreTest
import com.android.tools.backup.sampleTests.ExampleBackupRestoreTest
import com.android.tools.backup.sampleTests.ExampleInstrumentedTest
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertEquals

class AndroidBackupRunnerBuilderTest {
    @Test
    fun runnerForRestoreTest() {
        val runnerBuilder = AndroidBackupRunnerBuilder()

        val runner = runnerBuilder.runnerForClass(ExampleRestoreTest::class.java)

        assertEquals(runner!!.testCount(), 2)
    }

    @Test
    fun runnerForBackupRestoreTest() {
        val runnerBuilder = AndroidBackupRunnerBuilder()
        val runner = runnerBuilder.runnerForClass(ExampleBackupRestoreTest::class.java)

        assertEquals(runner!!.testCount(), 3)
    }

    @Test
    fun runnerForNonBackupRestoreTest() {
        val runnerBuilder = AndroidBackupRunnerBuilder()
        val runner = runnerBuilder.runnerForClass(ExampleInstrumentedTest::class.java)

        assertNull(runner)
    }
}
