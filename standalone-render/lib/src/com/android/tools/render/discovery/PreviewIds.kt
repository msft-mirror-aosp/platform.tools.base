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
package com.android.tools.render.discovery

import java.security.MessageDigest

/** Characters that can't be part of a file name, so a preview name containing them is left out of its preview id. */
private val invalidNameCharsRegex = """[\u0000-\u001F\\/:*?"<>|]+""".toRegex()

/**
 * Returns the id of the `@Preview` with the parameters [previewParams] of the method [methodFqn], whose `@PreviewParameter` annotations
 * have the parameters [methodParams].
 *
 * The id follows the scheme `<methodFqn>[_<name>][_<hash>][_<hash>]`, where the first hash (omitted when [previewParams] is empty) covers
 * [previewParams] and the second one (only for a method with a `@PreviewParameter`) covers [methodParams] as extracted by discovery (class
 * names rather than JVM descriptors, so hashes are not required to be byte-identical to screenshot tests). Each hash is the first 8
 * hexadecimal digits of the SHA-1 of the parameters sorted by name. The `name` of the preview is left out when it is blank (unlike
 * screenshot tests, to avoid empty or whitespace-only name segments such as `Foo__<hash>`) or contains characters that can't be part of a
 * file name.
 *
 * The id only depends on the preview itself, so a preview has the same id whatever the other previews rendered with it, and two `@Preview`
 * annotations with the same parameters on the same method have the same id.
 */
internal fun previewId(methodFqn: String, previewParams: Map<String, String>, methodParams: List<Map<String, String>>): String {
  val id = StringBuilder(methodFqn)
  val name = previewParams["name"]
  if (!name.isNullOrBlank() && !name.contains(invalidNameCharsRegex)) {
    id.append('_').append(name)
  }
  appendHash(id, "annotation-parameters", previewParams)
  for (parameters in methodParams) {
    appendHash(id, "method-parameter-annotations", parameters)
  }
  return id.toString()
}

/** Appends `_<hash>` to [id], the first 8 hexadecimal digits of the SHA-1 of [section] and [parameters] sorted by name, if any. */
private fun appendHash(id: StringBuilder, section: String, parameters: Map<String, String>) {
  if (parameters.isEmpty()) return
  val digest = MessageDigest.getInstance("SHA-1")
  digest.update(section.toByteArray())
  for ((key, value) in parameters.toSortedMap()) {
    digest.update(key.toByteArray())
    digest.update(value.toByteArray())
  }
  id.append('_').append(digest.digest().joinToString("") { "%02x".format(it) }.take(8))
}
