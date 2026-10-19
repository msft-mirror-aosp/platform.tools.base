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

package test.neverlink.api;

import com.android.annotations.concurrency.UiThread;

/** Compile-time only dependency (neverlink), like a Gradle or IntelliJ API jar. */
public final class Api {
    private Api() {}

    @UiThread
    public static void updateUi() {}

    // Inferred @UiThread.
    public static void doStuff() {
        updateUi();
    }
}
