"""Tests for failure_retry."""

from absl.testing import absltest
from absl.testing import parameterized
from tools.base.bazel.ci import fake_build_env
from tools.base.bazel.ci import fake_gce
from tools.base.bazel.ci import gce
from tools.base.bazel.ci.presubmit import failure_retry
from tools.base.bazel.ci.presubmit import gerrit


class FailureRetryTest(parameterized.TestCase):

  def setUp(self):
    super().setUp()
    self.build_env = self.enter_context(fake_build_env.make_fake_build_env())
    self.gce = self.enter_context(fake_gce.make_fake_gce(self, self.build_env))

    self.gce.add_change('owner', 'message', []),
    self.gerrit_info = gerrit.get_gerrit_info(self.build_env)
    self.changes_hash = self.gerrit_info.changes_hash

  def test_failure_retry_info(self):
    failed_tests_path = self.build_env.tmp_path / 'failed_tests.txt'
    failed_tests_path.write_text('\n'.join(['1', '2', '3']))
    gce.upload_to_gcs(
        failed_tests_path,
        'adt-byob',
        f'failed-tests/v1/{self.changes_hash}-studio-test.txt',
    )

    info = failure_retry.get_failure_retry_info(self.build_env, self.gerrit_info)
    self.assertEqual(
        info,
        failure_retry.FailureRetryInfo(
            targets=['1', '2', '3'],
        ),
    )

    self.assertEqual(
        info.get_bazel_flags(),
        [
            '--flaky_test_attempts=1@2',
            '--flaky_test_attempts=2@2',
            '--flaky_test_attempts=3@2',
        ],
    )

  @parameterized.named_parameters(
      dict(
          testcase_name='empty',
          targets=[],
          should_upload=False,
      ),
      dict(
          testcase_name='too_many',
          targets=['1', '2', '3', '4', '5', '6', '7', '8', '9'],
          should_upload=False,
      ),
      dict(
          testcase_name='typical',
          targets=['1', '2', '3'],
          should_upload=True,
      ),
  )
  def test_validate_and_upload(self, targets, should_upload):
    failed_tests_path = self.build_env.dist_path / 'failed_tests.txt'
    failed_tests_path.write_text('\n'.join(targets))
    failure_retry.validate_and_upload(self.build_env)

    downloaded = self.build_env.tmp_path / 'downloaded'
    did_download = gce.download_from_gcs(
        'adt-byob',
        f'failed-tests/v1/{self.changes_hash}-studio-test.txt',
        downloaded,
    )
    self.assertEqual(did_download, should_upload)
    if did_download:
      self.assertEqual(downloaded.read_text(), failed_tests_path.read_text())


if __name__ == "__main__":
  absltest.main()
