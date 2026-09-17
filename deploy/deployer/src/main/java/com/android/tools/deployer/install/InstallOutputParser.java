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

import com.android.annotations.Nullable;
import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.InstallStatus;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for package manager install command output (e.g. {@code pm install} or
 * {@code cmd package install-commit}).
 * <p> This logic was adapted from `com.android.ddmlib.InstallReceiver`.
 */
public class InstallOutputParser {

    private static final String SUCCESS_OUTPUT = "Success";

    /**
     * A pattern to parse strings of the form
     * {@code Failure [ERROR]} or {@code Failure [ERROR: description]}.
     * Captures "ERROR: description" in group 1 and "ERROR" in group 2.
     */
    private static final Pattern FAILURE_PATTERN =
            Pattern.compile("Failure\\s+\\[(([^:]*)(:.*)?)]");

    private InstallOutputParser() {}

    public static AdbClient.InstallResult parse(@Nullable String output) {
        if (output == null || output.isEmpty()) {
            return new AdbClient.InstallResult(InstallStatus.OK, "");
        }

        StringBuilder errorMessage = null;
        String errorCode = null;
        String successMessage = null;

        for (String rawLine : output.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith(SUCCESS_OUTPUT)) {
                errorMessage = null;
                successMessage = line;
                break;
            } else {
                Matcher m = FAILURE_PATTERN.matcher(line);
                if (m.matches()) {
                    errorMessage = new StringBuilder(m.group(1));
                    errorCode = m.group(2);
                    break;
                } else {
                    if (errorMessage == null) {
                        errorMessage = new StringBuilder("Unknown failure: ").append(line);
                        errorCode = "UNKNOWN";
                    } else {
                        errorMessage.append("\n").append(line);
                    }
                }
            }
        }

        if (errorMessage != null) {
            return AdbClient.toInstallerResult(errorCode, errorMessage.toString());
        }

        return new AdbClient.InstallResult(
                InstallStatus.OK, successMessage != null ? successMessage : "");
    }
}
