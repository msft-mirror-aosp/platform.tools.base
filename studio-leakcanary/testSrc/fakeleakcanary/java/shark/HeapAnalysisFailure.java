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

/** Test fake of Shark's HeapAnalysisFailure. */
public final class HeapAnalysisFailure extends HeapAnalysis {
    private final long createdAtTimeMillis;
    private final long dumpDurationMillis;
    private final long analysisDurationMillis;
    private final HeapAnalysisException exception;

    public HeapAnalysisFailure(
            long createdAtTimeMillis,
            long dumpDurationMillis,
            long analysisDurationMillis,
            HeapAnalysisException exception) {
        this.createdAtTimeMillis = createdAtTimeMillis;
        this.dumpDurationMillis = dumpDurationMillis;
        this.analysisDurationMillis = analysisDurationMillis;
        this.exception = exception;
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

    public HeapAnalysisException getException() {
        return exception;
    }
}
