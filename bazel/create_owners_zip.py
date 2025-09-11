import itertools
import sys
import pathlib
import zipfile

def main():
  workspace_root = pathlib.Path.cwd()
  if not (workspace_root / 'MODULE.bazel').exists():
    raise FileNotFoundError('Working directory is not the workspace root')

  owners_paths = itertools.chain(
      workspace_root.glob('tools/**/OWNERS'),
      workspace_root.glob('prebuilts/**/OWNERS'),
  )

  with zipfile.ZipFile(sys.argv[1], 'w') as owners_zip:
    for path in owners_paths:
      owners_zip.write(path, arcname=path.relative_to(workspace_root))

if __name__ == '__main__':
  main()
