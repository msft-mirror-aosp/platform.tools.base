#!/bin/bash -x
#
# Runs Gradle-based Sherlock (GPU Profiler) tests

# http://g3doc/wireless/android/build_tools/g3doc/public/buildbot#environment-variables
BUILD_NUMBER="${BUILD_NUMBER:-SNAPSHOT}"

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ROOT_DIR="$(realpath "${SCRIPT_DIR}/../../../..")"

export JAVA_HOME="${ROOT_DIR}/prebuilts/studio/jdk/jdk17/linux/"
pushd "${ROOT_DIR}/tools/profiler/sherlock-plugin"
./gradlew test -Pverbose.test.logging=true
