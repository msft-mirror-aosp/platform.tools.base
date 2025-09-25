"""Implements functions for running tests multiple times."""

import dataclasses
import logging
import pathlib
import tempfile
from typing import Dict, Iterator, List, Tuple

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import gce
from tools.base.bazel.ci.presubmit import impacted_targets
from tools.base.bazel.ci.presubmit import gerrit


_BUCKET = 'adt-byob'
_FILE_NAME = 'known-flakes/{target}.txt'

_MAX_RUNS_PER_TEST = 500
_RUNS_PER_FLAKY_TEST = 100
_MINIMUM_FLAKE_RATE = 0.05


class NoKnownFlakesError(Exception):
  """Error raised when the known flakes file does not exist."""


@dataclasses.dataclass(frozen=True)
class RunsPerTestInfo:
  runs_per_target: Dict[str,int]
  # Contains all targets that are impacted with a package distance of 0 and a
  # failure rate greater than _MINIMUM_FLAKE_RATE.
  impacted_flakes: Dict[str,float]

  def get_bazel_flags(self) -> List[str]:
    runs_per_target = self.runs_per_target.copy()
    runs_per_target.update({k:_RUNS_PER_FLAKY_TEST for k in self.impacted_flakes})
    flags = [f'--runs_per_test=^{k}$@{v}' for k, v in runs_per_target.items()]
    if self.impacted_flakes:
      flags.append(f'--build_metadata=selective_presubmit_impacted_flakes=' +
                   ','.join(f'({k}:{v})' for k, v in self.impacted_flakes.items()))
    return flags


def get_runs_per_test_info(
    build_env: bazel.BuildEnv,
    gerrit_info: gerrit.GerritInfo,
    impacted_targets_info: impacted_targets.ImpactedTargetsInfo | None,
) -> RunsPerTestInfo:
  """Returns information for running tests multiple times."""
  runs_per_target = {}
  for target, runs in _parse_gerrit_tags(gerrit_info):
    runs_per_target[target] = runs

  if impacted_targets_info:
    filtered_impacted_targets = set(
        target.label
        for target in impacted_targets_info.all_targets
        if target.package_distance == 0
    )
  else:
    filtered_impacted_targets = set()

  try:
    impacted_flakes = {
        target:rate
        for target, rate in _parse_known_flakes(build_env, gerrit_info)
        if target in filtered_impacted_targets and rate >= _MINIMUM_FLAKE_RATE
    }
  except NoKnownFlakesError as e:
    logging.warning('Failed to find known flakes: %s', e)
    impacted_flakes = {}

  return RunsPerTestInfo(
      runs_per_target=runs_per_target,
      impacted_flakes=impacted_flakes,
  )


def _parse_gerrit_tags(gerrit_info: gerrit.GerritInfo) -> Iterator[Tuple[str,int]]:
  """Yields runs per test based on Gerrit tags."""
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
    yield (target, runs)

def _parse_known_flakes(
    build_env: bazel.BuildEnv,
    gerrit_info: gerrit.GerritInfo,
) -> Iterator[Tuple[str,float]]:
  for value in gerrit_info.filter_tags('Presubmit-Flaky-Runs-Per-Test'):
    if value.lower() == 'optout':
      logging.info('Opting out of known flakes')
      return

  object_name = _FILE_NAME.format(target=build_env.build_target_name)
  logging.info('Attempting to find known flakes at %s', object_name)

  with tempfile.TemporaryDirectory() as temp_dir:
    temp_path = pathlib.Path(temp_dir) / 'known-flakes.txt'
    if not gce.download_from_gcs(_BUCKET, object_name, str(temp_path)):
      raise NoKnownFlakesError(f'Known flakes file {object_name} not found')
    logging.info('Known flakes file %s found', object_name)

    for line in temp_path.read_text().splitlines():
      target, rate = line.split()
      yield target, float(rate)
