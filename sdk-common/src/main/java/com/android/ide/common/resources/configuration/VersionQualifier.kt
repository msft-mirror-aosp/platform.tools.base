/*
 * Copyright (C) 2009 The Android Open Source Project
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
package com.android.ide.common.resources.configuration

import java.util.regex.Matcher
import java.util.regex.Pattern

/** Resource qualifier for Platform version.  */
class VersionQualifier : ResourceQualifier {
  var version: Int = DEFAULT_VERSION
    private set

  constructor(apiLevel: Int) {
    this.version = apiLevel
  }

  constructor()

  override fun getName(): String {
    return NAME
  }

  override fun getShortName(): String {
    return "Version"
  }

  override fun since(): Int {
    return 1
  }

  override fun isValid(): Boolean {
    return this.version != DEFAULT_VERSION
  }

  override fun hasFakeValue(): Boolean {
    return false
  }

  override fun checkAndSet(value: String, config: FolderConfiguration): Boolean {
    val qualifier: VersionQualifier? = getQualifier(value)
    if (qualifier != null) {
      config.setVersionQualifier(qualifier)
      return true
    }

    return false
  }

  override fun equals(qualifier: Any?): Boolean {
    return qualifier is VersionQualifier
      && this.version == qualifier.version
  }

  override fun isMatchFor(qualifier: ResourceQualifier): Boolean {
    if (qualifier is VersionQualifier) {
      // It is considered a match if our API level is equal or lower to the given qualifier,
      // or the given qualifier doesn't specify an API Level.
      return this.version <= qualifier.version
        || qualifier.version == DEFAULT_VERSION
    }

    return false
  }

  override fun isBetterMatchThan(
    compareTo: ResourceQualifier?, reference: ResourceQualifier
  ): Boolean {
    if (compareTo == null) {
      return true
    }

    val compareQ = compareTo as VersionQualifier
    val referenceQ = reference as VersionQualifier

    if (compareQ.version == referenceQ.version) {
      // what we have is already the best possible match (exact match)
      return false
    } else if (this.version == referenceQ.version) {
      // got new exact value, this is the best!
      return true
    } else {
      // In all case we're going to prefer the higher version (since they have been filtered
      // to not be too high.)
      return this.version > compareQ.version
    }
  }

  override fun hashCode(): Int {
    return this.version
  }

  /**
   * Returns the string used to represent this qualifier in the folder name.
   */
  override fun getFolderSegment(): String {
    return getFolderSegment(this.version)
  }

  override fun getShortDisplayValue(): String {
    return if (this.version == DEFAULT_VERSION) "" else "API " + this.version
  }

  override fun getLongDisplayValue(): String {
    return if (this.version == DEFAULT_VERSION) "" else "API Level " + this.version
  }

  companion object {
    /** Default version. This means the property is not set.  */
    val DEFAULT_VERSION: Int = -1

    private val sVersionPattern: Pattern = Pattern.compile("^v(\\d+)$")

    const val NAME: String = "Platform Version"

    /**
     * Creates and returns a qualifier from the given folder segment. If the segment is incorrect,
     * `null` is returned.
     *
     * @param segment the folder segment from which to create a qualifier
     * @return a new VersionQualifier object or `null`
     */
    fun getQualifier(segment: String): VersionQualifier? {
      val m: Matcher = sVersionPattern.matcher(segment)
      if (m.matches()) {
        val v = m.group(1)

        try {
          return VersionQualifier(v.toInt())
        } catch (e: NumberFormatException) {
          // Not a valid version qualifier segment - return null.
        }
      }

      return null
    }

    /**
     * Returns the folder name segment for the given version value. This is equivalent to calling
     * `new VersionQualifier(version).toString()`.
     *
     * @param version the value of the qualifier, as returned by [.getVersion].
     */
    fun getFolderSegment(version: Int): String {
      return if (version == DEFAULT_VERSION) "" else 'v'.toString() + version.toString()
    }
  }
}
