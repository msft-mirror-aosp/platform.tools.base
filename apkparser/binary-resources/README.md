# Binary Resource Module

This module is a fork
of [//java/com/google/devrel/gmscore/tools/apk/arsc](https://source.corp.google.com/piper///depot/google3/java/com/google/devrel/gmscore/tools/apk/arsc/)
in the google3 repo.

The code should be kept synchronized with the google3 repo, therefore,
no changes should be made to it directly.

Any bug fixes, maintenance and refactoring should be made directly to
the source-of-truth in google3.

## Refresh Instructions

In a google3 client, run the command:

```
g4 sync
```

And record the CL number in
the [GOOGLE3_CL_NUMBER](https://source.corp.google.com/h/googleplex-android/platform/superproject/base/+/studio-main:tools/base/apkparser/binary-resources/GOOGLE3_CL_NUMBER)
file.

Then, run the command:

```
STUDIO_MAIN=<studio main repo root>
cp java/com/google/devrel/gmscore/tools/apk/arsc/*.java \
  $STUDIO_MAIN/tools/base/apkparser/binary-resources/src/main/java/com/google/devrel/gmscore/tools/apk/arsc
```

After copying the files, remove any files that do not already exist. The
google3 repo contains other components that are not required by Android
Studio and don't even compile here because of lacking dependencies.

It is possible that moving forward, new required files will be added to
the google3 codebase. These cases should be evaluated as they occur.

## Tests

The google3 module has test coverage and since the code here will not
diverge from it, there is no need for addition or duplication here. If
new tests are required, add them
to [//javatests/com/google/devrel/gmscore/tools/apk/arsc](https://source.corp.google.com/piper///depot/google3/javatests/com/google/devrel/gmscore/tools/apk/arsc/)
