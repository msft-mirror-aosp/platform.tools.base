import logging
import pathlib
import tempfile
from typing import Iterator, List, Tuple

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import gce
from tools.base.bazel.ci import studio


_BUCKET = 'adt-byob'
_FILE_NAME = 'known-flakes/{target}.txt'


class NoKnownFlakesError(Exception):
  """Raised when the known flakes file is not found."""


def studio_linux_flake_reruns(build_env: bazel.BuildEnv) -> None:
  """Runs studio-linux-flake-reruns target.

  This AB target generates more test runs for recently failing Bazel targets.

  Args:
    build_env: The build environment.
  """
  known_flakes = _parse_known_flakes('studio-linux')
  rerun_flaky_tests(build_env, known_flakes)


def studio_win_flake_reruns(build_env: bazel.BuildEnv) -> None:
  """Runs studio-win-flake-reruns target.

  This AB target generates more test runs for recently failing Bazel targets.

  Args:
    build_env: The build environment.
  """
  known_flakes = _parse_known_flakes('studio-win')
  # TODO: b/409370526 - Implement a more robust tag checking for disallowed
  # targets.
  disallowed_targets = [
      # This target uses the network to get an emulator connection. Running
      # it multiple times will cause quota issues.
      '//tools/adt/idea/android/integration:BuildAndRunTest_windows',
  ]
  rerun_flaky_tests(build_env, known_flakes, disallowed_targets)


def rerun_flaky_tests(
    build_env: bazel.BuildEnv,
    known_flakes: Iterator[Tuple[str, float]],
    disallowed_targets: List[str] | None = None,
) -> None:
  """Runs tests again for recently failing targets."""
  flaky_tests_to_run = []
  if disallowed_targets is None:
    disallowed_targets = []
  for target, rate in known_flakes:
    if target in disallowed_targets:
      continue
    if rate > 0.01:
      flaky_tests_to_run.append(target)
  if not flaky_tests_to_run:
    logging.info('No flaky tests to run')
    return

  runs_per_test = _determine_runs_per_test(flaky_tests_to_run)
  flags = [
      '--config=dynamic',
      f'--runs_per_test={runs_per_test}',
      '--bes_keywords=flake-reruns',
      '-k',  # Continue even if test does not exist.
  ]
  logs_collector_options = studio.LogsCollectorOptions(zip_perfgate_data=False)
  result = studio.run_tests(
      build_env, flags, flaky_tests_to_run, logs_collector_options
  )
  if studio.is_build_successful(result):
    return
  raise studio.BazelTestError(exit_code=result.exit_code)


def _determine_runs_per_test(targets: List[str]) -> int:
  """Returns the runs_per_test to use based on the flaky targets."""
  num_targets = len(targets)
  # Ideas for better heuristics:
  # - Weight by how flaky the target is
  # - Weight by how long the target takes to run (timeout, test size, etc)
  # - Weight by how many shards the target uses
  if num_targets > 50:
    return 1
  if num_targets > 20:
    return 10
  if num_targets > 10:
    return 20
  if num_targets > 5:
    return 50
  return 100


def _parse_known_flakes(
    target_name: str,
) -> Iterator[Tuple[str, float]]:
  object_name = _FILE_NAME.format(target=target_name)
  logging.info('Attempting to find known flakes at %s', object_name)

  with tempfile.TemporaryDirectory() as temp_dir:
    temp_path = pathlib.Path(temp_dir) / 'known-flakes.txt'
    if not gce.download_from_gcs(_BUCKET, object_name, str(temp_path)):
      raise NoKnownFlakesError(f'Known flakes file {object_name} not found')
    logging.info('Known flakes file %s found', object_name)

    for line in temp_path.read_text().splitlines():
      target, rate = line.split()
      yield target, float(rate)
