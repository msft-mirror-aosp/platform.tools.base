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
 * [AnnotationAttributesProvider] that returns default values from a given annotation's [Class].
 * This class gets those values by getting the default value of the method associated to
 * the attribute's name.
 */
class DefaultAnnotationValuesAnnotationAttributesProvider(
    private val annotationClass: Class<*>,
) : AnnotationAttributesProvider {

    override fun <T> getAttributeValue(attributeName: String) = annotationClass.getMethod(attributeName).defaultValue as? T

    override fun getIntAttribute(attributeName: String) = getAttributeValue<Int>(attributeName)

    override fun getStringAttribute(attributeName: String) = getAttributeValue<String>(attributeName)

    override fun getFloatAttribute(attributeName: String) = getAttributeValue<Float>(attributeName)

    override fun getBooleanAttribute(attributeName: String) = getAttributeValue<Boolean>(attributeName)

    override fun <T> getDeclaredAttributeValue(attributeName: String) = getAttributeValue<T>(attributeName)

    override fun findClassNameValue(name: String) = getAttributeValue<String?>(name)

}
