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
- Use this when upgrading external Maven libraries pinned in `tools/base/bazel/maven/artifacts.bzl` or `tools/buildSrc/base/dependencies.properties`.
- Use this when running `local_maven_repository_generator` (`maven_fetch.sh`) to download prebuilt artifacts into `prebuilts/tools/common/m2/repository/`.
- This is helpful for resolving Bzlmod lockfile conflicts in `rules_android_lock_maven_install.patch` or diagnosing missing `NOTICE` file errors (`Creating symlink ..._license.NOTICE failed`) when building SDK packages.

## How to use it

### 1. Update Version Pins in Configuration Files
When upgrading a Maven dependency (such as `org.bouncycastle:bcprov-jdk18on`), update the central pinned version across both Bazel and Gradle configurations:

1. **Bazel Artifacts (`artifacts.bzl`)**
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
2. **Gradle Build Properties (`dependencies.properties`)**
   Locate `tools/buildSrc/base/dependencies.properties` and update the version strings so Gradle builds (`buildSrc`) stay aligned with Bazel:
   ```properties
   bouncycastle_pkix = org.bouncycastle:bcpkix-jdk18on:1.80.2
   bouncycastle_prov = org.bouncycastle:bcprov-jdk18on:1.80.2
   ```

### 2. Synchronize Prebuilts & Regenerate `BUILD.maven`
After modifying `artifacts.bzl`, execute the repository generator script to fetch the new JARs/POMs and regenerate `tools/base/bazel/maven/BUILD.maven`:

```bash
cd tools/base/bazel/maven
./maven_fetch.sh
```
Or via `bazel`:
```bash
bazel run //tools/base/bazel:local_maven_repository_generator
```

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
Check if any `BUILD` files or custom scripts hardcode legacy version suffixes (`_1.79`):
```bash
grep -rn "bcprov-jdk18on_1.79" tools/
```
In general, `BUILD` files should reference the unversioned alias `@maven//:org.bouncycastle.bcprov-jdk18on` so upgrades via `artifacts.bzl` propagate automatically without modifying individual `BUILD` targets.

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
