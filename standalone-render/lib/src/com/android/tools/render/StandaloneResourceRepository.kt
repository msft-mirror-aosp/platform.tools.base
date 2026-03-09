/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.render

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.AbstractResourceRepository
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceVisitor
import com.android.resources.ResourceType
import com.android.tools.res.CacheableResourceRepository
import com.android.tools.res.FolderResourceRepository
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.ListMultimap
import java.io.File

class StandaloneResourceRepository(
  private val resourceDirs: List<String>,
  private val apkResourcesRepo: com.android.ide.common.resources.ResourceRepository,
) : AbstractResourceRepository(), CacheableResourceRepository {

  private val folders = resourceDirs.map { FolderResourceRepository(File(it)) }
  private val children = folders + listOf(apkResourcesRepo)

  override val modificationCount: Long
    get() = children.sumOf { if (it is CacheableResourceRepository) it.modificationCount else 0L }

  override fun getNamespaces(): Set<ResourceNamespace> {
    return children.flatMapTo(mutableSetOf()) { it.namespaces }
  }

  /**
   * Returns resources for the given namespace and type.
   *
   * Shadowing behavior: children are searched in reverse order. The first child that contains a resource with a given key wins. Typically,
   * children are [folders] (dependency resources) followed by [apkResourcesRepo] (main project resources). By searching in reverse, the
   * [apkResourcesRepo] (project resources) overrides library resources in [folders].
   */
  override fun getResourcesInternal(namespace: ResourceNamespace, resourceType: ResourceType): ListMultimap<String, ResourceItem> {
    val result = ArrayListMultimap.create<String, ResourceItem>()
    for (child in children.reversed()) {
      if (child.namespaces.contains(namespace)) {
        val map = child.getResources(namespace, resourceType)
        for (key in map.keySet()) {
          if (!result.containsKey(key)) {
            result.putAll(key, map.get(key))
          }
        }
      }
    }
    return result
  }

  override fun accept(visitor: ResourceVisitor): ResourceVisitor.VisitResult {
    for (child in children) {
      if (child.accept(visitor) == ResourceVisitor.VisitResult.ABORT) {
        return ResourceVisitor.VisitResult.ABORT
      }
    }
    return ResourceVisitor.VisitResult.CONTINUE
  }

  override fun getPublicResources(namespace: ResourceNamespace, type: ResourceType): Collection<ResourceItem> {
    val result = mutableListOf<ResourceItem>()
    children.forEach { if (it.namespaces.contains(namespace)) result.addAll(it.getPublicResources(namespace, type)) }
    return result
  }

  override fun getLeafResourceRepositories(): Collection<com.android.ide.common.resources.SingleNamespaceResourceRepository> {
    return children.flatMapTo(mutableListOf()) { it.leafResourceRepositories }
  }
}
