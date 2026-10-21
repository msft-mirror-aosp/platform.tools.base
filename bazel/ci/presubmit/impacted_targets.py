import collections
import dataclasses
import json
import logging
import os
import pathlib
import shutil
import subprocess
import tempfile
from typing import List, Sequence, Set

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import gce
from tools.base.bazel.ci.presubmit import bazel_diff
from tools.base.bazel.ci.presubmit import gerrit


_BUCKET = 'adt-byob'
_FILE_NAME = 'bazel-diff-hashes/v8/{bid}-{target}.json'
_LOCAL_REPOSITORIES = []
_INCREMENTAL_BAZEL_DIFF_HASH = 'out/bazel-diff-hashes.json'

# Bazel packages under prebuilts/ excluded from `bazel-diff generate-hashes` via
# `--deleted_packages`.
#
# `bazel-diff generate-hashes` queries `//...:all-targets` across the workspace.
# `prebuilts/studio/sdk` (~191k targets) and `prebuilts/tools/common/m2` (~50k
# targets) make up ~81% of all targets under `//prebuilts/...` and ~54% of
# main-repo targets. Since changes to these packages opt out of selective
# presubmit and run the full test suite, we don't need target hashes for them.
#
# `--deleted_packages` requires exact workspace-relative directories containing
# a BUILD file (not top-level directory prefixes or Gerrit project names) and
# instructs Bazel to pretend those packages do not exist during `bazel query`.
# Other packages under `prebuilts/` (such as `prebuilts/studio/intellij-sdk` and
# `prebuilts/tools/common/aapt`) must remain visible because `//tools/...` BUILD
# files `load()` `.bzl` macros from them.
#
# Keep in sync with the SELECTIVE_PRESUBMIT_*_OPT_OUT lists below.
_DELETED_PREBUILTS_PACKAGES = (
    'prebuilts/studio/sdk',
    'prebuilts/tools/common/m2',
)

# Gerrit projects and backing BUILD files that are opted out of selective
# presubmit. Changes touching these or `_DELETED_PREBUILTS_PACKAGES` run the
# full baseline test suite.
SELECTIVE_PRESUBMIT_PROJECTS_OPT_OUT = [
    # Responsible for '@tools_idea' bzlmod repos.
    'platform/tools/idea',
    # Responsible for '@intellij' repo.
    'platform/tools/vendor/google_prebuilts/studio/intellij-sdk',
    # Responsible for //prebuilts/studio/sdk.
    'platform/tools/google_prebuilts/studio/sdk/remote',
    'platform/tools/vendor/google_prebuilts/studio/sdk/darwin',
    'platform/tools/vendor/google_prebuilts/studio/sdk/linux',
    'platform/tools/vendor/google_prebuilts/studio/sdk/windows',
]
SELECTIVE_PRESUBMIT_FILES_OPT_OUT = frozenset({
    'tools/base/bazel/bzlmod/idea.MODULE.bazel',
    'tools/base/bazel/maven/BUILD.maven',
    'tools/base/bazel/sdk/prebuilts.studio.sdk.BUILD',
})


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
    target_distances = collections.Counter(
        t.target_distance for t in filtered_targets
    )
    pkg_distances = collections.Counter(
        t.package_distance for t in filtered_targets
    )
    return [
        f'--build_metadata=selective_presubmit_impacted_target_count={len(labels)}',
        f'--build_metadata=selective_presubmit_target_distance='
        + ','.join(
            f'({distance}:{count})'
            for distance, count in target_distances.items()
        ),
        f'--build_metadata=selective_presubmit_package_distance='
        + ','.join(
            f'({distance}:{count})' for distance, count in pkg_distances.items()
        ),
    ]


def _check_selective_presubmit_opt_out(gerrit_info: gerrit.GerritInfo) -> None:
  """Raises ImpactedTargetsNotFoundError if any change is opted out."""
  deleted_prefixes = tuple(f'{pkg}/' for pkg in _DELETED_PREBUILTS_PACKAGES)
  for change in gerrit_info.changes:
    if change.project in SELECTIVE_PRESUBMIT_PROJECTS_OPT_OUT:
      raise ImpactedTargetsNotFoundError(
          f'Selective presubmit is disabled for project {change.project}'
      )
    project_path = change.project_path.strip('/')
    for file_info in change.file_infos:
      rel_path = (
          f'{project_path}/{file_info.path}'.strip('/')
          if project_path
          else file_info.path.strip('/')
      )
      if (
          rel_path.startswith(deleted_prefixes)
          or rel_path in SELECTIVE_PRESUBMIT_FILES_OPT_OUT
      ):
        raise ImpactedTargetsNotFoundError(
            f'Selective presubmit is disabled for file {rel_path}'
        )


def get_impacted_targets_info(
    build_env: bazel.BuildEnv,
    gerrit_info: gerrit.GerritInfo,
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
  _check_selective_presubmit_opt_out(gerrit_info)
  with tempfile.TemporaryDirectory() as temp_dir:
    modified_files = pathlib.Path(temp_dir) / 'modified-files.txt'
    write_modified_files(gerrit_info, modified_files)
    impacted_targets_info = ImpactedTargetsInfo(
        all_targets=_find_impacted_targets(build_env, modified_files),
        baseline_targets=query_baseline_targets(
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


def write_modified_files(
    gerrit_info: gerrit.GerritInfo, modified_files_path: pathlib.Path
) -> None:
  """Writes the modified files to the given path."""
  modified_files = 0
  with open(modified_files_path, 'w') as f:
    for change in gerrit_info.changes:
      for file in change.file_infos:
        if file.status != 'modified':
          continue
        modified_files += 1
        if not change.project_path:
          logging.warning('Change %s has no project path', change)
          continue
        prefix = change.project_path
        filepath = f'{prefix}/{file.path}'.replace('/', os.sep)
        f.write(filepath)
        f.write('\n')
  logging.info('Wrote modified-files with %d changes', modified_files)


def generate_and_upload_hash_file(build_env: bazel.BuildEnv) -> None:
  """Generates and uploads the hash file and copies to incremental out/."""
  object_name = _FILE_NAME.format(
      bid=build_env.build_number,
      target=build_env.build_target_name,
  )
  with tempfile.TemporaryDirectory() as temp_dir:
    # Write empty file for bazel-diff. This is needed when using
    # '--modified-filepaths' in bazel-diff, since it changes the hash file
    # generation logic.
    modified_files = pathlib.Path(temp_dir) / 'no-modified-files.txt'
    modified_files.write_text('\n', encoding='utf-8')
    try:
      hash_file_path = _generate_hash_file(
          build_env, modified_files_path=modified_files
      )
    except subprocess.TimeoutExpired as e:
      logging.warning('generate-hashes timed out after %f seconds.', e.timeout)
      return
  gce.upload_to_gcs(hash_file_path, _BUCKET, object_name)
  incremental_hash_file_path = (
      pathlib.Path(build_env.workspace_dir) / _INCREMENTAL_BAZEL_DIFF_HASH
  )
  incremental_hash_file_path.parent.mkdir(parents=True, exist_ok=True)
  shutil.copy2(hash_file_path, incremental_hash_file_path)
  logging.info('Uploaded hash file to GCS with object name: %s', object_name)


def _generate_hash_file(
    build_env: bazel.BuildEnv,
    deps_output_path: pathlib.Path | None = None,
    modified_files_path: pathlib.Path | None = None,
) -> str:
  """Generates the hash file for the current build."""
  hash_file_path = os.path.join(build_env.dist_dir, 'bazel-diff-hashes.json')
  bazel_diff.generate_hash_file(
      build_env,
      _LOCAL_REPOSITORIES,
      hash_file_path,
      deps_output_path=deps_output_path,
      modified_files_path=modified_files_path,
      deleted_packages=_DELETED_PREBUILTS_PACKAGES,
  )
  return hash_file_path


def get_base_hash_file(build_env: bazel.BuildEnv) -> str:
  """Returns the base hash file for the current build."""
  if os.environ.get('INCREMENTAL_BUILD') == 'true':
    base_hashes_path = (
        pathlib.Path(build_env.workspace_dir) / _INCREMENTAL_BAZEL_DIFF_HASH
    )
    if base_hashes_path.exists():
      logging.info('Found base hash file in incremental out/')
      return str(base_hashes_path)
    logging.warning(
        'Base hash file not found in incremental out/, falling back to GCS'
    )

  reference_bid = gce.get_reference_build_id(
      build_env.build_number,
      build_env.build_target_name,
  )
  logging.info('Found reference build ID: %s', reference_bid)
  if not reference_bid:
    raise ImpactedTargetsNotFoundError(
        f'Reference build ID not found for build {build_env.build_number}'
    )
  object_name = _FILE_NAME.format(
      bid=reference_bid,
      target=build_env.build_target_name,
  )
  base_hashes = pathlib.Path(build_env.dist_dir) / 'base-hashes.json'
  exists = gce.download_from_gcs(
      _BUCKET,
      object_name,
      str(base_hashes),
  )
  if not exists:
    raise ImpactedTargetsNotFoundError(
        f'Base hash file {object_name} not found'
    )
  return str(base_hashes)


def _find_impacted_targets(
    build_env: bazel.BuildEnv,
    modified_files_path: pathlib.Path,
) -> List[ImpactedTarget]:
  """Finds the targets impacted by the current change.

  Args:
    build_env: The build environment.

  Returns:
    The list of impacted targets.

  Raises:
    ImpactedTargetsNotFoundError: If impacted targets could not be determined.
  """
  # Resolve the base hash file before running generate-hashes (which is a
  # long-running operation taking several minutes) so we fail fast when the
  # base hash file is unavailable.
  base_hashes = get_base_hash_file(build_env)
  with tempfile.TemporaryDirectory() as temp_dir:
    temp_path = pathlib.Path(temp_dir)
    dep_edges = temp_path / 'dep-edges.json'
    try:
      current_hashes = _generate_hash_file(
          build_env, dep_edges, modified_files_path
      )
    except subprocess.TimeoutExpired as e:
      raise ImpactedTargetsNotFoundError(
          f'generate-hashes timed out after {e.timeout} seconds'
      )

    impacted_targets = pathlib.Path(build_env.dist_dir) / 'impacted-targets.txt'
    try:
      bazel_diff.get_impacted_targets(
          build_env,
          base_hashes,
          current_hashes,
          dep_edges,
          impacted_targets,
      )
    except subprocess.CalledProcessError as e:
      raise ImpactedTargetsNotFoundError(f'get-impacted-targets failed: {e}')
    data = json.loads(impacted_targets.read_text())
    return [
        ImpactedTarget(
            label=target['label'],
            target_distance=target['targetDistance'],
            package_distance=target['packageDistance'],
        )
        for target in data
    ]


def query_baseline_targets(
    build_env: bazel.BuildEnv,
    base_targets: Sequence[str],
    test_flag_filters: str,
) -> List[str]:
  """Queries the targets that match the given filters.

  The cquery command is used to resolve select() clauses with platform
  specific constraints like @platforms//os:linux. This is frequently used on
  the target_compatible_with attribute.

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
  output = build_env.bazel_cquery(
    query, "--config=ci"
  ).stdout.decode('utf-8').splitlines()
  # Filter out the configuration suffix from the target labels.
  baseline_targets = []
  for line in output:
    baseline_targets.append(line.split(' ', 1)[0])
  return baseline_targets
