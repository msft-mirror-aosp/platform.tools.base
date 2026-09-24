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

package com.android.tools.ui.inspector.payload.appinspection;

import androidx.inspection.ArtTooling;

import java.util.List;

/**
 * Adapts the App Inspection {@link ArtTooling} interface, which inspectors are written against,
 * onto the ART Tooling library facade. Every hook registered through this adapter is scoped to the
 * owning inspector's id, so {@link com.android.tools.arttooling.ArtTooling#clear} can remove them
 * all when the inspector is disposed.
 *
 * <p>The library facade shares this type's simple name, so it is referenced fully qualified
 * throughout.
 */
final class AppInspectionArtTooling implements ArtTooling {

    private final String inspectorId;

    AppInspectionArtTooling(String inspectorId) {
        this.inspectorId = inspectorId;
    }

    @Override
    public <T> List<T> findInstances(Class<T> clazz) {
        return com.android.tools.arttooling.ArtTooling.findInstances(clazz);
    }

    @Override
    public void registerEntryHook(Class<?> originClass, String originMethod, EntryHook entryHook) {
        com.android.tools.arttooling.ArtTooling.registerEntryHook(
                originClass, originMethod, inspectorId, entryHook::onEntry);
    }

    @Override
    public <T> void registerExitHook(
            Class<?> originClass, String originMethod, ExitHook<T> exitHook) {
        com.android.tools.arttooling.ArtTooling.registerExitHook(
                originClass, originMethod, inspectorId, exitHook::onExit);
    }
}
