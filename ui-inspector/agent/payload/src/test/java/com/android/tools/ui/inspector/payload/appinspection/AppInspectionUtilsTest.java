/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.android.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.ui.inspector.payload.appinspection;

import static com.google.common.truth.Truth.assertThat;

import androidx.inspection.Connection;
import androidx.inspection.Inspector;
import androidx.inspection.InspectorEnvironment;
import androidx.inspection.InspectorExecutors;
import androidx.inspection.ArtTooling;
import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class AppInspectionUtilsTest {

  private final Connection mockConnection = new Connection() {
    @Override
    public void sendEvent(byte[] data) {}
  };

  @Test
  public void testCreateInspectorEnvironment_ioExecutorDelegates() throws InterruptedException {
    HandlerThreadExecutor primaryExecutor = new HandlerThreadExecutor("test-thread", t -> {});
    InspectorEnvironment environment = AppInspectionUtils.createInspectorEnvironment(primaryExecutor, t -> {});

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

    InspectorEnvironment environment = AppInspectionUtils.createInspectorEnvironment(primaryExecutor, t -> {
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
    UiInspectorProtocol.Event event = UiInspectorProtocol.Event.parseFrom(responseBytes);

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
    InspectorEnvironment mockEnvironment = new InspectorEnvironment() {
      @Override
      public InspectorExecutors executors() {
        throw new UnsupportedOperationException("Not implemented");
      }

      @Override
      public ArtTooling artTooling() {
        throw new UnsupportedOperationException("Not implemented");
      }
    };

    boolean exceptionThrown = false;
    try {
      AppInspectionUtils.loadInspectorDynamically("test_id", "/invalid/path.dex", mockConnection, mockEnvironment);
    } catch (Exception e) {
      exceptionThrown = true;
      assertThat(e.getMessage()).contains("Failed to find InspectorFactory");
    }
    assertThat(exceptionThrown).isTrue();
  }
}
