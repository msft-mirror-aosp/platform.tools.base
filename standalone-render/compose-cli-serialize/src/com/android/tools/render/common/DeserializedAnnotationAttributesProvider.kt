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

package com.android.tools.render.common

import com.android.tools.preview.AnnotationAttributesProvider

/**
 * [AnnotationAttributesProvider] for the annotation represented by the mapping between its
 * parameters names and values.
 */
class DeserializedAnnotationAttributesProvider(private val params: Map<String, String>) : AnnotationAttributesProvider {

    override fun <T> getAttributeValue(attributeName: String): T? = params[attributeName] as? T

    override fun getIntAttribute(attributeName: String): Int? = getAttributeValue<String>(attributeName)?.toInt()

    override fun getStringAttribute(attributeName: String): String? = getAttributeValue<String>(attributeName)

    override fun getFloatAttribute(attributeName: String): Float? = getAttributeValue<String>(attributeName)?.toFloat()

    override fun getBooleanAttribute(attributeName: String): Boolean? = getAttributeValue<String>(attributeName)?.toBoolean()

    override fun <T> getDeclaredAttributeValue(attributeName: String): T? = getAttributeValue(attributeName)

    override fun findClassNameValue(name: String): String? = getAttributeValue(name)
}
