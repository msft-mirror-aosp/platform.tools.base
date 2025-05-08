"""Implements functions for running tests multiple times."""

import dataclasses
import logging
from typing import Dict, Iterator, List, Tuple

from tools.base.bazel.ci.presubmit import gerrit


_MAX_RUNS_PER_TEST = 200


@dataclasses.dataclass(frozen=True)
class RunsPerTestInfo:
  runs_per_target: Dict[str,int]

  def get_bazel_flags(self) -> List[str]:
    return [f'--runs_per_test=^{k}$@{v}' for k, v in self.runs_per_target.items()]


def get_runs_per_test_info(gerrit_info: gerrit.GerritInfo) -> RunsPerTestInfo:
  """Returns information for running tests multiple times."""
  runs_per_target = {}
  for target, runs in _parse_gerrit_tags(gerrit_info):
    runs_per_target[target] = runs

  return RunsPerTestInfo(runs_per_target=runs_per_target)


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
