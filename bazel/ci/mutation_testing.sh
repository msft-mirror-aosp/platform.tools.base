#!/bin/bash -x

# http://g3doc/wireless/android/build_tools/g3doc/public/buildbot#environment-variables
BUILD_NUMBER="${BUILD_NUMBER:-SNAPSHOT}"

# Ensure the distribution directory for artifacts exists
DIST_DIR="${DIST_DIR:-/tmp/dist_directory}"
mkdir -p "${DIST_DIR}"

readonly script_dir="$(dirname "$0")"
readonly bazel_wrapper="${script_dir}/../bazel"
readonly script_name="$(basename "$0")"
readonly mutated_file_metadata_dir="${DIST_DIR}/mutation_testing"
readonly mutated_file_metadata_path="${mutated_file_metadata_dir}/mutation-metadata-${BUILD_NUMBER}.txtpb"

# Create mutated metadata dir if it does not exist
mkdir -p "${mutated_file_metadata_dir}"

# Bazel configuration for CI builds
readonly config_options="--config=ci --config=remote-exec"

# --- 1. Run Mutation Script ---
echo "Running mutation script to prepare for testing..."
"${bazel_wrapper}" \
  --max_idle_secs=60 \
  run \
  ${config_options} \
  --tool_tag="${script_name}" \
  --build_metadata="ab_build_id=${BUILD_NUMBER}" \
  //tools/base/bazel/mutation:mutation \
  -- \
  --metadata_file_path="${mutated_file_metadata_path}"

readonly bazel_mutation_status=$?

if [[ ${bazel_mutation_status} -ne 0 ]]; then
  echo "Mutation script failed! Aborting build."
  exit 1
fi
echo "Mutation script finished successfully."

# --- 2. Run All Android Studio Tests ---
echo "Running all Android Studio tests..."
readonly test_targets="//prebuilts/studio/... //prebuilts/tools/... //tools/..."

# Bazel Command to run all tests
"${bazel_wrapper}" \
  test \
  ${config_options} \
  --tool_tag="${script_name}" \
  --build_metadata="ab_build_id=${BUILD_NUMBER}" \
  --build_metadata="ab_target=studio-mutation-tests" \
  --flaky_test_attempts=3 \
  --nocache_test_results \
  --bes_keywords=ab-postsubmit \
  ${test_targets}

readonly bazel_test_status=$?
readonly BAZEL_EXITCODE_TEST_FAILURES=3

if [[ ${bazel_test_status} -eq ${BAZEL_EXITCODE_TEST_FAILURES} ]]; then
  echo "Tests failed, so marking Mutation testing as passed"
  exit 0
else
  exit ${bazel_test_status}
fi
