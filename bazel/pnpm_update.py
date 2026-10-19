"""Hermetic pnpm runner and lockfile updater for Android Studio."""

import os
import subprocess
import sys


def find_pnpm_binary():
  candidates = [
      'aspect_rules_js++pnpm+pnpm/pnpm_/pnpm.cmd',
      'aspect_rules_js++pnpm+pnpm/pnpm_/pnpm.exe',
      'aspect_rules_js++pnpm+pnpm/pnpm_/pnpm',
  ]

  # Find the .runfiles directory from the script's path or environment
  runfiles_dirs = []
  if 'RUNFILES_DIR' in os.environ:
    runfiles_dirs.append(os.environ['RUNFILES_DIR'])

  this_file = os.path.abspath(__file__)
  idx = this_file.find('.runfiles')
  if idx != -1:
    runfiles_dirs.append(this_file[: idx + len('.runfiles')])

  if os.path.exists(sys.argv[0] + '.runfiles'):
    runfiles_dirs.append(sys.argv[0] + '.runfiles')

  for runfiles_dir in runfiles_dirs:
    for candidate in candidates:
      p = os.path.join(runfiles_dir, candidate)
      if os.path.exists(p):
        return p

  for runfiles_dir in runfiles_dirs:
    for root, _, files in os.walk(runfiles_dir):
      for f in files:
        if f in ['pnpm', 'pnpm.cmd', 'pnpm.exe'] and 'pnpm_' in root:
          return os.path.join(root, f)

  return None


def main():
  workspace = os.environ.get('BUILD_WORKSPACE_DIRECTORY')
  if not workspace:
    workspace = os.getcwd()

  pnpm_dir = os.path.join(workspace, 'tools', 'base', 'bazel')
  pnpm_bin = find_pnpm_binary()

  if not pnpm_bin:
    sys.exit('Error: Could not locate hermetic pnpm binary in runfiles.')

  args = sys.argv[1:]
  if not args:
    args = ['install', '--lockfile-only']

  cmd = [pnpm_bin, '--dir', pnpm_dir] + args
  sys.exit(subprocess.call(cmd))


if __name__ == '__main__':
  main()
