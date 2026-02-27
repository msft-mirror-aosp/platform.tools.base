import logging
import pathlib
import tempfile
from typing import Iterator, Tuple

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
  flags = [
      '--config=dynamic',
      '--bes_keywords=flake-reruns',
      '--runs_per_test=100',
      '-k',  # Continue even if test does not exist.
  ]
  known_flakes = _parse_known_flakes('studio-linux')
  flaky_tests_to_run = []
  for target, rate in known_flakes:
    if rate > 0.01:
      flaky_tests_to_run.append(target)
  if not flaky_tests_to_run:
    logging.info('No flaky tests to run')
    return

  result = studio.run_tests(build_env, flags, flaky_tests_to_run)
  if studio.is_build_successful(result):
    return
  raise studio.BazelTestError(exit_code=result.exit_code)


def studio_win_flake_reruns(build_env: bazel.BuildEnv) -> None:
  """Runs studio-win-flake-reruns target.

  This AB target generates more test runs for recently failing Bazel targets.

  Args:
    build_env: The build environment.
  """
  flags = [
      '--config=dynamic',
      '--bes_keywords=flake-reruns',
      '--runs_per_test=100',
      '-k',  # Continue even if test does not exist.
  ]
  known_flakes = _parse_known_flakes('studio-win')
  flaky_tests_to_run = []
  for target, rate in known_flakes:
    if rate > 0.01:
      flaky_tests_to_run.append(target)
  if not flaky_tests_to_run:
    logging.info('No flaky tests to run')
    return

  result = studio.run_tests(build_env, flags, flaky_tests_to_run)
  if studio.is_build_successful(result):
    return
  raise studio.BazelTestError(exit_code=result.exit_code)


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
