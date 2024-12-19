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
 * [AnnotationAttributesProvider] which returns attributes from a given
 * [delegate] [AnnotationAttributesProvider]. If the attribute is null,
 * it uses the given [fallback] to attempt to find the attribute.
 */
class WithFallbackAnnotationAttributesProvider(
    private val delegate: AnnotationAttributesProvider,
    private val fallback: AnnotationAttributesProvider,
) : AnnotationAttributesProvider {

    override fun <T> getAttributeValue(attributeName: String) = delegate.getAttributeValue<T>(attributeName) ?: fallback.getAttributeValue<T>(attributeName)

    override fun getIntAttribute(attributeName: String) = delegate.getIntAttribute(attributeName) ?: fallback.getIntAttribute(attributeName)

    override fun getStringAttribute(attributeName: String) = delegate.getStringAttribute(attributeName) ?: fallback.getStringAttribute(attributeName)

    override fun getFloatAttribute(attributeName: String) = delegate.getFloatAttribute(attributeName) ?: fallback.getFloatAttribute(attributeName)

    override fun getBooleanAttribute(attributeName: String) = delegate.getBooleanAttribute(attributeName) ?: fallback.getBooleanAttribute(attributeName)

    override fun <T> getDeclaredAttributeValue(attributeName: String) = delegate.getDeclaredAttributeValue<T>(attributeName) ?: fallback.getDeclaredAttributeValue<T>(attributeName)

    override fun findClassNameValue(name: String) = delegate.findClassNameValue(name) ?: fallback.findClassNameValue(name)

    companion object {
        fun AnnotationAttributesProvider.withFallback(fallback: AnnotationAttributesProvider)
            = WithFallbackAnnotationAttributesProvider(
                delegate = this,
                fallback = fallback
            )
    }
}
