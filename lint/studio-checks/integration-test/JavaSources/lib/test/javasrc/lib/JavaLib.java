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

package test.javasrc.lib;

import com.android.annotations.concurrency.UiThread;

public final class JavaLib {
    private JavaLib() {}

    @UiThread
    public static void updateUi() {}

    // Inferred @UiThread, in a Java source of a java_library dependency.
    public static void doStuff() {
        updateUi();
    }
}
