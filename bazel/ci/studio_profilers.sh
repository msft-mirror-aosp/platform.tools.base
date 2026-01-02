#!/bin/bash -x
#
# Runs Gradle-based Sherlock (GPU Profiler) tests

# http://g3doc/wireless/android/build_tools/g3doc/public/buildbot#environment-variables
BUILD_NUMBER="${BUILD_NUMBER:-SNAPSHOT}"

readonly script_dir="$(dirname "$0")"
readonly root_dir="${script_dir}/../../../.."

pushd "${root_dir}"/tools/profiler/sherlock-plugin
./gradlew test -Pverbose.test.logging=true
