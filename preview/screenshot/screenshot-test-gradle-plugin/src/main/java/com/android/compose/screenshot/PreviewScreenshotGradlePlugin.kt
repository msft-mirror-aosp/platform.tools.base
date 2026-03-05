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

package com.android.compose.screenshot

import com.android.SdkConstants
import com.android.build.api.AndroidPluginVersion
import com.android.build.api.artifact.Artifact
import com.android.build.api.artifact.Artifacts
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.SdkComponents
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasHostTests
import com.android.build.api.variant.HostTest
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.api.variant.Variant
import com.android.build.gradle.api.AndroidBasePlugin
import com.android.compose.screenshot.gradle.ScreenshotTestOptionsImpl
import com.android.compose.screenshot.layoutlibExtractor.LayoutlibDataFromMaven
import com.android.compose.screenshot.services.AnalyticsService
import com.android.compose.screenshot.tasks.PreviewScreenshotTestEngineInput
import com.android.compose.screenshot.tasks.PreviewScreenshotUpdateTask
import com.android.compose.screenshot.tasks.PreviewScreenshotValidationTask
import java.util.Properties
import java.util.UUID
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.util.GradleVersion

private val minAgpVersion = AndroidPluginVersion(8, 5, 0).beta(1)
private val maxAgpVersion = AndroidPluginVersion(9, Int.MAX_VALUE, Int.MAX_VALUE)

/** An entry point for Screenshot plugin that adds support for screenshot testing on Compose Previews */
class PreviewScreenshotGradlePlugin : Plugin<Project> {

  companion object {
    /**
     * Get build service name that works even if build service types come from different class loaders. If the service name is the same, and
     * some type T is defined in two class loaders L1 and L2. E.g. this is true for composite builds and other project setups (see
     * b/154388196).
     *
     * Registration of service may register (T from L1) or (T from L2). This means that querying it with T from other class loader will fail
     * at runtime. This method makes sure both T from L1 and T from L2 will successfully register build services.
     *
     * Copied from com.android.build.gradle.internal.services.BuildServicesKt.getBuildServiceName.
     */
    private fun getBuildServiceName(type: Class<*>): String {
      return type.name + "_" + perClassLoaderConstant
    }

    /** Used to get unique build service name. Each class loader will initialize its own version. */
    private val perClassLoaderConstant = UUID.randomUUID().toString()

    const val ST_SOURCE_SET_ENABLED = "android.experimental.enableScreenshotTest"
    const val VALIDATION_ENGINE_VERSION_OVERRIDE = "android.compose.screenshot.validationEngineVersion"
    const val MAX_HEAP_SIZE_OVERRIDE = "android.compose.screenshot.maxHeapSize"

    const val MIN_VALIDATION_ENGINE_VERSION = "0.0.1-alpha03"

    private const val LAYOUTLIB_VERSION = "16.1.0-jdk17"

    val SCREENSHOT_TEST_PLUGIN_VERSION: String by lazy {
      requireNotNull(PreviewScreenshotGradlePlugin::class.java.getResourceAsStream("/com-android-compose-screenshot.properties"))
        .buffered()
        .use { stream ->
          Properties().let { properties ->
            properties.load(stream)
            properties.getProperty("buildVersion")
          }
        }
    }

    private val reflectionCache by lazy {
      val classLoader = PreviewScreenshotGradlePlugin::class.java.classLoader
      val artifactsImplClass = classLoader.loadClass(ARTIFACT_IMPL)
      val analyticsEnabledArtifactsClass = classLoader.loadClass(ANALYTICS_ENABLED_ARTIFACTS)
      val analyticsEnabledArtifactsGetDelegateMethod = analyticsEnabledArtifactsClass.getMethod("getDelegate")
      val apkForLocalTestClass = classLoader.loadClass("${INTERNAL_ARTIFACT_TYPE}\$APK_FOR_LOCAL_TEST")
      val artifactsImplGet = artifactsImplClass.getDeclaredMethod("get", Artifact.Single::class.java)
      val apkForLocalTestInstance = apkForLocalTestClass.getField("INSTANCE").get(null)
      ReflectionCache(
        artifactsImplClass,
        analyticsEnabledArtifactsClass,
        analyticsEnabledArtifactsGetDelegateMethod,
        artifactsImplGet,
        apkForLocalTestInstance,
      )
    }

    private data class ReflectionCache(
      val artifactsImplClass: Class<*>,
      val analyticsEnabledArtifactsClass: Class<*>,
      val analyticsEnabledArtifactsGetDelegateMethod: java.lang.reflect.Method,
      val artifactsImplGet: java.lang.reflect.Method,
      val apkForLocalTestInstance: Any,
    )
  }

  override fun apply(project: Project) {
    project.plugins.withType(AndroidBasePlugin::class.java) {
      val componentsExtension = project.extensions.getByType(AndroidComponentsExtension::class.java)
      val agpVersion = componentsExtension.pluginVersion

      val currentJdk = JavaVersion.current()
      val currentGradleVersion = GradleVersion.current()
      /**
       * When an incompatible JDK (like 24+) is used, we must enforce a minimum Gradle version of 8.14. This is because versions prior to
       * 8.14 are not officially supported for running on JDK 24, which can cause the build itself to fail before our toolchain logic can
       * even execute. Gradle 8.14 is the first version to officially support Java 24, ensuring a stable environment for our plugin to find
       * and use a compatible JDK toolchain.
       */
      if (currentJdk.majorVersion.toInt() >= 24) {
        val requiredGradleVersion = GradleVersion.version("8.14")

        if (currentGradleVersion < requiredGradleVersion) {
          error(jdkVersionError(currentJdk, currentGradleVersion, requiredGradleVersion))
        }
      }

      if (agpVersion < minAgpVersion || (agpVersion > maxAgpVersion && agpVersion.previewType != "dev")) {
        error(agpVersionError(agpVersion))
      }
      val screenshotSourcesetEnabled = project.providers.gradleProperty(ST_SOURCE_SET_ENABLED).getOrNull()
      if (screenshotSourcesetEnabled?.toBoolean() != true) {
        error(screenshotSourceSetError)
      }

      val validationEngineVersionOverride = project.providers.gradleProperty(VALIDATION_ENGINE_VERSION_OVERRIDE).getOrNull()
      val validationEngineVersion =
        if (validationEngineVersionOverride != null && validationEngineVersionOverride.isNotEmpty()) {
          // Changes to the image naming format make versions 0.0.1-alpha02 and below of the
          // test engine incompatible with the latest plugin version
          val validationEngineOverrideString = validationEngineVersionOverride
          if (validationEngineOverrideString < MIN_VALIDATION_ENGINE_VERSION && !validationEngineOverrideString.endsWith("-dev")) {
            error(validationEngineVersionError(validationEngineOverrideString))
          }
          validationEngineOverrideString
        } else SCREENSHOT_TEST_PLUGIN_VERSION

      val screenshotExtension = project.extensions.create("screenshotTests", ScreenshotTestOptionsImpl::class.java)

      val analyticsServiceProvider =
        project.gradle.sharedServices.registerIfAbsent(getBuildServiceName(AnalyticsService::class.java), AnalyticsService::class.java) {
          spec ->
          spec.parameters.androidGradlePluginVersion.set(agpVersion.toVersionString())
        }

      val sdkDirectory = componentsExtension.sdkComponents.sdkDirectory

      createLayoutlibConfiguration(project)
      createLayoutlibResourcesConfiguration(project)
      maybeCreateScreenshotTestConfiguration(project, validationEngineVersion)

      val layoutlibJarConfig = project.configurations.getByName(layoutlibJarConfigurationName)
      val engineConfig = project.configurations.getByName(previewScreenshotTestEngineConfigurationName)

      val layoutlibDataFromMaven =
        LayoutlibDataFromMaven.create(project, LAYOUTLIB_VERSION, project.configurations.getByName(layoutlibResourcesConfigurationName))

      val updateAllTask =
        project.tasks.register("updateScreenshotTest", Task::class.java) { task ->
          task.description = "Update screenshots for all variants."
          task.group = JavaBasePlugin.VERIFICATION_GROUP
        }

      val validateAllTask =
        project.tasks.register("validateScreenshotTest", Task::class.java) { task ->
          task.description = "Run screenshot tests for all variants."
          task.group = JavaBasePlugin.VERIFICATION_GROUP
        }

      val buildDir = project.layout.buildDirectory
      val commonExtension = project.extensions.getByType(CommonExtension::class.java)
      val maxHeapSize = project.providers.gradleProperty(MAX_HEAP_SIZE_OVERRIDE).getOrNull()

      componentsExtension.beforeVariants {
        val screenshotSourceSetEnabledInModule = commonExtension.experimentalProperties[ST_SOURCE_SET_ENABLED]
        if (screenshotSourceSetEnabledInModule?.toString()?.toBoolean() != true) {
          error(moduleSourceSetError(project))
        }
      }

      componentsExtension.onVariants { variant ->
        if (variant is HasHostTests && variant.debuggable) {
          val variantName = variant.name
          val capitalizedVariantName = variantName.capitalized()
          val variantPath = variant.computePathSegments()
          val screenshotTestComponent = variant.hostTests[HostTestBuilder.SCREENSHOT_TEST_TYPE] ?: return@onVariants
          variant.runtimeConfiguration.checkToolingPresent(screenshotTestComponent)

          val updateTask =
            project.tasks.register("update${capitalizedVariantName}ScreenshotTest", PreviewScreenshotUpdateTask::class.java) { task ->
              task.description = "Update screenshots for the $variantName build."
              task.group = JavaBasePlugin.VERIFICATION_GROUP
              task.analyticsService.set(analyticsServiceProvider)
              task.usesService(analyticsServiceProvider)
              task.useJUnitPlatform {
                it.excludeEngines("junit-jupiter")
                it.includeEngines("preview-screenshot-test-engine")
              }
              task.testLogging { it.showStandardStreams = true }
              task.isScanForTestClasses = false
              task.systemProperty("java.awt.headless", "true")
              task.classpath.setFrom(
                engineConfig,
                componentsExtension.sdkComponents.bootClasspath, // Needed for test discovery
                task.classpath,
              )
              maxHeapSize?.let { task.maxHeapSize = it }
            }

          updateTask.configureTestEngineInput(
            project,
            variant,
            variantPath,
            capitalizedVariantName,
            screenshotTestComponent,
            componentsExtension.sdkComponents,
            layoutlibDataFromMaven,
            sdkDirectory,
            screenshotExtension,
            layoutlibJarConfig,
            null,
            { testEngineInput },
          )

          updateAllTask.configure { it.dependsOn(updateTask) }

          val previewScreenshotTestTask =
            project.tasks.register("validate${capitalizedVariantName}ScreenshotTest", PreviewScreenshotValidationTask::class.java) { task ->
              task.analyticsService.set(analyticsServiceProvider)
              task.usesService(analyticsServiceProvider)
              task.description = "Run screenshot tests for the $variantName build."
              task.group = JavaBasePlugin.VERIFICATION_GROUP

              task.useJUnitPlatform {
                it.excludeEngines("junit-jupiter")
                it.includeEngines("preview-screenshot-test-engine")
              }
              task.testLogging { it.showStandardStreams = true }
              task.isScanForTestClasses = false
              task.systemProperty("java.awt.headless", "true")
              task.reports {
                // TODO(b/325320710): Use the standard test report when extension points
                //  for adding custom information become available. As a short-term
                //  solution, we register XmlReportGeneratingListener to JUnit5 launcher
                //  using service loader.
                it.junitXml.required.set(false)
                // Set html to true so that Gradle's error message contains clickable
                // link to the html file.
                it.html.required.set(true)
                it.html.outputLocation.set(buildDir.dir("$PREVIEW_REPORTS/$variantPath"))
              }

              task.classpath.setFrom(
                engineConfig,
                componentsExtension.sdkComponents.bootClasspath, // Needed for test discovery
                task.classpath,
              )

              maxHeapSize?.let { task.maxHeapSize = it }
            }

          previewScreenshotTestTask.configureTestEngineInput(
            project,
            variant,
            variantPath,
            capitalizedVariantName,
            screenshotTestComponent,
            componentsExtension.sdkComponents,
            layoutlibDataFromMaven,
            sdkDirectory,
            screenshotExtension,
            layoutlibJarConfig,
            { reports.junitXml.outputLocation.get() },
            { testEngineInput },
          )

          validateAllTask.configure { it.dependsOn(previewScreenshotTestTask) }
        }
      }
    }
  }

  // This will be provided by AGP at some point.
  private fun Variant.computePathSegments(): String {
    return buildType?.let { bt -> flavorName?.let { fn -> "$bt/$fn" } ?: bt } ?: flavorName ?: ""
  }

  private fun <T : Task> TaskProvider<T>.configureTestEngineInput(
    project: Project,
    variant: Variant,
    variantPath: String,
    capitalizedVariantName: String,
    screenshotTestComponent: HostTest,
    sdkComponents: SdkComponents,
    layoutlibDataFromMaven: LayoutlibDataFromMaven,
    sdkDirectory: Provider<Directory>,
    screenshotExtension: ScreenshotTestOptionsImpl,
    layoutlibJarConfig: Configuration,
    junitXmlOutputDirectoryProvider: (T.() -> Directory)?,
    getTestEngineInput: T.() -> PreviewScreenshotTestEngineInput,
  ) {
    val buildDir = project.layout.buildDirectory
    configure { task ->
      getTestEngineInput(task).apply {
        threshold.set(screenshotExtension.imageDifferenceThreshold)
        namespace.set(variant.namespace)
        layoutlibDataDir.setFrom(layoutlibDataFromMaven.layoutlibDataDirectory)
        layoutlibClassPath.setFrom(layoutlibJarConfig, sdkComponents.bootClasspath)
        referenceImageDir.set(project.layout.projectDirectory.dir("src/screenshotTest$capitalizedVariantName/reference"))
        previewImageOutputDir.set(buildDir.dir("$PREVIEW_OUTPUT/$variantPath/rendered"))
        diffImageOutputDir.set(buildDir.dir("$PREVIEW_OUTPUT/$variantPath/diffs"))
        if (junitXmlOutputDirectoryProvider != null) {
          junitXmlOutputDirectory.set(project.provider { junitXmlOutputDirectoryProvider(task) })
        }
        getResourceApk(screenshotTestComponent.artifacts)?.let { resourceApkFile.set(it) }

        // Need to use project.providers as a workaround to gradle issue:
        // https://github.com/gradle/gradle/issues/12388
        val sdkFonts =
          project.providers.provider {
            val subDir = sdkDirectory.get().asFile.resolve(SdkConstants.SDK_DL_FONTS_FOLDER)
            if (subDir.exists()) {
              sdkDirectory.get().dir(SdkConstants.SDK_DL_FONTS_FOLDER)
            } else {
              null
            }
          }
        sdkFontsDir.set(sdkFonts)
      }
    }
    variant.artifacts
      .forScope(ScopedArtifacts.Scope.ALL)
      .use(this)
      .toGet(ScopedArtifact.CLASSES, { getTestEngineInput(it).mainRuntimeJars }, { getTestEngineInput(it).mainRuntimeClassDirs })
    variant.artifacts
      .forScope(ScopedArtifacts.Scope.PROJECT)
      .use(this)
      .toGet(ScopedArtifact.CLASSES, { getTestEngineInput(it).mainProjectJars }, { getTestEngineInput(it).mainProjectClassDirs })
    screenshotTestComponent.artifacts
      .forScope(ScopedArtifacts.Scope.ALL)
      .use(this)
      .toGet(ScopedArtifact.CLASSES, { getTestEngineInput(it).testRuntimeJars }, { getTestEngineInput(it).testRuntimeClassDirs })
    screenshotTestComponent.artifacts
      .forScope(ScopedArtifacts.Scope.PROJECT)
      .use(this)
      .toGet(ScopedArtifact.CLASSES, { getTestEngineInput(it).testProjectJars }, { getTestEngineInput(it).testProjectClassDirs })
  }

  private fun getResourceApk(screenshotTestComponentArtifacts: Artifacts): Provider<RegularFile>? {
    val cache = reflectionCache
    val artifacts = screenshotTestComponentArtifacts
    val artifactImplObject =
      when {
        cache.artifactsImplClass.isInstance(artifacts) -> artifacts
        cache.analyticsEnabledArtifactsClass.isInstance(artifacts) -> cache.analyticsEnabledArtifactsGetDelegateMethod.invoke(artifacts)
        else -> throw IllegalStateException("Unexpected artifact type ${artifacts.javaClass}")
      }

    // Invoking ArtifactsImpl::get(InternalArtifactType.APK_FOR_LOCAL_TEST) by reflection.
    @Suppress("UNCHECKED_CAST")
    val resourceFileProvider = cache.artifactsImplGet.invoke(artifactImplObject, cache.apkForLocalTestInstance) as? Provider<RegularFile>

    return resourceFileProvider
  }

  private fun maybeCreateScreenshotTestConfiguration(project: Project, validationEngineVersion: String) {
    val container = project.configurations
    val dependencies = project.dependencies
    if (container.findByName(previewScreenshotTestEngineConfigurationName) == null) {
      container.create(previewScreenshotTestEngineConfigurationName).apply {
        isTransitive = true
        isCanBeConsumed = false
        description = "A configuration to resolve screenshot test engine dependencies."
      }

      dependencies.add(previewScreenshotTestEngineConfigurationName, "org.junit.platform:junit-platform-launcher")
      dependencies.add(
        previewScreenshotTestEngineConfigurationName,
        "com.android.tools.screenshot:screenshot-validation-junit-engine:${validationEngineVersion}",
      )
    }
  }

  private fun createLayoutlibConfiguration(project: Project) {
    val container = project.configurations
    val dependencies = project.dependencies
    if (container.findByName(layoutlibJarConfigurationName) == null) {
      container.create(layoutlibJarConfigurationName).apply {
        isTransitive = true
        isCanBeConsumed = false
        description = "A configuration to resolve layoutlib jar dependencies."
      }
      dependencies.add(layoutlibJarConfigurationName, "com.android.tools.layoutlib:layoutlib:$LAYOUTLIB_VERSION")

      // Standalone renderer version is the same as plugin version.
      val standaloneRendererVersion = SCREENSHOT_TEST_PLUGIN_VERSION
      dependencies.add(layoutlibJarConfigurationName, "com.android.tools.compose:compose-preview-renderer:$standaloneRendererVersion")
    }
  }

  private fun createLayoutlibResourcesConfiguration(project: Project) {
    val container = project.configurations
    val dependencies = project.dependencies
    if (container.findByName(layoutlibResourcesConfigurationName) == null) {
      container.create(layoutlibResourcesConfigurationName).apply {
        isTransitive = true
        isCanBeConsumed = false
        description = "A configuration to resolve render CLI tool dependencies."
      }
      val version = LAYOUTLIB_VERSION
      dependencies.add(layoutlibResourcesConfigurationName, "com.android.tools.layoutlib:layoutlib-resources:$version")
    }
  }

  private fun AndroidPluginVersion.toVersionString(): String {
    val builder = StringBuilder("$major.$minor.$micro")
    previewType?.let { builder.append("-$it") }
    if (preview > 0) {
      builder.append(preview.toString().padStart(2, '0'))
    }
    return builder.toString()
  }

  /**
   * Rendering previews requires the presence of a "tooling" library. This method checks for the presence of the tooling library based on
   * the type of previews being used. This method assumes that a preview type is in use by the presence of its "tooling preview" library.
   *
   * For example, in the case of compose, a user must include androidx.compose.ui:ui-tooling-preview in order to declare previews in their
   * code. They must also include androidx.compose.ui:ui-tooling in order to render the previews.
   */
  private fun Configuration.checkToolingPresent(screenshotTestComponent: HostTest) {
    incoming.afterResolve { resolvableDependencies ->
      val allDependencies =
        resolvableDependencies.resolutionResult.allDependencies
          .filterIsInstance<ResolvedDependencyResult>()
          .map { result -> result.selected.id }
          .filterIsInstance<ModuleComponentIdentifier>()
          .map { identifier -> identifier.group to identifier.module }
          .toSet()

      val missingToolingDependencies =
        PREVIEW_DEPENDENCIES.filter { previewDependency ->
          val isPreviewPresent = allDependencies.contains(previewDependency.group to previewDependency.previewModule)
          val isToolingPresent = allDependencies.contains(previewDependency.group to previewDependency.toolingModule)
          isPreviewPresent && !isToolingPresent
        }

      if (missingToolingDependencies.isNotEmpty()) {
        screenshotTestComponent.runtimeConfiguration.incoming.afterResolve { resolvedScreenshotTestComponent ->
          val screenshotTestDependencies =
            resolvedScreenshotTestComponent.resolutionResult.allDependencies
              .filterIsInstance<ResolvedDependencyResult>()
              .map { result -> result.selected.id }
              .filterIsInstance<ModuleComponentIdentifier>()
              .map { identifier -> identifier.group to identifier.module }
              .toSet()

          for (missingDependency in missingToolingDependencies) {
            val isPresentInScreenshotTests = screenshotTestDependencies.contains(missingDependency.group to missingDependency.toolingModule)

            if (!isPresentInScreenshotTests) {
              val errorMessage =
                "Missing required runtime dependency. Please add ${missingDependency.group}:${missingDependency.toolingModule} as a screenshotTestImplementation dependency."
              throw IllegalStateException(errorMessage)
            }
          }
        }
      }
    }
  }

  private fun String.capitalized(): String {
    return replaceFirstChar { it.uppercase() }
  }

  private fun jdkVersionError(currentJdk: JavaVersion, currentGradleVersion: GradleVersion, requiredGradleVersion: GradleVersion): String =
    """
    Using JDK ${currentJdk.majorVersion} requires Gradle version ${requiredGradleVersion.version} or newer for screenshot tests.
    Current Gradle version is ${currentGradleVersion.version}.
    Please upgrade your project's Gradle version.
    """
      .trimIndent()

  private fun agpVersionError(currentAgpVersion: AndroidPluginVersion): String =
    """
    Preview screenshot plugin requires Android Gradle plugin version ${minAgpVersion.toVersionString()} or higher, but less than ${maxAgpVersion.major + 1}.0.
    Current version is $currentAgpVersion.
    """
      .trimIndent()

  private val screenshotSourceSetError =
    """
    Please enable screenshotTest source set first to apply the screenshot test plugin.
    Add "$ST_SOURCE_SET_ENABLED=true" to gradle.properties
    """
      .trimIndent()

  private fun validationEngineVersionError(version: String): String =
    """
    Preview screenshot plugin requires the screenshot validation engine version to be at least $MIN_VALIDATION_ENGINE_VERSION, $VALIDATION_ENGINE_VERSION_OVERRIDE cannot be set to $version.
    """
      .trimIndent()

  private fun moduleSourceSetError(project: Project): String =
    """
    Please enable screenshotTest source set in module first to apply the screenshot test plugin.
    Add "experimentalProperties["$ST_SOURCE_SET_ENABLED"] = true" to the android block of the module's build file: ${project.buildFile.toURI()}
    """
      .trimIndent()
}

private const val junitStandaloneLauncherConfigurationName = "_internal-junit-engine-standalone-launcher"
private const val previewScreenshotTestEngineConfigurationName = "_internal-screenshot-validation-junit-engine"
private const val layoutlibJarConfigurationName = "_internal-screenshot-test-task-layoutlib"
private const val layoutlibResourcesConfigurationName = "_internal-screenshot-test-task-layoutlib-res"
private const val ARTIFACT_IMPL = "com.android.build.api.artifact.impl.ArtifactsImpl"
private const val ANALYTICS_ENABLED_ARTIFACTS = "com.android.build.api.component.analytics.AnalyticsEnabledArtifacts"
private const val INTERNAL_ARTIFACT_TYPE = "com.android.build.gradle.internal.scope.InternalArtifactType"

private const val PREVIEW_OUTPUT = "outputs/screenshotTest-results/preview"
private const val PREVIEW_REPORTS = "reports/screenshotTest/preview"

private data class PreviewDependency(val group: String, val toolingModule: String, val previewModule: String)

private val COMPOSE_PREVIEW_DEPENDENCY =
  PreviewDependency(group = "androidx.compose.ui", toolingModule = "ui-tooling", previewModule = "ui-tooling-preview")

private val WEAR_TILE_PREVIEW_DEPENDENCY =
  PreviewDependency(group = "androidx.wear.tiles", toolingModule = "tiles-tooling", previewModule = "tiles-tooling-preview")

private val PREVIEW_DEPENDENCIES = listOf(COMPOSE_PREVIEW_DEPENDENCY, WEAR_TILE_PREVIEW_DEPENDENCY)
