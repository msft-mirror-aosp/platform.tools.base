# Detailed Line-by-Line Breakdown of Aspect Rules Windows & RBE Patches

## Executive Summary & Purpose of the Changes

This document explains the syntax, runtime semantics, and environmental context (Local Windows, Windows RBE, Linux) for all modifications made to `aspect_bazel_lib` (v2.22.5) and `aspect_rules_js` (v2.0.0) in commit `799b5727ac29cdb1fb320e4fe9d0bcc317c77a76`.

### Why Were These Changes Needed?
Building TypeScript and Node.js projects (such as `//tools/vendor/google/android-vsix:compile`) on Windows encountered four distinct failure modes across local and remote build environments:

1. **RBE Runfiles Resolution (Missing Manifest Crash):**
   * **Problem:** On local Windows, Bazel generates a text file (`MANIFEST`) mapping abstract runfile paths to absolute paths on the local machine (`C:/users/...`). On Remote Build Execution (RBE), Bazel downloads action inputs directly into the remote worker's sandbox directory and **does not upload or generate a text manifest file**. Upstream `aspect_bazel_lib` hardcoded `RUNFILES_MANIFEST_ONLY=1` and aborted with `ERROR: Manifest file does not exist.` whenever a manifest was absent.
   * **Fix:** Updated the batch `rlocation` function to probe the physical filesystem first (`RUNFILES_DIR`, adjacent to launcher, or relative path) and treat the text manifest strictly as an optional fallback.

2. **MSYS2 Bash Subshell Path Incompatibility (`C:\...` vs `/c/...`):**
   * **Problem:** When `aspect_bazel_lib` invokes shell scripts on Windows, it uses a native `.bat` launcher that executes `bash.exe -c "<script> <args>"`. Passing raw Windows drive paths (`C:\...` or `C:/...`) inside `bash -c` strings causes MSYS2 / Git Bash to misinterpret `C:` as a command or fail path translation in non-interactive subshells.
   * **Fix:** Added batch logic to normalize backslashes and convert drive letters (`C:/path/...` $\rightarrow$ `/c/path/...`) before invoking `bash.exe`.

3. **Windows NTFS Directory Symlinks vs. File Symlinks:**
   * **Problem:** `aspect_rules_js` uses a pnpm-style layout where `node_modules` contains symlinks pointing to package directories in a central store. On Windows NTFS, file symlinks (`0`) and directory symlinks (`1`) are distinct kernel types. Bazel's `ctx.actions.symlink` creates file symlinks by default unless `target_type = "directory"` is specified. This caused Node.js and TypeScript (`tsc`) to fail with `ERROR_DIRECTORY` when traversing package folders.
   * **Fix:** Explicitly passed `target_type = "directory"` in `npm/private/utils.bzl`.

4. **Bash Launcher Runfiles Discovery:**
   * **Problem:** Upstream `aspect_rules_js`'s bash runner (`bash.bzl`) omitted checks for standard `RUNFILES_DIR` / `RUNFILES` environment variables and Windows `.bat.runfiles` directories.
   * **Fix:** Added fallback checks for `RUNFILES_DIR`, `RUNFILES`, and `<binary>.bat.runfiles`.

---

## 1. `aspect_bazel_lib_windows_rbe.patch`

Target File: `lib/windows_utils.bzl` (in `aspect_bazel_lib 2.22.5`)

---

### **Section A: Full `rlocation` Function (Before vs. After)**

The `rlocation` subroutine maps an abstract runfile path (passed in `%~1`, e.g. `_main/tools/vendor/google/android-vsix/vsce_compiled_/vsce_compiled`) to a real file path on disk, storing the result in the variable named in `%~2`.

#### **Full `rlocation` Code BEFORE (Upstream `aspect_bazel_lib`)**

```bat
rem Usage of rlocation function:
rem        call :rlocation <runfile_path> <abs_path>
rem        The rlocation function maps the given <runfile_path> to its absolute
rem        path and stores the result in a variable named <abs_path>.
rem        This function fails if the <runfile_path> doesn't exist in mainifest
rem        file.
:: Start of rlocation
goto :rlocation_end
:rlocation
if "%~2" equ "" (
  echo>&2 ERROR: Expected two arguments for rlocation function.
  exit 1
)
if "%RUNFILES_MANIFEST_ONLY%" neq "1" (
  set %~2=%~1
  exit /b 0
)
if exist "%RUNFILES_DIR%" (
  set RUNFILES_MANIFEST_FILE=%RUNFILES_DIR%_manifest
)
if "%RUNFILES_MANIFEST_FILE%" equ "" (
  set RUNFILES_MANIFEST_FILE=%~f0.runfiles\MANIFEST
)
if not exist "%RUNFILES_MANIFEST_FILE%" (
  set RUNFILES_MANIFEST_FILE=%~f0.runfiles_manifest
)
set MF=%RUNFILES_MANIFEST_FILE:/=\%
if not exist "%MF%" (
  echo>&2 ERROR: Manifest file %MF% does not exist.
  exit 1
)
set runfile_path=%~1
for /F "tokens=2* usebackq" %%i in (`%SYSTEMROOT%\system32\findstr.exe /l /c:"!runfile_path! " "%MF%"`) do (
  set abs_path=%%i
)
if "!abs_path!" equ "" (
  echo>&2 ERROR: !runfile_path! not found in runfiles manifest
  exit 1
)
set %~2=!abs_path!
exit /b 0
:rlocation_end
:: End of rlocation
```

---

#### **Full `rlocation` Code AFTER (Patched for RBE & Local Windows)**

```bat
rem Usage of rlocation function:
rem        call :rlocation <runfile_path> <abs_path>
rem        The rlocation function maps the given <runfile_path> to its absolute
rem        path and stores the result in a variable named <abs_path>.
rem        This function fails if the <runfile_path> doesn't exist in mainifest
rem        file.
:: Start of rlocation
goto :rlocation_end
:rlocation
if "%~2" equ "" (
  echo>&2 ERROR: Expected two arguments for rlocation function.
  exit 1
)
:: [Local & RBE] Look for file directly in RUNFILES_DIR if set by Bazel or parent runner
if exist "%RUNFILES_DIR%\%~1" (
  set "%~2=%RUNFILES_DIR%\%~1"
  exit /b 0
)
:: [RBE] In remote execution sandboxes with flattened directory trees, files may be adjacent to the launcher
if exist "%~dp0%~nx1" (
  set "%~2=%~dp0%~nx1"
  exit /b 0
)
:: [Local & RBE] Check if the path is already valid relative to the current working directory
if exist "%~1" (
  set "%~2=%~1"
  exit /b 0
)
:: [Local] Fallback to querying the text runfiles manifest (used on Windows when symlinks are disabled)
if "%RUNFILES_MANIFEST_FILE%" neq "" (
  set MF=%RUNFILES_MANIFEST_FILE:/=\%
  if exist "!MF!" (
    set "runfile_path=%~1"
    for /F "tokens=2* usebackq" %%i in (`%SYSTEMROOT%\system32\findstr.exe /l /c:"!runfile_path! " "!MF!"`) do (
      set abs_path=%%i
    )
    if "!abs_path!" neq "" (
      set "%~2=!abs_path!"
      exit /b 0
    )
  )
)
:: [RBE & Sandboxed] Return original relative path as fallback to avoid hard abort when manifest is absent
set "%~2=%~1"
exit /b 0
:rlocation_end
:: End of rlocation
```

---

#### **Line-by-Line Explanation of the Patched `rlocation` Function**

* **Lines 1–9 (`goto :rlocation_end` / `:rlocation`):**
  * Standard batch subroutine idiom. When the batch script executes from the top, `goto :rlocation_end` jumps over the function so it only runs when explicitly invoked with `call :rlocation <target> <outvar>`.
* **Lines 10–13 (`if "%~2" equ "" (...)`):**
  * Validates that both input path (`%1`) and output variable name (`%2`) arguments were supplied.
* **Lines 14–18 (`if exist "%RUNFILES_DIR%\%~1" (...)`):**
  * **Syntax:** `if exist "<path>"` checks whether a file or folder physically exists. `%RUNFILES_DIR%` expands the environment variable. `%~1` strips surrounding quotes.
  * **Semantics:** Tests if the requested file exists under the standard Bazel runfiles directory.
  * **Environment:** **Local & RBE**. If Bazel or an invoking wrapper explicitly set `RUNFILES_DIR`, this resolves the target immediately without reading manifests.
* **Lines 19–23 (`if exist "%~dp0%~nx1" (...)`):**
  * **Syntax:**
    * `%0` = path to current batch script (`C:\sandbox\exec\launcher.bat`).
    * `%~dp0` = drive (`d`) and directory path (`p`) of `%0` (`C:\sandbox\exec\`).
    * `%~nx1` = filename (`n`) and extension (`x`) of `%1` (`copy_to_directory.sh`).
  * **Semantics:** Checks if the target script is located in the exact same directory as the `.bat` launcher.
  * **Environment:** **RBE**. In remote execution sandboxes, tool inputs are frequently downloaded directly into the same sandbox directory alongside the launcher stub.
* **Lines 24–28 (`if exist "%~1" (...)`):**
  * **Syntax:** Tests if the string in `%~1` is already a valid absolute path or valid relative path from the current working directory (`cd`).
  * **Semantics:** If the caller passed an existing path, use it immediately.
  * **Environment:** **Local & RBE**. Covers actions executing from the execution root where relative package paths resolve directly.
* **Lines 29–42 (`if "%RUNFILES_MANIFEST_FILE%" neq "" (...)`):**
  * **Syntax:** Checks that `RUNFILES_MANIFEST_FILE` is non-empty. Replaces forward slashes `/` with backslashes `\`. Uses delayed expansion `!MF!` to test if the manifest exists on disk.
  * **findstr parsing:** Runs `%SYSTEMROOT%\system32\findstr.exe /l /c:"!runfile_path! " "!MF!"` to find the exact line in the manifest and extracts the local absolute path via `tokens=2*`.
  * **Crucial Fix:** If the manifest does not exist, it safely skips manifest parsing without throwing an error or aborting.
  * **Environment:** **Local Windows**. Used when running locally without symlinks where Bazel writes a text `MANIFEST` file.
* **Lines 43–45 (`set "%~2=%~1"` / `exit /b 0`):**
  * **Semantics:** If none of the checks matched and no manifest exists, rather than crashing with `exit 1`, gracefully returns the unmapped path `%~1`.
  * **Environment:** **RBE**. Allows downstream shell interpreters to attempt execution from the sandbox root.

---

### **Section B: `create_windows_native_launcher_script`**

#### **Full Launcher Template BEFORE (Upstream `aspect_bazel_lib`)**

```bat
@echo off
SETLOCAL ENABLEEXTENSIONS
SETLOCAL ENABLEDELAYEDEXPANSION
set RUNFILES_MANIFEST_ONLY=1
{rlocation_function}
call :rlocation "{sh_script}" run_script
for %%a in ("{bash_bin}") do set "bash_bin_dir=%%~dpa"
set PATH=%bash_bin_dir%;%PATH%
set args=%*
if defined args (
  set args=!args:\=\\\\!
  set args=!args:"=\"!
)
"{bash_bin}" -c "!run_script! !args!"
```

---

#### **Full Launcher Template AFTER (Patched for RBE & Local Windows)**

```bat
@echo off
SETLOCAL ENABLEEXTENSIONS
SETLOCAL ENABLEDELAYEDEXPANSION
:: [Local & RBE] Discover RUNFILES_DIR and MANIFEST from standard launcher layout if not provided in environment
if "%RUNFILES_DIR%" equ "" (
  if exist "%~f0.runfiles" (
    set "RUNFILES_DIR=%~f0.runfiles"
  ) else if exist "%~dp0%~n0.runfiles" (
    set "RUNFILES_DIR=%~dp0%~n0.runfiles"
  )
)
if "%RUNFILES_MANIFEST_FILE%" equ "" (
  if exist "%~f0.runfiles_manifest" (
    set "RUNFILES_MANIFEST_FILE=%~f0.runfiles_manifest"
  ) else if exist "%~f0.runfiles\MANIFEST" (
    set "RUNFILES_MANIFEST_FILE=%~f0.runfiles\MANIFEST"
  )
)
{rlocation_function}
call :rlocation "{sh_script}" run_script
:: [Local & RBE] Convert Windows drive letter paths (C:\... -> /c/...) so MSYS2/Git bash interprets them in "bash -c"
set run_script=!run_script:\=/!
for %%d in (a b c d e f g h i j k l m n o p q r s t u v w x y z) do (
  if /i "!run_script:~0,2!" equ "%%d:" (
    set "run_script=/%%d!run_script:~2!"
  )
)
for %%a in ("{bash_bin}") do set "bash_bin_dir=%%~dpa"
set PATH=%bash_bin_dir%;%PATH%
set args=%*
if defined args (
  set args=!args:\=\\\\!
  set args=!args:"=\"!
)
"{bash_bin}" -c "\"!run_script!\" !args!"
```

---

#### **Line-by-Line Explanation**

1. **Removed `set RUNFILES_MANIFEST_ONLY=1`:**
   * In upstream code, setting this flag forced `rlocation` to require a `MANIFEST` file and forbid direct filesystem checks.
2. **Launcher Path Discovery Block:**
   * `%~f0` = full path to the `.bat` file (`C:\path\to\tool.bat`).
   * `%~dp0%~n0.runfiles` = `C:\path\to\tool.runfiles`.
   * Automatically discovers `.runfiles`, `.runfiles_manifest`, and `.runfiles\MANIFEST` when invoked without explicit environment variables.
3. **Drive Letter Conversion Block (`C:\...` $\rightarrow$ `/c/...`):**
   ```bat
   set run_script=!run_script:\=/!
   for %%d in (a b c d e f g h i j k l m n o p q r s t u v w x y z) do (
     if /i "!run_script:~0,2!" equ "%%d:" (
       set "run_script=/%%d!run_script:~2!"
     )
   )
   ```
   * Replaces all backslashes with `/`.
   * Loops through all 26 alphabetic drive letters.
   * `!run_script:~0,2!` extracts the first 2 characters.
   * If it equals `C:`, replaces it with `/c` and appends `!run_script:~2!` (the remainder of the path).
   * **Why needed:** MSYS2 / Git Bash expects POSIX paths (`/c/foo/bar.sh`). Passing `C:/foo/bar.sh` to `bash.exe -c` causes syntax and command parsing failures.
4. **Escaped Bash Invocation (`"{bash_bin}" -c "\"!run_script!\" !args!"`):**
   * Wraps `!run_script!` in escaped double quotes `\"` so script paths containing spaces or special characters are safely quoted inside the `bash -c` command string.

---

## 2. `aspect_rules_js_windows_symlinks.patch`

Target Files: `js/private/bash.bzl` & `npm/private/utils.bzl` (in `aspect_rules_js 2.0.0`)

---

### **Section A: `js/private/bash.bzl`**

```bash
<<<<<<<< BEFORE (Upstream aspect_rules_js)
    if [ "${RUNFILES_MANIFEST_FILE:-}" ]; then
        if [ -e "$RUNFILES_MANIFEST_FILE" ]; then
            RUNFILES_MANIFEST_ONLY=1
        fi
    elif [ -d "$0.runfiles" ]; then
        RUNFILES="$0.runfiles"
    fi
    ...
    if [ -e "$self.runfiles" ]; then
        RUNFILES="$self.runfiles"
        break
    fi
======== AFTER (Patched for Windows & RBE)
    if [ "${RUNFILES_MANIFEST_FILE:-}" ]; then
        if [ -e "$RUNFILES_MANIFEST_FILE" ]; then
            RUNFILES_MANIFEST_ONLY=1
        fi
    # [Local & RBE] Discover runfiles from standard RUNFILES_DIR or RUNFILES environment variables exported by Bazel
    elif [ "${RUNFILES_DIR:-}" ]; then
        RUNFILES=$(_normalize_path "$RUNFILES_DIR")
    elif [ "${RUNFILES:-}" ]; then
        RUNFILES=$(_normalize_path "$RUNFILES")
    fi
    ...
    if [ -e "$self.runfiles" ]; then
        RUNFILES="$self.runfiles"
        break
    fi
    # [Local] On Windows, binary targets have .bat wrappers where runfiles are placed under <binary>.bat.runfiles
    if [ -e "$self.bat.runfiles" ]; then
        RUNFILES="$self.bat.runfiles"
        break
    fi
>>>>>>>>
```

* **`RUNFILES_DIR` Discovery:** `${RUNFILES_DIR:-}` safely expands the variable without `set -u` unbound errors and sets `$RUNFILES`.
* **`.bat.runfiles` Discovery:** Checks if the runfiles folder was named with the Windows launcher extension `<binary>.bat.runfiles`.

---

### **Section B: `npm/private/utils.bzl`**

```bzl
<<<<<<<< BEFORE (Upstream aspect_rules_js)
    ctx.actions.symlink(
        output = symlink,
        target_path = relative_file(target_path, symlink.path),
    )
======== AFTER (Patched for Windows Directory Symlinks)
    ctx.actions.symlink(
        output = symlink,
        target_path = relative_file(target_path, symlink.path),
        target_type = "directory",
    )
>>>>>>>>
```

* **`target_type = "directory"`:**
  * Instructs Bazel to create an **NTFS Directory Symlink** (`SYMBOLIC_LINK_FLAG_DIRECTORY = 1`) on Windows.
  * Without this, Bazel created file symlinks for npm package directories, causing Node.js and `tsc` to fail with `ERROR_DIRECTORY` when traversing `node_modules`.

---

## 3. Comprehensive Environmental Summary Matrix

| Modification | File | Syntax / Mechanism | Local Windows | Windows RBE | Linux (Local & RBE) |
| :--- | :--- | :--- | :---: | :---: | :---: |
| **`target_type = "directory"`** | `npm/private/utils.bzl` | NTFS Directory Symlink flag | **Required** | **Required** | No-op (Ignored) |
| **`RUNFILES_DIR` / `.bat.runfiles` check** | `js/private/bash.bzl` | Bash env & path probing | **Required** | **Required** | N/A |
| **Direct File Probe (`if exist`)** | `lib/windows_utils.bzl` | Batch filesystem test | **Fallback** | **Primary** | N/A (Linux uses `/bin/sh`) |
| **Guarded Manifest Query** | `lib/windows_utils.bzl` | `findstr` on `%MF%` if exists | **Primary** | **Fallback** | N/A |
| **Drive Conversion (`C:` $\rightarrow$ `/c/`)** | `lib/windows_utils.bzl` | 26-letter drive loop | **Required** | **Required** | N/A (POSIX native) |
| **Launcher Escaped Quoting** | `lib/windows_utils.bzl` | `\"!run_script!\"` | **Required** | **Required** | N/A |
