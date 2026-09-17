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
package com.android.tools.deployer.model

import com.android.tools.deployer.apktestutils.manifest
import com.android.tools.deployer.model.component.ComponentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTest {

  @Test
  fun testResolveDefaultComponentSingleLauncher() {
    val app = manifest("com.example.app") { activity(".MainActivity") { launcher() } }

    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.Resolved)
    val resolved = res as ComponentResolution.Resolved
    assertEquals("com.example.app.MainActivity", resolved.component.qualifiedName)
    assertEquals("single launcher activity", resolved.reason)
  }

  @Test
  fun testResolveDefaultComponentMultipleCandidatesPrioritizesLauncher() {
    val app =
      manifest("com.example.app") {
        activity(".BackgroundActivity") { exported = true }
        activity(".LauncherActivity") { launcher() }
      }

    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.Resolved)
    val resolved = res as ComponentResolution.Resolved
    assertEquals("com.example.app.LauncherActivity", resolved.component.qualifiedName)
    assertEquals("single launcher activity", resolved.reason)
  }

  @Test
  fun testResolveDefaultComponentMultipleLaunchersIsAmbiguous() {
    val app =
      manifest("com.example.app") {
        activity(".FirstLauncherActivity") { launcher() }
        activity(".SecondLauncherActivity") { launcher() }
      }

    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.Ambiguous)
    assertEquals(2, (res as ComponentResolution.Ambiguous).candidates.size)
  }

  @Test
  fun testResolveSpecificComponentExactMatch() {
    val app =
      manifest("com.example.app") {
        activity("com.example.app.MainActivity") { launcher() }
        activity("com.example.app.SettingsActivity")
      }

    val res = app.resolveSpecificComponent(ComponentType.ACTIVITY, "com.example.app.SettingsActivity")
    assertTrue(res is ComponentResolution.Resolved)
    val resolved = res as ComponentResolution.Resolved
    assertEquals("com.example.app.SettingsActivity", resolved.component.qualifiedName)
  }

  @Test
  fun testResolveDefaultComponentSkipsDisabledAndUnexported() {
    val app =
      manifest("com.example.app") {
        activity(".DisabledLauncher") {
          enabled = false
          launcher()
        }
        activity(".PrivateLauncher") {
          exported = false
          launcher()
        }
        activity(".UnexportedNoFilter")
        activity(".ValidLauncher") { launcher() }
      }

    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.Resolved)
    val resolved = res as ComponentResolution.Resolved
    assertEquals("com.example.app.ValidLauncher", resolved.component.qualifiedName)
  }

  @Test
  fun testResolveDefaultComponentReturnsNoValidCandidatesWhenOnlyDisabledOrUnexportedExist() {
    val app =
      manifest("com.example.app") {
        activity(".DisabledLauncher") {
          enabled = false
          launcher()
        }
        activity(".UnexportedNoFilter")
      }

    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.NoValidCandidates)
    assertEquals(2, (res as ComponentResolution.NoValidCandidates).candidates.size)
  }

  @Test
  fun testResolveSpecificComponentDotPrefix() {
    val app =
      manifest("com.example.app") {
        activity(".MainActivity") { launcher() }
        activity(".SettingsActivity")
      }

    val res = app.resolveSpecificComponent(ComponentType.ACTIVITY, ".SettingsActivity")
    assertTrue(res is ComponentResolution.Resolved)
    val resolved = res as ComponentResolution.Resolved
    assertEquals("com.example.app.SettingsActivity", resolved.component.qualifiedName)
  }

  @Test
  fun testResolveSpecificComponentSimpleName() {
    val app =
      manifest("com.example.app") {
        activity(".MainActivity") { launcher() }
        activity("com.example.app.settings.SettingsActivity")
      }

    val res = app.resolveSpecificComponent(ComponentType.ACTIVITY, "SettingsActivity")
    assertTrue(res is ComponentResolution.Resolved)
    val resolved = res as ComponentResolution.Resolved
    assertEquals("com.example.app.settings.SettingsActivity", resolved.component.qualifiedName)
  }

  @Test
  fun testGetShortestComponentName() {
    val app =
      manifest("com.example.app") {
        activity(".MainActivity") { launcher() }
        activity("com.example.app.settings.SettingsActivity")
      }

    val main = (app.resolveSpecificComponent(ComponentType.ACTIVITY, "MainActivity") as ComponentResolution.Resolved).component
    val settings = (app.resolveSpecificComponent(ComponentType.ACTIVITY, "SettingsActivity") as ComponentResolution.Resolved).component

    assertEquals("MainActivity", app.getShortestComponentName(main))
    assertEquals("SettingsActivity", app.getShortestComponentName(settings))
  }

  @Test
  fun testDuplicateSimpleNameShortestNameFallback() {
    val app =
      manifest("com.example.app") {
        activity("com.example.app.settings.SettingsActivity") { launcher() }
        activity("com.example.app.other.SettingsActivity")
      }

    val settings1 =
      (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".settings.SettingsActivity") as ComponentResolution.Resolved).component
    val settings2 =
      (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".other.SettingsActivity") as ComponentResolution.Resolved).component

    // Since simple name "SettingsActivity" is ambiguous, shortest name must fall back to relative name
    assertEquals(".settings.SettingsActivity", app.getShortestComponentName(settings1))
    assertEquals(".other.SettingsActivity", app.getShortestComponentName(settings2))
  }

  @Test
  fun testDuplicateSimpleNameAmbiguity() {
    val app =
      manifest("com.example.app") {
        activity("com.example.app.settings.SettingsActivity") { exported = true }
        activity("com.example.app.other.SettingsActivity") { exported = true }
      }

    // Querying by the ambiguous simple name should return Ambiguous
    val res = app.resolveSpecificComponent(ComponentType.ACTIVITY, "SettingsActivity")
    assertTrue(res is ComponentResolution.Ambiguous)
    assertEquals(2, (res as ComponentResolution.Ambiguous).candidates.size)

    // Querying by specific relative name resolves correctly
    val res1 = app.resolveSpecificComponent(ComponentType.ACTIVITY, ".settings.SettingsActivity")
    assertTrue(res1 is ComponentResolution.Resolved)
    assertEquals("com.example.app.settings.SettingsActivity", (res1 as ComponentResolution.Resolved).component.qualifiedName)

    val res2 = app.resolveSpecificComponent(ComponentType.ACTIVITY, ".other.SettingsActivity")
    assertTrue(res2 is ComponentResolution.Resolved)
    assertEquals("com.example.app.other.SettingsActivity", (res2 as ComponentResolution.Resolved).component.qualifiedName)
  }

  @Test
  fun testDuplicateSimpleNameOneExportedDisambiguates() {
    val app =
      manifest("com.example.app") {
        activity("com.example.app.settings.SettingsActivity") {
          exported = true
          launcher()
        }
        activity("com.example.app.internal.SettingsActivity") { exported = false }
      }

    // Only one SettingsActivity is exported, so simple name resolves to the exported one
    val res = app.resolveSpecificComponent(ComponentType.ACTIVITY, "SettingsActivity")
    assertTrue(res is ComponentResolution.Resolved)
    assertEquals("com.example.app.settings.SettingsActivity", (res as ComponentResolution.Resolved).component.qualifiedName)
  }

  @Test
  fun testResolveSpecificComponentNotFound() {
    val app = manifest("com.example.app") { activity(".MainActivity") { launcher() } }

    val res = app.resolveSpecificComponent(ComponentType.ACTIVITY, ".NonExistentActivity")
    assertTrue(res is ComponentResolution.NotFound)
  }

  @Test
  fun testActivityIsAliasAndTargetActivity() {
    val app =
      manifest("com.example.app") {
        activity(".MainActivity") { launcher() }
        activity(".AliasActivity") {
          aliasOf(".MainActivity")
          launcher()
        }
      }

    val main = (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".MainActivity") as ComponentResolution.Resolved).component
    val alias = (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".AliasActivity") as ComponentResolution.Resolved).component

    assertFalse(main.isAlias)
    assertEquals("com.example.app.MainActivity", main.targetActivity)

    assertTrue(alias.isAlias)
    assertEquals("com.example.app.MainActivity", alias.targetActivity)
  }

  @Test
  fun testComponentEnabledAndExported() {
    val app =
      manifest("com.example.app") {
        activity(".NormalActivity") { launcher() }
        activity(".UnexportedWithoutFilter")
        activity(".DisabledActivity") {
          enabled = false
          launcher()
        }
        activity(".ExplicitlyExportedWithoutFilter") { exported = true }
        activity(".ExplicitlyPrivateWithFilter") {
          exported = false
          launcher()
        }
      }

    val normal = (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".NormalActivity") as ComponentResolution.Resolved).component
    val unexported =
      (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".UnexportedWithoutFilter") as ComponentResolution.Resolved).component
    val disabled = (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".DisabledActivity") as ComponentResolution.Resolved).component
    val explicitExported =
      (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".ExplicitlyExportedWithoutFilter") as ComponentResolution.Resolved).component
    val privateWithFilter =
      (app.resolveSpecificComponent(ComponentType.ACTIVITY, ".ExplicitlyPrivateWithFilter") as ComponentResolution.Resolved).component

    assertTrue(normal.isEnabled)
    assertTrue(normal.isExported)

    assertTrue(unexported.isEnabled)
    assertFalse(unexported.isExported)

    assertFalse(disabled.isEnabled)
    assertTrue(disabled.isExported)

    assertTrue(explicitExported.isEnabled)
    assertTrue(explicitExported.isExported)

    assertTrue(privateWithFilter.isEnabled)
    assertFalse(privateWithFilter.isExported)
  }

  @Test
  fun testResolveSpecificComponentWithSubpackageAndLeadingDot() {
    val app =
      manifest("com.example.app") {
        activity(".MainActivity") { launcher() }
        activity("com.example.app.settings.account.AccountSettingsActivity") { exported = true }
      }

    // Leading dot relative subpackage path
    val relSubpkg = app.resolveSpecificComponent(ComponentType.ACTIVITY, ".settings.account.AccountSettingsActivity")
    assertTrue(relSubpkg is ComponentResolution.Resolved)
    assertEquals(
      "com.example.app.settings.account.AccountSettingsActivity",
      (relSubpkg as ComponentResolution.Resolved).component.qualifiedName,
    )

    // Non-dot subpackage path
    val subpkg = app.resolveSpecificComponent(ComponentType.ACTIVITY, "settings.account.AccountSettingsActivity")
    assertTrue(subpkg is ComponentResolution.Resolved)
    assertEquals(
      "com.example.app.settings.account.AccountSettingsActivity",
      (subpkg as ComponentResolution.Resolved).component.qualifiedName,
    )
  }

  @Test
  fun testResolveDefaultComponentFallbackExportedReason() {
    val app = manifest("com.example.app") { activity(".OnlyActivity") { exported = true } }
    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.Resolved)
    val resolved = res as ComponentResolution.Resolved
    assertEquals("com.example.app.OnlyActivity", resolved.component.qualifiedName)
    assertEquals("single exported activity fallback", resolved.reason)
  }

  @Test
  fun testResolveDefaultComponentAmbiguous() {
    val app =
      manifest("com.example.app") {
        activity(".Activity1") { exported = true }
        activity(".Activity2") { exported = true }
      }
    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.Ambiguous)
    val ambiguous = res as ComponentResolution.Ambiguous
    assertEquals(2, ambiguous.candidates.size)
  }

  @Test
  fun testResolveDefaultComponentNoValidCandidates() {
    val app =
      manifest("com.example.app") {
        activity(".DisabledActivity") {
          enabled = false
          exported = true
        }
      }
    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.NoValidCandidates)
  }

  @Test
  fun testResolveDefaultComponentNotFound() {
    val app = manifest("com.example.app") {}
    val res = app.resolveDefaultComponent(ComponentType.ACTIVITY)
    assertTrue(res is ComponentResolution.NotFound)
  }

  @Test
  fun testResolveSpecificComponentReasons() {
    val app =
      manifest("com.example.app") {
        activity("com.example.app.MainActivity") { launcher() }
        activity("com.example.app.settings.SettingsActivity") { exported = true }
      }

    val exact = app.resolveSpecificComponent(ComponentType.ACTIVITY, "com.example.app.MainActivity")
    assertTrue(exact is ComponentResolution.Resolved)
    assertEquals("exact match", (exact as ComponentResolution.Resolved).reason)

    val relative = app.resolveSpecificComponent(ComponentType.ACTIVITY, ".MainActivity")
    assertTrue(relative is ComponentResolution.Resolved)
    assertEquals("relative name match", (relative as ComponentResolution.Resolved).reason)

    val simple = app.resolveSpecificComponent(ComponentType.ACTIVITY, "SettingsActivity")
    assertTrue(simple is ComponentResolution.Resolved)
    assertEquals("simple name match", (simple as ComponentResolution.Resolved).reason)

    val subpkg = app.resolveSpecificComponent(ComponentType.ACTIVITY, "settings.SettingsActivity")
    assertTrue(subpkg is ComponentResolution.Resolved)
    assertEquals("subpackage match", (subpkg as ComponentResolution.Resolved).reason)
  }
}
