"""Implements studio-nightly AB target."""
import pathlib
from tools.base.bazel.ci import bazel
from tools.base.bazel.ci import studio

_ARTIFACTS = [
    ('tools/adt/idea/studio/android-studio.nightly.linux.zip', 'artifacts'),
    ('tools/adt/idea/studio/android-studio.nightly.win.zip', 'artifacts'),
    ('tools/adt/idea/studio/android-studio.nightly.mac.zip', 'artifacts'),
    ('tools/adt/idea/studio/android-studio.nightly.mac_arm.zip', 'artifacts'),
    ('tools/adt/idea/studio/android-studio.nightly_build_manifest.textproto', 'artifacts'),
    ('tools/adt/idea/studio/android-studio.nightly_update_message.html', 'artifacts'),
    ('tools/adt/idea/studio/updater_deploy.jar', 'artifacts/android-studio-updater.jar'),
    ('tools/adt/idea/native/installer/android-studio-bundle-data.zip', 'artifacts'),
    ('tools/vendor/google/adrt/android-studio-cros-skeleton.zip', 'artifacts'),
    ('tools/vendor/google/adrt/android-studio-nsis-prebuilt.zip', 'artifacts'),
]


def studio_nightly(build_env: bazel.BuildEnv) -> None:
  """Runs studio-linux target."""
  build_env.bazel_build(
      '--config=ci',
      '--config=remote-exec',
      f'--embed_label={build_env.build_number}',
      f'--build_metadata=ab_build_id={build_env.build_number}',
      f'--build_metadata=ab_target={build_env.build_target_name}',
      '//tools/adt/idea/studio:android-studio.nightly.linux.zip',
      '//tools/adt/idea/studio:android-studio.nightly.mac.zip',
      '//tools/adt/idea/studio:android-studio.nightly.mac_arm.zip',
      '//tools/adt/idea/studio:android-studio.nightly.win.zip',
      '//tools/adt/idea/studio:android-studio.nightly_build_manifest.textproto',
      '//tools/adt/idea/studio:android-studio.nightly_update_message.html',
      '//tools/adt/idea/native/installer:android-studio-bundle-data',
      '//tools/vendor/google/adrt:android-studio-cros-skeleton.zip',
      '//tools/vendor/google/adrt:android-studio-nsis-prebuilt.zip',
      '//tools/adt/idea/studio:updater_deploy.jar',
  )
  dist_path = pathlib.Path(build_env.dist_dir)
  (dist_path / 'artifacts').mkdir(parents=True, exist_ok=True)
  studio.copy_artifacts(build_env, _ARTIFACTS)
