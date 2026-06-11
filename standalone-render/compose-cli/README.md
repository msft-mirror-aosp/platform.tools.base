# Compose Preview Renderer CLI

The `compose-preview-renderer` is a command-line tool that renders Jetpack Compose previews as PNG screenshots. It runs standalone, using Layoutlib to render the previews without requiring a full Android emulator or device.

## Building the Tool

You can build the standalone executable JAR (fat JAR) using Bazel:

```bash
bazel build //tools/base/standalone-render/compose-cli:compose-preview-renderer-cli_deploy.jar
```

This will generate a single JAR containing all the Java dependencies at:
`bazel-bin/tools/base/standalone-render/compose-cli/compose-preview-renderer-cli_deploy.jar`

## Running the Tool

> [!IMPORTANT]
> **Prerequisite**: You must compile your Android project (e.g., run `./gradlew assembleDebug` or build the project in Android Studio) before running the tool. The tool requires compiled class files, resources, and the resource APK to exist.

To run the tool, it is recommended to use the Java Runtime (JBR) and Layoutlib resources bundled with your Android Studio installation.

Define your Android Studio installation path (e.g. if installed in Downloads):

```bash
export ANDROID_STUDIO_PATH=~/Downloads/android-studio
```

Run the tool using the bundled JBR:

```bash
$ANDROID_STUDIO_PATH/jbr/bin/java -jar bazel-bin/tools/base/standalone-render/compose-cli/compose-preview-renderer-cli_deploy.jar <path-to-settings-json>
```

### Command Line Options

*   `-h`, `--help`: Show the help message and exit.

## Obtaining the Classpath

The standalone renderer requires a classpath containing all the compiled classes of your project and its dependencies (resolved to JARs, not DEX).

### Using a Gradle Init Script

The most reliable way to obtain this classpath in an Android Gradle project is to use a Gradle init script to register a task that extracts the classpath. A Kotlin-based init script using reflection is used to avoid classloader conflicts with the Android Gradle Plugin while maintaining compatibility with Gradle's Configuration Cache.

1.  Create a file named `extract-classpath.init.gradle.kts` in your project root with the following content:

    ```kotlin
    import org.gradle.api.Action
    import org.gradle.api.Project
    import org.gradle.api.DefaultTask
    import org.gradle.api.file.ConfigurableFileCollection
    import org.gradle.api.file.RegularFileProperty
    import org.gradle.api.provider.Property
    import org.gradle.api.tasks.InputFiles
    import org.gradle.api.tasks.Input
    import org.gradle.api.tasks.OutputFile
    import org.gradle.api.tasks.TaskAction
    import org.gradle.api.artifacts.Configuration
    import org.gradle.api.attributes.Attribute

    allprojects {
        val project = this
        plugins.withId("com.android.application") {
            val androidComponents = project.extensions.findByName("androidComponents")
            if (androidComponents != null) {
                try {
                    // Use reflection to bypass classloader isolation:
                    val selectorMethod = androidComponents.javaClass.getMethod("selector")
                    val selector = selectorMethod.invoke(androidComponents)

                    val allMethod = selector.javaClass.getMethod("all")
                    val allSelector = allMethod.invoke(selector)

                    val onVariantsMethod = androidComponents.javaClass.methods.firstOrNull {
                        it.name == "onVariants" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[1].name == "org.gradle.api.Action"
                    }

                    if (onVariantsMethod != null) {
                        val action = object : Action<Any> {
                            override fun execute(variant: Any) {
                                try {
                                    val getNameMethod = variant::class.java.getMethod("getName")
                                    val variantName = getNameMethod.invoke(variant) as String

                                    val getRuntimeConfigMethod = variant::class.java.getMethod("getRuntimeConfiguration")
                                    val config = getRuntimeConfigMethod.invoke(variant) as Configuration

                                    val runtimeClasses = config.incoming.artifactView {
                                        attributes {
                                            attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar")
                                        }
                                    }.files

                                    val taskName = "${variantName}ExtractClasspath"
                                    project.tasks.register(taskName, ExtractClasspathTask::class.java) {
                                        this.runtimeClasses.from(runtimeClasses)
                                        this.buildDirPath.set(project.layout.buildDirectory.map { it.asFile.absolutePath })
                                        this.variantName.set(variantName)
                                        this.outputFile.set(project.file("deps.txt"))
                                    }
                                } catch (e: Exception) {
                                    project.logger.error("Error in variant processing", e)
                                }
                            }
                        }

                        onVariantsMethod.invoke(androidComponents, allSelector, action)
                    }
                } catch (e: Exception) {
                    project.logger.error("Error setting up ExtractClasspath", e)
                }
            }
        }
    }

    abstract class ExtractClasspathTask : DefaultTask() {
        @get:InputFiles
        abstract val runtimeClasses: ConfigurableFileCollection

        @get:Input
        abstract val variantName: Property<String>

        @get:Input
        abstract val buildDirPath: Property<String>

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @TaskAction
        fun run() {
            val depsFile = outputFile.get().asFile
            depsFile.printWriter().use { writer ->
                val bDir = buildDirPath.get()
                val vName = variantName.get()
                writer.println("${bDir}/intermediates/compile_app_classes_jar/${vName}/classes.jar")
                runtimeClasses.files.forEach { file ->
                    writer.println(file.absolutePath)
                }
            }
        }
    }
    ```

2.  Run the registered task by passing the init script to Gradle:

    ```bash
    ./gradlew debugExtractClasspath --init-script extract-classpath.init.gradle.kts
    ```

3.  This will generate a `deps.txt` file in your app module directory containing one classpath entry per line.

4.  Populate the `classPath` field in the configuration JSON with these entries. You can convert the `deps.txt` file into a JSON array using `jq`:
    ```bash
    jq -R . < deps.txt | jq -s .
    ```
    This output can be directly pasted into the `classPath` field.


## Configuration JSON Format

The input JSON file must contain the following fields (all paths must be absolute, environment variables like `$ANDROID_STUDIO_PATH` are not supported):

*   `layoutlibPath` (String, required): Path to the layoutlib directory inside Android Studio.
    Typically: `<absolute path to android-studio>/plugins/design-tools/resources/layoutlib`
*   `outputFolder` (String, required): Path to the directory where the rendered PNG images will be saved.
    Typically: `<absolute path to the Android project root>/<module-name>/build/rendered-screenshots`
*   `metaDataFolder` (String, required): Path to the directory where metadata is stored.
    Typically: `<absolute path to the Android project root>/<module-name>/build/intermediates/preview-metadata`
*   `classPath` (Array of Strings, required): Classpath containing the compiled classes of the project, including Compose libraries (extracted from `deps.txt`).
*   `projectClassPath` (Array of Strings, required): Classpath containing only the project's own compiled classes.
    Typically: `<absolute path to the Android project root>/<module-name>/build/tmp/kotlin-classes/debug`
*   `namespace` (String, required): The package name / namespace of the application.
*   `resourceApkPath` (String, required): Path to the compiled resource APK of the application.
    Typically: `<absolute path to the Android project root>/<module-name>/build/outputs/apk/debug/<app-name>-debug.apk`
*   `resultsFilePath` (String, required): Path where the JSON results file will be written.
*   `fontsPath` (String, optional): Path to the fonts directory inside Android Studio.
    Typically: `<absolute path to android-studio>/plugins/design-tools/resources/layoutlib/data/fonts`
*   `screenshots` (Array of Objects, required): List of screenshots to render.

### Screenshot Object Format

Each screenshot object in the `screenshots` array has the following fields:

*   `previewType` (String, optional): Type of preview. Can be `COMPOSE` (default) or `WEAR_TILE`.
*   `methodFQN` (String, required): Fully qualified name of the Composable function or preview method.
*   `previewId` (String, required): A unique identifier for this preview. Used in the output filename.
*   `previewParams` (Map of String to String, optional): Parameters passed to the Preview annotation (e.g., `widthDp`, `heightDp`, `locale`).
*   `methodParams` (Array of Maps, optional, Compose only): Parameters for the method itself (if parameterized).
*   `previewWrapperFqn` (String, optional): Fully qualified name of a wrapper function if applicable.

### Example Input JSON

```json
{
  "layoutlibPath": "<absolute path to android-studio>/plugins/design-tools/resources/layoutlib",
  "fontsPath": "<absolute path to android-studio>/plugins/design-tools/resources/layoutlib/data/fonts",
  "outputFolder": "<absolute path to the Android project root>/app/build/rendered-screenshots",
  "metaDataFolder": "<absolute path to the Android project root>/app/build/intermediates/preview-metadata",
  "classPath": [
    "<absolute path to the Android project root>/app/build/tmp/kotlin-classes/debug",
    "/absolute/path/to/gradle/cache/caches/modules-2/files-2.1/androidx.compose.ui/ui-tooling/1.5.0/..."
  ],
  "projectClassPath": [
    "<absolute path to the Android project root>/app/build/tmp/kotlin-classes/debug"
  ],
  "namespace": "com.example.myapp",
  "resourceApkPath": "<absolute path to the Android project root>/app/build/outputs/apk/debug/app-debug.apk",
  "resultsFilePath": "<absolute path to the Android project root>/app/build/rendered-screenshots/results.json",
  "screenshots": [
    {
      "previewType": "COMPOSE",
      "methodFQN": "com.example.myapp.MainActivityKt.DefaultPreview",
      "previewId": "com.example.myapp.MainActivityKt.DefaultPreview_screenshot",
      "previewParams": {
        "showBackground": "true",
        "apiLevel": "33"
      }
    }
  ]
}
```

## Output

When execution completes, the tool:

1.  Saves the rendered PNG files in the `outputFolder`, organized by package structure.
2.  Prints the absolute paths of successfully rendered images to **stdout**.
3.  Prints error messages to **stderr** for any failed renders.
4.  Writes a detailed JSON results file to `resultsFilePath`.
5.  Exits with code `0` if all renders succeeded, or non-zero if there were errors.

### Example Stdout

```
/path/to/output/com/example/myapp/DefaultPreview_screenshot_0.png
```

### Example Results JSON

```json
{
  "screenshotResults": [
    {
      "previewId": "com.example.myapp.MainActivityKt.DefaultPreview_screenshot",
      "methodFQN": "com.example.myapp.MainActivityKt.DefaultPreview",
      "imagePath": "com/example/myapp/DefaultPreview_screenshot_0.png"
    }
  ]
}
```
