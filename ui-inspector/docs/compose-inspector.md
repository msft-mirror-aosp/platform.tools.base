# Developing against a local Compose inspector

By default the UI inspector downloads the Compose inspector jar from Google
Maven, matching the app's `androidx.compose.ui:ui` version. To test changes to
the Compose inspector itself, build it from an androidx-main checkout and pass
the resulting jar to the UI inspector with `--compose-inspector`.

## Conventions

- `ANDROIDX_MAIN_DIR` is an environment variable holding the **absolute** path
  to the root of an androidx-main checkout (the directory containing
  `frameworks/support`). Developers are encouraged to set it in their shell
  profile, e.g. `export ANDROIDX_MAIN_DIR=<path>` in `~/.zshenv` (preferred
  over `~/.zshrc`, which non-interactive shells such as agents don't read).
- Gradle commands are run from `$ANDROIDX_MAIN_DIR/frameworks/support`.
- Build outputs end up in `$ANDROIDX_MAIN_DIR/out`.

## Steps

### 1. Locate or download androidx-main

The checkout is large and slow to clone, so reuse an existing one whenever
possible.

- If `$ANDROIDX_MAIN_DIR` is set and contains `frameworks/support`, use it. Do
  **not** clone again. Optionally update it with `repo sync -c -j8`.
- Otherwise, ask the user whether they already have a local checkout and where
  it is. Only clone if they don't have one:
  ```sh
  mkdir androidx-main && cd androidx-main
  repo init -u https://android.googlesource.com/platform/manifest -b androidx-main --partial-clone --clone-filter=blob:limit=10M
  repo sync -c -j8
  ```
  Then suggest the user add `export ANDROIDX_MAIN_DIR=<path>` to their shell
  profile so the checkout is reused next time.

### 2. Build Compose

```sh
cd "$ANDROIDX_MAIN_DIR/frameworks/support"
ANDROIDX_PROJECTS=COMPOSE ./gradlew createArchive
```

This publishes the Compose libraries to the local Maven repository at
`$ANDROIDX_MAIN_DIR/out/repository`. The build can take 25+ minutes; later
builds are incremental.

### 3. Build the Compose inspector jar

```sh
cd "$ANDROIDX_MAIN_DIR/frameworks/support"
ANDROIDX_PROJECTS=COMPOSE ./gradlew copyInspectionArtifacts
```

This creates `$ANDROIDX_MAIN_DIR/out/dist/inspection/compose-ui-inspection.jar`.

### 4. Point the test app at the locally built Compose

The inspector jar must match the Compose version the app runs with. In the
Android project being inspected:

1. Find the version that was just built:
   ```sh
   grep '^COMPOSE =' "$ANDROIDX_MAIN_DIR/frameworks/support/libraryversions.toml"
   ```
   (`out/repository/androidx/compose/ui/ui` can also contain versions from
   older builds, so don't rely on listing it.)
2. Add the local repository to the project's repositories in
   `settings.gradle.kts`, under `dependencyResolutionManagement.repositories`.
   List it **first**, so it wins over Google Maven if the same version is
   also published there:
   ```kotlin
   maven { url = uri("${System.getenv("ANDROIDX_MAIN_DIR")}/out/repository") }
   ```
3. Set `androidx.compose.ui:ui` (and the other Compose dependencies) to that
   version, then rebuild and install the app.
4. Start the app. `dump-ui` requires the app to be running.

### 5. Run the UI inspector with the local jar

Pass the jar with `--compose-inspector` instead of letting the UI inspector
download it from Maven. Use an absolute path, since `bazel run` does not run
from the workspace directory:

```sh
tools/base/bazel/bazel run //tools/base/ui-inspector/cli:dump-ui -- dump-ui \
  --package=<pkg> --device=<serial> \
  --compose-inspector="$ANDROIDX_MAIN_DIR/out/dist/inspection/compose-ui-inspection.jar"
```

The CLI should print `Compose detected: <version>` and
`Compose Inspector successfully loaded on agent!`. The first `bazel run` on a
cold cache can take ~15 minutes.

To confirm the local jar is the one being loaded, add a temporary log to
`ComposeLayoutInspector`'s `init` block (in
`compose/ui/ui-inspection/src/main/java/androidx/compose/ui/inspection/ComposeLayoutInspector.kt`),
for example `Log.i(LOG_TAG, "local build")`, then check
`adb logcat -s ComposeInspector` after the dump.

## Iterating

After changing the Compose inspector code, repeat step 3 and re-run step 5.
There is no need to restart the app: the local jar's contents are part of the
agent's socket digest, so a changed jar starts a fresh inspector server
instead of reusing the old one. Repeat steps 2 and 4 only if the Compose
libraries themselves changed.
