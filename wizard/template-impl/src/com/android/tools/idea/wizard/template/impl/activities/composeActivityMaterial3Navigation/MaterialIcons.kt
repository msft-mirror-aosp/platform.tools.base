/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.idea.wizard.template.impl.activities.composeActivityMaterial3Navigation

internal fun createHomeIconXml(): String {
  return """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M240,760L360,760L360,520L600,520L600,760L720,760L720,400L480,220L240,400L240,760ZM160,840L160,360L480,120L800,360L800,840L520,840L520,600L440,600L440,840L160,840ZM480,490L480,490L480,490L480,490L480,490L480,490L480,490L480,490L480,490Z"/>
</vector>
"""
}

internal fun createFavoriteIconXml(): String {
  return """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M480,839L439,802Q333.23,704.88 264.12,634.44Q195,564 154,508.5Q113,453 96.5,408Q80,363 80,317Q80,226.85 140.5,166.42Q201,106 290,106Q347,106 395.5,133Q444,160 480,211Q522,157 569,131.5Q616,106 670,106Q759,106 819.5,166.42Q880,226.85 880,317Q880,363 863.5,408Q847,453 806,508.5Q765,564 695.88,634.44Q626.77,704.88 521,802L480,839ZM480,760Q581.24,667 646.62,600.5Q712,534 750.5,484Q789,434 804.5,394.86Q820,355.73 820,317.14Q820,251 778,208.5Q736,166 670.22,166Q618.7,166 574.85,197.5Q531,229 504,286L504,286L455,286L455,286Q429,230 385.15,198Q341.3,166 289.78,166Q224,166 182,208.5Q140,251 140,317.32Q140,356 155.5,395.5Q171,435 209.5,485.5Q248,536 314,602Q380,668 480,760ZM480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463L480,463L480,463L480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Q480,463 480,463Z"/>
</vector>
"""
}

internal fun createAccountBoxIconXml(): String {
  return """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
  <path
      android:fillColor="@android:color/white"
      android:pathData="M180,743Q240,687 315.9,652.5Q391.79,618 479.9,618Q568,618 644,652.5Q720,687 780,743L780,180Q780,180 780,180Q780,180 780,180L180,180Q180,180 180,180Q180,180 180,180L180,743ZM482,539Q540,539 580,499Q620,459 620,401Q620,343 580,303Q540,263 482,263Q424,263 384,303Q344,343 344,401Q344,459 384,499Q424,539 482,539ZM180,840Q156,840 138,822Q120,804 120,780L120,180Q120,156 138,138Q156,120 180,120L780,120Q804,120 822,138Q840,156 840,180L840,780Q840,804 822,822Q804,840 780,840L180,840ZM223,780L736,780Q736,780 736,780Q736,780 736,780Q674,727 610.5,702.5Q547,678 480,678Q413,678 349.5,702.5Q286,727 223,780Q223,780 223,780Q223,780 223,780ZM482,479Q449.5,479 426.75,456.25Q404,433.5 404,401Q404,368.5 426.75,345.75Q449.5,323 482,323Q514.5,323 537.25,345.75Q560,368.5 560,401Q560,433.5 537.25,456.25Q514.5,479 482,479ZM480,461L480,461Q480,461 480,461Q480,461 480,461L480,461Q480,461 480,461Q480,461 480,461L480,461Q480,461 480,461Q480,461 480,461Q480,461 480,461Q480,461 480,461Z"/>
</vector>
"""
}
