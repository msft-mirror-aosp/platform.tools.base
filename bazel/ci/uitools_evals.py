"""Implements uitools evals CI scripts."""

from typing import List
from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import studio

_TARGETS = [
    '//tools/vendor/google/ml/aiplugin/android/uitools-evals/...',
]

_FLAGS = [
    '--nozip_undeclared_test_outputs',
    '--test_timeout=3600',
    '--nocache_test_results',
    '--config=remote-exec',
    '--test_env=SECRET_MANAGER_KEY=DEFAULT_GEMINI_KEY',
    '--bes_keywords=cinder',
    '--build_metadata=cinder_pipelines=studio-evals',
]

def _get_uitools_evals_tests(build_env: bazel.BuildEnv) -> List[str]:
  """Queries bazel for uitools evals tests."""
  query = [f'tests({" + ".join(_TARGETS)})']
  target_tests = build_env.bazel_query(*query).stdout.decode('utf-8').splitlines()
  return target_tests

def uitools_evals(build_env: bazel.BuildEnv):
  """Runs uitools evals tests."""
  target_tests = _get_uitools_evals_tests(build_env)
  test_result = studio.run_tests(build_env, _FLAGS, target_tests)

  if not studio.is_build_successful(test_result):
    studio.copy_bazel_logs(build_env)
    raise studio.BazelTestError(exit_code=test_result.exit_code)