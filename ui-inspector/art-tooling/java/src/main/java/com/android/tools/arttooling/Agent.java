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

package com.android.tools.arttooling;

/**
 * A tool implements this interface to run its own code inside the target process. ART Tooling loads
 * the implementation and calls {@link #onAttach} once, at the end of attachment.
 *
 * <p>The class must be public and have a public no-argument constructor. It is loaded through a
 * class loader parented to the application's, so it can see the app's own classes.
 *
 * <p>{@link #onAttach} runs on the attach thread: it must not block, and should hand any
 * long-running work to a background thread.
 */
public interface Agent {

    /**
     * Called once, right after the agent is loaded.
     *
     * @param options the opaque options string supplied at attach time, passed through unchanged
     */
    void onAttach(String options);
}
