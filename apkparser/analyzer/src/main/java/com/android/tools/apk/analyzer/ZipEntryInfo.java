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
package com.android.tools.apk.analyzer;

import com.android.ide.common.pagealign.AlignmentProblem;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ZipEntryInfo {
    public enum Alignment {
        ALIGNMENT_NONE(""),
        ALIGNMENT_4K("4 KB"),
        ALIGNMENT_16K("16 KB"),
        ;

        public final String text;

        Alignment(String text) {
            this.text = text;
        }
    }

    public long size;
    public Alignment zipAlignment;
    public List<@NotNull AlignmentProblem> elfAlignmentProblems;
    public boolean isCompressed;

    public ZipEntryInfo(
            long size,
            Alignment zipAlignment,
            boolean isCompressed,
            // A null list means not an ELF file. An empty list means it's an ELF file but no
            // alignment problems were found. A non-empty list means the file is an ELF file and
            // alignment problems were found.
            List<@NotNull AlignmentProblem> elfLoadSectionAlignment) {
        this.size = size;
        this.zipAlignment = zipAlignment;
        this.isCompressed = isCompressed;
        this.elfAlignmentProblems = elfLoadSectionAlignment;
    }
}
