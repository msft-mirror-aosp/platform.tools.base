/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ddmlib;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ddmlib.clientmanager.ClientManager;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

class AndroidDebugBridgeImpl extends AndroidDebugBridgeBase {

    @Deprecated
    @Override
    public synchronized void initIfNeeded(boolean clientSupport) {
        unsupportedMethod();
    }

    @Override
    public synchronized void init(boolean clientSupport) {
        unsupportedMethod();
    }

    @Override
    public synchronized void init(
            boolean clientSupport, boolean useLibusb, @NonNull Map<String, String> env) {
        unsupportedMethod();
    }

    @Override
    public synchronized void init(AdbInitOptions options) {
        unsupportedMethod();
    }

    @Override
    public synchronized boolean optionsChanged(
            @NonNull AdbInitOptions options,
            @NonNull String osLocation,
            boolean forceNewBridge,
            long terminateTimeout,
            long initTimeout,
            @NonNull TimeUnit unit) {
        unsupportedMethod();
        return false;
    }

    @VisibleForTesting
    @Override
    public void enableFakeAdbServerMode(int port) {
        unsupportedMethod();
    }

    @VisibleForTesting
    @Override
    public void disableFakeAdbServerMode() {
        unsupportedMethod();
    }

    @Override
    public synchronized void terminate() {
        unsupportedMethod();
    }

    @Override
    public boolean getClientSupport() {
        unsupportedMethod();
        return false;
    }

    @Nullable
    @Override
    public ClientManager getClientManager() {
        unsupportedMethod();
        return null;
    }

    @Deprecated
    @Override
    public InetSocketAddress getSocketAddress() {
        unsupportedMethod();
        return null;
    }

    @Override
    public SocketChannel openConnection() throws IOException {
        unsupportedMethod();
        return null;
    }

    @Nullable
    @Override
    public AndroidDebugBridge createBridge() {
        unsupportedMethod();
        return null;
    }

    @Nullable
    @Override
    public AndroidDebugBridge createBridge(long timeout, @NonNull TimeUnit unit) {
        unsupportedMethod();
        return null;
    }

    @Deprecated
    @Nullable
    @Override
    public AndroidDebugBridge createBridge(@NonNull String osLocation, boolean forceNewBridge) {
        unsupportedMethod();
        return null;
    }

    @Nullable
    @Override
    public AndroidDebugBridge createBridge(
            @NonNull String osLocation,
            boolean forceNewBridge,
            long timeout,
            @NonNull TimeUnit unit) {
        unsupportedMethod();
        return null;
    }

    /** This method is still used by e.g. `AndroidDebugBridge.preInit` */
    @Nullable
    @Override
    public AndroidDebugBridge getBridge() {
        return null;
    }

    @Deprecated
    @Override
    public void disconnectBridge() {
        unsupportedMethod();
    }

    @Override
    public boolean disconnectBridge(long timeout, @NonNull TimeUnit unit) {
        unsupportedMethod();
        return false;
    }

    @Override
    public void addDebugBridgeChangeListener(
            @NonNull AndroidDebugBridge.IDebugBridgeChangeListener listener) {
        adbChangeEvents.addDebugBridgeChangeListener(listener);
    }

    @Override
    public void removeDebugBridgeChangeListener(
            AndroidDebugBridge.IDebugBridgeChangeListener listener) {
        adbChangeEvents.removeDebugBridgeChangeListener(listener);
    }

    @VisibleForTesting
    @Override
    public int getDebugBridgeChangeListenerCount() {
        return adbChangeEvents.debugBridgeChangeListenerCount();
    }

    @Override
    public void addDeviceChangeListener(
            @NonNull AndroidDebugBridge.IDeviceChangeListener listener) {
        adbChangeEvents.addDeviceChangeListener(listener);
    }

    @Override
    public void removeDeviceChangeListener(AndroidDebugBridge.IDeviceChangeListener listener) {
        adbChangeEvents.removeDeviceChangeListener(listener);
    }

    @VisibleForTesting
    @Override
    public int getDeviceChangeListenerCount() {
        return adbChangeEvents.deviceChangeListenerCount();
    }

    @Override
    public void addClientChangeListener(AndroidDebugBridge.IClientChangeListener listener) {
        adbChangeEvents.addClientChangeListener(listener);
    }

    @Override
    public void removeClientChangeListener(AndroidDebugBridge.IClientChangeListener listener) {
        adbChangeEvents.removeClientChangeListener(listener);
    }

    @Override
    public @Nullable AdbVersion getCurrentAdbVersion() {
        unsupportedMethod();
        return null;
    }

    @NonNull
    @Override
    public IDevice[] getDevices() {
        unsupportedMethod();
        return new IDevice[0];
    }

    @Override
    public boolean hasInitialDeviceList() {
        unsupportedMethod();
        return false;
    }

    @Override
    public boolean isConnected() {
        unsupportedMethod();
        return false;
    }

    @Nullable
    @Override
    public IDeviceUsageTracker getiDeviceUsageTracker() {
        unsupportedMethod();
        return null;
    }

    @Override
    public ListenableFuture<AdbVersion> getAdbVersion(@NonNull final File adb) {
        unsupportedMethod();
        return null;
    }

    @NonNull
    @Override
    public ListenableFuture<String> getVirtualDeviceId(
            @NonNull ListeningExecutorService service, @NonNull File adb, @NonNull IDevice device) {
        unsupportedMethod();
        return null;
    }

    @Override
    public ListenableFuture<List<AdbDevice>> getRawDeviceList() {
        unsupportedMethod();
        return null;
    }

    @Deprecated
    @Override
    public boolean restart() {
        unsupportedMethod();
        return false;
    }

    @Override
    public boolean restart(long timeout, @NonNull TimeUnit unit) {
        unsupportedMethod();
        return false;
    }

    @Override
    public void deviceConnected(@NonNull IDevice device) {
        unsupportedMethod();
    }

    @Override
    public void deviceDisconnected(@NonNull IDevice device) {
        unsupportedMethod();
    }

    @Override
    public void deviceChanged(@NonNull IDevice device, int changeMask) {
        unsupportedMethod();
    }

    @Override
    public void clientChanged(@NonNull Client client, int changeMask) {
        unsupportedMethod();
    }

    @Override
    public boolean isUserManagedAdbMode() {
        return false;
    }

    @Override
    public String queryFeatures(String adbFeaturesRequest)
            throws TimeoutException, AdbCommandRejectedException, IOException {
        unsupportedMethod();
        return null;
    }

    @Override
    public synchronized boolean startAdb(long timeout, @NonNull TimeUnit unit) {
        unsupportedMethod();
        return false;
    }

    @Override
    public synchronized boolean stopAdb(long timeout, @NonNull TimeUnit unit) {
        unsupportedMethod();
        return false;
    }

    private void unsupportedMethod() {
        throw new UnsupportedOperationException(
                "This `AndroidDebugBridgeImpl` method should not be used by AndroidStudio");
    }
}
