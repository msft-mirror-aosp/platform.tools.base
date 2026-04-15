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
package com.android.tools.deployer.common;

import com.android.tools.deployer.AdbInstaller;
import com.android.tools.deployer.Deployer;

public class DeployerTestConstants {
    public static final String INSTALLER_WORKSPACE =
            Deployer.INSTALLER_DIRECTORY + " " + Deployer.INSTALLER_TMP_DIRECTORY;
    public static final String RM_DIR = "rm -fr " + INSTALLER_WORKSPACE;
    public static final String MK_DIR = "mkdir -p " + INSTALLER_WORKSPACE;
    public static final String CHMOD_DIR = "chmod -R 775 " + Deployer.BASE_DIRECTORY;
    public static final String CHOWN_DIR = "chown -R shell:shell " + Deployer.BASE_DIRECTORY;
    public static final String CHMOD_INSTALLER = "chmod +x " + AdbInstaller.INSTALLER_PATH;
}
