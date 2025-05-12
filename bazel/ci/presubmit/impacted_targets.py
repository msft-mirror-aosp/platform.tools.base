import collections
import dataclasses
import json
import logging
import os
import pathlib
import subprocess
import tempfile
from typing import List, Sequence, Set

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import gce
from tools.base.bazel.ci.presubmit import bazel_diff


_BUCKET = 'adt-byob'
_FILE_NAME = 'bazel-diff-hashes/v8/{bid}-{target}.json'
_LOCAL_REPOSITORIES = [
    'maven',
]


class ImpactedTargetsNotFoundError(Exception):
  """Error raised when impacted targets cannot be determined."""


@dataclasses.dataclass(frozen=True)
class ImpactedTarget:
  """Represents a target from bazel-diff get-impacted-targets."""
  label: str
  target_distance: int
  package_distance: int


@dataclasses.dataclass(frozen=True)
class ImpactedTargetsInfo:
  """Represents all impacted targets related information."""
  all_targets: List[ImpactedTarget]
  # baseline_targets is the set of all targets for a CI target, and
  # has gone through target and tag filtering.
  baseline_targets: Set[str]

  def get_direct_targets(self) -> List[ImpactedTarget]:
    return [t for t in self.all_targets if t.target_distance == 0]

  def get_filtered_targets(self) -> List[ImpactedTarget]:
    return [t for t in self.all_targets if t.label in self.baseline_targets]

  def get_bazel_flags(self) -> List[str]:
    filtered_targets = self.get_filtered_targets()
    labels = [t.label for t in filtered_targets]
    target_distances = collections.Counter(t.target_distance for t in filtered_targets)
    pkg_distances = collections.Counter(t.package_distance for t in filtered_targets)
    return [
        f'--build_metadata=selective_presubmit_impacted_target_count={len(labels)}',
        f'--build_metadata=selective_presubmit_target_distance=' + ','.join(
            f'({distance}:{count})' for distance, count in target_distances.items()
        ),
        f'--build_metadata=selective_presubmit_package_distance=' + ','.join(
            f'({distance}:{count})' for distance, count in pkg_distances.items()
        ),
    ]


def get_impacted_targets_info(
    build_env: bazel.BuildEnv,
    base_targets: Sequence[str],
    test_flag_filters: str,
) -> ImpactedTargetsInfo:
  """Finds the test targets impacted by the current change.

  The comprehensive list of impacted targets found by bazel-diff is filtered
  using the test flag filters and the base targets.

  Args:
    build_env: The build environment.
    base_targets: The base set of targets used for filtering.
    test_flag_filters: The test flag filters used for filtering.

  Returns:
    The impacted targets info.

  Raises:
    ImpactedTargetsNotFoundError: If impacted targets could not be determined.
  """
  impacted_targets_info = ImpactedTargetsInfo(
      all_targets=_find_impacted_targets(build_env),
      baseline_targets=_query_baseline_targets(
          build_env,
          base_targets,
          test_flag_filters,
      ),
  )

  direct_impacted_targets_path = (
      pathlib.Path(build_env.dist_dir) / 'direct-impacted-targets.txt'
  )
  direct_impacted_targets_path.write_text(
      '\n'.join([t.label for t in impacted_targets_info.get_direct_targets()])
  )

  return impacted_targets_info


def generate_and_upload_hash_file(build_env: bazel.BuildEnv) -> None:
  """Generates and uploads the hash file for the current build to GCS."""
  object_name = _FILE_NAME.format(
      bid=build_env.build_number,
      target=build_env.build_target_name,
  )
  try:
    hash_file_path = _generate_hash_file(build_env)
  except subprocess.TimeoutExpired as e:
    logging.warning('generate-hashes timed out after %f seconds.', e.timeout)
    return
  gce.upload_to_gcs(hash_file_path, _BUCKET, object_name)
  logging.info('Uploaded hash file to GCS with object name: %s', object_name)


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
    ImpactedTargetsNotFoundError: If impacted targets could not be determined.
  """
  with tempfile.TemporaryDirectory() as temp_dir:
    temp_path = pathlib.Path(temp_dir)
    dep_edges = temp_path / 'dep-edges.json'
    try:
      current_hashes = _generate_hash_file(build_env, dep_edges)
    except subprocess.TimeoutExpired as e:
      raise ImpactedTargetsNotFoundError(
          f'generate-hashes timed out after {e.timeout} seconds'
      )

    reference_bid = gce.get_reference_build_id(
        build_env.build_number,
        build_env.build_target_name,
    )
    logging.info('Found reference build ID: %s', reference_bid)
    object_name = _FILE_NAME.format(
        bid=reference_bid,
        target=build_env.build_target_name,
    )
    base_hashes = temp_path / 'base-hashes.json'
    exists = gce.download_from_gcs(
        _BUCKET,
        object_name,
        str(base_hashes),
    )
    if not exists:
      raise ImpactedTargetsNotFoundError(f'Base hash file {object_name} not found')
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
