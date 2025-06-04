"""Tests for impacted_targets."""

import json
import pathlib
import subprocess
from typing import Iterable, List
from unittest import mock

from absl.testing import absltest
from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import fake_build_env
from tools.base.bazel.ci import fake_gce
from tools.base.bazel.ci import gce
from tools.base.bazel.ci.presubmit import bazel_diff
from tools.base.bazel.ci.presubmit import impacted_targets
from tools.base.bazel.ci.presubmit import gerrit


class ImpactedTargetsTest(absltest.TestCase):

  def setUp(self):
    super().setUp()
    self.build_env = self.enter_context(fake_build_env.make_fake_build_env())
    self.gce = self.enter_context(fake_gce.make_fake_gce(self, self.build_env))

  def _mock_generate_hash_file(self, contents: str) -> mock.Mock:
    def func(
        build_env: bazel.BuildEnv,
        external_repos: Iterable[str],
        path: str,
        deps_output_path: pathlib.Path | None = None,
        modified_files_path: pathlib.Path | None = None,
    ) -> None:
      del build_env, external_repos
      pathlib.Path(path).write_text(contents)

    return self.enter_context(
        mock.patch.object(bazel_diff, 'generate_hash_file', side_effect=func)
    )

  def _mock_get_impacted_targets(
      self,
      targets: List[impacted_targets.ImpactedTarget],
  ) -> mock.Mock:
    def func(
        build_env: bazel.BuildEnv,
        starting_hashes_path: str,
        final_hashes_path: str,
        dep_edges_path: pathlib.Path,
        output_path: pathlib.Path,
    ) -> None:
      del build_env, starting_hashes_path, final_hashes_path
      out = [
          {
              'label': t.label,
              'targetDistance': t.target_distance,
              'packageDistance': t.package_distance,
          }
          for t in targets
      ]
      output_path.write_text(json.dumps(out))

    return self.enter_context(
        mock.patch.object(bazel_diff, 'get_impacted_targets', side_effect=func)
    )

  def test_impacted_targets_info(self):
    expected_impacted_targets = [
        impacted_targets.ImpactedTarget('target1', 0, 0),
        impacted_targets.ImpactedTarget('target2', 0, 1),
        impacted_targets.ImpactedTarget('target3', 1, 2),
        impacted_targets.ImpactedTarget('target4', 2, 3),
    ]
    expected_baseline_targets = ['target2', 'target3']

    self._mock_generate_hash_file('hash-file')
    self._mock_get_impacted_targets(expected_impacted_targets)
    self.build_env.bazel_query.return_value.stdout = '\n'.join(
        expected_baseline_targets,
    ).encode('utf-8')

    parent_hash_path = self.build_env.tmp_path / 'parent.json'
    parent_hash_path.write_text('parent-hash-file')
    gce.upload_to_gcs(
        parent_hash_path,
        'adt-byob',
        'bazel-diff-hashes/v8/789-studio-test.json',
    )

    info = impacted_targets.get_impacted_targets_info(
        self.build_env, gerrit.GerritInfo(_build_env=self.build_env, changes=[], changes_hash=''), [], ''
    )
    self.assertEqual(
        info,
        impacted_targets.ImpactedTargetsInfo(
            all_targets=expected_impacted_targets,
            baseline_targets=expected_baseline_targets,
        ),
    )

    self.assertEqual(
        info.get_direct_targets(),
        [
          impacted_targets.ImpactedTarget('target1', 0, 0),
          impacted_targets.ImpactedTarget('target2', 0, 1),
        ],
    )

    self.assertEqual(
        info.get_filtered_targets(),
        [
          impacted_targets.ImpactedTarget('target2', 0, 1),
          impacted_targets.ImpactedTarget('target3', 1, 2),
        ],
    )

    self.assertEqual(
        info.get_bazel_flags(),
        [
            '--build_metadata=selective_presubmit_impacted_target_count=2',
            '--build_metadata=selective_presubmit_target_distance=(0:1),(1:1)',
            '--build_metadata=selective_presubmit_package_distance=(1:1),(2:1)',
        ]
    )

  def test_get_impacted_targets_failure(self):
    self._mock_generate_hash_file('hash-file')
    self.enter_context(
        mock.patch.object(
            bazel_diff,
            'get_impacted_targets',
            side_effect=subprocess.CalledProcessError(returncode=1, cmd=[]),
        )
    )

    parent_hash_path = self.build_env.tmp_path / 'parent.json'
    parent_hash_path.write_text('parent-hash-file')
    gce.upload_to_gcs(
        parent_hash_path,
        'adt-byob',
        'bazel-diff-hashes/v8/789-studio-test.json',
    )

    with self.assertRaises(impacted_targets.ImpactedTargetsNotFoundError):
      impacted_targets.get_impacted_targets_info(
          self.build_env,
          gerrit.GerritInfo(
              _build_env=self.build_env, changes=[], changes_hash=''
          ),
          [],
          '',
      )

  def test_generate_and_upload_hash_file(self):
    mock_generate = self._mock_generate_hash_file('hash-file')
    impacted_targets.generate_and_upload_hash_file(self.build_env)
    downloaded = self.build_env.tmp_path / 'downloaded'
    gce.download_from_gcs(
        'adt-byob',
        'bazel-diff-hashes/v8/P123-studio-test.json',
        downloaded,
    )
    self.assertEqual(downloaded.read_text(), 'hash-file')
    mock_generate.assert_called_once_with(
        self.build_env,
        impacted_targets._LOCAL_REPOSITORIES,
        mock.ANY,
        deps_output_path=None,
        modified_files_path=mock.ANY,
    )


if __name__ == "__main__":
  absltest.main()
