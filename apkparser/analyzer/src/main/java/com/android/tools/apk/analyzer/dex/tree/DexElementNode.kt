/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.apk.analyzer.dex.tree

import com.android.tools.proguard.ProguardMap
import com.android.tools.proguard.ProguardSeedsMap
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableReference
import javax.swing.tree.DefaultMutableTreeNode

abstract class DexElementNode
@JvmOverloads
internal constructor(val name: String, allowsChildren: Boolean, open val reference: ImmutableReference? = null) :
  DefaultMutableTreeNode(null, allowsChildren) {
  open var isDefined: Boolean = false

  var isRemoved: Boolean = false

  open var methodReferencesCount: Int = 0
    protected set

  open var methodDefinitionsCount: Int = 0
    protected set

  override fun getChildAt(i: Int): DexElementNode {
    return super.getChildAt(i) as DexElementNode
  }

  fun getChildren(): Sequence<DexElementNode> = children?.asSequence()?.map { it as DexElementNode } ?: emptySequence()

  open fun sort(comparator: Comparator<DexElementNode>) {
    getChildren().forEach { it.sort(comparator) }

    if (children != null) {
      children.sortWith(Comparator { o1, o2 -> comparator.compare(o1 as DexElementNode, o2 as DexElementNode) })
    }
  }

  @Deprecated("Use getChildByType(name: String)")
  fun <T : DexElementNode> getChildByType(name: String, type: Class<T>): T? {
    @Suppress("UNCHECKED_CAST")
    return getChildren().find { name == it.name && it.javaClass == type } as? T
  }

  inline fun <reified T : DexElementNode> getChildByType(name: String): T? {
    return getChildren().filterIsInstance<T>().find { name == it.name }
  }

  open fun isSeed(seedsMap: ProguardSeedsMap?, map: ProguardMap?, checkChildren: Boolean): Boolean {
    if (seedsMap != null && checkChildren) {
      var i = 0
      val n = childCount
      while (i < n) {
        val node = getChildAt(i)
        if (node.isSeed(seedsMap, map, true)) {
          return true
        }
        i++
      }
    }
    return false
  }

  override fun getParent(): DexElementNode? {
    return super.getParent() as DexElementNode?
  }

  open fun update() {
    var i = 0
    val n = childCount
    while (i < n) {
      val node = getChildAt(i)
      node.update()
      i++
    }
  }

  /**
   * Returns the private size of this dex node, i.e. size that can not share with other nodes. Example of shared size that is not included
   * in this value: strings in the string pool, annotation sets.
   *
   * @return private size of node in bytes
   */
  abstract val size: Long

  override fun toString(): String {
    return this.name
  }

  companion object {
    @JvmStatic
    protected fun combine(parentPackage: String, childName: String): String {
      return if (parentPackage.isEmpty()) childName else "$parentPackage.$childName"
    }
  }
}
