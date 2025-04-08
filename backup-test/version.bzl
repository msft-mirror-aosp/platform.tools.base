"""
This module contains variables storing the next release version number of the backup restore api.
"""

load("//tools/base/backup-test:release_version.bzl", "BACKUP_TEST_VERSION_DEV", "BACKUP_TEST_VERSION_RELEASE")

BACKUP_TEST_VERSION = select({
    "//tools/base/bazel:release": BACKUP_TEST_VERSION_RELEASE,
    "//conditions:default": BACKUP_TEST_VERSION_DEV,
})

BACKUP_TEST_PLUGIN_GRADLE_PROPERTIES = select({
    "//tools/base/bazel:release": {
        "BACKUP_TEST_VERSION": BACKUP_TEST_VERSION_RELEASE,
    },
    "//conditions:default": {
        "BACKUP_TEST_VERSION": BACKUP_TEST_VERSION_DEV,
    },
})
