#!/bin/sh
#
# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

print_help_text() {
  cat <<'EOF'
Usage:
  sdkmanager [--uninstall] [<common args>] [--package_file=<file>] [<packages>...]
  sdkmanager --update [<common args>]
  sdkmanager --list [<common args>]
  sdkmanager --list_installed [<common args>]
  sdkmanager --version

With --install (optional), installs or updates packages.
    By default, the listed packages are installed or (if already installed)
    updated to the latest version.
With --uninstall, uninstall the listed packages.

    <package> is a sdk-style path (e.g. "build-tools/23.0.0" or
             "platforms/android-23").
    <package-file> is a text file where each line is a sdk-style path
                   of a package to install or uninstall.
    Multiple --package_file arguments may be specified in combination
    with explicit paths.

With --update, all installed packages are updated to the latest version.

With --list, all installed and available packages are printed out.

With --list_installed, all installed packages are printed out.

With --version, prints the current version of sdkmanager.

Common Arguments:
    --sdk_root=<sdkRootPath>: Use the specified SDK root instead of the SDK
                              containing this tool

    --channel=<channelId>: Include packages in channels up to <channelId>.
                           Common channels are:
                           0 (Stable), 1 (Beta), 2 (Dev), and 3 (Canary).

* If the env var REPO_OS_OVERRIDE is set to "windows",
  "macosx", or "linux", packages will be downloaded for that OS.
EOF
}

show_help() {
  EXIT_CODE="${1:-1}"
  if [ "$EXIT_CODE" -eq 0 ]; then
    print_help_text
  else
    print_help_text >&2
  fi
  exit "$EXIT_CODE"
}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated. Android CLI will be used instead." >&2
echo "The 'android' binary can also be found in the cmdline-tools directory, and 'android sdk' is the replacement for 'sdkmanager'." >&2
echo "To learn more about the Android CLI and how to use it, see the documentation (https://d.android.com/tools/agents/android-cli)" >&2
echo "" >&2

show_version() {
  VERSION=""
  if [ -n "$ANDROID_CLI_BIN" ]; then
    VERSION="$("$ANDROID_CLI_BIN" --version 2>/dev/null)"
  elif [ -f "$SCRIPT_DIR/android" ]; then
    VERSION="$("$SCRIPT_DIR/android" --version 2>/dev/null)"
  fi
  if [ -n "$VERSION" ]; then
    printf '%s (Android CLI)\n' "$VERSION"
    exit 0
  fi
  echo "unknown (Android CLI)"
  exit 0
}

resolve_sdk_root() {
  if [ -n "$SDK_ROOT" ]; then
    return 0
  fi
  PARENT2="$(cd "$SCRIPT_DIR/../.." 2>/dev/null && pwd)"
  if [ "$(basename "$PARENT2")" = "cmdline-tools" ]; then
    SDK_ROOT="$(cd "$SCRIPT_DIR/../../.." 2>/dev/null && pwd)"
    return 0
  fi
  echo "Error: Could not determine SDK root." >&2
  echo "Error: Either specify it explicitly with --sdk_root= or move this package into its expected location: <sdk>/cmdline-tools/latest/" >&2
  exit 1
}

run_android_cli() {
  if [ -n "$ANDROID_CLI_BIN" ]; then
    "$ANDROID_CLI_BIN" --sdk="$SDK_ROOT" sdk "$@"
    return $?
  fi
  "$SCRIPT_DIR/android" --sdk="$SDK_ROOT" sdk "$@"
  return $?
}

if [ $# -eq 0 ]; then
  show_help
fi

SDK_ROOT=""
MODE="install"
SPECIAL_CMD=""
LICENSES_CONFIGURED=""
PACKAGES=""
EXTRA_ARGS=""
PROXY_TYPE=""
PROXY_HOST=""
PROXY_PORT=""

add_extra_arg() {
  EXTRA_ARGS="${EXTRA_ARGS}${EXTRA_ARGS:+
}${1}"
}

add_package() {
  PKG="$(printf '%s\n' "$1" | tr -d '\r' | tr ';' '/')"
  # Accumulate packages delimited by newlines to preserve spaces in paths or IDs.
  PACKAGES="${PACKAGES}${PACKAGES:+
}${PKG}"
}

read_package_file() {
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|\#*) continue ;;
    esac
    add_package "$line"
  done < "$1"
}

process_channel() {
  case "$1" in
    1|beta) add_extra_arg "--beta" ;;
    2|dev)
      echo "Warning: Channel 2 (Dev) is not discretely supported; mapping to --canary." >&2
      add_extra_arg "--canary"
      ;;
    3|canary) add_extra_arg "--canary" ;;
  esac
}

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h)
      show_help 0
      ;;
    --version)
      show_version
      ;;
    --sdk_root=*)
      SDK_ROOT="${1#*=}"
      ;;
    --sdk_root)
      shift
      SDK_ROOT="$1"
      ;;
    --list)
      SPECIAL_CMD="list"
      add_extra_arg "--all"
      ;;
    --list_installed)
      SPECIAL_CMD="list"
      ;;
    --update)
      SPECIAL_CMD="update"
      ;;
    --licenses)
      echo "Warning: The --licenses option is no longer needed." >&2
      LICENSES_CONFIGURED="true"
      ;;
    --uninstall)
      MODE="remove"
      ;;
    --install)
      MODE="install"
      ;;
    --channel=*)
      process_channel "${1#*=}"
      ;;
    --channel)
      shift
      process_channel "$1"
      ;;
    --include_obsolete|--newer|--no_https|--verbose)
      echo "Warning: Flag $1 is no longer supported. Ignoring." >&2
      ;;
    --proxy=*)
      PROXY_CONFIGURED="true"
      ;;
    --proxy)
      shift
      PROXY_CONFIGURED="true"
      ;;
    --proxy_host=*)
      PROXY_CONFIGURED="true"
      ;;
    --proxy_host)
      shift
      PROXY_CONFIGURED="true"
      ;;
    --proxy_port=*)
      PROXY_CONFIGURED="true"
      ;;
    --proxy_port)
      shift
      PROXY_CONFIGURED="true"
      ;;
    --no_proxy)
      PROXY_CONFIGURED="true"
      ;;
    --package_file=*)
      read_package_file "${1#*=}"
      ;;
    --package_file)
      shift
      read_package_file "$1"
      ;;
    -*)
      echo "Unknown option: '$1'" >&2
      show_help
      ;;
    *)
      add_package "$1"
      ;;
  esac
  shift
done

if [ "$PROXY_CONFIGURED" = "true" ]; then
  echo "Warning: Proxy options are no longer needed; using default system proxy configuration." >&2
fi

detect_host_arch() {
  case "$(uname -m 2>/dev/null)" in
    arm64|aarch64) echo "arm64" ;;
    x86_64|amd64) echo "x86_64" ;;
    i386|i686|x86) echo "x86" ;;
    *) echo "x86_64" ;;
  esac
}

if [ -n "$REPO_OS_OVERRIDE" ]; then
  if [ "$SPECIAL_CMD" = "update" ] || { [ -z "$SPECIAL_CMD" ] && [ "$MODE" = "install" ]; }; then
    OS_OVERRIDE="$(printf '%s\n' "$REPO_OS_OVERRIDE" | tr '-' '_')"
    case "$OS_OVERRIDE" in
      *_*) add_extra_arg "--platform=${OS_OVERRIDE}" ;;
      *) add_extra_arg "--platform=${OS_OVERRIDE}_$(detect_host_arch)" ;;
    esac
  fi
fi

if [ -z "$SPECIAL_CMD" ] && [ -z "$PACKAGES" ]; then
  if [ "$LICENSES_CONFIGURED" = "true" ]; then
    exit 0
  fi
  show_help 1
fi

resolve_sdk_root

# Helper function to invoke run_android_cli while preserving spaces in any EXTRA_ARGS flags.
# Since POSIX sh lacks indexed arrays, EXTRA_ARGS and any trailing arguments are accumulated
# separated by newlines and then loaded into positional parameters ($@) via IFS=<newline>.
call_cli_with_extra_args() {
  CMD="$1"
  shift
  TRAILING_ARGS=""
  for arg in "$@"; do
    TRAILING_ARGS="${TRAILING_ARGS}${TRAILING_ARGS:+
}${arg}"
  done
  COMBINED_ARGS="${EXTRA_ARGS}${EXTRA_ARGS:+${TRAILING_ARGS:+
}}${TRAILING_ARGS}"
  OLD_IFS="${IFS-unset}"
  IFS="
"
  set -f
  set -- $COMBINED_ARGS
  set +f
  if [ "$OLD_IFS" = "unset" ]; then
    unset IFS
  else
    IFS="$OLD_IFS"
  fi
  run_android_cli $CMD "$@"
}

if [ -n "$SPECIAL_CMD" ]; then
  call_cli_with_extra_args $SPECIAL_CMD
  exit $?
fi

# Load the newline-delimited packages into positional parameters ($@) by temporarily setting
# IFS to newline and disabling pathname expansion (set -f). Since POSIX sh lacks indexed arrays,
# delegating via "$@" ensures that package paths/names with spaces are forwarded without word splitting.
OLD_IFS="${IFS-unset}"
IFS="
"
set -f
set -- $PACKAGES
set +f
if [ "$OLD_IFS" = "unset" ]; then
  unset IFS
else
  IFS="$OLD_IFS"
fi

call_cli_with_extra_args "$MODE" "$@"
exit $?
