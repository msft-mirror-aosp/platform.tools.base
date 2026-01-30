# LeakCanary Stub Artifacts

**WARNING: These are STUB files. They do not contain the actual LeakCanary library.**

These artifacts (fake aar + minimal POM) exist solely to:
1. Satisfy compile-time dependencies for `studio-leakcanary` (which uses LeakCanary via reflection).
2. Ensure `com.squareup.leakcanary:leakcanary-android` appears as a dependency in the generated POM for `studio-leakcanary`.

End-users will download the *real* LeakCanary artifacts from Maven Central transitively.
