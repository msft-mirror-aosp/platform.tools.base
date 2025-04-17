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

package com.android.tools.backup.sampleTests

import com.android.tools.backup.BeforeBackup
import com.android.tools.backup.BetweenBackupAndRestore
import com.android.tools.backup.BackupAndRestoreTest
import com.android.tools.backup.TestBackupType
import org.junit.Ignore
import org.junit.Test

@Ignore("Example test for AndroidBackupRunnerBuilderTest")
@BackupAndRestoreTest(TestBackupType.DEVICE_TO_DEVICE)
class ExampleBackupRestoreTest {
    @BeforeBackup
    fun beforeBackupMethod() {}

    @BetweenBackupAndRestore
    fun betweenBackupAndRestoreMethod() {}

    @Test
    fun test1() {}
}
