"""Tests for query_checks module."""

import subprocess

from absl.testing import absltest
from tools.base.bazel.ci import fake_build_env
from tools.base.bazel.ci import query_checks


class GradleRequiresCpu4OrMoreTest(absltest.TestCase):
  """Tests for gradle_requires_cpu4_or_more query check."""

  def test_no_candidate_targets(self):
    with fake_build_env.make_fake_build_env() as env:
      env.bazel_query.return_value = subprocess.CompletedProcess(
          args=[], returncode=0, stdout=b'', stderr=b''
      )
      query_checks.gradle_requires_cpu4_or_more(env)
      env.bazel_cquery.assert_not_called()

  def test_candidate_targets_none_run_gradle(self):
    with fake_build_env.make_fake_build_env() as env:
      env.bazel_query.return_value = subprocess.CompletedProcess(
          args=[],
          returncode=0,
          stdout=b'//tools/vendor/google/android-cli/integration:android_test\n',
          stderr=b'',
      )
      env.bazel_cquery.return_value = subprocess.CompletedProcess(
          args=[], returncode=0, stdout=b'\n\n', stderr=b''
      )
      query_checks.gradle_requires_cpu4_or_more(env)
      env.bazel_cquery.assert_called_once()

  def test_candidate_targets_with_gradle_fails(self):
    with fake_build_env.make_fake_build_env() as env:
      env.bazel_query.return_value = subprocess.CompletedProcess(
          args=[],
          returncode=0,
          stdout=b'//tools/adt/idea/sync-memory-tests:my_test\n',
          stderr=b'',
      )
      env.bazel_cquery.return_value = subprocess.CompletedProcess(
          args=[],
          returncode=0,
          stdout=b'@@//tools/adt/idea/sync-memory-tests:my_test\n',
          stderr=b'',
      )
      with self.assertRaises(query_checks.BuildGraphException) as ctx:
        query_checks.gradle_requires_cpu4_or_more(env)
      self.assertIn(
          ' //tools/adt/idea/sync-memory-tests:my_test', ctx.exception.body
      )


if __name__ == '__main__':
  absltest.main()
