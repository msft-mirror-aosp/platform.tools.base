The python script `upgrade.py` can be run to make the necessary updates
to kick off a Gradle upgrade in AGP.

Steps to run:

1. Sync repos before running the script. Affected repos include:
```
tools/base
tools/buildSrc
prebuilts/tools
tools/gradle
tools/external/gradle
tools/gradle-recipes
```
2. If not already present, add the desired Gradle distribution to
`tools/external/gradle` from https://services.gradle.org/distributions/.

3. Run `python upgrade.py [version] [bugId]` from this directory
(`gradle-upgrade`) and the changes will be committed for each repo.
Upload these changes to Gerrit.

For instance, to upgrade to Gradle `8.11` and the bug filed is
`374810264`, you would run `python upgrade.py 8.11 374810264`.

Note: if the upgrade fails, any changes that were made will most likely need
to be reset before fixing the issue and attempting again.
