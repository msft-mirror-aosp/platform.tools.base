import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.kotlin.multiplatform.models.AndroidCompilation
import com.android.kotlin.multiplatform.models.AndroidSourceSet
import com.android.kotlin.multiplatform.models.AndroidTarget
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.protobuf.util.JsonFormat
import org.jetbrains.kotlin.gradle.ExternalKotlinTargetApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.ide.IdeMultiplatformImport
import org.jetbrains.kotlin.tooling.core.Extras
import java.lang.reflect.Type
import kotlin.apply
import kotlin.jvm.java
import kotlin.toString

plugins {
    id("kotlin-multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    androidLibrary {
        namespace = "com.buildlogic.lib.foo"
        compileSdk = 36
        minSdk = 24

        withHostTest {  }
        withDeviceTest {  }
    }
}

project.tasks.register("dumpAndroidTarget", DumpAndroidTargetTask::class.java).configure {
    description = "Debugging task that will snapshots android target"
    notCompatibleWithConfigurationCache(
        "DumpAndroidTargetTask is just for testing purposes"
    )
}

@DisableCachingByDefault(because = "Used for testing purpose.")
@OptIn(ExternalKotlinTargetApi::class)
abstract class DumpAndroidTargetTask: DefaultTask() {
    private val outputDirectory = project.layout.buildDirectory.dir("ide/targets")
    private val kotlinExtension = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
    private val kotlinIdeMultiplatformImport = IdeMultiplatformImport.instance(project)

    @TaskAction
    fun dump() {
        val outputDirectory = outputDirectory.get().asFile
        outputDirectory.deleteRecursively()

        val gson = GsonBuilder().setLenient().setPrettyPrinting()
            .registerTypeHierarchyAdapter(Extras::class.java, ExtrasAdapter)
            .create()

        kotlinExtension.sourceSets.forEach { sourceSet ->
            kotlinIdeMultiplatformImport.resolveDependencies(sourceSet)
        }

        kotlinExtension.targets.withType(KotlinMultiplatformAndroidLibraryTarget::class.java).configureEach {
            val json = gson.toJson(
                TargetData(
                    targetName = this.targetName,
                    compilations = this.compilations.map { compilation ->
                        CompilationData(
                            compilationName = compilation.compilationName,
                            defaultSourceSet = compilation.defaultSourceSet.let { sourceSet ->
                                SourceSetData(
                                    sourceSetName = sourceSet.name,
                                    extras = sourceSet.extras
                                )
                            },
                            allSourceSets = compilation.allKotlinSourceSets.map { sourceSet ->
                                BasicSourceSet(
                                    sourceSetName = sourceSet.name,
                                )
                            },
                            extras = compilation.extras
                        )
                    },
                    extras = this.extras
                )
            )

            val jsonOutput = outputDirectory.resolve("androidTarget.json")
            jsonOutput.parentFile.mkdirs()
            jsonOutput.writeText(json)
        }
    }

    private object ExtrasAdapter : JsonSerializer<Extras> {

        private val jsonFormat =
            JsonFormat.printer()
                .usingTypeRegistry(
                    JsonFormat.TypeRegistry.newBuilder()
                        .add(AndroidTarget.getDescriptor())
                        .add(AndroidCompilation.getDescriptor())
                        .add(AndroidSourceSet.getDescriptor())
                        .build()
                )
                .includingDefaultValueFields()
                .sortingMapKeys()

        override fun serialize(src: Extras, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            return JsonObject().apply {
                src.entries.forEach { entry ->

                    val value = (entry.value as? Function0<*>)?.let { it() } ?: entry.value

                    val valueElement = when {
                        value is AndroidTarget -> JsonParser.parseString(jsonFormat.print(value))
                        value is AndroidCompilation -> JsonParser.parseString(jsonFormat.print(value))
                        value is AndroidSourceSet -> JsonParser.parseString(jsonFormat.print(value))
                        value?.javaClass?.name == "org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage" -> {
                            // This class uses the default toString() method which contains a hash
                            // code, so we need to normalize it.
                            JsonPrimitive("${value.javaClass.name}@{HASH_CODE}")
                        }
                        else -> runCatching { context.serialize(value) }.getOrElse {
                            JsonPrimitive(value.toString())
                        }
                    }

                    // the kotlin plugin uses a hashset which will not have a deterministic order,
                    // skip since this is reported anyways.
                    if (!entry.key.stableString.contains(
                            "org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.KotlinCompilationSourceSetInclusion"
                        ) &&
                        // An object reference is dumped which changes on different runs
                        !entry.key.stableString.startsWith(
                            "org.jetbrains.kotlin.gradle.utils.Future"
                        ) &&
                        !entry.key.stableString.contains(
                            "org.jetbrains.kotlin.gradle.plugin.mpp.GranularMetadataTransformation"
                        )) {
                        add(entry.key.stableString, valueElement)
                    }
                }
            }
        }
    }

    data class TargetData(
        val targetName: String,
        val compilations: List<CompilationData>,
        val extras: Extras
    )

    data class CompilationData(
        val compilationName: String,
        val defaultSourceSet: SourceSetData,
        val allSourceSets: List<BasicSourceSet>,
        val extras: Extras
    )

    data class BasicSourceSet(
        val sourceSetName: String,
    )

    data class SourceSetData(
        val sourceSetName: String,
        val extras: Extras
    )
}
