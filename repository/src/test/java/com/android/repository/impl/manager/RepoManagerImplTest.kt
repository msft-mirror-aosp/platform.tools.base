/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.repository.impl.manager

import com.android.repository.Revision
import com.android.repository.api.LocalPackage
import com.android.repository.api.ProgressRunner
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoManager.RepoLoadedListener
import com.android.repository.api.RepoPackage
import com.android.repository.impl.meta.RepositoryPackages
import com.android.repository.testframework.FakeDownloader
import com.android.repository.testframework.FakeLoader
import com.android.repository.testframework.FakePackage.FakeLocalPackage
import com.android.repository.testframework.FakePackage.FakeRemotePackage
import com.android.repository.testframework.FakeProgressIndicator
import com.android.repository.testframework.FakeProgressRunner
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Tests for [RepoManagerImpl]. */
class RepoManagerImplTest {
  // test load with local and remote, fake loaders, callbacks called in order
  @Test
  fun testLoadOperationsInOrder() {
    val counter = AtomicInteger(0)
    val localLoader = OrderTestLoader<LocalPackage>(1, counter, false)
    val localCallback = RepoLoadedListener { _ -> assertEquals(2, counter.addAndGet(1)) }
    val remoteLoader = OrderTestLoader<RemotePackage>(3, counter, false)
    val remoteCallback = RepoLoadedListener { _ -> assertEquals(4, counter.addAndGet(1)) }
    val errorCallback = Runnable { fail() }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localLoader, remoteLoader)
    val runner = FakeProgressRunner()
    mgr.loadSynchronously(
      cacheExpirationMs = 0,
      onLocalComplete = localCallback,
      onSuccess = remoteCallback,
      onError = errorCallback,
      runner = runner,
      downloader = FakeDownloader(repoRoot.getRoot().resolve("tmp")),
    )

    assertEquals(4, counter.get())
  }

  /** If a synchronous load fails during remote loading, it should invoke its localCallback, its errorCallback, and throw the exception. */
  @Test
  fun testErrorCallbacks1() {
    val counter = AtomicInteger(0)
    val localLoader = OrderTestLoader<LocalPackage>(1, counter, false)
    val localCallback = RepoLoadedListener { _ -> assertEquals(2, counter.addAndGet(1)) }
    val remoteLoader = OrderTestLoader<RemotePackage>(3, counter, true)
    val remoteCallback = RepoLoadedListener { _ -> fail() }
    val errorCallback = Runnable { assertEquals(4, counter.addAndGet(1)) }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localLoader, remoteLoader)
    val runner = FakeProgressRunner()
    assertThrows(TestLoaderException::class.java) {
      mgr.loadSynchronously(
        cacheExpirationMs = 0,
        onLocalComplete = localCallback,
        onSuccess = remoteCallback,
        onError = errorCallback,
        runner = runner,
        downloader = FakeDownloader(repoRoot.getRoot().resolve("tmp")),
      )
    }
    assertThat(counter.get()).isEqualTo(4)
  }

  /** If a synchronous load fails during local loading, it should both call its errorCallback and throw the exception. */
  @Test
  fun testErrorCallbacks2() {
    val counter = AtomicInteger(0)
    val localLoader = OrderTestLoader<LocalPackage>(1, counter, true)
    val localCallback = RepoLoadedListener { _ -> fail() }
    val remoteLoader = OrderTestLoader<RemotePackage>(3, counter, false)
    val remoteCallback = RepoLoadedListener { _ -> fail() }
    val errorCallback = Runnable { assertEquals(2, counter.addAndGet(1)) }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localLoader, remoteLoader)
    val runner = FakeProgressRunner()
    assertThrows(TestLoaderException::class.java) {
      mgr.loadSynchronously(
        cacheExpirationMs = 0,
        onLocalComplete = localCallback,
        onSuccess = remoteCallback,
        onError = errorCallback,
        runner = runner,
        downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
      )
    }
    assertEquals(2, counter.get())
  }

  /** If the primary task fails, another task that is sharing its result should also fail and throw an exception. */
  @Test
  fun testExceptionPropagationToPiggybackTasks() {
    val secondCallStarted = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val counter = AtomicInteger(0)
    val localLoader = WaitingTestLoader<LocalPackage>(secondCallStarted, fail = true)
    val localCallback = RepoLoadedListener { _ -> fail() }
    val remoteLoader = FakeLoader<RemotePackage>()
    val errorCallback = Runnable {
      assertEquals(1, counter.addAndGet(1))
      finished.countDown()
    }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localLoader, remoteLoader)
    val runner = FakeProgressRunner()
    mgr.load(cacheExpirationMs = 0, onLocalComplete = localCallback, onError = errorCallback, runner = runner, downloader = null)
    assertThrows(TestLoaderException::class.java) {
      // We want to test the variant of loadSynchronously that uses DirectProgressRunner, but we
      // need a way to control the scheduling, so we use a custom ProgressRunner.
      mgr.loadSynchronously(
        cacheExpirationMs = 0,
        runner =
          object : ProgressRunner {
            override fun runAsyncWithProgress(r: ProgressRunner.ProgressRunnable) = throw UnsupportedOperationException()

            override fun runSyncWithProgress(r: ProgressRunner.ProgressRunnable) {
              runBlocking {
                secondCallStarted.countDown()
                r.run(FakeProgressIndicator())
              }
            }
          },
        downloader = null,
        settings = null,
      )
    }

    finished.await()
    assertEquals(1, counter.get())
  }

  /**
   * If one caller starts a load, and another concurrently starts the same load, then if the first caller is cancelled, the second caller
   * should not be cancelled, and it should still receive the result of the load after making its own request.
   */
  @Test
  fun testCancellationPropagationToPiggybackTasks() =
    runBlocking(Dispatchers.Default) {
      val loaderInvocationCount = AtomicInteger(0)
      val firstInvocationStarted = CountDownLatch(1)
      val firstInvocationCancelled = AtomicBoolean(false)
      val canPerformLoad = Semaphore(0)

      val fakePackage = FakeLocalPackage("foo")
      val fakeLoader =
        object : FakeLoader<LocalPackage>() {
          override fun run(): Map<String, LocalPackage> {
            val taskIndex = loaderInvocationCount.incrementAndGet()
            if (taskIndex == 1) {
              firstInvocationStarted.countDown()
            } else {
              // We shouldn't get here until the first invocation is cancelled
              assertThat(firstInvocationCancelled.get()).isTrue()
            }
            // Wait for permission to proceed.
            assertTrue("Loader timed out waiting to proceed", canPerformLoad.tryAcquire(5, TimeUnit.SECONDS))
            if (taskIndex == 1) {
              firstInvocationCancelled.set(true)
              throw CancellationException()
            }
            return mapOf("foo" to fakePackage)
          }
        }

      val repoRoot = createInMemoryFileSystemAndFolder("repo")
      val mgr = RepoManagerImpl(repoRoot, fakeLoader, FakeLoader<RemotePackage>())
      val progress = FakeProgressIndicator()

      launch {
        mgr.loadLocalPackages(progress, 1.seconds)
        fail("First caller should have been cancelled")
      }

      // Wait until the loader has been invoked by the first caller
      assertTrue("Loader was not started by the first caller", firstInvocationStarted.await(5, TimeUnit.SECONDS))

      // Second caller, which will piggyback on the first task
      val secondCallerJob = async { mgr.loadLocalPackages(progress, 2.seconds) }
      val thirdCallerJob = async { mgr.loadLocalPackages(progress, 3.seconds) }

      // Make sure that we actually created piggyback tasks and not separate loads
      assertThat(loaderInvocationCount.get()).isEqualTo(1)

      // Unblock the first loader invocation. Its task is cancelled, so its result will be
      // ignored.
      canPerformLoad.release()

      // Wait a bit so that both second and third caller can fallback; one should create a new
      // task
      // and the other should piggyback. (We have no way to observe piggyback task creation, so we
      // have to delay a bit to avoid the second task completing its load before the third task
      // falls back.)
      Thread.sleep(1000)

      // The fallback from the second caller should invoke the loader again.
      // We need to unblock this second invocation as well.
      canPerformLoad.release()

      // The second caller should complete successfully with the results from the fallback load.
      secondCallerJob.await().let {
        assertThat(it).hasSize(1)
        assertThat(it.first().path).isEqualTo("foo")
      }
      thirdCallerJob.await().let {
        assertThat(it).hasSize(1)
        assertThat(it.first().path).isEqualTo("foo")
      }
      assertThat(mgr.packages.localPackages).containsExactly("foo", fakePackage)

      // The loader should have been invoked twice: once for the original (cancelled) task,
      // and once for the fallback task. The third job should piggyback on the second rather than
      // making its own request.
      assertThat(loaderInvocationCount.get()).isEqualTo(2)
    }

  // test multiple loads at same time only kick off one load, and callbacks are invoked
  @Test
  fun testMultiLoad() {
    val localStarted = AtomicBoolean(false)
    val localCallback1Run = AtomicBoolean(false)
    val localCallback2Run = AtomicBoolean(false)
    val remoteCallback1Run = AtomicBoolean(false)
    val remoteCallback2Run = AtomicBoolean(false)
    val runLocal = Semaphore(1)
    runLocal.acquire()
    val completeDone = Semaphore(2)
    completeDone.acquire(2)

    val localLoader =
      object : FakeLoader<LocalPackage>() {
        override fun run(): Map<String, LocalPackage> {
          assertTrue(localStarted.compareAndSet(false, true))
          try {
            runLocal.acquire()
          } catch (_: InterruptedException) {
            fail()
          }
          return emptyMap()
        }
      }

    val localCallback1 = RunningCallback(localCallback1Run)
    val localCallback2 = RunningCallback(localCallback2Run)
    val remoteCallback1 =
      object : RunningCallback(remoteCallback1Run) {
        override fun loaded(packages: RepositoryPackages) {
          super.loaded(packages)
          completeDone.release()
        }
      }
    val remoteCallback2 =
      object : RunningCallback(remoteCallback2Run) {
        override fun loaded(packages: RepositoryPackages) {
          super.loaded(packages)
          completeDone.release()
        }
      }

    val errorCallback = Runnable { fail() }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localLoader, FakeLoader<RepoPackage>())
    val runner = FakeProgressRunner()
    mgr.load(
      cacheExpirationMs = 0,
      onLocalComplete = localCallback1,
      onSuccess = remoteCallback1,
      onError = errorCallback,
      runner = runner,
      downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
    )
    mgr.load(
      cacheExpirationMs = 0,
      onLocalComplete = localCallback2,
      onSuccess = remoteCallback2,
      onError = errorCallback,
      runner = runner,
      downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
    )
    runLocal.release()

    if (!completeDone.tryAcquire(2, 10, TimeUnit.SECONDS)) {
      fail()
    }
    assertTrue(localCallback1Run.get())
    assertTrue(localCallback2Run.get())
    assertTrue(remoteCallback1Run.get())
    assertTrue(remoteCallback2Run.get())
  }

  @Test
  fun testLocalLoadNotBlockedOnRemote() {
    val remoteLatch = CountDownLatch(1)
    val remoteCompleted = CountDownLatch(1)
    val remoteLoader =
      object : FakeLoader<RemotePackage>() {
        override fun run(): Map<String, RemotePackage> {
          remoteLatch.await()
          return emptyMap()
        }
      }
    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, FakeLoader<LocalPackage>(), remoteLoader)
    val runner = FakeProgressRunner()
    val localDidRun = AtomicBoolean(false)
    val remoteDidRun = AtomicBoolean(false)

    mgr.load(
      cacheExpirationMs = 0,
      onSuccess =
        object : RunningCallback(remoteDidRun) {
          override fun loaded(packages: RepositoryPackages) {
            super.loaded(packages)
            remoteCompleted.countDown()
          }
        },
      runner = runner,
      downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
    )

    // This should complete without waiting for the remote load to complete.
    mgr.loadSynchronously(cacheExpirationMs = 0, onLocalComplete = RunningCallback(localDidRun), runner = runner, downloader = null)

    assertTrue(localDidRun.get())
    assertFalse(remoteDidRun.get())

    remoteLatch.countDown()
    remoteCompleted.await()

    assertTrue(remoteDidRun.get())
  }

  @Test
  fun testLoadLocalPackages() {
    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val pkg1 = FakeLocalPackage("package;path1", repoRoot.resolve("pkg1"))
    val pkg2 = FakeLocalPackage("package;path2", repoRoot.resolve("pkg2"))
    val localLoader = FakeLoader<LocalPackage>(mapOf<String, LocalPackage>(pkg1.path to pkg1, pkg2.path to pkg2))

    // For this test, we don't need a functional remote loader
    val remoteLoader = FakeLoader<RemotePackage>()

    val repoManager = RepoManagerImpl(repoRoot, localLoader, remoteLoader)

    val loadedPackages = runBlocking { repoManager.loadLocalPackages(indicator = FakeProgressIndicator(), cacheExpiration = Duration.ZERO) }
    assertThat(loadedPackages).containsExactly(pkg1, pkg2)

    // Verify that the manager's internal state is also updated
    val managerInternalPackages = repoManager.packages.localPackages
    assertThat(managerInternalPackages).hasSize(2)
    assertThat(managerInternalPackages).containsEntry(pkg1.path, pkg1)
    assertThat(managerInternalPackages).containsEntry(pkg2.path, pkg2)
  }

  @Test
  fun testLoadRemotePackages() {
    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val pkg1 = FakeRemotePackage("package;path1")
    val pkg2 = FakeRemotePackage("package;path2")
    val remoteLoader = FakeLoader(mapOf<String, RemotePackage>(pkg1.path to pkg1, pkg2.path to pkg2))

    // For this test, we don't need a functional local loader

    val repoManager = RepoManagerImpl(repoRoot, FakeLoader<LocalPackage>(), remoteLoader)

    val loadedPackages = runBlocking {
      repoManager.loadRemotePackages(
        indicator = FakeProgressIndicator(),
        cacheExpiration = Duration.ZERO,
        downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
        settings = null,
      )
    }
    assertThat(loadedPackages).containsExactly(pkg1, pkg2)

    // Verify that the manager's internal state is also updated
    val managerInternalPackages = repoManager.packages.remotePackages
    assertThat(managerInternalPackages).hasSize(2)
    assertThat(managerInternalPackages).containsEntry(pkg1.path, pkg1)
    assertThat(managerInternalPackages).containsEntry(pkg2.path, pkg2)
  }

  // test timeout makes/doesn't make load happen
  @Test
  fun testTimeout() {
    val localDidRun = AtomicBoolean(false)
    val remoteDidRun = AtomicBoolean(false)

    val localRunningLoader =
      object : RunningLoader<LocalPackage>(localDidRun) {
        override fun needsUpdate(lastLocalRefreshMs: Long, deepCheck: Boolean): Boolean {
          return false
        }
      }
    val remoteRunningLoader = RunningLoader<RemotePackage>(remoteDidRun)

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localRunningLoader, remoteRunningLoader)
    val runner = FakeProgressRunner()
    mgr.loadSynchronously(0, null, null, null, runner, null, null)
    assertTrue(localDidRun.compareAndSet(true, false))
    assertFalse(remoteDidRun.get())

    // we shouldn't run because of timeout
    mgr.loadSynchronously(RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, runner = runner)

    assertFalse(localDidRun.get())
    assertFalse(remoteDidRun.get())

    // remote should run since we've specified a downloader
    mgr.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = runner,
      downloader = FakeDownloader(repoRoot.getRoot().resolve("tmp")),
    )
    assertFalse(localDidRun.compareAndSet(true, false))
    assertTrue(remoteDidRun.compareAndSet(true, false))

    // now neither should run because of caching
    mgr.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = runner,
      downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
    )
    assertFalse(localDidRun.get())
    assertFalse(remoteDidRun.get())

    // now we will timeout, so they should run again
    mgr.loadSynchronously(cacheExpirationMs = -1, runner = runner, downloader = FakeDownloader(repoRoot.root.resolve("tmp")))
    assertTrue(localDidRun.compareAndSet(true, false))
    assertTrue(remoteDidRun.compareAndSet(true, false))
  }

  // test that we do the local repo needsUpdate check correctly
  @Test
  fun testCheckForNewPackages() {
    val didRun = AtomicBoolean(false)
    val shallowResult = AtomicBoolean(false)
    val deepResult = AtomicBoolean(false)
    val loader =
      object : RunningLoader<LocalPackage>(didRun) {
        override fun needsUpdate(lastLocalRefreshMs: Long, deepCheck: Boolean): Boolean {
          return shallowResult.get() || (deepCheck && deepResult.get())
        }
      }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, loader, FakeLoader<RemotePackage>())
    val runner = FakeProgressRunner()

    // First time we should load, despite not being out of date
    mgr.loadSynchronously(cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, runner = runner)
    assertTrue(didRun.compareAndSet(true, false))

    // With default timeout, we shouldn't run again
    mgr.loadSynchronously(cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, runner = runner)
    assertFalse(didRun.get())

    // Now with shallow check, we should run
    shallowResult.set(true)
    mgr.loadSynchronously(cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, runner = runner)
    assertTrue(didRun.compareAndSet(true, false))

    // With deep check only we shouldn't run
    shallowResult.set(false)
    deepResult.set(true)
    mgr.loadSynchronously(cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, runner = runner)
    assertFalse(didRun.get())

    // now we do the deep check and should run.
    mgr.reloadLocalIfNeeded(runner.progressIndicator)
    assertTrue(didRun.compareAndSet(true, false))

    // check again that we won't reload because of caching
    shallowResult.set(false)
    deepResult.set(false)
    mgr.loadSynchronously(cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS, runner = runner)
    assertFalse(didRun.get())
  }

  // test local/remote change listeners
  @Test
  fun testChangeListeners() {
    val localPackages: MutableMap<String, LocalPackage> = HashMap<String, LocalPackage>()
    val localLoader = FakeLoader<LocalPackage>(localPackages)
    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    localPackages.put("foo", FakeLocalPackage("foo", repoRoot.resolve("foo")))

    val remotePackages = mutableMapOf<String, RemotePackage>()
    val remoteLoader = FakeLoader<RemotePackage>(remotePackages)
    val remote = FakeRemotePackage("foo")
    remote.setRevision(Revision(2))
    remotePackages.put("foo", remote)

    val mgr = RepoManagerImpl(repoRoot, localLoader, remoteLoader)

    val runner = FakeProgressRunner()
    val downloader = FakeDownloader(repoRoot.getRoot().resolve("tmp"))

    // Initial load to set current state
    mgr.loadSynchronously(-1, null, null, null, runner, downloader, null)
    val localRan = AtomicBoolean(false)
    val remoteRan = AtomicBoolean(false)
    mgr.addLocalChangeListener(RunningCallback(localRan))
    mgr.addRemoteChangeListener(RunningCallback(remoteRan))

    // load again with no changes
    mgr.loadSynchronously(-1, null, null, null, runner, downloader, null)
    assertFalse(localRan.get())
    assertFalse(remoteRan.get())

    // update local and ensure the local listener fired
    localPackages.put("bar", FakeLocalPackage("bar", repoRoot.resolve("bar")))
    mgr.loadSynchronously(-1, null, null, null, runner, downloader, null)
    assertTrue(localRan.compareAndSet(true, false))
    assertFalse(remoteRan.get())

    // update remote and ensure the remote listener fired
    remotePackages.put("baz", FakeRemotePackage("baz"))
    mgr.loadSynchronously(-1, null, null, null, runner, downloader, null)
    assertFalse(localRan.get())
    assertTrue(remoteRan.compareAndSet(true, false))
  }

  private open class RunningLoader<T : RepoPackage>(private val didRun: AtomicBoolean) : FakeLoader<T>() {
    override fun run(): Map<String, T> {
      assertTrue(didRun.compareAndSet(false, true))
      return super.run()
    }
  }

  private open class RunningCallback(private val didRun: AtomicBoolean) : RepoLoadedListener {
    override fun loaded(packages: RepositoryPackages) {
      assertTrue(didRun.compareAndSet(false, true))
    }
  }

  private class OrderTestLoader<T : RepoPackage>(private val target: Int, private val counter: AtomicInteger, private val fail: Boolean) :
    FakeLoader<T>() {
    override fun run(): Map<String, T> {
      assertEquals(target, counter.addAndGet(1))
      if (fail) {
        throw TestLoaderException()
      }
      return HashMap<String, T>()
    }
  }

  private class WaitingTestLoader<T : RepoPackage>(private val latch: CountDownLatch, private val fail: Boolean) : FakeLoader<T>() {
    override fun run(): Map<String, T> {
      latch.await()
      if (fail) {
        throw TestLoaderException()
      }
      return HashMap<String, T>()
    }
  }

  private class TestLoaderException : Exception("Simulated exception in loader")
}
