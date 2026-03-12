/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.deployer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.tools.deploy.proto.Deploy;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.ZipUtils;
import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.android.zipflinger.ZipInfo;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class OptimisticApkSwapperTest {

    private static final String TEST_ABI = "x86_64";
    private static final String TEST_PACKAGE = "test-package";
    private static final String TEST_SERIAL = "test-serial";

    private Installer installer;
    private DeploymentCacheDatabase cache;
    private MetricsRecorder metrics;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Rule public ExpectedException thrown = ExpectedException.none();

    @Before
    public void beforeTest() throws IOException {
        IDevice device = Mockito.mock(IDevice.class);
        when(device.getSerialNumber()).thenReturn(TEST_SERIAL);
        when(device.getAbis()).thenReturn(ImmutableList.of(TEST_ABI));

        installer = Mockito.mock(Installer.class);
        when(installer.overlaySwap(ArgumentMatchers.any()))
                .thenReturn(
                        Deploy.SwapResponse.newBuilder()
                                .setStatus(Deploy.SwapResponse.Status.OK)
                                .build());
        when(installer.restartActivity(ArgumentMatchers.any()))
                .thenReturn(
                        Deploy.RestartActivityResponse.newBuilder()
                                .setStatus(Deploy.RestartActivityResponse.Status.OK)
                                .build());

        cache = new DeploymentCacheDatabase(DeploymentCacheDatabase.DEFAULT_SIZE);
        metrics = new MetricsRecorder();
    }

    @Test
    public void testRestart() throws IOException, DeployerException {
        DeployerOption option = new DeployerOption.Builder().setUseOptimisticSwap(true).build();

        OptimisticApkSwapper swapperNoRestart =
                new OptimisticApkSwapper(installer, ImmutableMap.of(), false, option, metrics);

        OptimisticApkSwapper swapperRestart =
                new OptimisticApkSwapper(installer, ImmutableMap.of(), true, option, metrics);

        // Populate the cache. To prevent us from having to mock dump, we create a cache entry with
        // an empty overlay, which prevents the cache entry from being treated as a base install.
        Apk installedApk =
                buildApk(
                        "base",
                        "0",
                        ImmutableMap.of(
                                "file1", "0",
                                "file2", "1"));

        OverlayId baseId = OverlayId.builder(new OverlayId(ImmutableList.of(installedApk))).build();
        cache.store(TEST_SERIAL, TEST_PACKAGE, ImmutableList.of(installedApk), baseId);

        OptimisticApkSwapper.OverlayUpdate update =
                new OptimisticApkSwapper.OverlayUpdate(
                        cache.get(TEST_SERIAL, TEST_PACKAGE),
                        new DexComparator.ChangedClasses(ImmutableList.of(), ImmutableList.of()),
                        ImmutableMap.of());

        swapperRestart.optimisticSwap(
                TEST_PACKAGE, ImmutableList.of(1), Deploy.Arch.ARCH_64_BIT, update);
        Mockito.verify(installer).restartActivity(any());

        swapperNoRestart.optimisticSwap(
                TEST_PACKAGE, ImmutableList.of(1), Deploy.Arch.ARCH_64_BIT, update);
        Mockito.verify(installer, times(1)).restartActivity(any());
    }

    private Apk buildApk(String name, String checksum, Map<String, String> files)
            throws IOException {
        return buildApk(name, checksum, files, ImmutableList.of());
    }

    private Apk buildApk(
            String name, String checksum, Map<String, String> files, List<String> targetPackages)
            throws IOException {
        Path apkFile = folder.getRoot().toPath().resolve(name + "-" + checksum);

        ZipArchive zip = new ZipArchive(apkFile);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            zip.add(
                    new BytesSource(
                            entry.getValue().getBytes(StandardCharsets.UTF_8), entry.getKey(), 0));
        }
        ZipInfo info = zip.closeWithInfo();

        Apk.Builder builder =
                Apk.builder()
                        .setName(name)
                        .setChecksum(checksum)
                        .setPath(apkFile.toAbsolutePath().toString())
                        .addLibraryAbi(TEST_ABI)
                        .setPackageName(TEST_PACKAGE)
                        .setTargetPackages(targetPackages);

        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(apkFile));
        buffer.position((int) info.cd.first);
        List<ZipUtils.ZipEntry> entries = ZipUtils.readZipEntries(buffer);
        for (ZipUtils.ZipEntry entry : entries) {
            builder.addApkEntry(entry);
        }

        return builder.build();
    }
}
