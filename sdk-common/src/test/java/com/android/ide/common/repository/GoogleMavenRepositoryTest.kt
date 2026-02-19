/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.ide.common.repository

import com.android.ide.common.gradle.Dependency
import com.android.ide.common.gradle.Version
import com.android.ide.common.resources.BaseTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoogleMavenRepositoryTest : BaseTestCase() {
  companion object {

    @ClassRule @JvmField var temp = TemporaryFolder()

    /**
     * Snapshot of what the versions were when this test was written.
     *
     * This way tests don't break when we update.
     */
    private val builtInData =
      mapOf(
        "master-index.xml" to /*language=XML */
          """
          <?xml version='1.0' encoding='UTF-8'?>
          <metadata>
            <androidx.activity/>
            <com.android.support.constraint/>
            <com.android.databinding/>
            <com.android.support/>
            <com.android.support.test/>
            <com.android.support.test.janktesthelper/>
            <com.android.support.test.uiautomator/>
            <com.android.support.test.espresso/>
            <android.arch.persistence.room/>
            <android.arch.lifecycle/>
            <android.arch.core/>
            <com.google.android.instantapps/>
            <com.google.android.instantapps.thirdpartycompat/>
            <com.android.java.tools.build/>
            <com.android.tools/>
            <com.android.tools.layoutlib/>
            <com.android.tools.ddms/>
            <com.android.tools.external.com-intellij/>
            <com.android.tools.build/>
            <com.android.tools.analytics-library/>
            <com.android.tools.internal.build.test/>
            <com.android.tools.lint/>
          </metadata>
          """
            .trimIndent(),
        "com/android/support/group-index.xml" to /*language=XML */
          """
          <?xml version='1.0' encoding='UTF-8'?>
          <com.android.support>
            <support-compat versions="25.3.1,26.0.0-beta1"/>
            <leanback-v17 versions="25.3.1,26.0.0-beta1"/>
            <recommendation versions="25.3.1,26.0.0-beta1"/>
            <support-tv-provider versions="26.0.0-beta1"/>
            <support-vector-drawable versions="25.3.1,26.0.0-beta1"/>
            <recyclerview-v7 versions="25.3.1,26.0.0-beta1"/>
            <preference-leanback-v17 versions="25.3.1,26.0.0-beta1"/>
            <preference-v14 versions="25.3.1,26.0.0-beta1"/>
            <percent versions="25.3.1,26.0.0-beta1"/>
            <support-media-compat versions="25.3.1,26.0.0-beta1"/>
            <cardview-v7 versions="25.3.1,26.0.0-beta1"/>
            <wearable versions="26.0.0-alpha1"/>
            <exifinterface versions="25.3.1,26.0.0-beta1"/>
            <support-annotations versions="25.3.1,26.0.0-beta1"/>
            <appcompat-v7 versions="25.3.1,26.0.0-beta1"/>
            <palette-v7 versions="25.3.1,26.0.0-beta1"/>
            <multidex-instrumentation versions="1.0.1,1.0.1"/>
            <multidex versions="1.0.1,1.0.1"/>
            <mediarouter-v7 versions="25.3.1,26.0.0-beta1"/>
            <preference-v7 versions="25.3.1,26.0.0-beta1"/>
            <support-dynamic-animation versions="25.3.1,26.0.0-beta1"/>
            <support-fragment versions="25.3.1,26.0.0-beta1"/>
            <design versions="25.3.1,26.0.0-beta1"/>
            <transition versions="25.3.1,26.0.0-beta1"/>
            <customtabs versions="25.3.1,26.0.0-beta1"/>
            <support-core-ui versions="25.3.1,26.0.0-beta1"/>
            <gridlayout-v7 versions="25.3.1,26.0.0-beta1"/>
            <animated-vector-drawable versions="25.3.1,26.0.0-beta1"/>
            <support-core-utils versions="25.3.1,26.0.0-beta1"/>
            <support-v13 versions="25.3.1,26.0.0-beta1"/>
            <instantvideo versions="26.0.0-alpha1"/>
            <support-v4 versions="25.3.1,26.0.0-beta1"/>
            <support-emoji versions="26.0.0-beta1"/>
            <wear versions="26.0.0-beta1"/>
            <support-emoji-appcompat versions="26.0.0-beta1"/>
            <support-emoji-bundled versions="26.0.0-beta1"/>
          </com.android.support>
          """
            .trimIndent(),
        "com/android/support/support-compat/25.3.1/support-compat-25.3.1.pom" to /*language=XML */
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.android.support</groupId>
            <artifactId>support-compat</artifactId>
            <version>25.3.1</version>
            <packaging>aar</packaging>
            <dependencies>
              <dependency>
                <groupId>com.android.support</groupId>
                <artifactId>support-annotations</artifactId>
                <version>25.3.1</version>
                <scope>compile</scope>
              </dependency>
            </dependencies>
          </project>
          """
            .trimIndent(),
        "com/android/support/leanback-v17/25.3.1/leanback-v17-25.3.1.pom" to /*language=XML */
          """
          <?xml version="1.0" encoding="UTF-8"?>
          <project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.android.support</groupId>
            <artifactId>leanback-v17</artifactId>
            <version>25.3.1</version>
            <packaging>aar</packaging>
            <dependencies>
              <dependency>
                <groupId>com.android.support</groupId>
                <artifactId>support-compat</artifactId>
                <version>25.3.1</version>
                <type>aar</type>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>com.android.support</groupId>
                <artifactId>support-core-ui</artifactId>
                <version>25.3.1</version>
                <type>aar</type>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>com.android.support</groupId>
                <artifactId>support-media-compat</artifactId>
                <version>25.3.1</version>
                <type>aar</type>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>com.android.support</groupId>
                <artifactId>support-fragment</artifactId>
                <version>25.3.1</version>
                <type>aar</type>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>com.android.support</groupId>
                <artifactId>recyclerview-v7</artifactId>
                <version>[25.3.1.4.5,25.4.0)</version>
                <type>aar</type>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>androidx.recyclerview</groupId>
                <artifactId>recyclerview</artifactId>
                <version>2.0.0</version>
                <type>aar</type>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>org.mockito</groupId>
                <artifactId>mockito-core</artifactId>
                <version>1.9.5</version>
                <type>aar</type>
                <scope>test</scope>
              </dependency>
            </dependencies>
          </project>
          """
            .trimIndent(),
        "androidx/activity/group-index.xml" to /*language=XML */
          """
          <androidx.activity>
            <activity versions="1.10.0"/>
            <activity-compose versions="1.10.0"/>
            <activity-ktx versions="1.10.0"/>
          </androidx.activity>
          """
            .trimIndent(),
        "androidx/activity/activity-compose/1.10.0/activity-compose-1.10.0.pom" to /*language=XML */
          """
          <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            <!-- This module was also published with a richer model, Gradle metadata,  -->
            <!-- which should be used instead. Do not delete the following line which  -->
            <!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->
            <!-- that they should prefer consuming it instead. -->
            <!-- do_not_remove: published-with-gradle-metadata -->
            <modelVersion>4.0.0</modelVersion>
            <groupId>androidx.activity</groupId>
            <artifactId>activity-compose</artifactId>
            <version>1.10.0</version>
            <packaging>aar</packaging>
            <name>Activity Compose</name>
            <description>Compose integration with Activity</description>
            <url>https://developer.android.com/jetpack/androidx/releases/activity#1.10.0</url>
            <inceptionYear>2020</inceptionYear>
            <organization>
              <name>The Android Open Source Project</name>
            </organization>
            <licenses>
              <license>
                <name>The Apache Software License, Version 2.0</name>
                <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
                <distribution>repo</distribution>
              </license>
            </licenses>
            <developers>
              <developer>
                <name>The Android Open Source Project</name>
              </developer>
            </developers>
            <scm>
              <connection>scm:git:https://android.googlesource.com/platform/frameworks/support</connection>
              <url>https://cs.android.com/androidx/platform/frameworks/support</url>
            </scm>
            <dependencyManagement>
              <dependencies>
                <dependency>
                  <groupId>androidx.activity</groupId>
                  <artifactId>activity</artifactId>
                  <version>1.10.0</version>
                </dependency>
                <dependency>
                  <groupId>androidx.activity</groupId>
                  <artifactId>activity-ktx</artifactId>
                  <version>1.10.0</version>
                </dependency>
                <dependency>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-stdlib</artifactId>
                  <version>1.8.22</version>
                </dependency>
              </dependencies>
            </dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>androidx.compose.runtime</groupId>
                <artifactId>runtime-saveable</artifactId>
                <version>1.7.0</version>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>androidx.activity</groupId>
                <artifactId>activity-ktx</artifactId>
                <version>[1.10.0]</version>
                <scope>compile</scope>
                <type>aar</type>
              </dependency>
              <dependency>
                <groupId>androidx.compose.runtime</groupId>
                <artifactId>runtime</artifactId>
                <version>1.7.0</version>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>androidx.compose.ui</groupId>
                <artifactId>ui</artifactId>
                <version>1.0.1</version>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>androidx.core</groupId>
                <artifactId>core-ktx</artifactId>
                <version>1.13.0</version>
                <scope>compile</scope>
                <type>aar</type>
              </dependency>
              <dependency>
                <groupId>org.jetbrains.kotlinx</groupId>
                <artifactId>kotlinx-coroutines-core</artifactId>
                <version>1.7.3</version>
                <scope>runtime</scope>
              </dependency>
              <dependency>
                <groupId>androidx.lifecycle</groupId>
                <artifactId>lifecycle-runtime</artifactId>
                <version>2.6.1</version>
                <scope>runtime</scope>
              </dependency>
              <dependency>
                <groupId>androidx.savedstate</groupId>
                <artifactId>savedstate</artifactId>
                <version>1.2.1</version>
                <scope>runtime</scope>
              </dependency>
              <dependency>
                <groupId>androidx.lifecycle</groupId>
                <artifactId>lifecycle-viewmodel</artifactId>
                <version>2.6.1</version>
                <scope>compile</scope>
              </dependency>
              <dependency>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-stdlib</artifactId>
                <!-- In the real activity-compose pom, this has runtime scope; here we're
                    just testing that it's included in the resolved (compile) versions -->
                <scope>compile</scope>
              </dependency>
              <!-- This dependency isn't in the real activity-compose file; what we're
                testing here is that we don't bother validating dependencies where the
                scope doesn't match anyway -->
              <dependency>
                <groupId>my.invalid</groupId>
                <artifactId>dep</artifactId>
                <scope>other</scope>
              </dependency>
              <dependency>
                <groupId>androidx.lifecycle</groupId>
                <artifactId>lifecycle-common</artifactId>
                <version>2.6.1</version>
                <scope>runtime</scope>
                <exclusions>
                  <exclusion>
                    <artifactId>*</artifactId>
                    <groupId>androidx.annotation</groupId>
                  </exclusion>
                </exclusions>
              </dependency>
            </dependencies>
          </project>
          """
            .trimIndent(),
      )
  }

  @Test
  fun testBuiltin() {
    val repo = StubGoogleMavenRepository(builtInData = builtInData) // no cache dir set: will only read built-in index
    val version = repo.findVersion("com.android.support", "appcompat-v7", allowPreview = true)
    assertNotNull(version)
    assertEquals("26.0.0-beta1", version.toString())
  }

  @Test
  fun testBuiltinStableOnly() {
    val repo = StubGoogleMavenRepository(builtInData = builtInData) // no cache dir set: will only read built-in index
    val version = repo.findVersion("com.android.support", "appcompat-v7", allowPreview = false)
    assertNotNull(version)
    assertEquals("25.3.1", version.toString())
  }

  @Test
  fun testBuiltinFiltered() {
    val repo = StubGoogleMavenRepository(builtInData = builtInData) // no cache dir set: will only read built-in index
    val version = repo.findVersion("com.android.support", "appcompat-v7", filter = { it.major == 12 })
    assertNull(version)
  }

  @Test
  fun testBuiltinDependency() {
    val repo = StubGoogleMavenRepository(builtInData = builtInData) // no cache dir set: will only read built-in index
    val version = repo.findVersion("com.android.support", "leanback-v17")
    val dependencies = repo.findCompileDependencies("com.android.support", "leanback-v17", version!!)
    assertThat(dependencies)
      .containsExactly(
        Dependency.parse("com.android.support:support-compat:25.3.1"),
        Dependency.parse("com.android.support:support-core-ui:25.3.1"),
        Dependency.parse("com.android.support:support-media-compat:25.3.1"),
        Dependency.parse("com.android.support:support-fragment:25.3.1"),
        // Maven dependency ranges are always hard requirements, which we map to Gradle's
        // strictly (notated as `!!`)
        Dependency.parse("com.android.support:recyclerview-v7:[25.3.1.4.5,25.4.0)!!"),
        Dependency.parse("androidx.recyclerview:recyclerview:2.0.0"),
      )
    // TODO(xof): actually these tests are not well-founded; the special version ranges for
    //  particular artifacts are only relevant for the DependencyAnalyzer, and the logic has
    //  been moved there.
    // assertThat(dependencies[3].versionRange?.lowerEndpoint()).isEqualTo(Version.parse("25.3.1"))
    // assertThat(dependencies[3].versionRange?.upperEndpoint()).isEqualTo(Version.prefixInfimum("25.3.2"))
    // assertThat(dependencies[5].versionRange?.lowerEndpoint()).isEqualTo(Version.parse("2.0.0"))
    // assertThat(dependencies[5].versionRange?.upperEndpoint()).isEqualTo(Version.prefixInfimum("3"))
    assertThat(dependencies[4].version?.strictly?.lowerEndpoint()).isEqualTo(Version.parse("25.3.1.4.5"))
    assertThat(dependencies[4].version?.strictly?.upperEndpoint()).isEqualTo(Version.prefixInfimum("25.4.0"))
  }

  @Test
  fun testReadingFromUrl() {
    val repo =
      StubGoogleMavenRepository(
        cacheDir = temp.root.toPath(),
        urls =
          mapOf(
            "https://maven.google.com/master-index.xml" to
              """
              <?xml version='1.0' encoding='UTF-8'?>
              <metadata>
                <foo.bar/>
                <foo.bar.baz/>
              </metadata>
              """
                .trimIndent(),
            "https://maven.google.com/foo/bar/group-index.xml" to
              """
              <?xml version='1.0' encoding='UTF-8'?>
              <foo.bar>
                <my-artifact versions="1.0.1-alpha1"/>
                <another-artifact versions="2.5.0,2.6.0-rc1"/>
              </foo.bar>
              """
                .trimIndent(),
          ),
      )
    val version = repo.findVersion("foo.bar", "my-artifact", allowPreview = true)
    assertNotNull(version)
    assertEquals("1.0.1-alpha1", version.toString())

    val d1 = Dependency.parse("foo.bar:another-artifact:2.5.+")
    assertEquals("2.5.0", repo.findVersion(d1, null, d1.explicitlyIncludesPreview).toString())
    val d2 = Dependency.parse("foo.bar:another-artifact:2.6.0-alpha1")
    assertEquals("2.6.0-rc1", repo.findVersion(d2, null, d2.explicitlyIncludesPreview).toString())
    val d3 = Dependency.parse("foo.bar:another-artifact:2.6.+")
    assertEquals("2.6.0-rc1", repo.findVersion(d3, null, allowPreview = true).toString())

    assertEquals(setOf("foo.bar", "foo.bar.baz"), repo.getGroups())
    assertEquals(setOf("my-artifact", "another-artifact"), repo.getArtifacts("foo.bar"))
    assertEquals(setOf(Version.parse("2.5.0"), Version.parse("2.6.0-rc1")), repo.getVersions("foo.bar", "another-artifact"))
  }

  @Test
  fun testReadingFromHostileNetwork() {
    // Regression test for b/129362597
    val repo =
      StubGoogleMavenRepository(
        cacheDir = temp.newFolder().toPath(),
        urls =
          mapOf(
            "https://maven.google.com/master-index.xml" to
              """
              <!DOCTYPE html>
              <html lang=en>
                <meta charset=utf-8>
                <meta name=viewport content="initial-scale=1, minimum-scale=1, width=device-width">
                <title>Error 404 (Not Found)!!1</title>
                <style>
                  *{margin:0;padding:0}html,code{font:15px/22px arial,sans-serif}html{background:#fff;color:#222;padding:15px}body{margin:7% auto 0;max-width:390px;min-height:180px;padding:30px 0 15px}* > body{background:url(//www.google.com/images/errors/robot.png) 100% 5px no-repeat;padding-right:205px}p{margin:11px 0 22px;overflow:hidden}ins{color:#777;text-decoration:none}a img{border:0}@media screen and (max-width:772px){body{background:none;margin-top:0;max-width:none;padding-right:0}}#logo{background:url(//www.google.com/images/logos/errorpage/error_logo-150x54.png) no-repeat;margin-left:-5px}@media only screen and (min-resolution:192dpi){#logo{background:url(//www.google.com/images/logos/errorpage/error_logo-150x54-2x.png) no-repeat 0% 0%/100% 100%;-moz-border-image:url(//www.google.com/images/logos/errorpage/error_logo-150x54-2x.png) 0}}@media only screen and (-webkit-min-device-pixel-ratio:2){#logo{background:url(//www.google.com/images/logos/errorpage/error_logo-150x54-2x.png) no-repeat;-webkit-background-size:100% 100%}}#logo{display:inline-block;height:54px;width:150px}
                </style>
                <a href=//www.google.com/><span id=logo aria-label=Google></span></a>
                <p><b>404.</b> <ins>That’s an error.</ins>
                <p>  <ins>That’s all we know.</ins>
              """
                .trimIndent()
          ),
      )
    val version = repo.findVersion("foo.bar", "my-artifact", allowPreview = true)
    assertNull(version)
  }

  @Test
  fun testMissingVersion() {
    val repo = StubGoogleMavenRepository(builtInData = builtInData) // no cache dir set: will only read built-in index
    val version = repo.findVersion("androidx.activity", "activity-compose")
    val dependencies = repo.findCompileDependencies("androidx.activity", "activity-compose", version!!)
    assertThat(dependencies)
      .containsExactly(
        Dependency.parse("androidx.compose.runtime:runtime-saveable:1.7.0"),
        // The dependency is expressed in Maven POM form as [1.10.0], which is a hard
        // requirement, which we map to Gradle's `!!` notation indicating "strictly".
        Dependency.parse("androidx.activity:activity-ktx:1.10.0!!"),
        Dependency.parse("androidx.compose.runtime:runtime:1.7.0"),
        Dependency.parse("androidx.compose.ui:ui:1.0.1"),
        Dependency.parse("androidx.core:core-ktx:1.13.0"),
        Dependency.parse("androidx.lifecycle:lifecycle-viewmodel:2.6.1"),
        Dependency.parse("org.jetbrains.kotlin:kotlin-stdlib:1.8.22"),
      )
  }

  @Test
  fun testExclusions() {
    val repo = StubGoogleMavenRepository(builtInData = builtInData) // no cache dir set: will only read built-in index
    val version = repo.findVersion("androidx.activity", "activity-compose")
    val dependencies = repo.findDependencies("androidx.activity", "activity-compose", version!!, "runtime")
    assertThat(dependencies)
      .containsExactly(
        Dependency.parse("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3"),
        Dependency.parse("androidx.lifecycle:lifecycle-runtime:2.6.1"),
        Dependency.parse("androidx.savedstate:savedstate:1.2.1"),
        Dependency.parse("androidx.lifecycle:lifecycle-common:2.6.1"),
      )
  }
}
