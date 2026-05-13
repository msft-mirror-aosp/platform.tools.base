#!/bin/bash

readonly report_name="$1"
readonly html_dir="$2"

if [[ ! -f "./tools/base/bazel/coverage/report.sh" ]]; then
  echo "you should run this from the top level WORKSPACE directory"
  exit 1
fi

readonly genhtml="$(which genhtml)"
if [[ -z ${genhtml} ]]; then
  echo "you need genhtml to make reports. run this: sudo apt install lcov"
  exit 1
fi

readonly usage="report.sh <name of coverage report> <output directory for html report>"

if [[ -z ${report_name} || -z ${html_dir} ]]; then
  echo ${usage}
  exit 1
fi

readonly BAZEL_EXITCODE_TEST_FAILURES=3
exit_if_not_test_failure() {
  local -r exit_code=$1
  # Test failures in CI are displayed by other systems. (context: b/192362688)
  if [[ $exit_code != $BAZEL_EXITCODE_TEST_FAILURES ]]; then
    exit $exit_code
  fi
}

echo "Delete old baseline coverage file lists"
find bazel-bin/ -name '*.coverage.baseline*' | xargs rm -fv || exit $?
echo "Generate baseline coverage file lists"
./tools/base/bazel/bazel build --build_tag_filters="coverage-sources" -- //tools/... || exit $?
echo "Run tests to generate coverage data"
./tools/base/bazel/bazel test --define agent_coverage=true --flaky_test_attempts=3 --cache_test_results=yes -- "@cov//:${report_name}.suite" @baseline//... || exit_if_not_test_failure $?
echo "Processing raw coverage data"
./tools/base/bazel/bazel build -- "@cov//:${report_name}.lcov.notests" || exit $?
echo "Generating HTML report in ${html_dir}"
readonly lcov_path="$(./tools/base/bazel/bazel cquery --output files @cov//:${report_name}/lcov.notests)"
# ignore-errors range tolerates data beyond the end of the file, which kotlin does for inline functions
genhtml --ignore-errors range -o ${html_dir} -p $(pwd) --no-function-coverage ${lcov_path} || exit $?
echo "Done"
