/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.deployer;

import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ApkEntry;
import com.android.utils.ILogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log information about the APKs that was targeted for deployment.
 *
 * <p>This is only useful for debug
 */
public class ApkChecker {

    private final String deploySessionId;
    private final ILogger logger;

    ApkChecker(String deploySessionId, ILogger logger) {
        this.deploySessionId = deploySessionId;
        this.logger = logger;
    }

    boolean log(List<Apk> apks) {
        for (Apk apk : apks) {

            String fingerprint = apk.checksum; // This is the zip digest

            Path path = Paths.get(apk.path);
            long size = -1;
            String creationTime = "NOT_AVAILABLE";
            String lastModifiedTime = "NOT_AVAILABLE";
            String lastAccessTime = "NOT_AVAILABLE";

            try {
                BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
                size = attr.size();
                creationTime = attr.creationTime().toString();
                lastModifiedTime = attr.lastModifiedTime().toString();
                lastAccessTime = attr.lastAccessTime().toString();
            } catch (IOException e) {
                logger.error(e, "Unable to perform APKChecker logging on file %s", path.toString());
            }

            LinkedHashMap<String, Long> dexChecksumSorted = new LinkedHashMap<>();
            for (Map.Entry<String, ApkEntry> entry : apk.apkEntries.entrySet()) {
                String name = entry.getKey();
                if (name.endsWith(".dex")) {
                    dexChecksumSorted.put(entry.getKey(), entry.getValue().getChecksum());
                }
            }

            StringBuilder dexChecksums = new StringBuilder();
            for (Map.Entry<String, Long> entry : dexChecksumSorted.entrySet()) {
                dexChecksums.append(entry.getKey());
                dexChecksums.append("='");
                dexChecksums.append(entry.getValue());
                dexChecksums.append("' ");
            }

            logger.info(
                    "Deploy APK Check session='%s', path='%s', size='%,d', fingerprint='%s', "
                            + "crTime='%s', modTime='%s', acTime='%s' %s",
                    deploySessionId,
                    apk.path,
                    size,
                    fingerprint,
                    creationTime,
                    lastModifiedTime,
                    lastAccessTime,
                    dexChecksums);
        }
        return true;
    }
}
