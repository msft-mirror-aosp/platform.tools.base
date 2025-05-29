"""Implements shared functions for selective presubmit."""

import dataclasses
import enum
import logging
from typing import List, Sequence

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci.presubmit import impacted_targets
from tools.base.bazel.ci.presubmit import failure_retry
from tools.base.bazel.ci.presubmit import gerrit
from tools.base.bazel.ci.presubmit import runs_per_test


class SelectivePresubmitStrategy(enum.Enum):
  """Strategies used to select targets for selective presubmit."""
  DEFAULT_FALLBACK = 'default_fallback'
  DEFAULT_EXPLICIT = 'default_explicit'
  RETRY_FAILED     = 'retry_failed'
  IMPACTED_TARGETS = 'impacted_targets'


@dataclasses.dataclass(frozen=True)
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

  try:
    impacted_targets_info = impacted_targets.get_impacted_targets_info(
        build_env,
        gerrit_info,
        base_targets,
        test_flag_filters,
    )
  except impacted_targets.ImpactedTargetsNotFoundError as e:
    logging.warning('Failed to find impacted test targets: %s', e)
    impacted_targets_info = None

  runs_per_test_info = runs_per_test.get_runs_per_test_info(
      build_env,
      gerrit_info,
      impacted_targets_info,
  )

  flags = gerrit_info.get_bazel_flags() + runs_per_test_info.get_bazel_flags()

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
  if impacted_targets_info:
    labels = [t.label for t in impacted_targets_info.get_filtered_targets()]
    logging.info('Found %d impacted targets', len(labels))

    return SelectivePresubmitResult(
        strategy=SelectivePresubmitStrategy.IMPACTED_TARGETS,
        targets=labels + explicit_targets,
        base_flags = flags + impacted_targets_info.get_bazel_flags(),
    )

  logging.warning('Falling back to testing default targets')
  return SelectivePresubmitResult(
      strategy=SelectivePresubmitStrategy.DEFAULT_FALLBACK,
      targets=base_targets + explicit_targets,
      base_flags=flags,
  )
