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
package com.android.repository.testframework

import com.android.repository.api.PackageOperation
import com.android.repository.api.ProgressIndicator
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoPackage
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A fake [PackageOperation], for use in unit tests.
 *
 * [prepare] and [complete] don't do any actual work; they just advance the status and register/unregister the operation with [repoManager]
 * the way a real operation does. Tests that need finer-grained control can call [setInstallStatus] directly.
 */
class FakePackageOperation
@JvmOverloads
constructor(
  private val repoPackage: RepoPackage,
  private val repoManager: RepoManager,
  private val location: Path? = null,
) : PackageOperation {
  private val listeners = CopyOnWriteArrayList<PackageOperation.StatusChangeListener>()
  private var status = PackageOperation.InstallStatus.NOT_STARTED

  override fun getPackage(): RepoPackage = repoPackage

  override fun getLocation(progress: ProgressIndicator): Path =
    checkNotNull(location) { "No location was given to this FakePackageOperation" }

  override fun prepare(progress: ProgressIndicator): Boolean {
    repoManager.installBeginning(repoPackage, this)
    setInstallStatus(PackageOperation.InstallStatus.PREPARED, progress)
    return true
  }

  override fun complete(progress: ProgressIndicator): Boolean {
    setInstallStatus(PackageOperation.InstallStatus.COMPLETE, progress)
    repoManager.installEnded(repoPackage)
    return true
  }

  override fun getRepoManager(): RepoManager = repoManager

  override fun registerStateChangeListener(listener: PackageOperation.StatusChangeListener) {
    listeners.add(listener)
  }

  override fun getInstallStatus(): PackageOperation.InstallStatus = status

  override fun getName(): String = "Fake operation on ${repoPackage.path}"

  /** Sets the status of this operation and notifies its listeners, as a real operation does as it progresses. */
  @JvmOverloads
  fun setInstallStatus(
    status: PackageOperation.InstallStatus,
    progress: ProgressIndicator = FakeProgressIndicator(),
  ) {
    this.status = status
    listeners.forEach { it.statusChanged(this, progress) }
  }
}
