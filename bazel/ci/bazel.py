"""Provides the current build environment and Bazel commands."""

import dataclasses
import getpass
import logging
import os
import subprocess
import tempfile
from typing import List


EXITCODE_SUCCESS = 0
EXITCODE_TEST_FAILURES = 3
EXITCODE_NO_TESTS_FOUND = 4


@dataclasses.dataclass(frozen=True)
class BuildEnv:
  """Represents the build environment."""

  build_number: str
  build_target_name: str
  workspace_dir: str
  dist_dir: str
  tmp_dir: str
  bazel_path: str
  bazel_version: str
  user: str
  branch: str

  # Startup options for Bazel commands.
  startup_options: List[str]

  def bazel_build(
      self, *build_args, timeout=None
  ) -> subprocess.CompletedProcess:
    """Runs a 'bazel build' command."""
    return self._bazel(True, False, "build", *build_args, timeout=timeout)

  def bazel_test(self, *test_args, timeout=None) -> subprocess.CompletedProcess:
    """Runs a 'bazel test' command."""
    return self._bazel(False, False, "test", *test_args, timeout=timeout)

  def bazel_run(self, *run_args, timeout=None) -> subprocess.CompletedProcess:
    """Runs a 'bazel run' command.

    Raises:
      CalledProcessError: If the command fails.
    """
    return self._bazel(False, True, "run", *run_args, timeout=timeout)

  def bazel_query(self, *query_args) -> subprocess.CompletedProcess:
    """Runs a 'bazel query' command.

    Raises:
      CalledProcessError: If the query fails.
    """
    return self._bazel(True, True, "query", *query_args)

  def bazel_cquery(self, *query_args) -> subprocess.CompletedProcess:
    """Runs a 'bazel cquery' command.

    Raises:
      CalledProcessError: If the query fails.
    """
    return self._bazel(True, True, "cquery", *query_args)

  def bazel_info(self, *info_args) -> subprocess.CompletedProcess:
    """Runs a 'bazel info' command.

    Raises:
      CalledProcessError: If the command fails.
    """
    return self._bazel(True, True, "info", *info_args)

  def bazel_shutdown(self) -> subprocess.CompletedProcess:
    """Runs a 'bazel shutdown' command."""
    return self._bazel(False, False, "shutdown")

  def _bazel(
      self,
      capture_output: bool,
      check: bool,
      *args: List[str],
      timeout: int = None,
  ) -> subprocess.CompletedProcess:
    """Runs a Bazel command with the given args."""
    cmd = [self.bazel_path, *self.startup_options, *args]
    # Inherit env vars, but drop problematic ones added by the parent Bazel invocation.
    # E.g., PYTHONSAFEPATH causes problems for Python scripts in repository rules (b/395760815).
    env = os.environ.copy()
    env.pop("PYTHONSAFEPATH", None)
    logging.info("Running command: %s", cmd)
    return subprocess.run(
        cmd,
        capture_output=capture_output,
        check=check,
        cwd=self.workspace_dir,
        env=env,
        timeout=timeout,
    )


def make_build_env(
    bazel_path: str,
    user: str = getpass.getuser(),
    bazel_version: str = "",
):
  build_number = os.environ.get("BUILD_NUMBER", "SNAPSHOT")
  build_target_name = os.environ.get("BUILD_TARGET_NAME", "")
  workspace_dir = os.environ.get("BUILD_WORKSPACE_DIRECTORY", "")
  dist_dir = os.environ.get("DIST_DIR")
  if dist_dir is None:
    # If DIST_DIR does not exist, create one.
    dist_dir = tempfile.mkdtemp('dist-dir')
  tmp_dir = os.environ.get("TMPDIR", "")
  bazel_path = os.path.normpath(bazel_path)
  if not bazel_version:
    with open(os.path.join(workspace_dir, ".bazelversion")) as f:
      bazel_version = f.readline().rstrip()
  # Assuming the workspace root is the name of the branch.
  # Ideally, buildbot provides a concerete environment variable.
  branch = workspace_dir.split("/")[-1]

  startup_options = ["--max_idle_secs=60"]
  if build_target_name and user == "android-build":  # AB environment
    install_base = os.path.join(tmp_dir, "bazel_install", bazel_version)
    startup_options.extend([
        f"--output_base={os.path.join(tmp_dir, 'bazel_out')}",
        f"--install_base={install_base}",
    ])

  return BuildEnv(
      build_number=build_number,
      build_target_name=build_target_name,
      workspace_dir=workspace_dir,
      dist_dir=dist_dir,
      tmp_dir=tmp_dir,
      bazel_path=bazel_path,
      bazel_version=bazel_version,
      user=user,
      branch=branch,
      startup_options=startup_options,
  )
