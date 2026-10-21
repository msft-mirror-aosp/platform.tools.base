"""Module for running bazel-diff."""

import logging
import os
import pathlib
import subprocess
import sys
import tempfile
import time
from typing import Sequence

from tools.base.bazel.ci import bazel


# Bzlmod local_repository targets pointing to tools/idea. Excluding them from
# `bazel mod show_repo` prevents bazel-diff v26.0.0's
# computeLocalRepoContentHash() from walking and hashing ~274k files in
# tools/idea on every run.
_EXCLUDED_BZLMOD_REPOS = (
    '@@community+',
    '@@rules_jvm+',
    '@@lib+',
    '@@jps_to_bazel+',
)


def _make_bazel_wrapper(temp_dir: pathlib.Path, bazel_path: str) -> pathlib.Path:
  """Creates a wrapper around bazel_path that filters excluded bzlmod repos."""
  if os.name == 'nt':
    py_script = temp_dir / 'bazel_wrapper.py'
    py_script.write_text(
        'import subprocess, sys\n'
        f'EXCLUDED = {set(_EXCLUDED_BZLMOD_REPOS)!r}\n'
        'args = sys.argv[1:]\n'
        "if 'show_repo' in args:\n"
        '  args = [a for a in args if a not in EXCLUDED]\n'
        f'raise SystemExit(subprocess.call([{bazel_path!r}, *args]))\n'
    )
    wrapper = temp_dir / 'bazel_wrapper.bat'
    wrapper.write_text(f'@"{sys.executable}" "{py_script}" %*\n')
    return wrapper

  excluded_pattern = '|'.join(_EXCLUDED_BZLMOD_REPOS)
  wrapper = temp_dir / 'bazel_wrapper.sh'
  wrapper.write_text(
      '#!/bin/sh\n'
      'for arg in "$@"; do\n'
      '  if [ "$arg" = "show_repo" ]; then\n'
      '    for a in "$@"; do\n'
      '      case "$a" in\n'
      f'        {excluded_pattern}) ;;\n'
      '        *) set -- "$@" "$a" ;;\n'
      '      esac\n'
      '      shift\n'
      '    done\n'
      '    break\n'
      '  fi\n'
      'done\n'
      f'exec "{bazel_path}" "$@"\n'
  )
  wrapper.chmod(0o755)
  return wrapper


def _run(cmd: Sequence[str], timeout: int = 300):
  logging.info('Running command: %s', cmd)
  return subprocess.run(
      cmd,
      check=True,
      timeout=timeout,
  )


def generate_hash_file(
    build_env: bazel.BuildEnv,
    external_repos: Sequence[str],
    output_path: pathlib.Path,
    deps_output_path: pathlib.Path | None = None,
    modified_files_path: pathlib.Path | None = None,
    deleted_packages: Sequence[str] = (),
):
  """Generates the hash file for the current build."""
  start = time.time()
  with tempfile.TemporaryDirectory() as temp_dir:
    bazel_wrapper = _make_bazel_wrapper(
        pathlib.Path(temp_dir), build_env.bazel_path
    )
    args = [
        os.environ['BAZEL_DIFF_BINARY'],
        'generate-hashes',
        '--bazelPath',
        str(bazel_wrapper),
        '--workspacePath',
        build_env.workspace_dir,
    ]
    if build_env.startup_options:
      args.extend(['--bazelStartupOptions', ' '.join(build_env.startup_options)])
    args.extend([
        '--bazelCommandOptions',
        '--config=ci'
        + (
            f' --deleted_packages={",".join(deleted_packages)}'
            if deleted_packages
            else ''
        ),
    ])
    if external_repos:
      args.extend([
          '--fineGrainedHashExternalRepos',
          ','.join(external_repos),
      ])
    if deps_output_path:
      args.extend(['--depEdgesFile', deps_output_path])
    if modified_files_path:
      args.extend(['--modified-filepaths', str(modified_files_path)])
    args.append(str(output_path))
    _run(args)
  end = time.time()
  logging.info('generate-hashes took %d seconds', end - start)


def get_impacted_targets(
    build_env: bazel.BuildEnv,
    starting_hashes_path: str,
    final_hashes_path: str,
    dep_edges_path: pathlib.Path,
    output_path: pathlib.Path,
) -> None:
  """Generates the list of impacted targets given base and current hash file paths."""
  start = time.time()
  args = [
      os.environ['BAZEL_DIFF_BINARY'],
      '--verbose',
      'get-impacted-targets',
      '--bazelPath',
      build_env.bazel_path,
      '--workspacePath',
      build_env.workspace_dir,
  ]
  if build_env.startup_options:
    args.extend(['--bazelStartupOptions', ' '.join(build_env.startup_options)])
  args.extend([
      '--startingHashes',
      starting_hashes_path,
      '--finalHashes',
      final_hashes_path,
      '--depEdgesFile',
      str(dep_edges_path),
      '--output',
      str(output_path),
  ])
  _run(args)
  end = time.time()
  logging.info('get-impacted-targets took %d seconds', end - start)
