"""Implements shared functions for selective presubmit."""

import collections
import dataclasses
import enum
import json
import logging
import os
import pathlib
import re
import shutil
import subprocess
import tempfile
from typing import Iterator, List, Sequence, Set

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import gce
from tools.base.bazel.ci.presubmit import bazel_diff
from tools.base.bazel.ci.presubmit import failure_retry
from tools.base.bazel.ci.presubmit import gerrit


_FILE_BUCKET = 'adt-byob'

_FAILED_TESTS_FILE_NAME = 'failed-tests/v1/{changes_hash}-{target}.txt'
_MAX_FAILED_TESTS = 5
_FAILED_TESTS_RUNS = 2

_HASH_FILE_NAME = 'bazel-diff-hashes/v8/{bid}-{target}.json'
_MAX_RUNS_PER_TEST = 200
_LOCAL_REPOSITORIES = [
    'intellij',
    'maven',
]


class SelectivePresubmitError(Exception):
  """Represents an error obtaining selective presubmit targets."""
  pass


class SelectivePresubmitStrategy(enum.Enum):
  """Strategies used to select targets for selective presubmit."""
  DEFAULT_FALLBACK = 'default_fallback'
  DEFAULT_EXPLICIT = 'default_explicit'
  RETRY_FAILED     = 'retry_failed'
  IMPACTED_TARGETS = 'impacted_targets'


@dataclasses.dataclass
class ImpactedTarget:
  """Represents a target from bazel-diff get-impacted-targets."""

  label: str
  target_distance: int
  package_distance: int


@dataclasses.dataclass
class TargetsResult:
  """Represents targets detected as impacted from a Bazel Diff run."""

  targets: List[ImpactedTarget]
  # baseline_targets is the set of all targets for a CI target, and
  # has gone through target and tag filtering.
  baseline_targets: Set[str]

  def all_targets(self) -> List[ImpactedTarget]:
    """Returns all targets, filtered by baseline targets.

    This is important as it filters out noci:$AB_TARGET_NAME tags, etc.
    """
    all_targets = []
    for target in self.targets:
      if target.label in self.baseline_targets:
        all_targets.append(target)
    return all_targets


@dataclasses.dataclass
class SelectivePresubmitResult:
  """Represents the result of selecting presubmit targets."""

  strategy: SelectivePresubmitStrategy
  targets: List[str]
  base_flags: List[str]

  @property
  def flags(self) -> List[str]:
    # TODO: Remove selective_presubmit_found when stats are migrated to
    # selective_presubmit_strategy.
    found = self.strategy == SelectivePresubmitStrategy.IMPACTED_TARGETS
    return self.base_flags + [
        f'--build_metadata=selective_presubmit_found={found}',
        f'--build_metadata=selective_presubmit_strategy={self.strategy.value}',
    ]


def _generate_hash_file(
    build_env: bazel.BuildEnv,
    deps_output_path: pathlib.Path | None = None,
) -> str:
  """Generates the hash file for the current build."""
  hash_file_path = os.path.join(build_env.dist_dir, 'bazel-diff-hashes.json')
  bazel_diff.generate_hash_file(
      build_env,
      _LOCAL_REPOSITORIES,
      hash_file_path,
      deps_output_path=deps_output_path,
  )
  return hash_file_path


def _find_impacted_targets(
    build_env: bazel.BuildEnv,
) -> List[ImpactedTarget]:
  """Finds the targets impacted by the current change.

  Args:
    build_env: The build environment.

  Returns:
    The list of impacted targets.

  Raises:
    SelectivePresubmitError: If there was a problem obtaining the impacted
    targets.
  """
  with tempfile.TemporaryDirectory() as temp_dir:
    temp_path = pathlib.Path(temp_dir)
    dep_edges = temp_path / 'dep-edges.json'
    try:
      current_hashes = _generate_hash_file(build_env, dep_edges)
    except subprocess.TimeoutExpired as e:
      raise SelectivePresubmitError(
          f'generate-hashes timed out after {e.timeout} seconds'
      )

    reference_bid = gce.get_reference_build_id(
        build_env.build_number,
        build_env.build_target_name,
    )
    logging.info('Found reference build ID: %s', reference_bid)
    object_name = _HASH_FILE_NAME.format(
        bid=reference_bid,
        target=build_env.build_target_name,
    )
    base_hashes = temp_path / 'base-hashes.json'
    exists = gce.download_from_gcs(
        _FILE_BUCKET,
        object_name,
        str(base_hashes),
    )
    if not exists:
      raise SelectivePresubmitError(f'Base hash file {object_name} not found')
    logging.info('Base hash file %s found', object_name)

    impacted_targets = pathlib.Path(build_env.dist_dir) / 'impacted-targets.txt'
    bazel_diff.get_impacted_targets(
        build_env,
        base_hashes,
        current_hashes,
        dep_edges,
        impacted_targets,
    )
    data = json.loads(impacted_targets.read_text())
    return [
        ImpactedTarget(
            label=target['label'], target_distance=target['targetDistance'],
            package_distance=target['packageDistance'],
        )
        for target in data
    ]


def _query_baseline_targets(
    build_env: bazel.BuildEnv,
    base_targets: Sequence[str],
    test_flag_filters: str,
) -> List[str]:
  """Queries the targets that match the given filters.

  Args:
    build_env: The build environment.
    base_targets: The base set of targets used for filtering.
    test_flag_filters: The test flag filters used for filtering.

  Returns:
    A list of target labels.
  """
  filters = test_flag_filters.split(',') if test_flag_filters else []
  filters.append('-manual')

  include_query = []
  exclude_query = []
  for target in base_targets:
    if target[0] == '-':
      target = target[1:]
      exclude_query.append(target)
    else:
      include_query.append(target)

    for test_filter in filters:
      if test_filter[0] == '-':
        exclude_query.append(f'attr(tags, "{test_filter[1:]}", {target})')
      else:
        include_query.append(f'attr(tags, "{test_filter}", {target})')

    exclude_query.append(
        f'attr(target_compatible_with, "@platforms//:incompatible", {target})'
    )

  query = (
      ' union '.join(include_query)
      + ' except '
      + ' except '.join(exclude_query)
  )
  return build_env.bazel_query(query).stdout.decode('utf-8').splitlines()


def _find_impacted_test_targets(
    build_env: bazel.BuildEnv,
    base_targets: Sequence[str],
    test_flag_filters: str,
) -> TargetsResult:
  """Finds the test targets impacted by the current change.

  The comprehensive list of impacted targets found by bazel-diff is filtered
  using the test flag filters and the base targets.

  Args:
    build_env: The build environment.
    base_targets: The base set of targets used for filtering.
    test_flag_filters: The test flag filters used for filtering.

  Returns:
    A list of targets that should be tested.

  Raises:
    SelectivePresubmitError: If there was a problem obtaining the impacted
    targets.
  """
  targets = _find_impacted_targets(build_env)
  direct_targets = [t for t in targets if t.target_distance == 0]
  direct_impacted_targets_path = (
      pathlib.Path(build_env.dist_dir) / 'direct-impacted-targets.txt'
  )
  direct_impacted_targets_path.write_text(
      '\n'.join([t.label for t in direct_targets])
  )
  baseline_targets = _query_baseline_targets(
      build_env, base_targets, test_flag_filters
  )
  return TargetsResult(
      targets=targets,
      baseline_targets=set(baseline_targets),
  )


def generate_and_upload_hash_file(build_env: bazel.BuildEnv) -> None:
  """Generates and uploads the hash file for the current build to GCS."""
  object_name = _HASH_FILE_NAME.format(
      bid=build_env.build_number,
      target=build_env.build_target_name,
  )
  try:
    hash_file_path = _generate_hash_file(build_env)
  except subprocess.TimeoutExpired as e:
    logging.warning('generate-hashes timed out after %f seconds.', e.timeout)
    return
  gce.upload_to_gcs(hash_file_path, _FILE_BUCKET, object_name)
  logging.info('Uploaded hash file to GCS with object name: %s', object_name)


def _generate_runs_per_test_flags(gerrit_info: gerrit.GerritInfo) -> List[str]:
  """Returns the flags used to specify the number of runs per test."""
  flags = []
  for value in gerrit_info.filter_tags('Presubmit-Runs-Per-Test'):
    target, runs = value.split('@')
    runs = int(runs)

    # Limit the number of runs per test.
    if runs > _MAX_RUNS_PER_TEST:
      raise ValueError(
          f'Exceeded maximum runs per test: {runs} > {_MAX_RUNS_PER_TEST}'
      )

    # Prevent wildcards.
    if target.endswith('...') or target.endswith(':all'):
      raise ValueError(f'Wildcard target not allowed: {target}')

    logging.info('Running %s with %d runs per test', target, runs)
    flags.append(f'--runs_per_test={value}')

  return flags


def find_test_targets(
    build_env: bazel.BuildEnv,
    base_targets: Sequence[str],
    test_flag_filters: str,
) -> SelectivePresubmitResult:
  """Returns the result of selecting test targets for the current build.

  Tags in the CL description are used to customize the behavior of the
  presubmit, e.g.:
    - Presubmit-Test: default
      - Tests all default targets regardless of whether they are impacted.
    - Presubmit-Test: studio-linux:default
      - Tests all default targets on studio-linux regardless of whether they are
        impacted.
    - Presubmit-Test: //tools/base:some_test
      - Explicitly tests //tools/base:some_test on all platforms.
    - Presubmit-Test: studio-win://tools/base:some_test
      - Explicitly tests //tools/base:some_tests only on studio-win.

  Tags can be repeated in one description and across multiple changes.
  """
  gerrit_info = gerrit.get_gerrit_info(build_env)

  flags = gerrit_info.get_bazel_flags() + _generate_runs_per_test_flags(gerrit_info)

  # Parse Presubmit-Test tags.
  explicit_targets = []
  for value in gerrit_info.filter_tags('Presubmit-Test'):
    # "default" is a special value that indicates that all default targets
    # should be tested.
    if value.lower() == 'default':
      return SelectivePresubmitResult(
          strategy=SelectivePresubmitStrategy.DEFAULT_EXPLICIT,
          targets=base_targets + explicit_targets,
          base_flags=flags,
      )

    explicit_targets.append(value)

  # Prioritize failed tests.
  try:
    failure_retry_info = failure_retry.get_failure_retry_info(
        build_env,
        gerrit_info,
    )
    return SelectivePresubmitResult(
        strategy=SelectivePresubmitStrategy.RETRY_FAILED,
        targets=failure_retry_info.targets + explicit_targets,
        base_flags=flags + failure_retry_info.get_bazel_flags(),
    )
  except failure_retry.NoFailedTestsError as e:
    logging.warning('Failed to find failed tests: %s', e)

  # Finally, try impacted targets.
  try:
    targets_result = _find_impacted_test_targets(
        build_env,
        base_targets,
        test_flag_filters,
    )
    impacted_targets = targets_result.all_targets()
    labels = [t.label for t in impacted_targets]
    impacted_count = len(labels)
    logging.info('Found %d impacted targets', impacted_count)

    target_distances = collections.Counter(t.target_distance for t in impacted_targets)
    pkg_distances = collections.Counter(t.package_distance for t in impacted_targets)

    return SelectivePresubmitResult(
        strategy=SelectivePresubmitStrategy.IMPACTED_TARGETS,
        targets=labels + explicit_targets,
        base_flags = flags + [
            f'--build_metadata=selective_presubmit_impacted_target_count={impacted_count}',
            f'--build_metadata=selective_presubmit_target_distance=' + ','.join(
                f'({distance}:{count})' for distance, count in target_distances.items()
            ),
            f'--build_metadata=selective_presubmit_package_distance=' + ','.join(
                f'({distance}:{count})' for distance, count in pkg_distances.items()
            ),
        ],
    )
  except SelectivePresubmitError as e:
    logging.warning('Failed to find impacted test targets: %s', e)
    logging.warning('Falling back to testing default targets')

  return SelectivePresubmitResult(
      strategy=SelectivePresubmitStrategy.DEFAULT_FALLBACK,
      targets=base_targets + explicit_targets,
      base_flags=flags,
  )
