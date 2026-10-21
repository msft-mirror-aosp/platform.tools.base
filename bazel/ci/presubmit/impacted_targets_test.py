"""Tests for impacted_targets."""

import json
import os
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

  def _mock_generate_hash_file(
      self, contents: str, deps_contents: str = '{}'
  ) -> mock.Mock:
    def func(
        build_env: bazel.BuildEnv,
        external_repos: Iterable[str],
        path: str,
        deps_output_path: pathlib.Path | None = None,
        modified_files_path: pathlib.Path | None = None,
        deleted_packages: Iterable[str] = (),
    ) -> None:
      del build_env, external_repos, modified_files_path, deleted_packages
      pathlib.Path(path).write_text(contents)
      if deps_output_path:
        deps_output_path.write_text(deps_contents)

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
    self._mock_generate_hash_file('hash-file')
    self._mock_get_impacted_targets(expected_impacted_targets)
    self.build_env.bazel_cquery.return_value.stdout = '\n'.join(
        ['target2 (configA)', 'target3 (configB)'],
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
            baseline_targets=['target2', 'target3'],
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
    incremental_hash_path = pathlib.Path(self.build_env.workspace_dir) / 'out/bazel-diff-hashes.json'
    self.assertEqual(incremental_hash_path.read_text(), 'hash-file')
    mock_generate.assert_called_once_with(
        self.build_env,
        impacted_targets._LOCAL_REPOSITORIES,
        mock.ANY,
        deps_output_path=None,
        modified_files_path=mock.ANY,
        deleted_packages=impacted_targets._DELETED_PREBUILTS_PACKAGES,
    )

  def test_impacted_targets_info_with_real_bazel_diff(self):
    if os.name == 'nt':
      fake_bazel = self.build_env.tmp_path / 'fake_bazel.bat'
      fake_bazel.write_text('@echo off\necho fake_output_base\n')
    else:
      fake_bazel = self.build_env.tmp_path / 'fake_bazel.sh'
      fake_bazel.write_text('#!/bin/sh\necho fake_output_base\n')
      fake_bazel.chmod(0o755)
    object.__setattr__(self.build_env, 'bazel_path', str(fake_bazel))
    self._mock_generate_hash_file(
        json.dumps({
            '//pkg:target1': 'Rule#a2~a2',
            '//pkg:target2': 'Rule#b2~b1',
        }),
        deps_contents=json.dumps({'//pkg:target2': ['//pkg:target1']}),
    )
    self.build_env.bazel_cquery.return_value.stdout = (
        b'//pkg:target2 (configA)\n'
    )

    parent_hash_path = self.build_env.tmp_path / 'parent.json'
    parent_hash_path.write_text(
        json.dumps({
            '//pkg:target1': 'Rule#a1~a1',
            '//pkg:target2': 'Rule#b1~b1',
        })
    )
    gce.upload_to_gcs(
        parent_hash_path,
        'adt-byob',
        'bazel-diff-hashes/v8/789-studio-test.json',
    )

    info = impacted_targets.get_impacted_targets_info(
        self.build_env,
        gerrit.GerritInfo(
            _build_env=self.build_env, changes=[], changes_hash=''
        ),
        [],
        '',
    )
    self.assertEqual(
        info.get_filtered_targets(),
        [impacted_targets.ImpactedTarget('//pkg:target2', 1, 0)],
    )

  def test_missing_base_hash_skips_generate_hashes(self):
    mock_generate = self._mock_generate_hash_file('hash-file')
    with self.assertRaises(impacted_targets.ImpactedTargetsNotFoundError):
      impacted_targets.get_impacted_targets_info(
          self.build_env,
          gerrit.GerritInfo(
              _build_env=self.build_env, changes=[], changes_hash=''
          ),
          [],
          '',
      )
    mock_generate.assert_not_called()

  def test_incremental_build_fallback_to_gcs(self):
    parent_hash_path = self.build_env.tmp_path / 'parent.json'
    parent_hash_path.write_text('parent-hash-file')
    gce.upload_to_gcs(
        parent_hash_path,
        'adt-byob',
        'bazel-diff-hashes/v8/789-studio-test.json',
    )
    with mock.patch.dict(os.environ, {'INCREMENTAL_BUILD': 'true'}):
      base_hash_path = impacted_targets.get_base_hash_file(self.build_env)
    self.assertEqual(pathlib.Path(base_hash_path).read_text(), 'parent-hash-file')

  def test_prebuilts_and_m2_changes_opted_out(self):
    mock_generate = self._mock_generate_hash_file('hash-file')
    cases = [
        [
            gce.GerritChange(
                change_id='c1',
                change_number='1',
                project='platform/tools/base',
                project_path='tools/base',
                patchset='1',
                file_infos=[gce.FileInfo('foo/Bar.java', 'modified')],
                owner='owner',
                message='msg',
                topic='topic1',
                tags=[],
            ),
            gce.GerritChange(
                change_id='c2',
                change_number='2',
                project='platform/prebuilts/tools',
                project_path='prebuilts/tools',
                patchset='1',
                file_infos=[
                    gce.FileInfo(
                        'common/m2/repository/foo/bar.jar', 'modified'
                    )
                ],
                owner='owner',
                message='msg',
                topic='topic1',
                tags=[],
            ),
        ],
        [
            gce.GerritChange(
                change_id='c3',
                change_number='3',
                project='platform/tools/base',
                project_path='tools/base',
                patchset='1',
                file_infos=[
                    gce.FileInfo('bazel/maven/BUILD.maven', 'modified')
                ],
                owner='owner',
                message='msg',
                topic='',
                tags=[],
            ),
        ],
        [
            gce.GerritChange(
                change_id='c4',
                change_number='4',
                project='platform/tools/base',
                project_path='tools/base',
                patchset='1',
                file_infos=[
                    gce.FileInfo(
                        'bazel/sdk/prebuilts.studio.sdk.BUILD', 'modified'
                    )
                ],
                owner='owner',
                message='msg',
                topic='',
                tags=[],
            ),
        ],
        [
            gce.GerritChange(
                change_id='c5',
                change_number='5',
                project=(
                    'platform/tools/vendor/google_prebuilts/studio/sdk/linux'
                ),
                project_path='prebuilts/studio/sdk/linux',
                patchset='1',
                file_infos=[
                    gce.FileInfo('platforms/android-35/android.jar', 'modified')
                ],
                owner='owner',
                message='msg',
                topic='',
                tags=[],
            ),
        ],
        [
            gce.GerritChange(
                change_id='c6',
                change_number='6',
                project='platform/tools/idea',
                project_path='tools/idea',
                patchset='1',
                file_infos=[gce.FileInfo('platform/util/Foo.java', 'modified')],
                owner='owner',
                message='msg',
                topic='',
                tags=[],
            ),
        ],
        [
            gce.GerritChange(
                change_id='c7',
                change_number='7',
                project='platform/tools/base',
                project_path='tools/base',
                patchset='1',
                file_infos=[
                    gce.FileInfo(
                        'bazel/bzlmod/idea.MODULE.bazel', 'modified'
                    )
                ],
                owner='owner',
                message='msg',
                topic='',
                tags=[],
            ),
        ],
    ]
    for changes in cases:
      with self.subTest(changes=changes):
        with self.assertRaises(impacted_targets.ImpactedTargetsNotFoundError):
          impacted_targets.get_impacted_targets_info(
              self.build_env,
              gerrit.GerritInfo(
                  _build_env=self.build_env,
                  changes=changes,
                  changes_hash='',
              ),
              [],
              '',
          )
    mock_generate.assert_not_called()

  def test_generate_hash_file_excludes_prebuilts_and_m2(self):
    self.assertEqual(impacted_targets._LOCAL_REPOSITORIES, [])
    with mock.patch.object(subprocess, 'run') as mock_run:
      impacted_targets._generate_hash_file(self.build_env)
    mock_run.assert_called_once()
    cmd = mock_run.call_args[0][0]
    self.assertNotIn('--fineGrainedHashExternalRepos', cmd)
    cmd_options_idx = cmd.index('--bazelCommandOptions')
    self.assertEqual(
        cmd[cmd_options_idx + 1],
        '--config=ci'
        ' --deleted_packages=prebuilts/studio/sdk,prebuilts/tools/common/m2',
    )

  def test_bazel_wrapper_filters_excluded_bzlmod_repos(self):
    if os.name == 'nt':
      fake_bazel = self.build_env.tmp_path / 'echo_args.bat'
      fake_bazel.write_text('@echo off\necho %*\n')
    else:
      fake_bazel = self.build_env.tmp_path / 'echo_args.sh'
      fake_bazel.write_text('#!/bin/sh\necho "$*"\n')
      fake_bazel.chmod(0o755)
    wrapper = bazel_diff._make_bazel_wrapper(
        self.build_env.tmp_path, str(fake_bazel)
    )
    res = subprocess.run(
        [
            str(wrapper),
            'mod',
            'show_repo',
            '@@community+',
            '@@rules_jvm+',
            '@@lib+',
            '@@jps_to_bazel+',
            '@@+new_local_repository+maven',
            '--output=streamed_proto',
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    self.assertEqual(
        res.stdout.strip(),
        'mod show_repo @@+new_local_repository+maven --output=streamed_proto',
    )


if __name__ == "__main__":
  absltest.main()
