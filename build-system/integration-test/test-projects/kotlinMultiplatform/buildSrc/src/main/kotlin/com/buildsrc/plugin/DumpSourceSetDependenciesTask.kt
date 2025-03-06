/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.buildsrc.plugin

import com.android.kotlin.multiplatform.models.DependencyInfo
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.protobuf.util.JsonFormat
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.ide.IdeDependencyResolver
import org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport
import org.jetbrains.kotlin.gradle.ExternalKotlinTargetApi
import org.jetbrains.kotlin.tooling.core.Extras
import java.io.File
import java.lang.reflect.Type

/**
 * Task intended to be use for testing purposes.
 * This will invoke the [IdeMultiplatformImport] to resolve all dependencies (like the IDE would).
 */
@DisableCachingByDefault(because = "Used for testing purpose.")
@OptIn(ExternalKotlinTargetApi::class)
abstract class DumpSourceSetDependenciesTask: DefaultTask() {
    private val outputDirectory = project.layout.buildDirectory.dir("ide/dependencies/json")
    private val kotlinExtension = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    private val kotlinIdeMultiplatformImport = IdeMultiplatformImport.instance(project)

    @TaskAction
    fun dump() {
        val outputDirectory = outputDirectory.get().asFile
        outputDirectory.deleteRecursively()

        val gson = GsonBuilder().setLenient().setPrettyPrinting()
            .registerTypeHierarchyAdapter(Extras::class.java, ExtrasAdapter)
            .registerTypeHierarchyAdapter(IdeDependencyResolver::class.java, IdeDependencyResolverAdapter)
            .registerTypeHierarchyAdapter(Map::class.java, MapAdapter())
            .registerTypeAdapter(File::class.java, FileAdapter())
            .create()

        kotlinExtension.sourceSets.forEach { sourceSet ->
            val dependencies = kotlinIdeMultiplatformImport.resolveDependencies(sourceSet)
            val jsonOutput = outputDirectory.resolve("${sourceSet.name}.json")
            jsonOutput.parentFile.mkdirs()
            jsonOutput.writeText(gson.toJson(dependencies))
        }
    }

    private object ExtrasAdapter : JsonSerializer<Extras> {

        private val jsonFormat =
            JsonFormat.printer()
                .usingTypeRegistry(
                    JsonFormat.TypeRegistry.newBuilder()
                        .add(DependencyInfo.getDescriptor())
                        .build()
                )
                .includingDefaultValueFields()
                .sortingMapKeys()

        override fun serialize(src: Extras, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            return JsonObject().apply {
                src.entries.forEach { entry ->

                    val valueElement = when (entry.value) {
                        is DependencyInfo ->
                            JsonParser.parseString(
                                jsonFormat.print(entry.value as DependencyInfo)
                            )
                        else -> runCatching { context.serialize(entry.value) }.getOrElse {
                            JsonPrimitive(entry.value.toString())
                        }
                    }

                    add(entry.key.stableString, valueElement)
                }
            }
        }
    }

    private object IdeDependencyResolverAdapter : JsonSerializer<IdeDependencyResolver> {
        override fun serialize(src: IdeDependencyResolver, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            return JsonPrimitive(src.javaClass.name)
        }
    }

    private class FileAdapter() : JsonSerializer<File> {
        override fun serialize(src: File, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
            return JsonPrimitive(src.path)
        }
    }

    private class MapAdapter : JsonSerializer<Map<String, String>> {
        override fun serialize(
            src: Map<String, String>,
            typeOfSrc: Type?,
            context: JsonSerializationContext?
        ): JsonElement {
            val sortedMap = src.toSortedMap()
            return JsonObject().apply {
                for ((key, value) in sortedMap) {
                    add(key, context?.serialize(value))
                }
            }
        }
    }
}
