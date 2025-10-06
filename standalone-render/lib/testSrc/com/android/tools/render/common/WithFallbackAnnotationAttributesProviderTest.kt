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
import com.android.tools.render.common.WithFallbackAnnotationAttributesProvider.Companion.withFallback
import org.junit.Assert.assertEquals
import org.junit.Test

class WithFallbackAnnotationAttributesProviderTest {
    @Test
    fun testAttributesProviderReturnsDelegateAttributesIfNotNull() {
        val delegateAttributesProvider = annotationAttributesProvider(
            intAttribute = 1,
            floatAttribute = 2f,
            booleanAttribute = true,
            stringAttribute = "delegate string attribute",
            className = "delegate class name"
        )

        val fallbackAttributesProvider = annotationAttributesProvider(
            intAttribute = 3,
            floatAttribute = 4f,
            booleanAttribute = false,
            stringAttribute = "fallback string attribute",
            className = "fallback class name"
        )

        val attributesProvider = delegateAttributesProvider.withFallback(fallbackAttributesProvider)

        assertEquals("delegate string attribute", attributesProvider.getStringAttribute("string"))
        assertEquals(2f, attributesProvider.getFloatAttribute("float"))
        assertEquals(1, attributesProvider.getIntAttribute("int"))
        assertEquals(true, attributesProvider.getBooleanAttribute("boolean"))
        assertEquals("delegate class name", attributesProvider.findClassNameValue("className"))
    }

    @Test
    fun testAttributesProviderReturnsFallbackAttributesWhenDelegateReturnsNull() {
        val delegateAttributesProvider = annotationAttributesProvider(
            intAttribute = null,
            floatAttribute = null,
            booleanAttribute = null,
            stringAttribute = null,
            className = null
        )

        val fallbackAttributesProvider = annotationAttributesProvider(
            intAttribute = 3,
            floatAttribute = 4f,
            booleanAttribute = false,
            stringAttribute = "fallback string attribute",
            className = "fallback class name"
        )

        val attributesProvider = delegateAttributesProvider.withFallback(fallbackAttributesProvider)

        assertEquals("fallback string attribute", attributesProvider.getStringAttribute("string"))
        assertEquals(4f, attributesProvider.getFloatAttribute("float"))
        assertEquals(3, attributesProvider.getIntAttribute("int"))
        assertEquals(false, attributesProvider.getBooleanAttribute("boolean"))
        assertEquals("fallback class name", attributesProvider.findClassNameValue("className"))
    }
}

private fun annotationAttributesProvider(
    intAttribute: Int?,
    floatAttribute: Float?,
    booleanAttribute: Boolean?,
    stringAttribute: String?,
    className: String?,
) = object : AnnotationAttributesProvider {
    override fun <T> getAttributeValue(attributeName: String) = null

    override fun getIntAttribute(attributeName: String) = intAttribute

    override fun getStringAttribute(attributeName: String) = stringAttribute

    override fun getFloatAttribute(attributeName: String) = floatAttribute

    override fun getBooleanAttribute(attributeName: String) = booleanAttribute

    override fun <T> getDeclaredAttributeValue(attributeName: String) = null

    override fun findClassNameValue(name: String) = className
}

