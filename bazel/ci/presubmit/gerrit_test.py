"""Tests for gerrit."""

from absl.testing import absltest
from tools.base.bazel.ci import fake_build_env
from tools.base.bazel.ci import fake_gce
from tools.base.bazel.ci.presubmit import gerrit


class GerritTest(absltest.TestCase):

  def setUp(self):
    super().setUp()
    self.build_env = self.enter_context(fake_build_env.make_fake_build_env())
    self.gce = self.enter_context(fake_gce.make_fake_gce(self, self.build_env))

  def test_gerrit_info(self):
    changes = [
      self.gce.add_change('owner', 'message', []),
      self.gce.add_change('owner', 'message', [('Tag', 'Value')]),
    ]
    changes[0].topic = 'topic'

    gerrit_info = gerrit.get_gerrit_info(self.build_env)

    self.assertEqual(
        gerrit_info,
        gerrit.GerritInfo(
            _build_env=self.build_env,
            changes=changes,
            changes_hash='31b0f5e6e02fb7fce39ad8cbc2623308bfabe4e357b3856cd7f64343c9d99dce',
        ),
    )

  def test_filter_tags(self):
    changes = [
      self.gce.add_change(
          'owner',
          'message',
          [
              ('Tag', 'Value1'),
              ('Tag', 'studio-test:Value2'),
          ],
      ),
      self.gce.add_change(
          'owner',
          'message',
          [
              ('Tag', 'Value3'),
              ('Tag', 'other-target:Value4'),
          ],
      ),
    ]

    gerrit_info = gerrit.get_gerrit_info(self.build_env)

    self.assertEqual(
        list(gerrit_info.filter_tags('Tag')),
        ['Value1', 'Value2', 'Value3'],
    )

    def test_bazel_flags(self):
      changes = [
        self.gce.add_change('owner', 'message', []),
        self.gce.add_change('owner', 'message', [('Tag', 'Value')]),
      ]
      changes[0].topic = 'topic'

      gerrit_info = gerrit.get_gerrit_info(self.build_env)

      self.assertEqual(
          gerrit_info.get_bazel_flags(),
          [
              f'--build_metadata=gerrit_change_set_hash=31b0f5e6e02fb7fce39ad8cbc2623308bfabe4e357b3856cd7f64343c9d99dce',
              f'--build_metadata=gerrit_owner=owner@google.com',
              f'--build_metadata=gerrit_change_id=changeid0',
              f'--build_metadata=gerrit_change_number=0',
              f'--build_metadata=gerrit_change_patchset=0',
              f'--build_metadata=gerrit_topic=topic',
          ],
      )


if __name__ == "__main__":
  absltest.main()
