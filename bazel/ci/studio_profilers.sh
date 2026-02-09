#!/bin/bash -x
#
# Runs Gradle-based Sherlock (GPU Profiler) tests

# Ensure the script exits on simple command failures.
set -e

# http://g3doc/wireless/android/build_tools/g3doc/public/buildbot#environment-variables
BUILD_NUMBER="${BUILD_NUMBER:-SNAPSHOT}"

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly ROOT_DIR="$(realpath "${SCRIPT_DIR}/../../../..")"

# Gradle needs JAVA_HOME that points to a JDK 21 (jbrjdk-next).
export JAVA_HOME="${ROOT_DIR}/prebuilts/studio/jdk/jbrjdk-next/linux/"

# Sherlock's Gradle build invokes Python sub-processes.
export PYTHON3_DIR="${ROOT_DIR}/prebuilts/python/linux-x86/bin/"
export PATH="${PYTHON3_DIR}:${PATH}"

# Building Sherlock APK requires Android SDK.
export ANDROID_HOME="${ROOT_DIR}/prebuilts/studio/sdk/linux/"

# Disable 'set -e' temporarily to capture exit codes manually. This allows
# all checks to run even if some of them fail.
set +e

pushd "${ROOT_DIR}/tools/profiler/sherlock-plugin"

./gradlew --info test -Pverbose.test.logging=true
EXIT_CODE_GRADLE_TESTS=$?

./gradlew ktfmtCheck
EXIT_CODE_KTFMT_CHECK=$?

popd > /dev/null

# Check the individual exit codes.
if [ $EXIT_CODE_GRADLE_TESTS -ne 0 ] || [ $EXIT_CODE_KTFMT_CHECK -ne 0 ]; then
    echo "Some checks failed..."
    exit 1
fi

echo "All checks passed!"
exit 0
