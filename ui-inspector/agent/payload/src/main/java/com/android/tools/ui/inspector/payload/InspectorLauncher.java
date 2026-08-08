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

package com.android.tools.ui.inspector.payload;

import android.util.Log;
import androidx.annotation.VisibleForTesting;
import com.android.tools.arttooling.Agent;
import com.android.tools.ui.inspector.common.ProtocolConstants;
import java.util.function.Consumer;

/**
 * Entry point for the UI Inspector payload, loaded by ART Tooling as its {@link Agent}. Starts a Unix domain socket server to listen for
 * commands from the host.
 */
public final class InspectorLauncher implements Agent {
  private static final String TAG = ProtocolConstants.LOG_TAG_PREFIX + ".InspectorLauncher";
  @VisibleForTesting
  static Thread serverThread = null;

  public InspectorLauncher() {}

  /** @param options the server token chosen by the host */
  @Override
  public void onAttach(String options) {
    onAttach(options, Server::startServer);
  }

  @VisibleForTesting
  void onAttach(String options, Consumer<String> serverStarter) {
    start(options, serverStarter);
  }

  public static synchronized void start(String serverToken) {
    start(serverToken, Server::startServer);
  }

  public static synchronized void start(String serverToken, Consumer<String> serverStarter) {
    if (serverThread != null && serverThread.isAlive()) {
      Log.i(TAG, "Inspector server is already running.");
      return;
    }
    serverThread = new Thread(() -> {
      try {
        serverStarter.accept(serverToken);
      } catch (Throwable t) {
        // Catching Throwable prevents any unhandled exception or error in the agent
        // from bringing down the entire application process.
        Log.e(TAG, "Uncaught exception in inspector", t);
      }
    }, "ui-inspector-server");
    serverThread.start();
  }
}
