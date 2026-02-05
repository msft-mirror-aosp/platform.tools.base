/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.render.common

import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.wear.WearTileScreenshot
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonSerializationTest {
  @Test
  fun testJsonToPreviewRendering() {
    // language=json
    val jsonString =
      """
      {
        "fontsPath": "/path/to/fonts",
        "layoutlibPath": "/path/to/layout/lib",
        "outputFolder": "/path/to/output/folder",
        "metaDataFolder": "/path/to/meta-data/folder",
        "classPath": [
          "/path/to/lib1.jar",
          "/path/to/lib2.jar",
          "/path/to/classes"
        ],
        "projectClassPath": [
          "/path/to/lib1.jar",
          "/path/to/lib2.jar",
          "/path/to/classes"
        ],
        "namespace": "com.my.package",
        "resourceApkPath": "/path/to/resource.apk",
        "screenshots": [
          {
            "methodFQN": "com.my.package.ClKt.Method1",
            "methodParams": [],
            "previewParams": {},
            "previewId": "/path/to/image/pattern/name"
          }
        ],
        "resultsFilePath": "/path/to/my_results.json"
      }
      """
        .trimIndent()

    val previewRendering = readPreviewRenderingJson(jsonString.reader())

    val expectedPreviewRendering =
      PreviewRendering(
        "/path/to/fonts",
        "/path/to/layout/lib",
        "/path/to/output/folder",
        "/path/to/meta-data/folder",
        listOf("/path/to/lib1.jar", "/path/to/lib2.jar", "/path/to/classes"),
        listOf("/path/to/lib1.jar", "/path/to/lib2.jar", "/path/to/classes"),
        "com.my.package",
        "/path/to/resource.apk",
        listOf(ComposeScreenshot("com.my.package.ClKt.Method1", emptyList(), emptyMap(), "/path/to/image/pattern/name")),
        "/path/to/my_results.json",
      )

    assertEquals(expectedPreviewRendering, previewRendering)
  }

  @Test
  fun testJsonToScreenshots() {
    // language=json
    val jsonString =
      """
      {
        "screenshots": [
          {
            "methodFQN": "com.my.package.ClKt.Method1",
            "methodParams": [
              {
                "provider": "com.my.package2.SomeParameterProvider"
              }
            ],
            "previewParams": {
              "name": "Dark theme",
              "uiMode": "32"
            },
            "previewId": "/path/to/image/pattern/name",
            "previewType": "COMPOSE"
          },
          {
            "methodFQN": "com.my.package.Cl2Kt.Method3",
            "methodParams": [],
            "previewParams": {
              "name": "Light theme"
            },
            "previewId": "/path/to/image/pattern/name",
            "previewType": "COMPOSE"
          },
          {
            "methodFQN": "com.my.package.Cl3Kt.Method5",
            "methodParams": [
              {
                "provider": "com.my.package2.SomeOtherParameterProvider"
              }
            ],
            "previewParams": {},
            "previewId": "/path/to/image/pattern/name",
            "previewType": "COMPOSE"
          },
          {
            "methodFQN": "com.my.package.Cl3Kt.TilePreviewMethod",
            "previewParams": {
              "name": "tile preview"
            },
            "previewId": "/path/to/image/pattern/name",
            "previewType": "WEAR_TILE"
          }
        ]
      }
      """
        .trimIndent()

    val screenshots = readPreviewScreenshotsJson(jsonString.reader())

    assertEquals(
      listOf(
        ComposeScreenshot(
          "com.my.package.ClKt.Method1",
          listOf(mapOf("provider" to "com.my.package2.SomeParameterProvider")),
          mapOf("name" to "Dark theme", "uiMode" to "32"),
          "/path/to/image/pattern/name",
        ),
        ComposeScreenshot("com.my.package.Cl2Kt.Method3", emptyList(), mapOf("name" to "Light theme"), "/path/to/image/pattern/name"),
        ComposeScreenshot(
          "com.my.package.Cl3Kt.Method5",
          listOf(mapOf("provider" to "com.my.package2.SomeOtherParameterProvider")),
          emptyMap(),
          "/path/to/image/pattern/name",
        ),
        WearTileScreenshot("com.my.package.Cl3Kt.TilePreviewMethod", mapOf("name" to "tile preview"), "/path/to/image/pattern/name"),
      ),
      screenshots,
    )
  }

  @Test
  fun testScreenshotJsonWithoutPreviewTypeDefaultsToComposeScreenshot() {
    // language=json
    val jsonString =
      """
      {
        "screenshots": [
          {
            "methodFQN": "com.my.package.ClKt.Method1",
            "methodParams": [
              {
                "provider": "com.my.package2.SomeParameterProvider"
              }
            ],
            "previewParams": {
              "name": "Dark theme",
              "uiMode": "32"
            },
            "previewId": "/path/to/image/pattern/name"
          }
        ]
      }
      """
        .trimIndent()

    val screenshots = readPreviewScreenshotsJson(jsonString.reader())

    assertEquals(
      listOf(
        ComposeScreenshot(
          "com.my.package.ClKt.Method1",
          listOf(mapOf("provider" to "com.my.package2.SomeParameterProvider")),
          mapOf("name" to "Dark theme", "uiMode" to "32"),
          "/path/to/image/pattern/name",
        )
      ),
      screenshots,
    )
  }

  @Test
  fun testPreviewRenderingToJsonAndBack() {
    val screenshot = ComposeScreenshot("com.my.package.ClKt.Method1", emptyList(), emptyMap(), "/path/to/image/pattern/name")
    val previewRendering =
      PreviewRendering(
        "/path/to/fonts",
        "/path/to/layout/lib",
        "/path/to/output/folder",
        "/path/to/meta-data/folder",
        listOf("/path/to/lib1.jar", "/path/to/lib2.jar", "/path/to/classes"),
        listOf("/path/to/lib1.jar", "/path/to/lib2.jar", "/path/to/classes"),
        "com.my.package",
        "/path/to/resource.apk",
        listOf(screenshot),
        "/path/to/my_results.json",
      )

    val stringWriter = StringWriter()

    writePreviewRenderingToJson(stringWriter, previewRendering)

    val restoredPreviewRendering = readPreviewRenderingJson(stringWriter.toString().reader())

    assertEquals(previewRendering, restoredPreviewRendering)
  }

  @Test
  fun testScreenshotsToJsonAndBack() {
    val screenshots =
      listOf(
        ComposeScreenshot(
          "com.my.package.ClKt.Method1",
          listOf(mapOf("provider" to "com.my.package2.SomeParameterProvider")),
          mapOf("name" to "Dark theme", "uiMode" to "32"),
          "/path/to/image/pattern/name",
        ),
        ComposeScreenshot("com.my.package.Cl2Kt.Method3", emptyList(), mapOf("name" to "Light theme"), "/path/to/image/pattern/name"),
        ComposeScreenshot(
          "com.my.package.Cl3Kt.Method5",
          listOf(mapOf("provider" to "com.my.package2.SomeOtherParameterProvider")),
          emptyMap(),
          "/path/to/image/pattern/name",
        ),
        WearTileScreenshot("com.my.package.Cl3Kt.TilePreviewMethod", mapOf("name" to "tile preview"), "/path/to/image/pattern/name"),
      )

    val stringWriter = StringWriter()
    writePreviewScreenshotsToJson(stringWriter, screenshots)

    val restoredScreenshots = readPreviewScreenshotsJson(stringWriter.toString().reader())

    assertEquals(screenshots, restoredScreenshots)
  }

  @Test
  fun testJsonToPreviewRenderingResult_GlobalError() {
    // language=json
    val jsonString =
      """
      {
        "globalError": "Error message\nStack trace line 1\nStackTrace line 2"
      }
      """
        .trimIndent()

    val previewRenderingResult = readPreviewRenderingResultJson(jsonString.reader())

    val expectedPreviewRenderingResult =
      PreviewRenderingResult(
        """
        Error message
        Stack trace line 1
        StackTrace line 2
        """
          .trimIndent(),
        emptyList(),
      )

    assertEquals(expectedPreviewRenderingResult, previewRenderingResult)
  }

  @Test
  fun testJsonToPreviewRenderingResult_ScreenshotResults() {
    // language=json
    val jsonString =
      """
      {
        "screenshotResults": [
          {
            "previewId": "previewId1",
            "methodFQN": "methodFQN1",
            "imagePath": "pkg/class/image1.png"
          },
          {
            "previewId": "previewId2",
            "methodFQN": "methodFQN2",
            "imagePath": "pkg/class/image2.png",
            "error": {
                "status": "ERROR_RENDER_TASK",
                "message": "Error message",
                "stackTrace": "Error message\nStack trace line 1\nStackTrace line 2",
                "problems": [],
                "brokenClasses": [],
                "missingClasses": []
            }
          },
          {
            "previewId": "previewId3",
            "methodFQN": "methodFQN3",
            "error": {
                "status": "SUCCESS",
                "message": "",
                "stackTrace": "",
                "problems": [
                  {
                    "html": "<html>Some error description</html>"
                  },
                  {
                    "html": "<html>Some other error description</html>",
                    "stackTrace": "Other error message\nStack trace line 1\nStackTrace line 2"
                  }
                ],
                "brokenClasses": [
                  {
                    "className": "com.baz.Qwe",
                    "stackTrace": "Error message\nStack trace line 1\nStackTrace line 2"
                  }
                ],
                "missingClasses": ["com.foo.Bar"]
            },
            "imagePath": "pkg/class/image3.png"
          }
        ]
      }
      """
        .trimIndent()

    val previewRenderingResult = readPreviewRenderingResultJson(jsonString.reader())

    val expectedPreviewRenderingResult =
      PreviewRenderingResult(
        null,
        listOf(
          PreviewScreenshotResult("previewId1", "methodFQN1", "pkg/class/image1.png", null),
          PreviewScreenshotResult(
            "previewId2",
            "methodFQN2",
            "pkg/class/image2.png",
            ScreenshotError(
              "ERROR_RENDER_TASK",
              "Error message",
              """
              Error message
              Stack trace line 1
              StackTrace line 2
              """
                .trimIndent(),
              emptyList(),
              emptyList(),
              emptyList(),
            ),
          ),
          PreviewScreenshotResult(
            "previewId3",
            "methodFQN3",
            "pkg/class/image3.png",
            ScreenshotError(
              "SUCCESS",
              "",
              "",
              listOf(
                RenderProblem("<html>Some error description</html>", null),
                RenderProblem(
                  "<html>Some other error description</html>",
                  """
                  Other error message
                  Stack trace line 1
                  StackTrace line 2
                  """
                    .trimIndent(),
                ),
              ),
              listOf(
                BrokenClass(
                  "com.baz.Qwe",
                  """
                  Error message
                  Stack trace line 1
                  StackTrace line 2
                  """
                    .trimIndent(),
                )
              ),
              listOf("com.foo.Bar"),
            ),
          ),
        ),
      )

    assertEquals(expectedPreviewRenderingResult, previewRenderingResult)
  }

  @Test
  fun testPreviewRenderingResultToJsonAndBack_GlobalError() {
    val previewRenderingResult =
      PreviewRenderingResult(
        """
        Error message
        Stack trace line 1
        StackTrace line 2
        """
          .trimIndent(),
        emptyList(),
      )

    val stringWriter = StringWriter()
    writePreviewRenderingResult(stringWriter, previewRenderingResult)

    val restoredPreviewRenderingResult = readPreviewRenderingResultJson(stringWriter.toString().reader())

    assertEquals(previewRenderingResult, restoredPreviewRenderingResult)
  }

  @Test
  fun testPreviewRenderingResultToJsonAndBack_ScreenshotResults() {
    val previewRenderingResult =
      PreviewRenderingResult(
        null,
        listOf(
          PreviewScreenshotResult("previewId1", "methodFQN1", "pkg/class/image.png", null),
          PreviewScreenshotResult(
            "previewId2",
            "methodFQN2",
            "pkg/class/image2.png",
            ScreenshotError(
              "ERROR_RENDER_TASK",
              "Error message",
              """
              Error message
              Stack trace line 1
              StackTrace line 2
              """
                .trimIndent(),
              emptyList(),
              emptyList(),
              emptyList(),
            ),
          ),
          PreviewScreenshotResult(
            "previewId3",
            "methodFQN3",
            "pkg/class/image3.png",
            ScreenshotError(
              "SUCCESS",
              "",
              "",
              listOf(
                RenderProblem("<html>Some error description</html>", null),
                RenderProblem(
                  "<html>Some other error description</html>",
                  """
                  Other error message
                  Stack trace line 1
                  StackTrace line 2
                  """
                    .trimIndent(),
                ),
              ),
              listOf(
                BrokenClass(
                  "com.baz.Qwe",
                  """
                  Error message
                  Stack trace line 1
                  StackTrace line 2
                  """
                    .trimIndent(),
                )
              ),
              listOf("com.foo.Bar"),
            ),
          ),
        ),
      )

    val stringWriter = StringWriter()
    writePreviewRenderingResult(stringWriter, previewRenderingResult)

    val restoredPreviewRenderingResult = readPreviewRenderingResultJson(stringWriter.toString().reader())

    assertEquals(previewRenderingResult, restoredPreviewRenderingResult)
  }
}
