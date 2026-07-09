from absl import logging
from absl.testing import absltest


class NoopTest(absltest.TestCase):

  def test_noop(self):
    msg = "The test is being executed as introduced mutation did not affect any test targets."
    logging.info(msg)

if __name__ == '__main__':
  absltest.main()
