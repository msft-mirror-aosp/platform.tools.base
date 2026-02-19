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
package com.android.repository.testframework

import com.android.repository.api.Downloader
import com.android.repository.api.LocalPackage
import com.android.repository.api.PackageOperation
import com.android.repository.api.ProgressIndicator
import com.android.repository.api.ProgressRunner
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoPackage
import com.android.repository.api.RepositorySource
import com.android.repository.api.RepositorySourceProvider
import com.android.repository.api.SchemaModule
import com.android.repository.api.SettingsController
import com.android.repository.impl.meta.RepositoryPackages
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import org.w3c.dom.ls.LSResourceResolver

/** A fake [RepoManager], for use in unit tests. */
class FakeRepoManager(
  override val localPath: Path?,
  override val packages: RepositoryPackages,
  additionalSchemaModules: List<SchemaModule<*>>,
) : RepoManager() {
  constructor(localPath: Path?, packages: RepositoryPackages) : this(localPath, packages, emptyList())

  constructor(packages: RepositoryPackages) : this(null, packages, emptyList())

  override val schemaModules = setOf(commonModule, genericModule) + additionalSchemaModules

  override val sourceProviders: List<RepositorySourceProvider>
    get() = emptyList()

  override fun getSources(downloader: Downloader?, progress: ProgressIndicator, forceRefresh: Boolean): List<RepositorySource> = emptyList()

  override fun load(
    cacheExpirationMs: Long,
    onLocalComplete: RepoLoadedListener?,
    onSuccess: RepoLoadedListener?,
    onError: Runnable?,
    runner: ProgressRunner,
    downloader: Downloader?,
    settings: SettingsController?,
  ) {
    onLocalComplete?.loaded(this.packages)
    onSuccess?.loaded(this.packages)
  }

  override fun loadSynchronously(
    cacheExpirationMs: Long,
    onLocalComplete: RepoLoadedListener?,
    onSuccess: RepoLoadedListener?,
    onError: Runnable?,
    runner: ProgressRunner,
    downloader: Downloader?,
    settings: SettingsController?,
  ) {
    onLocalComplete?.loaded(this.packages)
    onSuccess?.loaded(this.packages)
  }

  override suspend fun loadLocalPackages(indicator: ProgressIndicator, cacheExpiration: Duration): List<LocalPackage> = emptyList()

  override suspend fun loadRemotePackages(
    indicator: ProgressIndicator,
    downloader: Downloader,
    settings: SettingsController?,
    cacheExpiration: Duration,
  ): List<RemotePackage> = emptyList()

  override fun markInvalid() {}

  override fun markLocalCacheInvalid() {}

  override fun reloadLocalIfNeeded(progress: ProgressIndicator) {}

  override fun getResourceResolver(progress: ProgressIndicator): LSResourceResolver? = null

  private val localListeners = CopyOnWriteArrayList<RepoLoadedListener>()
  private val remoteListeners = CopyOnWriteArrayList<RepoLoadedListener>()

  override fun addLocalChangeListener(listener: RepoLoadedListener) {
    localListeners.add(listener)
  }

  override fun removeLocalChangeListener(listener: RepoLoadedListener) {
    localListeners.remove(listener)
  }

  fun updateLocalPackages(localPackages: Collection<LocalPackage>) {
    packages.setLocalPkgInfos(localPackages)
    localListeners.forEach { it.loaded(packages) }
  }

  override fun addRemoteChangeListener(listener: RepoLoadedListener) {
    remoteListeners.add(listener)
  }

  override fun removeRemoteChangeListener(listener: RepoLoadedListener) {
    remoteListeners.remove(listener)
  }

  fun updateRemotePackages(localPackages: Collection<RemotePackage>) {
    packages.setRemotePkgInfos(localPackages)
    remoteListeners.forEach { it.loaded(packages) }
  }

  override fun installBeginning(repoPackage: RepoPackage, installer: PackageOperation) {}

  override fun installEnded(repoPackage: RepoPackage) {}

  override fun getInProgressInstallOperation(remotePackage: RepoPackage): PackageOperation? = null
}
