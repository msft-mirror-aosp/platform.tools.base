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

/** Test fake of Shark's LibraryLeak. */
public final class LibraryLeak extends Leak {
    private final String signature;
    private final List<LeakTrace> leakTraces;

    public LibraryLeak(
            String signature, Integer totalRetainedHeapByteSize, List<LeakTrace> leakTraces) {
        super(totalRetainedHeapByteSize);
        this.signature = signature;
        this.leakTraces = leakTraces;
    }

    @Override
    public List<LeakTrace> getLeakTraces() {
        return leakTraces;
    }

    @Override
    public String getSignature() {
        return signature;
    }
}
