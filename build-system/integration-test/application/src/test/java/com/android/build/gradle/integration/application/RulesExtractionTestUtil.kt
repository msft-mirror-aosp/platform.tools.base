/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject

private const val LIB1_BUILD_GRADLE =
  """
android {
    buildTypes {
        create("minified").initWith(buildTypes.debug)
        minified {
            consumerProguardFiles "proguard-rules.pro"
        }
    }
}
"""

/** Creates a minimal library subproject with ProGuard rules for testing. */
fun testLib(i: Int) =
  MinimalSubProject.lib("com.example.lib$i")
    .appendToBuild(LIB1_BUILD_GRADLE)
    .withFile(
      "src/main/java/com/example/lib$i/Lib${i}ClassToKeep.java",
      """
package com.example.lib$i;
public class Lib${i}ClassToKeep {
}
""",
    )
    .withFile(
      "src/main/java/com/example/lib$i/Lib${i}ClassToRemove.java",
      """
package com.example.lib$i;
public class Lib${i}ClassToRemove {
}
""",
    )
    .withFile("proguard-rules.pro", "-keep public class com.example.lib$i.Lib${i}ClassToKeep")

/** Creates a minimal Java library subproject with embedded ProGuard rules for testing. */
fun testJavalib(i: Int) =
  MinimalSubProject.javaLibrary()
    .withFile(
      "src/main/java/com/example/javalib$i/Javalib${i}ClassToKeep.java",
      """
package com.example.javalib$i;
public class Javalib${i}ClassToKeep {
}
""",
    )
    .withFile(
      "src/main/java/com/example/javalib$i/Javalib${i}ClassToRemove.java",
      """
package com.example.javalib$i;
public class Javalib${i}ClassToRemove {
}
""",
    )
    .withFile("src/main/resources/META-INF/proguard/rules.pro", "-keep public class com.example.javalib$i.Javalib${i}ClassToKeep")

/** Java source for a class in the base module that should be kept during shrinking. */
const val BASE_CLASS_KEEP =
  """
package com.example.baseModule;
public class BaseClassToKeep {
}
"""

/** Java source for a class in the base module that should be removed during shrinking. */
const val BASE_CLASS_REMOVE =
  """
package com.example.baseModule;
public class BaseClassToRemove {
}
"""

/** Java source for a class in a feature module that should be kept during shrinking. */
const val FEATURE1_CLASS_KEEP =
  """
package com.example.feature1;
public class Feature1ClassToKeep {
}
"""

/** Java source for a class in a feature module that should be removed during shrinking. */
const val FEATURE_CLASS_REMOVE =
  """
package com.example.feature1;
public class Feature1ClassToRemove {
}
"""

/** The [GradleTestProject.ApkType] for the "minified" build type. */
val APK_TYPE = GradleTestProject.ApkType.of("minified", true)
