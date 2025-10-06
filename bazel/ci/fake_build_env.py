"""Implements a fake build environment for testing."""

import contextlib
import dataclasses
import pathlib
import tempfile
from typing import Iterator
from unittest import mock

from tools.base.bazel.ci import bazel


@dataclasses.dataclass(frozen=True)
class FakeBuildEnv(bazel.BuildEnv):
  """Represents a fake build environment for testing."""

  workspace_path: pathlib.Path
  dist_path: pathlib.Path
  tmp_path: pathlib.Path

  def __post_init__(self):
    for method_name in [
        "bazel_build",
        "bazel_test",
        "bazel_run",
        "bazel_query",
        "bazel_cquery",
        "bazel_info",
        "bazel_shutdown",
    ]:
      # object.__setattr__ must be used here because the dataclass is frozen.
      object.__setattr__(
          self,
          method_name,
          mock.create_autospec(getattr(self, method_name)),
      )


@contextlib.contextmanager
def make_fake_build_env(**kwargs) -> Iterator[bazel.BuildEnv]:
  """Yields a fake build environment for testing.

  Args:
    **kwargs: Keyword arguments to pass to the BuildEnv constructor.
  """
  with contextlib.ExitStack() as es:
    root_dir = es.enter_context(tempfile.TemporaryDirectory())
    root_path = pathlib.Path(root_dir)
    workspace_path = root_path / 'workspace'
    workspace_path.mkdir()
    dist_path = root_path / 'dist'
    dist_path.mkdir()
    tmp_path = root_path / 'tmp'
    tmp_path.mkdir()

    yield FakeBuildEnv(
        build_number='P123',
        build_target_name='studio-test',
        workspace_dir=str(workspace_path),
        workspace_path=workspace_path,
        dist_dir=str(dist_path),
        dist_path=dist_path,
        tmp_dir=str(tmp_path),
        tmp_path=tmp_path,
        bazel_path='',
        bazel_version='7.0.0',
        user='user',
        branch='',
        startup_options=[],
        is_studio_only_release=False,
        **kwargs,
    )
