/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static com.android.ddmlib.Log.LogLevel.INFO;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Locale;

/** Contains the data of a Client. */
public class ClientData {
    /* This is a place to stash data associated with a Client, such as thread
     * states or heap data.  ClientData maps 1:1 to Client, but it's a little
     * cleaner if we separate the data out.
     *
     * Message handlers are welcome to stash arbitrary data here.
     *
     * IMPORTANT: The data here is written by HandleFoo methods and read by
     * FooPanel methods, which run in different threads.  All non-trivial
     * access should be synchronized against the ClientData object.
     */

    /** Temporary name of VM to be ignored. */
    public static final String PRE_INITIALIZED = "<pre-initialized>"; // $NON-NLS-1$

    private static final Names UNINITIALIZED = new Names(null, null, null);

    public enum DebuggerStatus {
        /** Debugger connection status: not waiting on one, not connected to one, but accepting
         * new connections. This is the default value. */
        DEFAULT,
        /**
         * Debugger connection status: the application's VM is paused, waiting for a debugger to
         * connect to it before resuming. */
        WAITING,
        /** Debugger connection status : Debugger is connected */
        ATTACHED,
        /** Debugger connection status: The listening port for debugger connection failed to listen.
         * No debugger will be able to connect. */
        ERROR
    }

    public enum MethodProfilingStatus {
        /**
         * Method profiling status: unknown.
         *
         * <p>This happens right after a Client is discovered by the {@link AndroidDebugBridge}, and
         * before the Client answered the query regarding its method profiling status.
         *
         * @see Client#requestMethodProfilingStatus()
         */
        UNKNOWN,
        /** Method profiling status: the Client is not profiling method calls. */
        OFF,
        /** Method profiling status: the Client is tracing method calls. */
        TRACER_ON,
        /** Method profiling status: the Client is being profiled via sampling. */
        SAMPLER_ON
    }

    /**
     * String for feature enabling direct streaming of method profiling data
     *
     * @see #hasFeature(String)
     */
    public static final String FEATURE_PROFILING_STREAMING =
            "method-trace-profiling-streaming"; //$NON-NLS-1$

    private static IMethodProfilingHandler sMethodProfilingHandler;

    // owning Client
    private final Client mClient;

    // the client's process ID
    private final int mPid;

    // Java VM identification string
    private String mVmIdentifier;

    // client's self-description
    private Names mClientNames = UNINITIALIZED;

    // client's ABI
    private String mAbi;

    // is the app native debuggable?
    private boolean mNativeDebuggable = false;

    // how interested are we in a debugger?
    private DebuggerStatus mDebuggerInterest;

    // List of supported features by the client.
    private final HashSet<String> mFeatures = new HashSet<String>();

    private byte[] mAllocationsData;

    @Deprecated
    private String mPendingHprofDump;

    private MethodProfilingStatus mProfilingStatus = MethodProfilingStatus.UNKNOWN;

    /**
     * Handlers able to act on Method profiling info
     */
    public interface IMethodProfilingHandler {
        /**
         * Called when a method tracing was successful.
         * @param remoteFilePath the device-side path of the trace file.
         * @param client the client that was profiled.
         */
        void onSuccess(String remoteFilePath, Client client);

        /**
         * Called when a method tracing was successful.
         * @param data the data containing the trace file, streamed from the VM
         * @param client the client that was profiled.
         */
        void onSuccess(byte[] data, Client client);

        /**
         * Called when method tracing failed to start
         * @param client the client that was profiled.
         * @param message an optional (<code>null</code> ok) error message to be displayed.
         */
        void onStartFailure(Client client, String message);

        /**
         * Called when method tracing failed to end on the VM side
         * @param client the client that was profiled.
         * @param message an optional (<code>null</code> ok) error message to be displayed.
         */
        void onEndFailure(Client client, String message);
    }

    /**
     * Sets the handler to receive notifications when an HPROF dump succeeded or failed.
     * This method is deprecated, please register a client listener and listen for CHANGE_HPROF.
     */
    public static void setMethodProfilingHandler(IMethodProfilingHandler handler) {
        sMethodProfilingHandler = handler;
    }

    public static IMethodProfilingHandler getMethodProfilingHandler() {
        return sMethodProfilingHandler;
    }

    /** Generic constructor. */
    public ClientData(@NonNull Client client, int pid) {
        mClient = client;
        mPid = pid;

        mDebuggerInterest = DebuggerStatus.DEFAULT;

        // Log pid to help troubleshoot "Cannot connect to debugger" issued.
        if (Log.isAtLeast(INFO)) {
            String msg =
                    String.format(Locale.US, "Device '%s' ", client.getDevice().getName())
                            + String.format(Locale.US, "tracking jdwp process (pid=%d)", pid);
            Log.i("ddms_client", msg);
        }
    }

    /**
     * Returns the process ID.
     */
    public int getPid() {
        return mPid;
    }

    /**
     * Returns the Client's VM identifier.
     */
    public String getVmIdentifier() {
        return mVmIdentifier;
    }

    /** Sets VM identifier. */
    public void setVmIdentifier(String ident) {
        mVmIdentifier = ident;
    }

    /**
     * @deprecated Use {@link #getProcessName} instead.
     */
    @Deprecated()
    @Nullable
    public String getClientDescription() {
        return getProcessName();
    }

    /**
     * Returns the process name.
     *
     * <p>This is generally the name of the package defined in the <code>AndroidManifest.xml</code>.
     *
     * @return the process name or <code>null</code> if not the process name was not yet sent
     *     by the client.
     */
    @Nullable
    public String getProcessName() {
        return mClientNames.mProcessName;
    }

    /**
     * Returns the client's user id.
     * @return user id if set, -1 otherwise
     */
    public int getUserId() {
        return mClientNames.mUserId == null ? -1 : mClientNames.mUserId;
    }

    /**
     * Returns true if the user id of this client was set. Only devices that support multiple
     * users will actually return the user id to ddms. For other/older devices, this will not
     * be set.
     */
    public boolean isValidUserId() {
        return mClientNames != UNINITIALIZED;
    }

    /** Returns the abi flavor (32-bit or 64-bit) of the application, null if unknown or not set. */
    @Nullable
    public String getAbi() {
        return mAbi;
    }

    /**
     * Sets the process, user ID (i.e. personal vs work profile), and package names.
     *
     * <p>There may be a race between HELO and APNM. Rather than try to enforce ordering on the
     * device, we just don't allow the pre-initialized name to replace a specified one.
     */
    public void setNames(Names names) {
        /*
         * The application VM is first named <pre-initialized> before being assigned
         * its real name.
         * Depending on the timing, we can get an APNM chunk setting this name before
         * another one setting the final actual name. So if we get a SetClientDescription
         * with this value we ignore it.
         */
        if (!names.mProcessName.isEmpty() && !PRE_INITIALIZED.equals(names.mProcessName)) {
            mClientNames = names;

            // Log when pid gets a name to debug issue likes "Cannot connect to debugger"
            if (Log.isAtLeast(INFO)) {
                String msg =
                        String.format(Locale.US, "Device '%s' ", mClient.getDevice().getName())
                                + String.format(
                                        Locale.US,
                                        "jdwp process '%d' is now known as pkg='%s' (proc='%s')",
                                        getPid(),
                                        names.mPackageName,
                                        names.mProcessName);
                Log.i("ddms_client", msg);
            }
        }
    }

    public void setAbi(String abi) {
        mAbi = abi;
    }

    public boolean isNativeDebuggable() {
        return mNativeDebuggable;
    }

    public void setNativeDebuggable(boolean nativeDebuggable) {
        mNativeDebuggable = nativeDebuggable;
    }

    /**
     * Returns the debugger connection status.
     */
    public DebuggerStatus getDebuggerConnectionStatus() {
        return mDebuggerInterest;
    }

    /** Sets debugger connection status. */
    public void setDebuggerConnectionStatus(DebuggerStatus status) {
        mDebuggerInterest = status;
    }

    public synchronized void setAllocationsData(byte[] data) {
        mAllocationsData = data;
    }

    /**
     * Returns the raw data for tracked allocations.
     *
     * @see Client#requestAllocationDetails()
     */
    public synchronized byte[] getAllocationsData() {
        return mAllocationsData;
    }

    /**
     * Returns the list of tracked allocations.
     *
     * @see Client#requestAllocationDetails()
     */
    @Nullable
    public synchronized AllocationInfo[] getAllocations() {
        if (mAllocationsData != null) {
            return AllocationsParser.parse(ByteBuffer.wrap(mAllocationsData));
        }
        return null;
    }

    public void addFeature(String feature) {
        mFeatures.add(feature);
    }

    /**
     * Returns true if the Client supports the given <var>feature</var>
     *
     * @param feature The feature to test.
     * @return true if the feature is supported
     * @see ClientData#FEATURE_PROFILING_STREAMING
     */
    public boolean hasFeature(String feature) {
        return mFeatures.contains(feature);
    }

    /**
     * Sets the device-side path to the hprof file being written
     *
     * @param pendingHprofDump the file to the hprof file
     */
    @Deprecated
    public void setPendingHprofDump(String pendingHprofDump) {
        mPendingHprofDump = pendingHprofDump;
    }

    /** Returns the path to the device-side hprof file being written. */
    @Deprecated
    public String getPendingHprofDump() {
        return mPendingHprofDump;
    }

    @Deprecated
    public boolean hasPendingHprofDump() {
        return mPendingHprofDump != null;
    }

    public synchronized void setMethodProfilingStatus(MethodProfilingStatus status) {
        mProfilingStatus = status;
    }

    /**
     * Returns the method profiling status.
     *
     * @see Client#requestMethodProfilingStatus()
     */
    public synchronized MethodProfilingStatus getMethodProfilingStatus() {
        return mProfilingStatus;
    }

    /**
     * Returns the application's real package name if there is protocol support. If there is no
     * protocol support, returns the attempted derivation of the package name from the app name (to
     * maintain backward compatibility), or the app name if not successful.
     */
    @Nullable
    public String getPackageName() {
        // Use the reported package name if the version (R+) supports it.
        if (mClient.getDevice().supportsFeature(IDevice.Feature.REAL_PKG_NAME)) {
            return mClientNames.mPackageName;
        }
        // Try to infer the package name.
        // Check for multi-process app name that might have a following format - "$applicationId:$processName"
        if (mClientNames.mProcessName == null) {
            return null;
        }
        int colonPos = mClientNames.mProcessName.indexOf(':');
        return (colonPos == -1)
                ? mClientNames.mProcessName
                : mClientNames.mProcessName.substring(0, colonPos);
    }

    /**
     * Returns the application's data directory.
     */
    @NonNull
    public String getDataDir() {
        String packageName = getPackageName();

        if (isValidUserId() && getUserId() > 0) {
            return String.format("/data/user/%d/%s", getUserId(), packageName);
        }
        return "/data/data/" + packageName;
    }

    public static class Names {
        public final String mProcessName;
        public final Integer mUserId;
        public final String mPackageName;

        public Names(String processName, Integer id, String packageName) {
            this.mProcessName = processName;
            mUserId = id;
            this.mPackageName = packageName;
        }
    }
}
