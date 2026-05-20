/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.sdklib.devices;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Slow;
import com.android.io.CancellableFileIo;
import com.android.prefs.AndroidLocationsProvider;
import com.android.repository.api.RepoManager;
import com.android.sdklib.internal.avd.AvdInfo;
import com.android.sdklib.repository.AndroidSdkHandler;
import com.android.sdklib.repository.LoggerProgressIndicatorWrapper;
import com.android.sdklib.repository.meta.DetailsTypes;
import com.android.utils.ILogger;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

import org.xml.sax.SAXException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactoryConfigurationError;

/** Manager class for interacting with {@link Device}s within the SDK */
public class DeviceManager {

    @Nullable private final Path mAndroidFolder;

    private final ILogger mLog;

    private final VendorDevices mVendorDevices;

    private final Predicate<Device> mIsSupportedDevice;

    private Table<String, String, Device> mSysImgDevices;

    private Table<String, String, Device> mUserDevices;

    private final DefaultDevices mDefaultDevices;

    private final Object mLock = new Object();

    private final List<DevicesChangedListener> sListeners = new ArrayList<>();

    private final Path mOsSdkPath;

    private final AndroidSdkHandler mSdkHandler;

    public enum DeviceCategory {
        /** getDevices() flag to list default devices from the bundled devices.xml definitions. */
        DEFAULT,
        /** getDevices() flag to list user devices saved in the .android home folder. */
        USER,
        /**
         * getDevices() flag to list vendor devices -- the bundled nexus.xml devices as well as all
         * those coming from extra packages.
         */
        VENDOR,
        /** getDevices() flag to list devices from system-images/platform-N/tag/abi/devices.xml */
        SYSTEM_IMAGES,
    }

    /** getDevices() flag to list all devices. */
    public static final EnumSet<DeviceCategory> ALL_DEVICES = EnumSet.allOf(DeviceCategory.class);

    public enum DeviceStatus {
        /** The device exists unchanged from the given configuration */
        EXISTS,
        /**
         * A device exists with the given name and manufacturer, but has a different configuration
         */
        CHANGED,
        /** There is no device with the given name and manufacturer */
        MISSING
    }

    /**
     * Creates a new instance of {@link DeviceManager}, using the user's android folder.
     *
     * @see #createInstance(AndroidSdkHandler, ILogger, Predicate)
     */
    public static DeviceManager createInstance(
            @NonNull AndroidLocationsProvider androidLocationsProvider,
            @Nullable Path sdkLocation,
            @NonNull ILogger log) {
        return createInstance(
                AndroidSdkHandler.getInstance(androidLocationsProvider, sdkLocation),
                log,
                (device) -> true);
    }

    public static DeviceManager createInstance(
            @NonNull AndroidSdkHandler sdkHandler,
            @NonNull ILogger log,
            @NonNull Predicate<Device> isSupportedDevice) {
        return new DeviceManager(sdkHandler, log, isSupportedDevice);
    }

    public static DeviceManager createInstance(
            @NonNull AndroidSdkHandler sdkHandler, @NonNull ILogger log) {
        return new DeviceManager(sdkHandler, log, (device) -> true);
    }

    /**
     * Creates a new instance of DeviceManager.
     *
     * @param sdkHandler The AndroidSdkHandler to use.
     * @param log SDK logger instance. Should be non-null.
     * @param isSupportedDevice function that allows filtering certain devices.
     */
    private DeviceManager(
            @NonNull AndroidSdkHandler sdkHandler,
            @NonNull ILogger log,
            @NonNull Predicate<Device> isSupportedDevice) {
        mSdkHandler = sdkHandler;
        mOsSdkPath = sdkHandler.getLocation() == null ? null : sdkHandler.getLocation();
        mAndroidFolder =
                sdkHandler.getAndroidFolder() == null
                        ? Paths.get("")
                        : sdkHandler.getAndroidFolder();
        mLog = log;
        mDefaultDevices = new DefaultDevices(mLog);
        mVendorDevices = new VendorDevices(mLog);
        mIsSupportedDevice = isSupportedDevice;
    }

    /**
     * Interface implemented by objects which want to know when changes occur to the {@link Device}
     * lists.
     */
    public interface DevicesChangedListener {

        /** Called after one of the {@link Device} lists has been updated. */
        void onDevicesChanged();
    }

    /**
     * Register a listener to be notified when the device lists are modified.
     *
     * @param listener The listener to add. Ignored if already registered.
     */
    public void registerListener(@NonNull DevicesChangedListener listener) {
        synchronized (sListeners) {
            if (!sListeners.contains(listener)) {
                sListeners.add(listener);
            }
        }
    }

    /**
     * Removes a listener from the notification list such that it will no longer receive
     * notifications when modifications to the {@link Device} list occur.
     *
     * @param listener The listener to remove.
     */
    public boolean unregisterListener(@NonNull DevicesChangedListener listener) {
        synchronized (sListeners) {
            return sListeners.remove(listener);
        }
    }

    @NonNull
    public DeviceStatus getDeviceStatus(@NonNull String name, @NonNull String manufacturer) {
        Device d = getDevice(name, manufacturer);
        if (d == null) {
            return DeviceStatus.MISSING;
        }

        return DeviceStatus.EXISTS;
    }

    @Nullable
    public Device getDevice(@NonNull String id, @NonNull String manufacturer) {
        initDevicesLists();
        Device d = mUserDevices.get(id, manufacturer);
        if (d != null) {
            return d;
        }
        d = mSysImgDevices.get(id, manufacturer);
        if (d != null) {
            return d;
        }
        d = mDefaultDevices.getDevice(id, manufacturer);
        if (d != null) {
            return d;
        }
        d = mVendorDevices.getDevice(id, manufacturer);
        if (d != null) {
            return d;
        }
        return d;
    }

    @Nullable
    public Device getDevice(@NonNull AvdInfo avdInfo) {
        return getDevice(avdInfo.getDeviceName(), avdInfo.getDeviceManufacturer());
    }

    /**
     * Returns the known {@link Device} list.
     *
     * @param deviceCategory One of the {@link DeviceCategory} constants.
     * @return A copy of the list of {@link Device}s. Can be empty but not null.
     */
    @NonNull
    public Collection<Device> getDevices(@NonNull DeviceCategory deviceCategory) {
        return getDevices(EnumSet.of(deviceCategory));
    }

    /**
     * Returns the known {@link Device} list.
     *
     * @param deviceCategory A combination of the {@link DeviceCategory} constants or the constant
     *     {@link DeviceManager#ALL_DEVICES}.
     * @return A copy of the list of {@link Device}s. Can be empty but not null.
     */
    @NonNull
    public Collection<Device> getDevices(@NonNull Collection<DeviceCategory> deviceCategory) {
        initDevicesLists();
        Table<String, String, Device> devices = HashBasedTable.create();
        if (mUserDevices != null && (deviceCategory.contains(DeviceCategory.USER))) {
            devices.putAll(mUserDevices);
        }
        if (mDefaultDevices.getDevices() != null
                && (deviceCategory.contains(DeviceCategory.DEFAULT))) {
            devices.putAll(mDefaultDevices.getDevices());
        }
        if (mVendorDevices.getDevices() != null
                && (deviceCategory.contains(DeviceCategory.VENDOR))) {
            devices.putAll(mVendorDevices.getDevices());
        }
        if (mSysImgDevices != null && (deviceCategory.contains(DeviceCategory.SYSTEM_IMAGES))) {
            devices.putAll(mSysImgDevices);
        }

        return Collections.unmodifiableCollection(devices.values());
    }

    private void initDevicesLists() {
        boolean changed = mDefaultDevices.init();
        changed |= mVendorDevices.init(mIsSupportedDevice);
        changed |= initSysImgDevices();
        changed |= initUserDevices();
        if (changed) {
            notifyListeners();
        }
    }

    /**
     * Initializes all system-image provided {@link Device}s.
     *
     * @return true if the list has changed.
     */
    @Slow
    private boolean initSysImgDevices() {
        synchronized (mLock) {
            if (mSysImgDevices != null) {
                return false;
            }

            if (mOsSdkPath == null) {
                mSysImgDevices = HashBasedTable.create();
                return false;
            }

            Table<String, String, Device> sysImgDevices = HashBasedTable.create();

            // Load device definitions from the system image directories.
            // Load in increasing order of Android version. This way, if there is a conflict,
            // we'll retain the definitions from the higher API level. The file in the higher
            // API directory is probably newer and more accurate.
            LoggerProgressIndicatorWrapper progress = new LoggerProgressIndicatorWrapper(mLog);

            RepoManager mgr = mSdkHandler.getRepoManager(progress);
            mgr.loadSynchronously(RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, progress, null, null);
            mgr.getPackages().getLocalPackages().values().stream()
                    .filter(pkg -> pkg.getTypeDetails() instanceof DetailsTypes.SysImgDetailsType)
                    .sorted(
                            Comparator.comparing(
                                    pkg ->
                                            ((DetailsTypes.SysImgDetailsType) pkg.getTypeDetails())
                                                    .getAndroidVersion()))
                    .forEach(
                            pkg -> {
                                Path deviceXml =
                                        pkg.getLocation().resolve(SdkConstants.FN_DEVICES_XML);
                                if (CancellableFileIo.isRegularFile(deviceXml)) {
                                    for (Device device : loadDevices(deviceXml).values()) {
                                        if (!mIsSupportedDevice.test(device)) continue;
                                        if (isDeprecatedWearDevice(device)) {
                                            Device.Builder builder = new Device.Builder(device);
                                            builder.setDeprecated(true);
                                            device = builder.build();
                                        }
                                        sysImgDevices.put(
                                                device.getId(), device.getManufacturer(), device);
                                    }
                                }
                            });
            mSysImgDevices = sysImgDevices;
            return true;
        }
    }

    /**
     * Some device definitions are present in old system images which can override some vendor
     * device definitions. This method ensures these devices are considered as deprecated. For
     * example, `wearos_rect` is deprecated in the vendor definition, however APIs 30 and 33 also
     * provide a `wearos_rect` which is not deprecated. This function checks whether the device is a
     * deprecated wear device based on its id. If the device's id does not start with `wearos` or
     * the id is either `wearos_square` or `wearos_rect` then it is considered as deprecated.
     */
    private static boolean isDeprecatedWearDevice(Device device) {
        boolean isWearDevice = "android-wear".equals(device.getTagId());
        if (!isWearDevice) return false;
        return !device.getId().startsWith("wearos")
                || "wearos_square".equals(device.getId())
                || "wearos_rect".equals(device.getId());
    }

    /**
     * Initializes all user-created {@link Device}s
     *
     * @return True if the list has changed.
     */
    private boolean initUserDevices() {
        synchronized (mLock) {
            if (mUserDevices != null) {
                return false;
            }
            // User devices should be saved out to
            // $HOME/.android/devices.xml
            Table<String, String, Device> userDevices = HashBasedTable.create();
            Path userDevicesFile = null;
            try {
                try {
                    userDevicesFile = mAndroidFolder.resolve(SdkConstants.FN_DEVICES_XML);
                    if (userDevicesFile != null && Files.exists(userDevicesFile)) {
                        DeviceParser.parse(userDevicesFile)
                                .cellSet()
                                .forEach(
                                        (cell) -> {
                                            if (mIsSupportedDevice.test(cell.getValue())) {
                                                userDevices.put(
                                                        cell.getRowKey(),
                                                        cell.getColumnKey(),
                                                        cell.getValue());
                                            } else {
                                                mLog.warning(
                                                        "Unsupported device %s", cell.getRowKey());
                                            }
                                        });

                        mUserDevices = userDevices;
                        return true;
                    }
                } catch (SAXException e) {
                    // Probably an old config file which we don't want to overwrite.
                    if (userDevicesFile != null) {
                        Path parent = userDevicesFile.toAbsolutePath().getParent();
                        String base = userDevicesFile.getFileName().toString() + ".old";
                        Path renamedConfig = parent.resolve(base);
                        int i = 0;
                        while (CancellableFileIo.exists(renamedConfig)) {
                            renamedConfig = parent.resolve(base + '.' + (i++));
                        }
                        mLog.error(
                                e,
                                "Error parsing %1$s, backing up to %2$s",
                                userDevicesFile.toAbsolutePath(),
                                renamedConfig.toAbsolutePath());
                        Files.move(userDevicesFile, renamedConfig);
                    }
                }
            } catch (ParserConfigurationException | IOException e) {
                mLog.error(
                        e,
                        "Error parsing %1$s",
                        userDevicesFile == null ? "(null)" : userDevicesFile.toAbsolutePath());
            }
        }
        mUserDevices = HashBasedTable.create();
        return false;
    }

    public void addUserDevice(@NonNull Device d) {
        if (!mIsSupportedDevice.test(d)) return;
        boolean changed = false;
        synchronized (mLock) {
            if (mUserDevices == null) {
                initUserDevices();
                assert mUserDevices != null;
            }
            if (mUserDevices != null) {
                mUserDevices.put(d.getId(), d.getManufacturer(), d);
            }
            changed = true;
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void removeUserDevice(@NonNull Device d) {
        synchronized (mLock) {
            if (mUserDevices == null) {
                initUserDevices();
                assert mUserDevices != null;
            }
            if (mUserDevices != null) {
                if (mUserDevices.contains(d.getId(), d.getManufacturer())) {
                    mUserDevices.remove(d.getId(), d.getManufacturer());
                    notifyListeners();
                }
            }
        }
    }

    public void replaceUserDevice(@NonNull Device d) {
        synchronized (mLock) {
            if (mUserDevices == null) {
                initUserDevices();
            }
            removeUserDevice(d);
            addUserDevice(d);
        }
    }

    /** Saves out the user devices to {@link SdkConstants#FN_DEVICES_XML} in the Android folder. */
    public void saveUserDevices() {
        if (mUserDevices == null) {
            return;
        }
        if (mAndroidFolder == null) {
            return;
        }

        Path userDevicesFile = mAndroidFolder.resolve(SdkConstants.FN_DEVICES_XML);

        if (mUserDevices.isEmpty()) {
            try {
                Files.deleteIfExists(userDevicesFile);
            } catch (IOException ignore) {
                // nothing
            }
            return;
        }

        synchronized (mLock) {
            if (!mUserDevices.isEmpty()) {
                try {
                    DeviceWriter.writeToXml(
                            Files.newOutputStream(userDevicesFile), mUserDevices.values());
                } catch (FileNotFoundException e) {
                    mLog.warning("Couldn't open file: %1$s", e.getMessage());
                } catch (ParserConfigurationException
                        | IOException
                        | TransformerException
                        | TransformerFactoryConfigurationError e) {
                    mLog.warning("Error writing file: %1$s", e.getMessage());
                }
            }
        }
    }

    @NonNull
    private Table<String, String, Device> loadDevices(@NonNull Path deviceXml) {
        try {
            return DeviceParser.parse(deviceXml);
        } catch (SAXException | ParserConfigurationException | AssertionError e) {
            mLog.error(e, "Error parsing %1$s", deviceXml.toAbsolutePath());
        } catch (IOException e) {
            mLog.error(e, "Error reading %1$s", deviceXml.toAbsolutePath());
        } catch (IllegalStateException e) {
            // The device builders can throw IllegalStateExceptions if
            // build gets called before everything is properly setup
            mLog.error(e, null);
        }
        return HashBasedTable.create();
    }

    private void notifyListeners() {
        synchronized (sListeners) {
            for (DevicesChangedListener listener : sListeners) {
                listener.onDevicesChanged();
            }
        }
    }
}
