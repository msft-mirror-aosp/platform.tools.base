/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.tools.apk.analyzer;

import static com.android.tools.apk.analyzer.ZipEntryInfo.Alignment.ALIGNMENT_NONE;

import com.android.ide.common.pagealign.AlignmentProblem;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ArchivePathEntry extends ArchiveEntry {
    private long rawFileSize = -1;
    private long downloadFileSize = -1;
    private ZipEntryInfo.Alignment zipAlignment = ALIGNMENT_NONE;
    private List<AlignmentProblem> elfAlignmentProblems = null;
    private boolean isCompressed = false;
    private boolean isSelfOrChild16kbIncompatible = false;

    public ArchivePathEntry(
            @NotNull Archive archive, @NotNull Path path, @NotNull String pathPrefix) {
        super(archive, path, pathPrefix);
    }

    @Override
    public void setRawFileSize(long rawFileSize) {
        this.rawFileSize = rawFileSize;
    }

    @Override
    public void setFileAlignment(ZipEntryInfo.Alignment alignment) {
        this.zipAlignment = alignment;
    }

    @Override
    public ZipEntryInfo.Alignment getFileAlignment() {
        return zipAlignment;
    }

    @Override
    public void setElfAlignmentProblems(List<AlignmentProblem> loadAlignment) {
        this.elfAlignmentProblems = loadAlignment;
    }

    @Override
    public List<AlignmentProblem> getElfAlignmentProblems() {
        return elfAlignmentProblems;
    }

    @Override
    public boolean getSelfOrChild16kbIncompatible() {
        return isSelfOrChild16kbIncompatible;
    }

    @Override
    public void setSelfOrChild16kbIncompatible(Boolean value) {
        isSelfOrChild16kbIncompatible = value;
    }

    @Override
    public void setIsFileCompressed(boolean isCompressed) {
        this.isCompressed = isCompressed;
    }

    @Override
    public boolean isFileCompressed() {
        return !Files.isDirectory(getPath()) && isCompressed;
    }

    @Override
    public long getRawFileSize() {
        return rawFileSize;
    }

    @Override
    public void setDownloadFileSize(long downloadFileSize) {
        this.downloadFileSize = downloadFileSize;
    }

    @Override
    public long getDownloadFileSize() {
        return downloadFileSize;
    }

    @Override
    @NotNull
    public String getNodeDisplayString() {
        Path base = getPath().getFileName();
        String name = base == null ? "" : base.toString();
        return trimEnd(name, "/");
    }

    @Override
    @NotNull
    public String getSummaryDisplayString() {
        return getPathPrefix() + PathUtils.pathWithTrailingSeparator(getPath());
    }

    @NotNull
    private static String trimEnd(@NotNull String s, @NotNull String suffix) {
        boolean endsWith = s.endsWith(suffix);
        if (endsWith) {
            return s.substring(0, s.length() - suffix.length());
        }
        return s;
    }
}
