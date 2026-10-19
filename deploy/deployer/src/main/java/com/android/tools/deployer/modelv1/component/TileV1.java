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
package com.android.tools.deployer.modelv1.component;

import com.android.annotations.NonNull;
import com.android.tools.deployer.common.DeployerIShellOutputReceiver;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.component.AppComponent;
import com.android.tools.deployer.model.component.Tile;
import com.android.tools.manifest.parser.components.ManifestServiceInfo;
import com.android.utils.ILogger;

public class TileV1 extends Tile implements WearComponentV1 {

    public TileV1(
            @NonNull ManifestServiceInfo info, @NonNull String appId, @NonNull ILogger logger) {
        super(info, appId, logger);
    }

    @Override
    public void activate(
            @NonNull String extraFlags,
            @NonNull Mode activationMode,
            @NonNull DeployerIShellOutputReceiver addTileReceiver,
            @NonNull DeviceHolder device)
            throws ModelException {
        validate(extraFlags);
        logger.info(
                "Activating Tile '%s' %s",
                info.getQualifiedName(),
                activationMode.equals(AppComponent.Mode.DEBUG) ? "for debug" : "");

        if (activationMode.equals(AppComponent.Mode.DEBUG)) {
            setUpAmDebugApp(device, appId);
            setUpDebugSurfaceDebugApp(device, appId, logger);
        }
        String command = getStartTileCommand();
        runStartCommand(command, addTileReceiver, logger, device);
    }
}
