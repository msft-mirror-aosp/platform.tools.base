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

package com.android.tools.ui.inspector.payload.appinspection;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.inspection.ArtTooling;
import androidx.inspection.Connection;
import androidx.inspection.InspectorEnvironment;
import androidx.inspection.InspectorExecutors;

import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;

import android.os.Build;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

@RunWith(RobolectricTestRunner.class)
public final class AppInspectionUtilsTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Connection mockConnection = new Connection() {
    @Override
    public void sendEvent(byte[] data) {}
  };

  private final InspectorEnvironment unusedEnvironment = new InspectorEnvironment() {
    @Override
    public InspectorExecutors executors() {
      throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public ArtTooling artTooling() {
      throw new UnsupportedOperationException("Not implemented");
    }
  };

  @Test
  public void testCreateInspectorEnvironment_ioExecutorDelegates() throws InterruptedException {
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
        InspectorEnvironment environment =
                AppInspectionUtils.createInspectorEnvironment(
                        "test_inspector", primaryExecutor, t -> {});

    CountDownLatch latch = new CountDownLatch(1);
    environment.executors().io().execute(latch::countDown);

    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    primaryExecutor.quitSafely();
  }

  @Test
  public void testCreateInspectorEnvironment_ioExecutorCatchesException() throws InterruptedException {
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
    AtomicReference<Throwable> caughtThrowable = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    RuntimeException exception = new RuntimeException("Test exception");

        InspectorEnvironment environment =
                AppInspectionUtils.createInspectorEnvironment(
                        "test_inspector",
                        primaryExecutor,
                        t -> {
                            caughtThrowable.set(t);
                            latch.countDown();
                        });

    environment.executors().io().execute(() -> {
      throw exception;
    });

    boolean completed = latch.await(5, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    assertThat(caughtThrowable.get()).isEqualTo(exception);
    primaryExecutor.quitSafely();
  }

  @Test
  public void testCreateAppInspectionConnection_wrapsEventWithId() throws Exception {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    String inspectorId = "test_inspector_id";
    Connection connection = AppInspectionUtils.createAppInspectionConnection(inspectorId, outputStream, t -> {});

    byte[] eventPayload = new byte[]{1, 2, 3};
    connection.sendEvent(eventPayload);

    byte[] writtenBytes = outputStream.toByteArray();
    byte[] responseBytes = FramingProtocol.readMessage(new ByteArrayInputStream(writtenBytes));
        UiInspectorProtocol.AgentMessage agentMessage =
                UiInspectorProtocol.AgentMessage.parseFrom(responseBytes);
        assertThat(agentMessage.hasEvent()).isTrue();
        UiInspectorProtocol.Event event = agentMessage.getEvent();

    assertThat(event.getSpecializedCase())
        .isEqualTo(UiInspectorProtocol.Event.SpecializedCase.INSPECTOR_MESSAGE);
    assertThat(event.getInspectorMessage().getInspectorId()).isEqualTo(inspectorId);
    assertThat(event.getInspectorMessage().getPayload().toByteArray()).isEqualTo(eventPayload);
  }

  @Test
  public void testDelegatingConnection_delegates() {
    AtomicReference<byte[]> capturedEvent = new AtomicReference<>();
    Connection realConnection = new Connection() {
      @Override
      public void sendEvent(byte[] data) {
        capturedEvent.set(data);
      }
    };

    AppInspectionUtils.DelegatingConnection delegatingConnection = new AppInspectionUtils.DelegatingConnection();
    delegatingConnection.activeConnection = realConnection;

    byte[] eventPayload = new byte[]{4, 5};
    delegatingConnection.sendEvent(eventPayload);

    assertThat(capturedEvent.get()).isEqualTo(eventPayload);
  }

  @Test
  public void testLoadInspectorDynamically_throwsOnInvalidPath() {
    UncheckedIOException exception = assertThrows(
        UncheckedIOException.class,
        () -> AppInspectionUtils.loadInspectorDynamically("test_id", "/invalid/path.dex", mockConnection, unusedEnvironment));

    assertThat(exception).hasMessageThat().contains("Failed to prepare native libraries of /invalid/path.dex");
  }

  @Test
  public void testLoadInspectorDynamically_retriesDexWhoseNativeLibrariesFailed() throws Exception {
    File dex = new File(temporaryFolder.getRoot(), "retried_inspector.jar");

    assertThrows(
        UncheckedIOException.class,
        () -> AppInspectionUtils.loadInspectorDynamically("test_id", dex.getPath(), mockConnection, unusedEnvironment));

    writeJar(dex, "META-INF/");
    // The second call gets past class loader creation and fails only because the jar has no inspector.
    Exception exception = assertThrows(
        Exception.class,
        () -> AppInspectionUtils.loadInspectorDynamically("test_id", dex.getPath(), mockConnection, unusedEnvironment));
    assertThat(exception).hasMessageThat().contains("Failed to find InspectorFactory with id test_id");
  }

  @Test
  public void testLoadInspectorDynamically_extractsNativeLibrariesOncePerDex() throws Exception {
    // Extraction happens under java.io.tmpdir in a directory named after the jar, so the jar name must be unique to this test run.
    String jarName = temporaryFolder.getRoot().getName() + "_native_inspector.jar";
    File dex = new File(temporaryFolder.getRoot(), jarName);
    String abiDirectory = "lib/" + Build.SUPPORTED_ABIS[0] + "/";
    writeJar(dex, "lib/", abiDirectory, abiDirectory + "libinspector.so");
    File extractedLibrary = new File(System.getProperty("java.io.tmpdir"), jarName + "_unpacked_lib/libinspector.so");

    // Both calls get past class loader creation and fail only because the jar has no inspector.
    Exception firstException = assertThrows(
        Exception.class,
        () -> AppInspectionUtils.loadInspectorDynamically("test_id", dex.getPath(), mockConnection, unusedEnvironment));
    assertThat(firstException).hasMessageThat().contains("Failed to find InspectorFactory with id test_id");
    assertThat(extractedLibrary.exists()).isTrue();
    assertThat(extractedLibrary.delete()).isTrue();

    Exception secondException = assertThrows(
        Exception.class,
        () -> AppInspectionUtils.loadInspectorDynamically("test_id", dex.getPath(), mockConnection, unusedEnvironment));
    assertThat(secondException).hasMessageThat().contains("Failed to find InspectorFactory with id test_id");
    assertThat(extractedLibrary.exists()).isFalse();
  }

    @Test
    public void testCreateInspectorEnvironment_artToolingReachesTheEngine() {
        HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
        InspectorEnvironment environment =
                AppInspectionUtils.createInspectorEnvironment(
                        "test_inspector", primaryExecutor, t -> {});
        ArtTooling artTooling = environment.artTooling();
        assertThat(artTooling).isNotNull();

        // The adapter forwards every call to ART Tooling. No native engine is attached in this
        // test, so ART Tooling rejects each call.
        assertThrows(IllegalStateException.class, () -> artTooling.findInstances(String.class));
        assertThrows(
                IllegalStateException.class,
                () ->
                        artTooling.registerEntryHook(
                                Object.class,
                                "toString()Ljava/lang/String;",
                                (self, params) -> {}));
        assertThrows(
                IllegalStateException.class,
                () ->
                        artTooling.registerExitHook(
                                Object.class,
                                "toString()Ljava/lang/String;",
                                returnValue -> returnValue));

        primaryExecutor.quitSafely();
    }

  /** Writes a jar with the given entries. Names ending in a slash are directories; other names are empty files. */
  private static void writeJar(File jar, String... entryNames) throws Exception {
    try (JarOutputStream output = new JarOutputStream(new FileOutputStream(jar))) {
      for (String entryName : entryNames) {
        output.putNextEntry(new JarEntry(entryName));
        output.closeEntry();
      }
    }
  }
}
