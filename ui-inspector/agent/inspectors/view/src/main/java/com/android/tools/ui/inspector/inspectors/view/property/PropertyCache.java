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

package com.android.tools.ui.inspector.inspectors.view.property;

import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.InspectionCompanion;
import android.view.inspector.StaticInspectionCompanionProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A cache that dynamically loads Android property metadata and inspection companions using
 * reflection, then caches them to prevent expensive runtime reflection lookups during view
 * hierarchy dumps.
 *
 * <p>Loads companions lazily using the platform's companion provider the first time a class type is
 * encountered, caching them to enable O(1) lookups during recursive view hierarchy traversals.
 *
 * <p>The root class (e.g., {@link View} or {@link ViewGroup.LayoutParams}) is used as the stop
 * condition when recursively resolving inherited properties up the class hierarchy.
 */
public final class PropertyCache<T> {

    /** Container holding cached property data for a specific class type. */
    public static final class PropertyData<T> {
        private final List<AttributeMetadata> properties;
        private final List<InspectionCompanion<T>> companions;

        PropertyData(List<AttributeMetadata> properties, List<InspectionCompanion<T>> companions) {
            this.properties = properties;
            this.companions = companions;
        }

        /** The resolved list of {@link AttributeMetadata}s for this class type. */
        public List<AttributeMetadata> getProperties() {
            return properties;
        }

        /** The active list of {@link InspectionCompanion}s used to read properties. */
        public List<InspectionCompanion<T>> getCompanions() {
            return companions;
        }
    }

    /**
     * Creates a new cache instance for resolving standard and custom {@link View} hierarchy
     * properties.
     */
    public static PropertyCache<View> createViewPropertyCache() {
        return new PropertyCache<>(View.class);
    }

    /**
     * Creates a new cache instance for resolving layout-specific {@link ViewGroup.LayoutParams}
     * properties.
     */
    public static PropertyCache<ViewGroup.LayoutParams> createLayoutParamsPropertyCache() {
        return new PropertyCache<>(ViewGroup.LayoutParams.class);
    }

    private final Class<T> rootClass;

    /**
     * Framework provider used to lazily load {@link InspectionCompanion}s via reflection based on
     * class names.
     */
    private final StaticInspectionCompanionProvider provider =
            new StaticInspectionCompanionProvider();

    /**
     * Internal map caching resolved {@link PropertyData} against specific class types to prevent
     * redundant reflection.
     */
    private final Map<Class<?>, PropertyData<T>> cache = new HashMap<>();

    private PropertyCache(Class<T> rootClass) {
        this.rootClass = rootClass;
    }

    /** Retrieves or builds the cached property data for the given {@code inspectable} instance. */
    public PropertyData<T> getOrResolve(T inspectable) {
        return getImpl(inspectable.getClass());
    }

    /**
     * Internal helper recursively walking up the class hierarchy to load, map, and flatly compile
     * property metadata and companions, caching the results to ensure subsequent lookups are O(1).
     */
    private PropertyData<T> getImpl(Class<?> inspectable) {
        PropertyData<T> cachedType = cache.get(inspectable);
        if (cachedType != null) {
            return cachedType;
        }

        PropertyData<T> superTypePropertyData =
                inspectable != rootClass ? getImpl(inspectable.getSuperclass()) : null;

        InspectionCompanion<T> companion = loadInspectionCompanion(inspectable);
        List<InspectionCompanion<T>> companions = new ArrayList<>();
        if (superTypePropertyData != null) {
            companions.addAll(superTypePropertyData.getCompanions());
        }
        if (companion != null) {
            companions.add(companion);
        }

        List<AttributeMetadata> baseProperties =
                superTypePropertyData != null
                        ? superTypePropertyData.getProperties()
                        : Collections.emptyList();
        List<AttributeMetadata> properties;
        if (companion != null) {
            PropertyTypeMapper mapper = new PropertyTypeMapper(baseProperties);
            companion.mapProperties(mapper);
            properties = mapper.getProperties();
        } else {
            properties = baseProperties;
        }

        PropertyData<T> type = new PropertyData<>(properties, companions);
        cache.put(inspectable, type);

        return type;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private InspectionCompanion<T> loadInspectionCompanion(Class<?> javaClass) {
        return (InspectionCompanion<T>) provider.provide((Class) javaClass);
    }
}
