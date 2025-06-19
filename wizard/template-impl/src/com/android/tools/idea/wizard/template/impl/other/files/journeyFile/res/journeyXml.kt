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
package com.android.tools.idea.wizard.template.impl.other.files.journeyFile.res

import com.intellij.openapi.util.text.StringUtil

fun journeyXml(name: String, description: String) =
  """
<?xml version="1.0" encoding="utf-8"?>
<journey name="${StringUtil.escapeXmlEntities(name)}">
    <description>${StringUtil.escapeXmlEntities(description)}</description>
    <actions>
        <action></action>
    </actions>
</journey>
"""
