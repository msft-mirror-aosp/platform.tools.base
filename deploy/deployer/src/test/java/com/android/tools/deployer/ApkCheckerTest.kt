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
package com.android.tools.deployer

import com.android.testutils.TestUtils
import com.android.tools.deployer.model.ApkParser
import org.junit.Assert
import org.junit.Test

class ApkCheckerTest {
    val BASE: String = "tools/base/deploy/deployer/src/test/resource/"

    @Test
    fun testBasic() {
        val logger = TestLogger()
        val checker = ApkChecker("SESSION_ID", logger)
        val file = TestUtils.resolveWorkspacePath(BASE + "sample.apk")
        val apk = ApkParser.parse(file.toAbsolutePath().toString())
        checker.log(listOf(apk))
        assertLogContains(logger, "fingerprint='74eaa38f4d4d8619c7bb886289f84efe1fce7ce3'")
        assertLogContains(logger, "classes.dex='2596924577'")
    }

    private fun assertLogContains(logger: TestLogger, expected: String) {
        Assert.assertTrue("Expecting to find $expected in logger.", logger.log.any {  it.contains(expected) })
    }
}
