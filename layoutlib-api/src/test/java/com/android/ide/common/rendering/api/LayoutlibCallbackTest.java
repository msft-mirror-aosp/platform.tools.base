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
package com.android.ide.common.rendering.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;

import java.util.Map;

/** Unit tests for {@link LayoutlibCallback}. */
public class LayoutlibCallbackTest {

    @Test
    @SuppressWarnings("deprecation")
    public void testDeprecatedNamespacingDefaults() {
        LayoutlibCallback callback =
                new LayoutlibCallback() {
                    @Nullable
                    @Override
                    public Object loadView(
                            @NonNull String name,
                            @NonNull Class[] constructorSignature,
                            Object[] constructorArgs) {
                        return null;
                    }

                    @Nullable
                    @Override
                    public ResourceReference resolveResourceId(int id) {
                        return null;
                    }

                    @Override
                    public int getOrGenerateResourceId(@NonNull ResourceReference resource) {
                        return 0;
                    }

                    @Nullable
                    @Override
                    public ILayoutPullParser getParser(@NonNull ResourceValue layoutResource) {
                        return null;
                    }

                    @Nullable
                    @Override
                    public AdapterBinding getAdapterBinding(
                            Object viewObject, Map<String, String> attributes) {
                        return null;
                    }

                    @Override
                    public ActionBarCallback getActionBarCallback() {
                        return null;
                    }

                    @Nullable
                    @Override
                    public XmlPullParser createXmlParserForPsiFile(@NonNull String fileName) {
                        return null;
                    }

                    @Nullable
                    @Override
                    public XmlPullParser createXmlParserForFile(@NonNull String fileName) {
                        return null;
                    }

                    @NonNull
                    @Override
                    public XmlPullParser createXmlParser() {
                        throw new UnsupportedOperationException();
                    }
                };

        assertSame(ResourceNamespace.Resolver.TOOLS_ONLY, callback.getImplicitNamespaces());
        assertFalse(callback.isResourceNamespacingRequired());
    }
}
