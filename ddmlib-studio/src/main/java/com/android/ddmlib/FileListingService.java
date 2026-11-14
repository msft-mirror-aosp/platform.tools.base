/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.ddmlib;

import java.util.regex.Pattern;

/** Constants and helpers for parsing device-side file listings (e.g. {@code ls} and {@code stat}). */
public final class FileListingService {

    /** Device side file separator. */
    public static final String FILE_SEPARATOR = "/"; //$NON-NLS-1$

    /**
     * Regexp pattern to parse the result from ls.
     */
    public static final Pattern LS_L_PATTERN = Pattern.compile(
            "^([bcdlsp-][-r][-w][-xsS][-r][-w][-xsS][-r][-w][-xstST])\\s+" +
            "(?:\\d+\\s+)?" + // toolbox ls (<=M) didn't have nlink; toybox's POSIX ls does.
            "(\\S+)\\s+(\\S+)\\s+" +
            "([\\d\\s,]*)\\s+(\\d{4}-\\d\\d-\\d\\d)\\s+(\\d\\d:\\d\\d)\\s+(.*)$"); //$NON-NLS-1$

    public static final Pattern LS_LD_PATTERN = Pattern.compile(
            "d[rwxs-]{9}\\s+" + // We only use this to match directories!
            "(\\d+\\s+)?" + // toolbox ls (<=M) didn't have nlink; toybox's POSIX ls does.
            "\\S+\\s+\\S+\\s+" +
            "(\\d+\\s+)?" + // toolbox ls (<=M) didn't have size; toybox's POSIX ls does.
            "[0-9-]{10}\\s+\\d{2}:\\d{2}" +
            ".*" + // toolbox ls (<=M) didn't have the filename in the "/sdcard/" case!
            "$"); //$NON-NLS-1$

    /**
     * Regexp pattern to parse the result from stat. Ex. drwxr-xr-x root root 2021-08-02
     * 09:20:53.000000000 -0700 4096 /
     */
    public static final Pattern STAT_PATTERN =
            Pattern.compile(
                    "^([bcdlsp-][-r][-w][-xsS][-r][-w][-xsS][-r][-w][-xstST])\\s+"
                            + "(\\S+)\\s+"
                            + "(\\S+)\\s+"
                            + "(\\d{4}-\\d\\d-\\d\\d)\\s+"
                            + "(\\d\\d:\\d\\d:\\d\\d\\.\\d+)\\s+"
                            + "([+-]?\\d+\\s+)?"
                            + "(\\d+)\\s+"
                            + "(.*)"
                            + "$");

    private FileListingService() {}

    /** Utility for escaping device file names for shell command consumption. */
    public static final class FileEntry {
        /** Pattern to escape filenames for shell command consumption.
         *  This pattern identifies any special characters that need to be escaped with a
         *  backslash. */
        private static final Pattern sEscapePattern = Pattern.compile(
                "([\\\\()*+?\"'&$#/\\s])"); //$NON-NLS-1$

        /**
         * Returns an escaped version of the entry name.
         * @param entryName
         */
        public static String escape(String entryName) {
            return sEscapePattern.matcher(entryName).replaceAll("\\\\$1"); //$NON-NLS-1$
        }
    }
}
