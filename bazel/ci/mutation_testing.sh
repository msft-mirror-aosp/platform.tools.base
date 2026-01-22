#!/bin/bash -x

# http://g3doc/wireless/android/build_tools/g3doc/public/buildbot#environment-variables
BUILD_NUMBER="${BUILD_NUMBER:-SNAPSHOT}"

# Ensure the distribution directory for artifacts exists
DIST_DIR="${DIST_DIR:-/tmp/dist_directory}"
mkdir -p "${DIST_DIR}"

# Get temp directory
TMP_DIR="${TMPDIR:-/tmp}"

# Get absolute paths
readonly script_dir="$(cd "$(dirname "$0")" && pwd)"
readonly WORKSPACE="$(cd "${script_dir}/../../../.." && pwd)"
readonly bazel_wrapper="${WORKSPACE}/tools/base/bazel/bazel"
readonly script_name="$(basename "$0")"

# Set output dir for mutation testing and other files, files written to dist_dir will be present under artifacts
readonly mutation_testing_dir="${DIST_DIR}/mutation_testing"
readonly mutated_file_metadata_path="${mutation_testing_dir}/mutation-metadata-${BUILD_NUMBER}.txtpb"
readonly impacted_targets_filepath="${mutation_testing_dir}/impacted-targets.txt"

# Set temp dir for mutation testing
readonly temp_mutation_testing_dir="${TMP_DIR}/mutation_testing"
readonly start_hashes_filepath="${temp_mutation_testing_dir}/start-hashes.json"
readonly final_hashes_filepath="${temp_mutation_testing_dir}/final-hashes.json"


# Create mutation testing directory if it does not exist
mkdir -p "${mutation_testing_dir}"
# Create mutation testing temp directory if it does not exist
mkdir -p "${temp_mutation_testing_dir}"

# Bazel configuration for CI builds
readonly config_options="--config=ci --config=remote-exec"

# Common Functions
print_file_as_escaped_string() {
  if [[ -f "$1" ]]; then
    awk '{printf "%s\\n", $0}' "$1"
  else
    echo "Error: File not found: $1" >&2
    return 1
  fi
}

# --- Generate Starting Hashes ---
readonly empty_file="${TMP_DIR}/empty.txt"
echo "" > "${empty_file}"
"${bazel_wrapper}" run //tools/base/bazel:bazel-diff generate-hashes -- \
 -w "${WORKSPACE}" \
 --includeTargetType \
 --modified-filepaths="${empty_file}" \
 "${start_hashes_filepath}"

readonly start_generate_hashes_status=$?
if [[ ${start_generate_hashes_status} -ne 0 ]]; then
    echo "Error while generating starting hashes, aborting..."
    exit 1
fi

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

# --- Generate Final Hashes ---
# File contains the path of mutated files
mutated_files_filepath="${TMP_DIR}/mutated-files.txt"
# Read mutated-metadata file to get mutated filepath
grep "file_path:" "${mutated_file_metadata_path}" | sed  -re "s/file_path: \"(.*)\"/\1/" > "${mutated_files_filepath}"
# Generate final hashes
"${bazel_wrapper}" run //tools/base/bazel:bazel-diff generate-hashes -- \
  -w "${WORKSPACE}" \
  --includeTargetType \
  --modified-filepaths="${mutated_files_filepath}" \
  "${final_hashes_filepath}"

readonly final_generate_hashes_status=$?
if [[ ${final_generate_hashes_status} -ne 0 ]]; then
    echo "Error while generating final hashes, aborting..."
    exit 1
fi

# --- Get Impacted Targets ---
"${bazel_wrapper}" run //tools/base/bazel:bazel-diff get-impacted-targets -- \
 --startingHashes="${start_hashes_filepath}" \
 --finalHashes="${final_hashes_filepath}" \
 --output="${impacted_targets_filepath}" \
 --targetType=Rule

# -- Check if Impacted Target File is non-empty ---
if [[ ! -s "${impacted_targets_filepath}" ]]; then
  echo "Mutation did not impact any test targets. Marking Mutation Testing as failed."
  exit 1
fi

# --- 2. Run Impacted Android Studio Targets ---
echo "Running impacted Android Studio targets..."

# Bazel Command to run all tests
"${bazel_wrapper}" \
 test \
 ${config_options} \
 --tool_tag="${script_name}" \
 --test_tag_filters="-noci:studio-linux" \
 --build_metadata="ab_build_id=${BUILD_NUMBER}" \
 --build_metadata="ab_target=studio-mutation-tests" \
 --build_metadata="mutated_file=$(print_file_as_escaped_string "${mutated_files_filepath}")" \
 --build_metadata="mutated_file_metadata=$(print_file_as_escaped_string "${mutated_file_metadata_path}")" \
 --flaky_test_attempts=3 \
 --nocache_test_results \
 --bes_keywords=ab-postsubmit \
 --target_pattern_file="${impacted_targets_filepath}" \
 --bes_keywords="cinder_autopush" \
 --build_metadata="cinder_pipelines=mutation-testing"

readonly bazel_test_status=$?
readonly BAZEL_EXITCODE_TEST_FAILURES=3

if [[ ${bazel_test_status} -eq ${BAZEL_EXITCODE_TEST_FAILURES} ]]; then
  # Mutation-testing plugin documentation: go/adt-cinder/plugins/mutation_testing.md
  echo "Tests failed, mutation-testing cinder plugin will process failed target logs."
  exit 0
elif [[ ${bazel_test_status} -eq 0 ]]; then
  echo "Mutation testing failed: All tests passed, mutation was not caught by any test. Mutation-testing cinder plugin will handle post-processing"
  exit 0
else
  echo "ERROR: Bazel exited with an unexpected status: ${bazel_test_status}."
  exit ${bazel_test_status}
fi
