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
package com.android.tools.deployer.install;

import static org.junit.Assert.assertEquals;

import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.InstallStatus;
import org.junit.Test;

public class InstallOutputParserTest {

    @Test
    public void testNullAndEmptyOutput() {
        AdbClient.InstallResult nullResult = InstallOutputParser.parse(null);
        assertEquals(InstallStatus.OK, nullResult.status);
        assertEquals("", nullResult.reason);

        AdbClient.InstallResult emptyResult = InstallOutputParser.parse("");
        assertEquals(InstallStatus.OK, emptyResult.status);
        assertEquals("", emptyResult.reason);

        AdbClient.InstallResult whitespaceResult = InstallOutputParser.parse("   \n\n  \n");
        assertEquals(InstallStatus.OK, whitespaceResult.status);
    }

    @Test
    public void testSuccessOutput() {
        AdbClient.InstallResult result = InstallOutputParser.parse("Success");
        assertEquals(InstallStatus.OK, result.status);
        assertEquals("Success", result.reason);

        AdbClient.InstallResult streamedResult =
                InstallOutputParser.parse("Success: streamed 13740091 bytes\n");
        assertEquals(InstallStatus.OK, streamedResult.status);
        assertEquals("Success: streamed 13740091 bytes", streamedResult.reason);
    }

    @Test
    public void testStandardFailure() {
        AdbClient.InstallResult result =
                InstallOutputParser.parse("Failure [INSTALL_FAILED_TEST_ONLY]");
        assertEquals(InstallStatus.INSTALL_FAILED_TEST_ONLY, result.status);
        assertEquals("INSTALL_FAILED_TEST_ONLY", result.reason);
    }

    @Test
    public void testFailureWithDescription() {
        AdbClient.InstallResult result =
                InstallOutputParser.parse(
                        "Failure [INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install com.example"
                                + " without first uninstalling.]");
        assertEquals(InstallStatus.INSTALL_FAILED_ALREADY_EXISTS, result.status);
        assertEquals(
                "INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install com.example without first"
                        + " uninstalling.",
                result.reason);
    }

    @Test
    public void testFailureWithNumericCode() {
        AdbClient.InstallResult result =
                InstallOutputParser.parse(
                        "Failure [-26: Package blah blah bah but the old target SDK 28 does.]");
        assertEquals(InstallStatus.INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE, result.status);
        assertEquals(
                "-26: Package blah blah bah but the old target SDK 28 does.", result.reason);
    }

    @Test
    public void testUnknownFailure() {
        AdbClient.InstallResult result =
                InstallOutputParser.parse("Some random error message occurred");
        assertEquals(InstallStatus.UNKNOWN_ERROR, result.status);
        assertEquals("Unknown failure: Some random error message occurred", result.reason);
    }

    @Test
    public void testMultilineUnknownFailure() {
        AdbClient.InstallResult result =
                InstallOutputParser.parse("Error line 1\nError line 2\nError line 3");
        assertEquals(InstallStatus.UNKNOWN_ERROR, result.status);
        assertEquals("Unknown failure: Error line 1\nError line 2\nError line 3", result.reason);
    }

    @Test
    public void testFailureWithSurroundingEmptyLines() {
        AdbClient.InstallResult result =
                InstallOutputParser.parse(
                        "\n\n  Failure [INSTALL_FAILED_USER_RESTRICTED: Restricted by admin]  \n\n");
        assertEquals(InstallStatus.INSTALL_FAILED_USER_RESTRICTED, result.status);
        assertEquals("INSTALL_FAILED_USER_RESTRICTED: Restricted by admin", result.reason);
    }
}
