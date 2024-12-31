import re
import shutil
import subprocess
import sys
import os

from pathlib import Path

if len(sys.argv) != 3:
    print("Please provide the right number of arguments. E.g. \"python upgrade.py 8.11 374810264\". See README for more details.")
    sys.exit(0)

gradleVersion = sys.argv[1]

# Update artifacts.bzl
with open("../bazel/maven/artifacts.bzl", "r+") as f:
    file = f.read()
    oldTooling = r'org\.gradle:gradle-tooling-api:(.*?)(?=")'
    newTooling = 'org.gradle:gradle-tooling-api:' + gradleVersion
    match = re.search(oldTooling, file)
    if match:
        oldGradleVersion = match.group(1)
    else:
        print("Gradle tooling API not found in artifacts.bzl.")
        sys.exit(0)
    file = re.sub(oldTooling, newTooling, file)
    f.seek(0)
    f.write(file)
    f.truncate()

# prebuilts/tools changes
command = "../bazel/maven/maven_fetch.sh"
subprocess.run(command)
currentDir = Path.cwd()
studioDir = currentDir.parent.parent.parent
oldToolingArtifact = studioDir / "prebuilts/tools/common/m2/repository/org/gradle/gradle-tooling-api/{0}".format(oldGradleVersion)
if oldToolingArtifact.is_dir():
    shutil.rmtree(oldToolingArtifact)
print("\nUpdated prebuilts/tools")

# Remaining tools/base changes
with open("../build-system/BUILD", "r+") as f:
    file = f.read()
    oldVersion = r'GRADLE_VERSION = ".*?(?=")'
    newVersion = 'GRADLE_VERSION = "' + gradleVersion
    file = re.sub(oldVersion, newVersion, file)
    f.seek(0)
    f.write(file)
    f.truncate()
with open("../build-system/supported-versions.properties", "r+") as f:
    file = f.read()
    oldVersion = r'gradle_minimum=.*'
    newVersion = 'gradle_minimum=' + gradleVersion
    file = re.sub(oldVersion, newVersion, file)
    oldDefault = r'gradle_default=.*'
    newDefault = 'gradle_default=' + gradleVersion
    file = re.sub(oldDefault, newDefault, file)
    f.seek(0)
    f.write(file)
    f.truncate()
with open("../common/src/main/java/com/android/SdkConstants.java", "r+") as f:
    file = f.read()
    oldVersion = r'GRADLE_LATEST_VERSION = ".*?(?=")'
    newVersion = 'GRADLE_LATEST_VERSION = "' + gradleVersion
    file = re.sub(oldVersion, newVersion, file)
    f.seek(0)
    f.write(file)
    f.truncate()
print("Updated tools/base")

# tools/buildSrc changes
with open("../../buildSrc/base/dependencies.properties", "r+") as f:
    file = f.read()
    oldVersion = r'org\.gradle:gradle-tooling-api:.*'
    newVersion = 'org.gradle:gradle-tooling-api:' + gradleVersion
    file = re.sub(oldVersion, newVersion, file)
    f.seek(0)
    f.write(file)
    f.truncate()
print("Updated tools/buildSrc")

# tools/gradle changes
with open("../../gradle/wrapper/gradle-wrapper.properties", "r+") as f:
    file = f.read()
    oldVersion = r'gradle-.*-bin'
    newVersion = 'gradle-' + gradleVersion + '-bin'
    file = re.sub(oldVersion, newVersion, file)
    f.seek(0)
    f.write(file)
    f.truncate()
print("Updated tools/gradle")

# tools/gradle-recipes changes
versionMappingPath = "../../gradle-recipes/version_mappings.txt"
with open(versionMappingPath, "r") as f:
    recipeLines = f.readlines()
for i, line in enumerate(recipeLines):
    parts = line.split(";")
    if len(parts) == 3 and parts[1] == oldGradleVersion:
        parts[1] = gradleVersion
        updatedLine = ";".join(parts)
        recipeLines[i] = updatedLine
with open(versionMappingPath, 'w') as f:
    f.writelines(recipeLines)
print("Updated tools/gradle-recipes")

# tools/external/gradle changes
with open("../../external/gradle/BUILD", 'r') as f:
    externalBuildLines = f.readlines()

textToInsert = '''#######
# gradle {0}
#######

# do not use directly. Use //tools/base/build-system:gradle
filegroup(
    name = "gradle-distrib-{0}",
    srcs = ["gradle-{0}-bin.zip"],
    visibility = ["//tools/base/build-system:__pkg__"],
)

'''.format(gradleVersion)

for i, line in enumerate(externalBuildLines):
    if line.strip() == "# Gradle SNAPSHOT distributions":
        for newLine in textToInsert.splitlines():
            externalBuildLines.insert(i, newLine + '\n')
            i += 1
        break

with open("../../external/gradle/BUILD", 'w') as f:
    f.writelines(externalBuildLines)

currentDir = Path.cwd()
toolsDir = currentDir.parent.parent
oldDistPath = toolsDir / "external/gradle/gradle-{0}-bin.zip".format(oldGradleVersion)
if oldDistPath.exists():
    oldDistPath.unlink()

print("Updated tools/external/gradle")

# Commit all changes
repos = [
    studioDir / "prebuilts/tools",
    studioDir / "tools/base",
    studioDir / "tools/buildSrc",
    studioDir / "tools/gradle",
    studioDir / "tools/gradle-recipes",
    studioDir / "tools/external/gradle",
]
commands = [
    "repo start upgrade",
    "git add .",
    "git commit -m \"Gradle {0} Upgrade\n\nBug: {1}\nTest: n/a\"".format(gradleVersion, sys.argv[2])
]
for repo in repos:
    relativePath = os.path.relpath(repo, studioDir)
    print("\nCommitting to {0}".format(relativePath))
    for command in commands:
        subprocess.run([command], cwd=repo, shell=True)

print(
    """

    Upload the following repos' changes to Gerrit:

    prebuilts/tools
    tools/base
    tools/buildSrc
    tools/gradle
    tools/gradle-recipes
    tools/external/gradle

    """
)

print(
    "IMPORTANT: add the Gradle distribution from https://services.gradle.org/distributions/ to "
    "tools/external/gradle if not already present."
)
