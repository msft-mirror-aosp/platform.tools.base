# TypeScript, Node, and VSIX Support

This document describes the TypeScript, Node.js, and VS Code extension (VSIX) packaging
architecture in Android Studio's Bazel build system.

---

## 1. Architecture Overview

TypeScript and Node.js support is integrated into the Bazel build system using modern Bzlmod rules:

* **`@rules_nodejs`**: Manages a pinned, hermetic Node.js runtime toolchain (Node 20.14.0).
  Developers do not need Node.js or npm installed locally.
* **`@aspect_rules_js`**: Provides hermetic npm package management using `pnpm` lockfiles,
  translating npm packages into isolated Bazel targets and generating virtual `node_modules` trees.
* **`@aspect_rules_ts`**: Integrates the TypeScript compiler (`tsc`) via `ts_project` rules for
  fast, incremental, and sandboxed TypeScript compilation.
* **Packaging Tooling (`vsce`)**: Packages the compiled extension, manifest, and assets into a
  standard `.vsix` bundle using hermetic `vsce` via `js_run_binary`. (Windows CI currently skips
  these targets via `tags = ["no_windows"]`).

Dependencies across multiple projects and repositories are organized as a **centralized pnpm
workspace** rooted in `tools/base/bazel/`.

---

## 2. File Organization

### Central Workspace Files (`tools/base/bazel/`)

| File | Purpose |
| :--- | :--- |
| **`tools/base/bazel/.npmrc`** | Defines the default npm registry (`https://registry.npmjs.org/`) passed to Bazel's `npm.npm_translate_lock`. |
| **`tools/base/bazel/package.json`** | The root workspace manifest. Declares `pnpm.onlyBuiltDependencies` and workspace-wide package overrides. |
| **`tools/base/bazel/pnpm-workspace.yaml`** | Defines member package locations across repositories (e.g. `../../vendor/google/android-vsix`). |
| **`tools/base/bazel/pnpm-lock.yaml`** | The central multi-package lockfile pinning exact versions and integrity checksums for all npm packages in the workspace. |
| **`tools/base/bazel/BUILD`** | Calls `npm_link_all_packages(name = "node_modules")` for the root package and exposes the `//tools/base/bazel:pnpm_update` target. |
| **`tools/base/bazel/toplevel.MODULE.bazel`** | Registers `@aspect_bazel_lib` (2.22.5), `@rules_nodejs`, `@aspect_rules_js`, `@aspect_rules_ts`, Node 20.14.0, `pnpm` 9.7.0, and `npm.npm_translate_lock`. |
| **`tools/base/bazel/common.bazelrc`** | Sets `--@aspect_rules_ts//ts:skipLibCheck=honor_tsconfig` so `ts_project` adheres to local `tsconfig.json` settings. |
| **`tools/base/bazel/ci/data/downloader.cfg`** | Configures network proxy and airlock rules for CI, allowlisting `registry.npmjs.org`, `us-npm.pkg.dev`, and `nodejs.org`. |

### Extension Project Files (e.g. `tools/vendor/google/android-vsix/`)

| File | Purpose |
| :--- | :--- |
| **`package.json`** | Extension manifest (defining `name`, `publisher`, `engines`, `main`, `contributes`, `commands`) and `devDependencies`. |
| **`BUILD.bazel`** | Defines targets for linking npm packages (`:node_modules`), compiling TypeScript (`:compile`), structuring the package (`:extension_package`), and packaging (`:package_vsix`). |
| **`tsconfig.json`** | TypeScript compiler settings (`Node16` module resolution, target `es2022`, strict typing, `inlineSources`). |
| **`src/extension.ts`** | Extension TypeScript entry point (`activate` and `deactivate` handlers). |
| **`.vscode/launch.json`** | Interactive F5 debug configuration for the VS Code Extension Development Host with source map location overrides. |
| **`.vscode/tasks.json`** | Defines pre-launch task running `tools/base/bazel/bazel build //tools/vendor/google/android-vsix:extension_package`. |
| **`.gitignore`** | Ignores untracked outputs (`node_modules/`, `out/`, `.vscode-test/`). |

---

## 3. Multi-Repo Structure and Path Resolution

In an Android Studio `repo` checkout, the workspace consists of multiple Git repositories placed
side-by-side:

```
<repo_root>/
├── tools/
│   ├── base/                 <-- Git repo "tools/base"
│   │   └── bazel/
│   │       ├── .npmrc
│   │       ├── package.json
│   │       ├── pnpm-workspace.yaml
│   │       └── pnpm-lock.yaml
│   └── vendor/
│       └── google/           <-- Git repo "tools/vendor/google"
│           └── android-vsix/
│               ├── package.json
│               └── BUILD.bazel
```

### Path Mapping in `pnpm-workspace.yaml`
From `tools/base/bazel/`:
* `..` &rarr; `tools/base`
* `../..` &rarr; `tools`
* `../../vendor/google/android-vsix` &rarr; `tools/vendor/google/android-vsix`

`aspect_rules_js` evaluates the importer path `../../vendor/google/android-vsix` relative to the
lockfile package `tools/base/bazel`, producing the normalized Bazel package label
`//tools/vendor/google/android-vsix`.

---

## 4. Build Artifacts in `bazel-bin`

When building targets such as `//tools/vendor/google/android-vsix:package_vsix`, Bazel structures
the outputs in `bazel-bin`:

```
bazel-bin/
├── tools/
│   ├── base/
│   │   └── bazel/
│   │       └── .aspect_rules_js/
│   │           └── node_modules/          <-- Virtual store (unpacked npm packages)
│   │               ├── @types+vscode@1.125.0/
│   │               ├── typescript@5.0.4/
│   │               └── vsce@1.103.1/
│   └── vendor/
│       └── google/
│           └── android-vsix/
│               ├── node_modules/          <-- Package symlinks into the virtual store
│               │   ├── @types/
│               │   │   ├── node -> ...
│               │   │   └── vscode -> ...
│               │   ├── typescript -> ...
│               │   └── vsce -> ...
│               ├── out/                   <-- Compiled TypeScript (:compile)
│               │   ├── extension.js
│               │   ├── extension.d.ts
│               │   └── extension.js.map
│               ├── extension_package/     <-- Staged package directory (:extension_package)
│               └── android-vsc-0.0.1.vsix <-- Final packaged VSIX bundle (:package_vsix)
```

---

## 5. Dependency Management Workflow

> **Note: No local Node.js or pnpm installation required!**
> Bazel provides a convenient, hermetic updater target: `//tools/base/bazel:pnpm_update`.
> Running this target automatically executes the Bazel-managed `pnpm` binary with
> `--dir tools/base/bazel install --lockfile-only`.

### Updating Existing Dependencies (e.g. Upgrading `@types/vscode`)
1. Edit the version in `tools/vendor/google/android-vsix/package.json`.
2. From the repo root, re-generate the lockfile:
   ```bash
   tools/base/bazel/bazel run //tools/base/bazel:pnpm_update
   ```
3. Commit the updated `tools/base/bazel/pnpm-lock.yaml` and
   `tools/vendor/google/android-vsix/package.json`.

### Adding a New Dependency to an Existing Project
1. Add the package to `devDependencies` or `dependencies` in
   `tools/vendor/google/android-vsix/package.json`.
2. Re-generate the lockfile:
   ```bash
   tools/base/bazel/bazel run //tools/base/bazel:pnpm_update
   ```
3. In `tools/vendor/google/android-vsix/BUILD.bazel`, add `:node_modules/<package_name>` to the
   `deps` attribute of the target (e.g. `ts_project`).

### Adding a New VSIX Project to the Workspace
1. Create the new project directory (e.g. `tools/vendor/other/my-vsix/`) containing:
   * `package.json` (extension metadata + dependencies)
   * `BUILD.bazel` (with `npm_link_all_packages(name = "node_modules")` and `ts_project`)
   * `tsconfig.json` and `src/`
2. Add the relative path to `tools/base/bazel/pnpm-workspace.yaml`:
   ```yaml
   packages:
     - '../../vendor/google/android-vsix'
     - '../../vendor/other/my-vsix'
   ```
3. Re-generate the lockfile:
   ```bash
   tools/base/bazel/bazel run //tools/base/bazel:pnpm_update
   ```

---

## 6. Building and Debugging

### Build Commands
From the workspace root:

* **Compile TypeScript:**
  ```bash
  tools/base/bazel/bazel build //tools/vendor/google/android-vsix:compile
  ```
* **Package VSIX:**
  ```bash
  tools/base/bazel/bazel build //tools/vendor/google/android-vsix:package_vsix
  ```

### Interactive Debugging in VS Code
1. Open the extension directory (`tools/vendor/google/android-vsix`) in VS Code.
2. Press **`F5`** (or select **"Run Extension (Bazel)"** in the Run & Debug panel).
3. VS Code triggers the `preLaunchTask` (`Bazel Compile`) and launches an **Extension Development
   Host** window with source maps attached to `bazel-bin/tools/vendor/google/android-vsix/out/`.
4. Set breakpoints directly in `src/extension.ts` in your main VS Code editor.
