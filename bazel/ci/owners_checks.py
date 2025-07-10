import os
import sys
import argparse

from functools import cache
from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import errors


_EXEMPT_LIST = "tools/base/bazel/ci/owners_exempt.lst"

def _is_single_directory_owned(directory_path: str) -> bool:
  """Checks if the specified directory contains an 'OWNERS' file with a '# Bug component:' line. """
  owner_file_path = os.path.join(directory_path, 'OWNERS')

  if os.path.exists(owner_file_path) and os.path.isfile(owner_file_path):
    with open(owner_file_path, 'r', encoding='utf-8') as f:
      for line in f:
        if line.strip().startswith('# Bug component:'):
          return True
  return False

@cache
def _is_directory_owned(directory_path: str) -> bool:
  """Checks if a directory is itself or are its parents owned. """

  if _is_single_directory_owned(directory_path):
    return True

  parent_dir = os.path.dirname(directory_path)
  if parent_dir != directory_path:
    return _is_directory_owned(parent_dir)

  return False


def require_component_id(build_env: bazel.BuildEnv):
  """ Checks all files in the workspace are owned (except exempted ones)."""

  success = _check_owned_files(build_env.workspace_dir)
  if not success:
    raise errors.CIError()


def _find_unowned_files(workspace_dir: str) -> (set[str], int):
  """Returns the set of files not owned in a directory tree."""

  unowned_files = set()
  sources = [
      'tools/base',
      'tools/adt/idea',
      'tools/vendor/google',
      'tools/vendor/google3',
  ]

  total = 0
  for source_root in sources:
    directory_path = os.path.join(workspace_dir, source_root)
    for root, _, files in os.walk(directory_path):
      for file in files:
        if file.endswith(('.java', '.kt')):
          total += 1
          if not _is_directory_owned(root):
            file_path = os.path.join(root, file)
            workspace_path = os.path.relpath(file_path, workspace_dir)
            unowned_files.add(workspace_path)

  return unowned_files, total


def _write_exempt_files(workspace_dir: str, files: set[str]):
  file_path = os.path.join(workspace_dir, _EXEMPT_LIST)
  with open(file_path, 'w', encoding='utf-8') as file:
    for path in sorted(files):
      file.write(f"{path}\n")


def _read_exempt_files(workspace_dir: str) -> set[str]:
  file_path = os.path.join(workspace_dir, _EXEMPT_LIST)
  exempt_files = set()
  with open(file_path, 'r', encoding='utf-8') as file:
    for line in file:
      exempt_files.add(line.strip())
  return exempt_files


def _check_owned_files(workspace_dir:str) -> bool:
  """Returns true if given directory contains all owned files (except exempt ones)."""
  unowned, total = _find_unowned_files(workspace_dir)
  exempt = _read_exempt_files(workspace_dir)
  new_files = unowned - exempt
  print(f'Checked {total} files. Of which {len(new_files)} are unowned, and {len(unowned) - len(new_files)} are exempt.')
  if new_files:
    for f in new_files:
      print(f'ERROR: {f} is not owned.')
    print(f'Total unowned files: {len(new_files)}')
    print('See go/studio-code-ownership for more info')
  return len(new_files) == 0


def _update_owned_files(workspace_dir):
  unowned, _ = _find_unowned_files(workspace_dir)
  _write_exempt_files(workspace_dir, unowned)

def main():
  parser = argparse.ArgumentParser()
  parser.add_argument('action', help='The action to perform', choices=["check", "update"], nargs="?", default="update")
  args = parser.parse_args()
  workspace_dir = os.environ.get('BUILD_WORKSPACE_DIRECTORY')
  if args.action == "check":
    return 0 if _check_owned_files(workspace_dir) else 1
  elif args.action == "update":
    _update_owned_files(workspace_dir)


if __name__ == '__main__':
  sys.exit(main())
