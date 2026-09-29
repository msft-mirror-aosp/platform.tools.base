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

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class InspectorLauncherTest {

  @After
  public void tearDown() throws Exception {
    for (Thread thread : InspectorLauncher.serverThreads.values()) {
      thread.interrupt();
      thread.join(5_000);
    }
    InspectorLauncher.serverThreads.clear();
  }

  @Test
  public void testStart_doesNotStartNewServerIfAlreadyRunning() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    AtomicReference<String> receivedToken = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    Consumer<String> starter = serverToken -> {
      callCount.incrementAndGet();
      receivedToken.set(serverToken);
      try {
        latch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    InspectorLauncher.start("1234_0123456789ab", starter);

    Thread.sleep(100);

    InspectorLauncher.start("1234_0123456789ab", starter);

    latch.countDown();

    assertThat(callCount.get()).isEqualTo(1);
    assertThat(receivedToken.get()).isEqualTo("1234_0123456789ab");
  }

  @Test
  public void testStart_startsServerForNewTokenWhileAnotherRuns() throws Exception {
    List<String> startedTokens = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch bothStarted = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    Consumer<String> starter = serverToken -> {
      startedTokens.add(serverToken);
      bothStarted.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    try {
      InspectorLauncher.start("1234_0123456789ab", starter);
      InspectorLauncher.start("1234_ba9876543210", starter);

      assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(startedTokens).containsExactly("1234_0123456789ab", "1234_ba9876543210");
    } finally {
      release.countDown();
    }
  }

  @Test
  public void testStart_restartsServerForTokenAfterItsServerStops() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    Consumer<String> starter = serverToken -> callCount.incrementAndGet();

    InspectorLauncher.start("1234_0123456789ab", starter);
    InspectorLauncher.serverThreads.get("1234_0123456789ab").join(5_000);
    InspectorLauncher.start("1234_0123456789ab", starter);
    InspectorLauncher.serverThreads.get("1234_0123456789ab").join(5_000);

    assertThat(callCount.get()).isEqualTo(2);
  }

  @Test
  public void testOnAttach_forwardsOptionsAsServerTokenToServer() throws Exception {
    AtomicReference<String> startedToken = new AtomicReference<>();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    Consumer<String> starter = serverToken -> {
      startedToken.set(serverToken);
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    try {
      new InspectorLauncher().onAttach("1234_0123456789ab", starter);
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(startedToken.get()).isEqualTo("1234_0123456789ab");
    } finally {
      release.countDown();
    }
  }
}
