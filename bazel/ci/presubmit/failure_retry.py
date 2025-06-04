"""Implements functions for retry of failed tests."""

import dataclasses
import logging
import pathlib
import tempfile
from typing import List

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import gce
from tools.base.bazel.ci.presubmit import gerrit
from tools.base.bazel.ci.presubmit import runs_per_test


_BUCKET = 'adt-byob'
_FILE_NAME = 'failed-tests/v1/{changes_hash}-{target}.txt'
_MAX_FAILED_TESTS = 5
_FAILED_TESTS_RUNS = 2


class NoFailedTestsError(Exception):
  """Error thrown when no failed tests are found."""


@dataclasses.dataclass(frozen=True)
class FailureRetryInfo:
  targets: List[str]

  def get_bazel_flags(self):
    return [
        f'--flaky_test_attempts={target}@{_FAILED_TESTS_RUNS}'
        for target in self.targets
    ]


def get_failure_retry_info(
    build_env: bazel.BuildEnv,
    gerrit_info: gerrit.GerritInfo,
    runs_per_test_info: runs_per_test.RunsPerTestInfo,
) -> FailureRetryInfo:
  """Returns information for retrying failed targets.

  Args:
    build_env: The build environment.
    gerrit_info: The Gerrit info.

  Returns:
    FailureRetryInfo used to retry failed tests.

  Raises:
    NoFailedTestsError: If previous failed tests could not be found.
  """
  object_name = _FILE_NAME.format(
      changes_hash=gerrit_info.changes_hash,
      target=build_env.build_target_name,
  )
  logging.info('Attempting to find failed tests at %s', object_name)

  with tempfile.TemporaryDirectory() as temp_dir:
    temp_path = pathlib.Path(temp_dir) / 'failed_tests.txt'
    if not gce.download_from_gcs(_BUCKET, object_name, str(temp_path)):
      raise NoFailedTestsError(f'Failed tests file {object_name} not found')
    logging.info('Failed tests file %s found', object_name)
    targets = temp_path.read_text().splitlines()
    # If impacted flakes (highly flaky targets that are run 100 times) are
    # included, they get run with --flaky_test_attempts, which causes them to be
    # more likely to pass.
    # This is undesired behavior, so those targets are excluded here.
    targets = [t for t in targets if t not in runs_per_test_info.impacted_flakes]
    return FailureRetryInfo(targets=targets)


def validate_and_upload(build_env: bazel.BuildEnv) -> None:
  """Validates and uploads the failed tests file for the current build.

  If there are no failed tests or if there are too many failed tests, the file
  is not uploaded.

  This function assumes the failed tests file is located at
  DIST_DIR/failed_tests.txt.

  Args:
    build_env: The build environment.
  """
  gerrit_info = gerrit.get_gerrit_info(build_env)
  failed_tests_path = pathlib.Path(build_env.dist_dir) / 'failed_tests.txt'
  failed_tests = failed_tests_path.read_text().splitlines()
  if not failed_tests or len(failed_tests) > _MAX_FAILED_TESTS:
    logging.info('%d failed tests, not uploading', len(failed_tests))
    return

  object_name = _FILE_NAME.format(
      changes_hash=gerrit_info.changes_hash,
      target=build_env.build_target_name,
  )

  gce.upload_to_gcs(failed_tests_path, _BUCKET, object_name)
  logging.info('Uploaded failed tests to GCS with object name: %s', object_name)

