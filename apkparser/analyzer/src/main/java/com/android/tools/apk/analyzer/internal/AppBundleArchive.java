/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.apk.analyzer.internal;

import com.android.SdkConstants;
import com.android.tools.apk.analyzer.Archive;
import com.android.tools.apk.analyzer.dex.ProguardMappings;
import com.android.tools.proguard.ProguardMap;
import com.android.utils.FileUtils;
import com.android.utils.XmlUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;

/**
 * Implementation of {@link Archive} for an &quot;Android App Bundle&quot; zip file.
 *
 * <p>The archive is opened as a <code>zip</code> {@link FileSystem} until the {@link #close()}
 * method is called.
 */
public class AppBundleArchive extends AbstractArchive {

    public static String BUNDLE_BASELINE_PROFILE_PATH =
            String.format(
                    "/BUNDLE-METADATA/%s/%s",
                    SdkConstants.FN_BINART_ART_PROFILE_FOLDER_IN_AAB,
                    SdkConstants.FN_BINARY_ART_PROFILE);

    public static String BUNDLE_BASELINE_PROFILE_METADATA_PATH =
            String.format(
                    "/BUNDLE-METADATA/%s/%s",
                    SdkConstants.FN_BINART_ART_PROFILE_FOLDER_IN_AAB,
                    SdkConstants.FN_BINARY_ART_PROFILE_METADATA);

    private static final String BUNDLE_PROGUARD_MAPPING_PATh =
            "/BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map";

    @NotNull private final FileSystem zipFileSystem;

    private AppBundleArchive(@NotNull Path path) throws IOException {
        super(path);
        this.zipFileSystem = FileUtils.createZipFilesystem(path);
    }

    @NotNull
    public static AppBundleArchive fromBundleFile(@NotNull Path artifact) throws IOException {
        return new AppBundleArchive(artifact);
    }

    @Override
    @NotNull
    public Path getContentRoot() {
        return zipFileSystem.getPath("/");
    }

    @Override
    public void close() throws IOException {
        zipFileSystem.close();
    }

    @Override
    public boolean isProtoXml(@NotNull Path p, @NotNull byte[] content) {
        if (!p.toString().endsWith(SdkConstants.DOT_XML)) {
            return false;
        }

        Path name = p.getFileName();
        if (name == null) {
            return false;
        }

        boolean manifest = isManifestFile(p);
        boolean insideResFolder = isInsideResFolder(p);
        boolean insideResRaw = isInsiderResRawFolder(p);
        boolean xmlResource = insideResFolder && !insideResRaw;
        if (!manifest && !xmlResource) {
            return false;
        }

        return XmlUtils.isProtoXml(content);
    }

    @Override
    public boolean isBaselineProfile(@NotNull Path p, @NotNull byte[] content) {
        String path = p.toString();

        return path.equals(BUNDLE_BASELINE_PROFILE_PATH)
                || path.equals(BUNDLE_BASELINE_PROFILE_METADATA_PATH);
    }

    @Override
    @Nullable
    public ProguardMappings loadProguardMapping() {
        Path path = getContentRoot().resolve(BUNDLE_PROGUARD_MAPPING_PATh);
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(path))) {
            ProguardMap proguardMap = new ProguardMap();
            proguardMap.readFromReader(reader);
            return new ProguardMappings(proguardMap, null, null);
        } catch (IOException e) {
            return null;
        } catch (ParseException e) {
            throw new RuntimeException("Invalid Proguard mapping file", e);
        }
    }

    private static boolean isManifestFile(@NotNull Path p) {
        return matchPathPrefix(
                p, PathEntry.any(), PathEntry.FD_MANIFEST, PathEntry.FN_ANDROID_MANIFEST_XML);
    }

    private static boolean isInsideResFolder(@NotNull Path p) {
        return matchPathPrefix(p, PathEntry.any(), PathEntry.name(SdkConstants.FD_RES));
    }

    private static boolean isInsiderResRawFolder(@NotNull Path p) {
        return matchPathPrefix(p, PathEntry.any(), PathEntry.FD_RES, PathEntry.FD_RES_RAW);
    }

    private static boolean matchPathPrefix(
            @NotNull Path path, @NotNull PathEntry... prefixEntries) {
        int index = 0;
        for (PathEntry entry : prefixEntries) {
            if (!entry.matches(path.getName(index))) {
                return false;
            }
            index++;
        }
        return true;
    }

    public abstract static class PathEntry {

        public abstract boolean matches(@NotNull Path name);

        @NotNull
        public static PathEntry any() {
            return AnyPathEntry.instance;
        }

        @NotNull
        public static PathEntry name(@NotNull String name) {
            return new NamePathEntry(name);
        }

        @NotNull public static final PathEntry FD_RES = name(SdkConstants.FD_RES);

        @NotNull public static final PathEntry FD_RES_RAW = name(SdkConstants.FD_RES_RAW);

        @NotNull
        public static final PathEntry FN_ANDROID_MANIFEST_XML =
                name(SdkConstants.FN_ANDROID_MANIFEST_XML);

        @NotNull public static final PathEntry FD_MANIFEST = name("manifest");
    }

    public static class AnyPathEntry extends PathEntry {

        @NotNull public static AnyPathEntry instance = new AnyPathEntry();

        @Override
        public boolean matches(@NotNull Path name) {
            return true;
        }
    }

    public static class NamePathEntry extends PathEntry {

        private final String name;

        public NamePathEntry(@NotNull String name) {
            this.name = name;
        }

        @Override
        public boolean matches(@NotNull Path name) {
            // For ZIP paths, we use strict string equality
            return name.toString().equals(this.name);
        }
    }
}
