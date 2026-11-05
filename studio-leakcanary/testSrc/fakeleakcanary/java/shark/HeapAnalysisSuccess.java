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

package shark;

import java.util.List;
import java.util.Map;

/** Test fake of Shark's HeapAnalysisSuccess. */
public final class HeapAnalysisSuccess extends HeapAnalysis {
    private final long createdAtTimeMillis;
    private final long dumpDurationMillis;
    private final long analysisDurationMillis;
    private final Map<String, String> metadata;
    private final List<ApplicationLeak> applicationLeaks;
    private final List<LibraryLeak> libraryLeaks;

    public HeapAnalysisSuccess(
            long createdAtTimeMillis,
            long dumpDurationMillis,
            long analysisDurationMillis,
            Map<String, String> metadata,
            List<ApplicationLeak> applicationLeaks,
            List<LibraryLeak> libraryLeaks) {
        this.createdAtTimeMillis = createdAtTimeMillis;
        this.dumpDurationMillis = dumpDurationMillis;
        this.analysisDurationMillis = analysisDurationMillis;
        this.metadata = metadata;
        this.applicationLeaks = applicationLeaks;
        this.libraryLeaks = libraryLeaks;
    }

    @Override
    public long getCreatedAtTimeMillis() {
        return createdAtTimeMillis;
    }

    @Override
    public long getDumpDurationMillis() {
        return dumpDurationMillis;
    }

    @Override
    public long getAnalysisDurationMillis() {
        return analysisDurationMillis;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public List<ApplicationLeak> getApplicationLeaks() {
        return applicationLeaks;
    }

    public List<LibraryLeak> getLibraryLeaks() {
        return libraryLeaks;
    }
}
