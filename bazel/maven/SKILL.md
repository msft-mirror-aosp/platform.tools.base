---
name: maven-dependency-upgrade
description: |
  Use this skill when upgrading or updating third-party Maven dependency versions across the studio-main monorepo, regenerating Bazel BUILD.maven targets, syncing local prebuilts, or resolving Bzlmod rules_android lockfile conflicts and NOTICE files.
  Use when: Upgrading Maven artifacts in artifacts.bzl or dependencies.properties, running maven_fetch.sh or local_maven_repository_generator, or fixing missing NOTICE file errors and Bzlmod JSON syntax issues in rules_android.
  Don't use when: Upgrading Gradle plugins or non-Maven external dependencies that do not use artifacts.bzl or local_maven_repository_generator.
---

# Upgrading Maven Dependencies in `studio-main`

This skill provides step-by-step instructions for updating third-party Maven dependency versions across the `studio-main` monorepo, regenerating Bazel definitions (`BUILD.maven`), updating Gradle dependency properties, handling `NOTICE` files, and synchronizing Bzlmod external lockfiles.

## When to use this skill
- Use this when upgrading external Maven libraries pinned in `tools/base/bazel/maven/artifacts.bzl`, exact test fixtures / multi-version plugins in `tools/base/bazel/maven/data.bzl`, or `tools/buildSrc/base/dependencies.properties`.
- Use this when running `local_maven_repository_generator` (`maven_fetch.sh`) to download prebuilt artifacts into `prebuilts/tools/common/m2/repository/`.
- This is helpful for resolving Bzlmod lockfile conflicts in `rules_android_lock_maven_install.patch` or diagnosing missing `NOTICE` file errors (`Creating symlink ..._license.NOTICE failed`) when building SDK packages.

## How to use it

### 1. Update Version Pins in Configuration Files
When upgrading a Maven dependency (such as `org.bouncycastle:bcprov-jdk18on` or `androidx.navigation:navigation-safe-args-gradle-plugin`), first determine whether the dependency should be defined in `artifacts.bzl` (`ARTIFACTS`) or `data.bzl` (`DATA`).

As explained in [`tools/base/bazel/README.md`](file:///usr/local/google/home/hmehmed/studio-main/tools/base/bazel/README.md):

#### Choosing the Right File: `artifacts.bzl` vs. `data.bzl` (`README.md` Guidelines)
- **`data.bzl` (`DATA` / `_CLASS_JARS` / `_SOURCE_JARS`)**: In most cases, dependencies should be added to `DATA`. Specifically:
  - If the artifact is only used in the `data` section of rules (e.g., it is used as a Gradle dependency artifact or plugin in tests).
  - If the artifact is going to be used to build Android Studio (i.e., added to an IntelliJ IDEA library either in `.idea/libraries/*.xml`, or an inline library in `*.iml` files). Note that the Android Studio build case requires running `iml_to_build` (`bazel run //tools/base/bazel:iml_to_build`), which generates a `java_import` rule that wraps the files listed in the library.
  - Dependencies in `data.bzl` bypass version conflict resolution (`+` prefix / `noresolveCoords`), meaning `local_maven_repository_generator` generates **only versioned rules** (`@maven//:group.artifact_version`). No unversioned `@maven//:group.artifact` alias is created. This allows exact pinned test fixtures, multi-version snapshot integration tests (`2.3.1`, `2.5.3`, `2.6.0`, `2.9.8`), and Gradle plugins to coexist without conflict resolution overriding them.
- **`artifacts.bzl` (`ARTIFACTS`)**: If you need to use the artifact directly in the `deps` or `runtime_deps` section of a `(maven|kotlin|java)_library` rule, then add it to `ARTIFACTS`. This performs all the same work that adding the library to `DATA` would do, but additionally resolves and validates the entire dependency graph, and generates an unversioned alias/import rule (`@maven//:group.artifact` without the version suffix) which can be used in `deps` or `runtime_deps` and models the resolved transitive dependencies.

1. **Bazel Standard Artifacts (`artifacts.bzl`)**
   Locate `tools/base/bazel/maven/artifacts.bzl` and update the coordinate list under `ARTIFACTS`:
   ```python
   ARTIFACTS = [
       ...
       # Update coordinate version string
       "org.bouncycastle:bcprov-jdk18on:1.80.2",
       "org.bouncycastle:bcpkix-jdk18on:1.80.2",
       ...
   ]
   ```
2. **Bazel Exact/Multi-Version Fixtures & Plugins (`data.bzl`)**
   Locate `tools/base/bazel/maven/data.bzl` and add or update coordinates under `_CLASS_JARS` (or `_SOURCE_JARS`):
   ```python
   _CLASS_JARS = [
       ...
       "androidx.navigation:navigation-safe-args-gradle-plugin:2.9.8",
       ...
   ]
   ```
3. **Gradle Build Properties (`dependencies.properties`)**
   If the dependency is also used during `buildSrc` or Gradle builds, locate `tools/buildSrc/base/dependencies.properties` and update the version strings so Gradle builds stay aligned with Bazel:
   ```properties
   bouncycastle_pkix = org.bouncycastle:bcpkix-jdk18on:1.80.2
   bouncycastle_prov = org.bouncycastle:bcprov-jdk18on:1.80.2
   ```

### 2. Synchronize Prebuilts & Regenerate `BUILD.maven`
After modifying `artifacts.bzl` or `data.bzl`, execute the repository generator script to fetch the new JARs/POMs and regenerate `tools/base/bazel/maven/BUILD.maven`:

```bash
cd tools/base/bazel/maven
./maven_fetch.sh
```
Or via `bazel`:
```bash
bazel run //tools/base/bazel:local_maven_repository_generator
```

> [!WARNING]
> **Cleaning up unused dependencies (`maven_clean.sh`)**: As noted in [`README.md`](file:///usr/local/google/home/hmehmed/studio-main/tools/base/bazel/README.md), if you remove or modify artifact lists, you may run `tools/base/bazel/maven/maven_clean.sh` before `maven_fetch.sh` to clean up orphaned prebuilts.
> **Important**: If you run `maven_clean.sh`, you **must** follow it up with `maven_fetch.sh`. This is crucial because `maven_clean.sh` can be overly aggressive and remove an entire dependency if it considers the JAR artifact to be orphaned, even when the associated `sources` artifact is still explicitly required in `DATA` or `ARTIFACTS`.

What `local_maven_repository_generator` does:
- Resolves all coordinates listed in `artifacts.bzl` (`ARTIFACTS`) plus test data fixtures (`DATA`).
- Downloads binary `.jar` and `.pom` files (and sources if available) into `prebuilts/tools/common/m2/repository/<group>/<artifact>/<version>/`.
- Rewrites `tools/base/bazel/maven/BUILD.maven` by creating versioned targets (e.g., `org.bouncycastle.bcprov-jdk18on_1.80.2`) and updating unversioned alias/import targets (`org.bouncycastle.bcprov-jdk18on`) to point to the newly downloaded version (`_1.80.2`).

### 3. Update Bzlmod Lockfile Patches (`rules_android`)
External Bzlmod dependencies (such as `@rules_android`) carry their own pinned Maven install lockfiles (`rules_android_maven_install.json`) that are patched locally via `tools/base/bazel/bzlmod/rules_android_lock_maven_install.patch`.

When upgrading core libraries pinned in `rules_android_lock_maven_install.patch`:
1. Calculate the SHA-256 checksums of the newly fetched JAR files inside `prebuilts/tools/common/m2/repository/`:
   ```bash
   sha256sum prebuilts/tools/common/m2/repository/org/bouncycastle/bcprov-jdk18on/1.80.2/*.jar
   ```
2. Open `tools/base/bazel/bzlmod/rules_android_lock_maven_install.patch` and update:
   - Any `conflict_resolution` overrides (`org.bouncycastle:bcprov-jdk18on:1.77` -> `org.bouncycastle:bcprov-jdk18on:1.80.2`).
   - Pinned artifact entries (`"version": "1.80.2"`) along with the `shasums.jar` value matching the new SHA-256 hash.
3. **Strict JSON Syntax Rules**
   Ensure that patched sections strictly adhere to JSON specification. Specifically, **no trailing commas** (`"shasums": { ... }, }`) are permitted before closing object braces (`}`). Starlark's `json.decode(lock_file_content)` in `rules_jvm_external` (`maven_impl`) will fail with `unexpected character "}"` during module evaluation if a trailing comma is present.

### 4. Handle Notice and License Metadata (`NOTICE` Files)
When Bazel compiles SDK packages or bundles `maven_import` targets (e.g., `//tools/base/sdklib:commandlinetools`), `maven.bzl` automatically generates a license metadata target (`<name>_license`) expecting a `NOTICE` file in the artifact directory (`repository/<group>/<artifact>/<version>/NOTICE`). If missing, Bazel builds fail with:
```
Creating symlink ..._license.NOTICE failed: missing input file '@@+_repo_rules2+maven//:repository/<group>/<artifact>/<version>/NOTICE'
```

How notice resolution works and how to fix missing notices:
1. **Local Repository Generator Behavior**: When `maven_fetch.sh` (`LocalMavenRepositoryGenerator.java`) downloads new artifacts, `copyNotice(Artifact artifact)` walks upwards from the downloaded directory (`<version>/`) to `prebuilts/tools/common/m2/repository/` looking for `"LICENSE"`, `"LICENSE.txt"`, `"NOTICE"`, or `"NOTICE.txt"`.
2. **Upstream Missing Notices**: Many third-party artifacts on Maven Central do not serve standalone `NOTICE` files in their directory structure. Furthermore, if previous versions stored `NOTICE` inside individual legacy folders (`1.79/NOTICE`) rather than in a shared parent directory (`<group>/<artifact>/NOTICE` or `<group>/NOTICE`), `copyNotice` will not find a parent file to copy.
3. **Resolution Strategy**:
   - For any new version lacking a `NOTICE` file, copy the `NOTICE` file from a prior version folder into the new version folder:
     ```bash
     cp prebuilts/tools/common/m2/repository/<group>/<artifact>/<old_version>/NOTICE prebuilts/tools/common/m2/repository/<group>/<artifact>/<new_version>/NOTICE
     ```
   - **Best Practice**: Also place a copy of the `NOTICE` file in the parent group directory (`prebuilts/tools/common/m2/repository/<group>/NOTICE`). This ensures that all future version upgrades processed by `maven_fetch.sh` automatically detect and copy the `NOTICE` file without manual intervention.

### 5. Audit and Clean Up Legacy Version References
After regenerating `BUILD.maven`, check whether any `BUILD` files or custom scripts hardcode legacy version suffixes (`_1.79` or `_2.5.3`):
```bash
grep -rn "bcprov-jdk18on_1.79" tools/
grep -rn "navigation-safe-args-gradle-plugin_2.5.3" tools/
```

- **For dependencies in `artifacts.bzl`**: In general, `BUILD` files should reference the unversioned alias `@maven//:org.bouncycastle.bcprov-jdk18on` so future upgrades propagate automatically without modifying individual `BUILD` targets.
- **For dependencies in `data.bzl`**: Because `data.bzl` dependencies bypass conflict resolution (`noresolveCoords`), no unversioned alias is generated. All downstream `BUILD` files depend directly on the versioned rule (`@maven//:androidx.navigation.navigation-safe-args-gradle-plugin_2.9.8`). When upgrading a `data.bzl` dependency, you **must** locate all downstream `BUILD` files using `grep_search` and manually update their `@maven//:..._oldversion` target strings to `@maven//:..._newversion`.

### 6. Build Verification, Multi-Repo CI Coordination & Version Control

1. **Test Core Targets**
   Verify compilation and runtime behavior of affected packages using `bazel`:
   ```bash
   bazel build //tools/base/sdk-common/... //tools/base/bazel/... //tools/base/sdklib:commandlinetools
   bazel test //tools/base/sdk-common:tests //tools/base/bazel:local_maven_repository_generator_tests
   ```
2. **Multi-Repo CI Topic Coordination**
   In the `studio-main` monorepo structure, `tools/base` and `prebuilts/tools/common/m2/repository` are maintained as **separate Git repositories**.
   - If a CI build is triggered for a CL in `tools/base` without testing the corresponding changes in `prebuilts/tools/common/m2/repository`, the CI worker checks out the `goog/studio-main` (`HEAD`) branch for `prebuilts`.
   - If the new prebuilt `.jar`, `.pom`, and `NOTICE` files exist only on your local branch or unmerged prebuilts CL, the `tools/base` CI job will fail with `missing input file` or unresolved dependencies.
   - **Rule**: Whenever updating both `tools/base` and `prebuilts/tools/common/m2/repository`, upload both CLs using the exact same **Gerrit Topic** (`repo upload -t <topic_name>`). This informs Critique/CI to check out and test both CLs together.
3. **Git Tracked Files Checklist**
   When committing dependency updates across repositories, ensure the following files are tracked and staged:
   - `tools/base/bazel/maven/artifacts.bzl`
   - `tools/buildSrc/base/dependencies.properties`
   - `tools/base/bazel/maven/BUILD.maven`
   - `tools/base/bazel/bzlmod/rules_android_lock_maven_install.patch`
   - `prebuilts/tools/common/m2/repository/<group>/<artifact>/<version>/` (all newly downloaded `.jar`, `.pom`, `.sha1`, `NOTICE`, etc. files)
