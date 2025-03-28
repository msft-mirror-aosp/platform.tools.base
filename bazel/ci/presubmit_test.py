"""Tests for presubmit."""

import json
import pathlib
from typing import Iterable, List
from unittest import mock

from absl.testing import absltest
from absl.testing import parameterized
from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import bazel_diff
from tools.base.bazel.ci import fake_build_env
from tools.base.bazel.ci import fake_gce
from tools.base.bazel.ci import gce
from tools.base.bazel.ci import presubmit


class PresubmitTest(parameterized.TestCase):
  """Tests for the presubmit module."""

  def _mock_generate_hash_file(self, contents: str) -> mock.Mock:
    def func(
        build_env: bazel.BuildEnv,
        external_repos: Iterable[str],
        path: str,
        deps_output_path: pathlib.Path | None = None,
    ) -> None:
      del build_env, external_repos
      pathlib.Path(path).write_text(contents)

    return self.enter_context(
        mock.patch.object(bazel_diff, 'generate_hash_file', side_effect=func)
    )

  def _mock_get_impacted_targets(self, targets: List[str]) -> mock.Mock:
    def func(
        build_env: bazel.BuildEnv,
        starting_hashes_path: str,
        final_hashes_path: str,
        dep_edges_path: pathlib.Path,
        output_path: pathlib.Path,
    ) -> None:
      del build_env, starting_hashes_path, final_hashes_path
      out = [{'label': target, 'targetDistance': 0, 'packageDistance': 0} for target in targets]
      output_path.write_text(json.dumps(out))

    return self.enter_context(
        mock.patch.object(bazel_diff, 'get_impacted_targets', side_effect=func)
    )

  def setUp(self):
    super().setUp()
    self.build_env = self.enter_context(fake_build_env.make_fake_build_env())
    self.gce = self.enter_context(fake_gce.make_fake_gce(self, self.build_env))

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
  def test_validate_and_upload_failed_tests(self, targets, should_upload):
    failed_tests_path = self.build_env.dist_path / 'failed_tests.txt'
    failed_tests_path.write_text('\n'.join(targets))
    changes = [
        self.gce.add_change('owner', 'message', []),
        self.gce.add_change('owner', 'message', []),
    ]
    changes_hash = presubmit.change_set_hash(changes)

    presubmit.validate_and_upload_failed_tests(self.build_env)

    downloaded = self.build_env.tmp_path / 'downloaded'
    did_download = gce.download_from_gcs(
        'adt-byob',
        f'failed-tests/v1/{changes_hash}-studio-test.txt',
        downloaded,
    )
    self.assertEqual(did_download, should_upload)
    if did_download:
      self.assertEqual(downloaded.read_text(), failed_tests_path.read_text())

  def test_generate_and_upload_hash_file(self):
    mock_generate = self._mock_generate_hash_file('hash-file')
    presubmit.generate_and_upload_hash_file(self.build_env)
    downloaded = self.build_env.tmp_path / 'downloaded'
    gce.download_from_gcs(
        'adt-byob',
        'bazel-diff-hashes/v8/P123-studio-test.json',
        downloaded,
    )
    self.assertEqual(downloaded.read_text(), 'hash-file')
    mock_generate.assert_called_once_with(
        self.build_env,
        presubmit._LOCAL_REPOSITORIES,
        mock.ANY,
        deps_output_path=None,
    )

  def test_change_set_hash(self):
    changes = [
        gce.GerritChange(
            change_id='',
            change_number='123',
            patchset='2',
            file_infos=[],
            owner='owner',
            message='message',
            topic='topic',
            tags=[],
        ),
    ]
    self.assertEqual(
        presubmit.change_set_hash(changes),
        'e894ae70705dfaee4674cb48c5194c2c56a51be1c3f6fb80aa2510a98512e908',
    )

  @parameterized.named_parameters(
      dict(
          testcase_name='basic',
          tags=[],
          failed_tests=[],
          impacted_targets=['target1', 'target2', 'target3', 'target4'],
          query_targets=['target2', 'target3'],
          expected_targets=['target2', 'target3'],
          expected_flags=[
              f'--build_metadata=selective_presubmit_strategy=impacted_targets',
              f'--build_metadata=selective_presubmit_found=True',
              f'--build_metadata=selective_presubmit_impacted_target_count=2',
              f'--build_metadata=selective_presubmit_target_distance=(0:2)',
              f'--build_metadata=selective_presubmit_package_distance=(0:2)',
          ],
      ),
      dict(
          testcase_name='with_default_presubmit_test',
          tags=[('Presubmit-Test', 'default')],
          failed_tests=[],
          impacted_targets=['target1', 'target2'],
          query_targets=['target2'],
          expected_targets=['base_target1', 'base_target2'],
          expected_flags=[
              f'--build_metadata=selective_presubmit_strategy=default_explicit',
              f'--build_metadata=selective_presubmit_found=False',
          ],
      ),
      dict(
          testcase_name='with_other_target_name',
          tags=[('Presubmit-Test', 'studio-other:target3')],
          failed_tests=[],
          impacted_targets=['target1', 'target2'],
          query_targets=['target2'],
          expected_targets=['target2'],
          expected_flags=[
              f'--build_metadata=selective_presubmit_strategy=impacted_targets',
              f'--build_metadata=selective_presubmit_found=True',
              f'--build_metadata=selective_presubmit_impacted_target_count=1',
              f'--build_metadata=selective_presubmit_target_distance=(0:1)',
              f'--build_metadata=selective_presubmit_package_distance=(0:1)',
          ],
      ),
      dict(
          testcase_name='with_multiple_explicit_targets',
          tags=[
              ('Presubmit-Test', 'target3'),
              ('Presubmit-Test', 'target4'),
          ],
          failed_tests=[],
          impacted_targets=['target1', 'target2'],
          query_targets=['target2'],
          expected_targets=['target2', 'target3', 'target4'],
          expected_flags=[
              f'--build_metadata=selective_presubmit_strategy=impacted_targets',
              f'--build_metadata=selective_presubmit_found=True',
              f'--build_metadata=selective_presubmit_impacted_target_count=1',
              f'--build_metadata=selective_presubmit_target_distance=(0:1)',
              f'--build_metadata=selective_presubmit_package_distance=(0:1)',
          ],
      ),
      dict(
          testcase_name='with_failed_tests',
          tags=[
              ('Presubmit-Test', 'target3'),
              ('Presubmit-Test', 'target4'),
          ],
          failed_tests=['target1', 'target2'],
          impacted_targets=[],
          query_targets=[],
          expected_targets=['target1', 'target2', 'target3', 'target4'],
          expected_flags=[
              f'--flaky_test_attempts=target1@2',
              f'--flaky_test_attempts=target2@2',
              f'--build_metadata=selective_presubmit_strategy=retry_failed',
              f'--build_metadata=selective_presubmit_found=False',
          ],
      ),
  )
  def test_find_test_targets(
      self,
      tags,
      failed_tests,
      impacted_targets,
      query_targets,
      expected_targets,
      expected_flags,
  ):
    if failed_tests:
      failed_tests_path = self.build_env.tmp_path / 'failed_tests.txt'
      failed_tests_path.write_text('\n'.join(failed_tests))
      gce.upload_to_gcs(
          failed_tests_path,
          'adt-byob',
          'failed-tests/v1/15ec7bf0b50732b49f8228e07d24365338f9e3ab994b00af08e5a3bffe55fd8b-studio-test.txt',
      )

    self._mock_generate_hash_file('hash-file')
    self._mock_get_impacted_targets(impacted_targets)
    self.build_env.bazel_query.return_value.stdout = '\n'.join(
        query_targets
    ).encode('utf-8')

    parent_hash_path = self.build_env.tmp_path / 'parent.json'
    parent_hash_path.write_text('parent-hash-file')
    gce.upload_to_gcs(
        parent_hash_path,
        'adt-byob',
        'bazel-diff-hashes/v8/789-studio-test.json',
    )
    self.gce.add_change('owner', 'message', tags)
    self.gce.changes[0].topic = 'topic'

    result = presubmit.find_test_targets(
        self.build_env,
        ['base_target1', 'base_target2'],
        'includefilter,-excludefilter',
    )
    self.assertEqual(set(result.targets), set(expected_targets))
    expected_flags += [
        '--build_metadata=gerrit_change_set_hash=15ec7bf0b50732b49f8228e07d24365338f9e3ab994b00af08e5a3bffe55fd8b',
        '--build_metadata=gerrit_owner=owner@google.com',
        '--build_metadata=gerrit_change_id=changeid0',
        '--build_metadata=gerrit_change_number=0',
        '--build_metadata=gerrit_change_patchset=0',
        '--build_metadata=gerrit_topic=topic',
    ]
    self.assertSameElements(
        result.flags,
        expected_flags,
    )

    if result.strategy == 'impacted_targets':
      self.build_env.bazel_query.assert_called_with(
          'base_target1 union attr(tags, "includefilter", base_target1) union'
          ' base_target2 union attr(tags, "includefilter", base_target2) except'
          ' attr(tags, "excludefilter", base_target1) except attr(tags,'
          ' "manual", base_target1) except attr(target_compatible_with,'
          ' "@platforms//:incompatible", base_target1) except attr(tags,'
          ' "excludefilter", base_target2) except attr(tags, "manual",'
          ' base_target2) except attr(target_compatible_with,'
          ' "@platforms//:incompatible", base_target2)',
      )

  def test_generate_runs_per_test_flags(self):
    self.gce.add_change(
        'owner',
        'message',
        [
            ('Presubmit-Runs-Per-Test', 'studio-test:target1@10'),
            ('Presubmit-Runs-Per-Test', 'target2@20'),
            ('Presubmit-Runs-Per-Test', 'studio-other:target3@30'),
        ],
    )
    self.assertEqual(
        presubmit.generate_runs_per_test_flags(self.build_env),
        ['--runs_per_test=target1@10', '--runs_per_test=target2@20'],
    )


if __name__ == '__main__':
  absltest.main()
