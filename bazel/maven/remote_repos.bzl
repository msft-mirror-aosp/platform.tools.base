"""
The repositories to use when running maven_fetch.sh to download Maven artifacts declared in
DATA_ARTIFACTS (data_artifacts.bzl) and RESOLVED_ARTIFACTS (resolved_artifacts.bzl)
"""

REMOTE_REPOS = {
    "Google": "https://maven.google.com/",
    "Maven Central": "https://repo1.maven.org/maven2/",
    "Gradle Libs": "https://repo.gradle.org/gradle/libs-releases/",
    "GradlePlugins": "https://plugins.gradle.org/m2",
    "Kotlin dev": "https://packages.jetbrains.team/maven/p/kt/dev/",
    "Gradle Snapshots": "https://repo.gradle.org/gradle/libs-snapshots/",
    "IntelliJ Third-Party deps": "https://cache-redirector.jetbrains.com/intellij-dependencies",
    "Compose": "https://maven.pkg.jetbrains.space/public/p/compose/dev/",
    "Jewel": "https://packages.jetbrains.team/maven/p/kpm/public/",
}
