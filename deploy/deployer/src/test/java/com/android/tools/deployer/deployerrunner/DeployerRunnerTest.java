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
package com.android.tools.deployer.deployerrunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.deployer.DeployerRunner;
import com.android.tools.deployer.common.UIService;

import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public class DeployerRunnerTest {

    @Test
    public void testGetDbDirPath() {
        String originalTmp = System.getProperty("java.io.tmpdir");
        String originalUser = System.getProperty("user.name");
        try {
            // Test normal case
            System.setProperty("java.io.tmpdir", "/custom/tmp");
            System.setProperty("user.name", "testuser");
            String expected = "/custom/tmp" + File.separator + "android-testuser";
            assertEquals(expected, DeployerRunner.getDbDirPath());

            // Test null/empty cases
            System.clearProperty("java.io.tmpdir");
            System.clearProperty("user.name");
            String expectedFallback = "/tmp" + File.separator + "android-unknown";
            assertEquals(expectedFallback, DeployerRunner.getDbDirPath());

            System.setProperty("user.name", "");
            assertEquals(expectedFallback, DeployerRunner.getDbDirPath());
        } finally {
            restoreSystemProperty("java.io.tmpdir", originalTmp);
            restoreSystemProperty("user.name", originalUser);
        }
    }

    @Test
    public void testDefaultDbDirPath() {
        // High level test to ensure it works with actual system properties
        String userName = System.getProperty("user.name");
        if (userName != null && !userName.isEmpty()) {
            assertTrue(DeployerRunner.getDbDirPath().contains("android-" + userName));
        }
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value != null) {
            System.setProperty(key, value);
        } else {
            System.clearProperty(key);
        }
    }

    @Test
    public void testConstructorCreatesAndSecuresDir() throws Exception {
        Path tmpDir = Files.createTempDirectory("deployer_test");
        File deployDb = tmpDir.resolve("android-testuser/deploy.db").toFile();
        File dexDb = tmpDir.resolve("android-testuser/dex.db").toFile();

        // The directory "android-testuser" does not exist yet.
        assertFalse(deployDb.getParentFile().exists());

        // Instantiate runner, which should create and secure the directory
        UIService service = Mockito.mock(UIService.class);
        DeployerRunner runner = new DeployerRunner(deployDb, dexDb, service);

        // Verify directory was created
        assertTrue(deployDb.getParentFile().exists());
        assertTrue(dexDb.getParentFile().exists());

        // Verify permissions if on POSIX
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> perms =
                    Files.getPosixFilePermissions(deployDb.getParentFile().toPath());

            // Should be rwx------ (OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
            Set<PosixFilePermission> expectedPerms =
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
            assertEquals(expectedPerms, perms);
        }

        // Clean up
        deployDb.delete();
        dexDb.delete();
        deployDb.getParentFile().delete();
        Files.delete(tmpDir);
    }
}
