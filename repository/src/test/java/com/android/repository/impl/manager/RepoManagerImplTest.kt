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
import com.android.repository.api.ProgressIndicator
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoManager.RepoLoadedListener
import com.android.repository.api.RepoPackage
import com.android.repository.api.RepositorySourceProvider
import com.android.repository.api.SimpleRepositorySource
import com.android.repository.impl.manager.RepoManagerImpl.LocalRepoLoaderFactory
import com.android.repository.impl.manager.RepoManagerImpl.RemoteRepoLoaderFactory
import com.android.repository.impl.meta.RepositoryPackages
import com.android.repository.testframework.FakeDownloader
import com.android.repository.testframework.FakeLoader
import com.android.repository.testframework.FakePackage.FakeLocalPackage
import com.android.repository.testframework.FakePackage.FakeRemotePackage
import com.android.repository.testframework.FakeProgressRunner
import com.android.repository.testframework.FakeRepositorySourceProvider
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

/** Tests for [RepoManagerImpl]. */
class RepoManagerImplTest {
  // test load with local and remote, fake loaders, callbacks called in order
  @Test
  fun testLoadOperationsInOrder() {
    val counter = AtomicInteger(0)
    val localFactory = TestLoaderFactory(OrderTestLoader(1, counter, false))
    val localCallback = RepoLoadedListener { _ -> assertEquals(2, counter.addAndGet(1)) }
    val remoteFactory = TestLoaderFactory(OrderTestLoader(3, counter, false))
    val remoteCallback = RepoLoadedListener { _ -> assertEquals(4, counter.addAndGet(1)) }
    val errorCallback = Runnable { fail() }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localFactory, remoteFactory)
    mgr.registerSourceProvider(FakeRepositorySourceProvider(emptyList()))
    val runner = FakeProgressRunner()
    mgr.loadSynchronously(
      cacheExpirationMs = 0,
      onLocalComplete = listOf(localCallback),
      onSuccess = listOf(remoteCallback),
      onError = listOf(errorCallback),
      runner = runner,
      downloader = FakeDownloader(repoRoot.getRoot().resolve("tmp")),
    )

    assertEquals(4, counter.get())
  }

  // test error causes error callbacks to be called
  @Test
  fun testErrorCallbacks1() {
    val counter = AtomicInteger(0)
    val localFactory = TestLoaderFactory(OrderTestLoader(1, counter, false))
    val localCallback = RepoLoadedListener { _ -> assertEquals(2, counter.addAndGet(1)) }
    val remoteFactory = TestLoaderFactory(OrderTestLoader(3, counter, true))
    val remoteCallback = RepoLoadedListener { _ -> fail() }
    val errorCallback = Runnable { assertEquals(4, counter.addAndGet(1)) }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localFactory, remoteFactory)
    mgr.registerSourceProvider(FakeRepositorySourceProvider(emptyList()))
    val runner = FakeProgressRunner()
    try {
      mgr.loadSynchronously(
        cacheExpirationMs = 0,
        onLocalComplete = listOf(localCallback),
        onSuccess = listOf(remoteCallback),
        onError = listOf(errorCallback),
        runner = runner,
        downloader = FakeDownloader(repoRoot.getRoot().resolve("tmp")),
      )
    } catch (e: Exception) {
      // expected
    }
    assertEquals(4, counter.get())
  }

  // test error causes error callbacks to be called
  @Test
  fun testErrorCallbacks2() {
    val counter = AtomicInteger(0)
    val localFactory = TestLoaderFactory(OrderTestLoader(1, counter, true))
    val localCallback = RepoLoadedListener { _ -> fail() }
    val remoteFactory = TestLoaderFactory(OrderTestLoader(3, counter, false))
    val remoteCallback = RepoLoadedListener { _ -> fail() }
    val errorCallback = Runnable { assertEquals(2, counter.addAndGet(1)) }

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localFactory, remoteFactory)
    mgr.registerSourceProvider(FakeRepositorySourceProvider(emptyList()))
    val runner = FakeProgressRunner()
    try {
      mgr.loadSynchronously(
        cacheExpirationMs = 0,
        onLocalComplete = listOf(localCallback),
        onSuccess = listOf(remoteCallback),
        onError = listOf(errorCallback),
        runner = runner,
        downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
      )
    } catch (_: Exception) {
      // expected
    }
    assertEquals(2, counter.get())
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

    val localFactory =
      TestLoaderFactory(
        object : FakeLoader<LocalPackage>() {
          override fun run(): Map<String, LocalPackage> {
            assertTrue(localStarted.compareAndSet(false, true))
            try {
              runLocal.acquire()
            } catch (_: InterruptedException) {
              fail()
            }
            return emptyMap<String, LocalPackage>()
          }
        }
      )
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
    val mgr = RepoManagerImpl(repoRoot, localFactory, TestLoaderFactory<RepoPackage>())
    mgr.registerSourceProvider(FakeRepositorySourceProvider(emptyList()))
    val runner = FakeProgressRunner()
    mgr.load(
      cacheExpirationMs = 0,
      onLocalComplete = listOf(localCallback1),
      onSuccess = listOf(remoteCallback1),
      onError = listOf(errorCallback),
      runner = runner,
      downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
    )
    mgr.load(
      cacheExpirationMs = 0,
      onLocalComplete = listOf(localCallback2),
      onSuccess = listOf(remoteCallback2),
      onError = listOf(errorCallback),
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

  // test timeout makes/doesn't make load happen
  @Test
  fun testTimeout() {
    val localDidRun = AtomicBoolean(false)
    val remoteDidRun = AtomicBoolean(false)

    val localRunningFactory =
      TestLoaderFactory(
        object : RunningLoader<LocalPackage>(localDidRun) {
          override fun needsUpdate(lastLocalRefreshMs: Long, deepCheck: Boolean): Boolean {
            return false
          }
        }
      )
    val remoteRunningFactory = TestLoaderFactory(RunningLoader<RemotePackage>(remoteDidRun))

    val repoRoot = createInMemoryFileSystemAndFolder("repo")
    val mgr = RepoManagerImpl(repoRoot, localRunningFactory, remoteRunningFactory)
    mgr.registerSourceProvider(FakeRepositorySourceProvider(emptyList()))
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
    mgr.loadSynchronously(
      cacheExpirationMs = -1,
      runner = runner,
      downloader = FakeDownloader(repoRoot.root.resolve("tmp")),
    )
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
    val mgr = RepoManagerImpl(repoRoot, TestLoaderFactory(loader), null)
    val runner = FakeProgressRunner()

    // First time we should load, despite not being out of date
    mgr.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = runner,
    )
    assertTrue(didRun.compareAndSet(true, false))

    // With default timeout, we shouldn't run again
    mgr.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = runner,
    )
    assertFalse(didRun.get())

    // Now with shallow check, we should run
    shallowResult.set(true)
    mgr.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = runner,
    )
    assertTrue(didRun.compareAndSet(true, false))

    // With deep check only we shouldn't run
    shallowResult.set(false)
    deepResult.set(true)
    mgr.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = runner,
    )
    assertFalse(didRun.get())

    // now we do the deep check and should run.
    mgr.reloadLocalIfNeeded(runner.progressIndicator)
    assertTrue(didRun.compareAndSet(true, false))

    // check again that we won't reload because of caching
    shallowResult.set(false)
    deepResult.set(false)
    mgr.loadSynchronously(
      cacheExpirationMs = RepoManager.DEFAULT_EXPIRATION_PERIOD_MS,
      runner = runner,
    )
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

    val localFactory = TestLoaderFactory<LocalPackage>(localLoader)
    val remoteFactory = TestLoaderFactory<RemotePackage>(remoteLoader)
    val mgr = RepoManagerImpl(repoRoot, localFactory, remoteFactory)

    val runner = FakeProgressRunner()
    val downloader = FakeDownloader(repoRoot.getRoot().resolve("tmp"))

    val provider =
      FakeRepositorySourceProvider(
        listOf(
          SimpleRepositorySource(
            "foo",
            "source",
            true,
            emptyList(),
            mock<RepositorySourceProvider>(),
          )
        )
      )
    mgr.registerSourceProvider(provider)
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

  private open class RunningLoader<T : RepoPackage>(private val didRun: AtomicBoolean) :
    FakeLoader<T>() {
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

  private class TestLoaderFactory<T : RepoPackage>(
    private val loader: FakeLoader<T> = FakeLoader<T>()
  ) : RemoteRepoLoaderFactory, LocalRepoLoaderFactory {

    override fun createRemoteRepoLoader(progress: ProgressIndicator): RemoteRepoLoader {
      return loader
    }

    override fun createLocalRepoLoader(): LocalRepoLoader {
      return loader
    }
  }

  private class OrderTestLoader<T : RepoPackage>(
    private val target: Int,
    private val counter: AtomicInteger,
    private val fail: Boolean,
  ) : FakeLoader<T>() {
    override fun run(): Map<String, T> {
      assertEquals(target, counter.addAndGet(1))
      if (fail) {
        throw RuntimeException("expected")
      }
      return HashMap<String, T>()
    }
  }
}
