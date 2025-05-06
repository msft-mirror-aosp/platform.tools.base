"""Tests for runs_per_test."""

from absl.testing import absltest
from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import fake_build_env
from tools.base.bazel.ci import fake_gce
from tools.base.bazel.ci.presubmit import gerrit
from tools.base.bazel.ci.presubmit import runs_per_test

class RunsPerTestTest(absltest.TestCase):

  def setUp(self):
    super().setUp()
    self.build_env = self.enter_context(fake_build_env.make_fake_build_env())
    self.gce = self.enter_context(fake_gce.make_fake_gce(self, self.build_env))

  def test_gerrit_tags(self):
    self.gce.add_change('owner', 'message', [
        ('Presubmit-Runs-Per-Test', 'studio-test:target1@1'),
        ('Presubmit-Runs-Per-Test', 'studio-other-test:target2@2'),
        ('Presubmit-Runs-Per-Test', 'target3@3'),
        ('Unrelated-Tag', 'target4@4'),
    ])

    gerrit_info = gerrit.get_gerrit_info(self.build_env)
    runs_per_test.get_runs_per_test_info(gerrit_info)
    runs_per_test_info = runs_per_test.get_runs_per_test_info(gerrit_info)

    self.assertEqual(
        runs_per_test_info,
        runs_per_test.RunsPerTestInfo(
            runs_per_target={
                'target1': 1,
                'target3': 3,
            },
        ),
    )

    self.assertCountEqual(
        runs_per_test_info.get_bazel_flags(),
        [
            '--runs_per_test=^target1$@1',
            '--runs_per_test=^target3$@3',
        ],
    )


if __name__ == "__main__":
  absltest.main()
