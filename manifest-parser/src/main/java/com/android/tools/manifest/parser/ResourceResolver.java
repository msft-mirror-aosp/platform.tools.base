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
package com.android.tools.manifest.parser;

import com.android.annotations.Nullable;

/**
 * Resolves resource references found in a manifest, such as {@code
 * android:enabled="@bool/leak_canary_add_launcher_icon"}.
 */
public interface ResourceResolver {

    /**
     * Returns the value of the boolean resource {@code resourceId}, or null if it does not exist or
     * is not a boolean.
     */
    @Nullable
    Boolean resolveBoolean(int resourceId);
}
