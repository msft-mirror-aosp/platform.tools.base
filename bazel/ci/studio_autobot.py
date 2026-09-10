"""Implements Android Studio AI Automations"""

from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import studio


def studio_journeys(build_env: bazel.BuildEnv):
  """Runs studio journey tests"""
  query = [f'attr(tags, "e2e-journey-test", "//tools/...")']
  target_tests = build_env.bazel_query(*query).stdout.decode('utf-8').splitlines()
  flags = [
    '--test_timeout=3600',
    '--nocache_test_results',
    '--config=remote-exec',
    '--test_env=SECRET_MANAGER_PROJECT_ID=android-studio-test-automation',
    '--test_env=SECRET_MANAGER_KEY=GEMINI_API_KEY',
    '--jobs=5',  # avoid having too many jobs in parallel to prevent quota issues
  ]
  test_result = studio.run_tests(build_env, flags, target_tests)
  if not studio.is_build_successful(test_result):
    raise studio.BazelTestError(exit_code=test_result.exit_code)
