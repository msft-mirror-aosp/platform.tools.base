/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.annotations.NonNull;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.common.DeployerIShellOutputReceiver;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.deployer.model.Apk;
import com.android.tools.deployer.model.App;
import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.component.AppComponent;
import com.android.tools.deployer.model.component.ComponentType;
import com.android.tools.deployer.modelv1.component.ActivityV1;
import com.android.tools.deployer.modelv1.component.AppComponentV1;
import com.android.tools.deployer.modelv1.component.ComplicationV1;
import com.android.tools.deployer.modelv1.component.TileV1;
import com.android.tools.deployer.modelv1.component.WatchFaceV1;
import com.android.tools.deployer.modelv1.component.WearWidgetV1;
import com.android.tools.manifest.parser.components.ManifestActivityInfo;
import com.android.tools.manifest.parser.components.ManifestServiceInfo;
import com.android.utils.ILogger;

import java.util.Optional;

public class Activator {
    static final String NO_FLAGS = "";

    private final App app;
    private final ILogger logger;

    public Activator(@NonNull App app, @NonNull ILogger logger) {
        this.app = app;
        this.logger = logger;
    }

    public void activate(
            @NonNull ComponentType type,
            @NonNull String componentName,
            @NonNull DeployerIShellOutputReceiver receiver,
            @NonNull DeviceHolder device)
            throws DeployerException {
        activate(type, componentName, NO_FLAGS, AppComponent.Mode.RUN, receiver, device);
    }

    public void activate(
            @NonNull ComponentType type,
            @NonNull String componentName,
            @NonNull String extraFlags,
            @NonNull DeployerIShellOutputReceiver receiver,
            @NonNull DeviceHolder device)
            throws DeployerException {
        activate(type, componentName, extraFlags, AppComponent.Mode.RUN, receiver, device);
    }

    public void activate(
            @NonNull ComponentType type,
            @NonNull String componentName,
            @NonNull AppComponent.Mode mode,
            @NonNull DeployerIShellOutputReceiver receiver,
            @NonNull DeviceHolder device)
            throws DeployerException {
        activate(type, componentName, NO_FLAGS, mode, receiver, device);
    }

    public void activate(
            @NonNull ComponentType type,
            @NonNull String componentName,
            @NonNull String extraFlags,
            @NonNull AppComponent.Mode mode,
            @NonNull DeployerIShellOutputReceiver receiver,
            @NonNull DeviceHolder device)
            throws DeployerException {
        String qualifiedName =
                componentName.startsWith(".") ? app.getAppId() + componentName : componentName;
        AppComponentV1 component = getComponent(type, qualifiedName);
        try {
            component.activate(extraFlags, mode, receiver, device);
        } catch (ModelException e) {
            throw DeployerException.componentActivationException(e.getMessage());
        }
    }

    @NonNull
    private AppComponentV1 getComponent(@NonNull ComponentType type, @NonNull String qualifiedName)
            throws DeployerException {
        AppComponentV1 component = null;
        switch (type) {
            case ACTIVITY:
                Optional<ManifestActivityInfo> optionalActivity = getActivity(qualifiedName);
                if (optionalActivity.isPresent()) {
                    component = new ActivityV1(optionalActivity.get(), app.getAppId(), logger);
                }
                break;
            case WATCH_FACE:
                Optional<ManifestServiceInfo> optionalService = getService(qualifiedName);
                if (optionalService.isPresent()) {
                    component = new WatchFaceV1(optionalService.get(), app.getAppId(), logger);
                }
                break;
            case TILE:
                optionalService = getService(qualifiedName);
                if (optionalService.isPresent()) {
                    component = new TileV1(optionalService.get(), app.getAppId(), logger);
                }
                break;
            case WEAR_WIDGET:
                optionalService = getService(qualifiedName);
                if (optionalService.isPresent()) {
                    component = new WearWidgetV1(optionalService.get(), app.getAppId(), logger);
                }
                break;
            case COMPLICATION:
                optionalService = getService(qualifiedName);
                if (optionalService.isPresent()) {
                    component = new ComplicationV1(optionalService.get(), app.getAppId(), logger);
                }
                break;
            default:
                throw DeployerException.componentActivationException(
                        "Unsupported app component type " + type);
        }
        if (component == null) {
            throw DeployerException.componentActivationException(
                    String.format(
                            "'%s' with name '%s' is not found in '%s'",
                            type, qualifiedName, app.getAppId()));
        }
        return component;
    }

    @NonNull
    private Optional<ManifestActivityInfo> getActivity(@NonNull String qualifiedName) {
        for (Apk apk : app.getApks()) {
            Optional<ManifestActivityInfo> optionalActivity =
                    apk.activities.stream()
                            .filter(a -> a.getQualifiedName().equals(qualifiedName))
                            .findAny();
            if (optionalActivity.isPresent()) {
                return optionalActivity;
            }
        }
        return Optional.empty();
    }

    @NonNull
    private Optional<ManifestServiceInfo> getService(@NonNull String qualifiedName) {
        for (Apk apk : app.getApks()) {
            Optional<ManifestServiceInfo> optionalService =
                    apk.services.stream()
                            .filter(a -> a.getQualifiedName().equals(qualifiedName))
                            .findAny();
            if (optionalService.isPresent()) {
                return optionalService;
            }
        }
        return Optional.empty();
    }
}
