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

package com.android.tools.studio.leakcanary;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Listens to LeakCanary's events and sends the heap analysis progress to Android Studio.
 *
 * <p>Used in ON_DEVICE only, where LeakCanary analyzes the heap on the device. It is added to
 * LeakCanary's Config.eventListeners next to LeakCanary's own listeners.
 *
 * <p>NOTE: Like {@link StudioLeakCanaryListener}, this uses a dynamic proxy, so there is no
 * compile-time dependency on LeakCanary.
 */
final class StudioLeakCanaryEventListener {

    private final Application application;

    private StudioLeakCanaryEventListener(Application application) {
        this.application = application;
    }

    /**
     * Creates a proxy of leakcanary.EventListener that calls a new StudioLeakCanaryEventListener.
     * Returns null if LeakCanary is missing or the proxy can't be made.
     */
    static Object createInstance(Application application) {
        try {
            Class<?> listenerInterface = Class.forName(HelperConfig.EVENT_LISTENER_CLASS);
            final StudioLeakCanaryEventListener listener =
                    new StudioLeakCanaryEventListener(application);

            return Proxy.newProxyInstance(
                    listenerInterface.getClassLoader(),
                    new Class<?>[] {listenerInterface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String name = method.getName();
                            if (HelperConfig.ON_EVENT_METHOD.equals(name)) {
                                listener.onEvent(args != null && args.length > 0 ? args[0] : null);
                                return null;
                            }
                            // Object methods, needed when the proxy is in a list or logged.
                            if (HelperConfig.TO_STRING_METHOD.equals(name)) {
                                return "StudioLeakCanaryEventListenerProxy";
                            }
                            if (HelperConfig.HASH_CODE_METHOD.equals(name)) {
                                return listener.hashCode();
                            }
                            if (HelperConfig.EQUALS_METHOD.equals(name)) {
                                return args[0] != null
                                        && Proxy.isProxyClass(args[0].getClass())
                                        && Proxy.getInvocationHandler(args[0]) == this;
                            }
                            Log.w(
                                    HelperConfig.LOG_TAG,
                                    "Unhandled method call on StudioLeakCanaryEventListener"
                                            + " proxy: "
                                            + name);
                            return StudioLeakCanaryListener.getDefaultValue(method.getReturnType());
                        }
                    });
        } catch (ClassNotFoundException e) {
            Log.i(HelperConfig.LOG_TAG, "LeakCanary EventListener not found. Progress not sent.");
            return null;
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to create StudioLeakCanaryEventListener proxy.", t);
            return null;
        }
    }

    /** Called by LeakCanary on its own threads. Must never throw, so the app can't crash. */
    private void onEvent(Object event) {
        try {
            if (StudioLeakCanaryManager.getInstance().getCurrentMode()
                    != StudioLeakCanaryManager.MODE_ON_DEVICE) {
                return;
            }
            int percent = LeakCanaryReflectionHelper.getHeapAnalysisProgressPercent(event);
            if (percent == HelperConfig.NO_ANALYSIS_PROGRESS) return; // Not a progress event.
            Intent intent = new Intent(HelperConfig.ANALYSIS_PROGRESS_INTENT);
            intent.putExtra(HelperConfig.ANALYSIS_PROGRESS_EXTRA, percent);
            intent.setPackage(application.getPackageName()); // Security: Keep within app
            application.sendBroadcast(intent);
            Log.d(HelperConfig.LOG_TAG, "Sent analysis progress broadcast with value: " + percent);
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to send LeakCanary analysis progress.", t);
        }
    }
}
