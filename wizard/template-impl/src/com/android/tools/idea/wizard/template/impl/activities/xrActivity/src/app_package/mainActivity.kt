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
package com.android.tools.idea.wizard.template.impl.activities.xrActivity.src.app_package

import com.android.tools.idea.wizard.template.escapeKotlinIdentifier

fun mainActivityKt(activityClass: String, packageName: String, themeName: String) =
  """
package ${escapeKotlinIdentifier(packageName)}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterAnchorPoint
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeOffset
import androidx.xr.scenecore.scene
import ${escapeKotlinIdentifier(packageName)}.ui.theme.${escapeKotlinIdentifier(themeName)}


class $activityClass : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ${escapeKotlinIdentifier(themeName)} {
                val session = LocalSession.current
                if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
                    Subspace {
                        MySpatialContent {
                            session?.scene?.requestHomeSpace()
                        }
                    }
                } else {
                    My2DContent {
                        session?.scene?.requestHomeSpace()
                    }
                }
            }
        }
    }
}

@Composable
fun MySpatialContent(onRequestHomeSpace: () -> Unit) {
    SpatialPanel(SubspaceModifier.width(1280.dp).height(800.dp).resizable().movable()) {
        Surface {
            MainContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
            )
        }
        Orbiter(
            anchorPoint = OrbiterAnchorPoint.TopEnd,
            offset = DpVolumeOffset(y = 20.dp),
            shape = SpatialRoundedCornerShape(CornerSize(28.dp)),
        ) {
            HomeSpaceIconButton(
                onClick = onRequestHomeSpace,
                modifier = Modifier.size(56.dp)
            )
        }
    }
}

@Composable
fun My2DContent(onRequestFullSpace: () -> Unit) {
    Surface {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MainContent(modifier = Modifier.padding(48.dp))
            // Preview does not current support XR sessions.
            if (LocalSession.current != null) {
                FullSpaceIconButton(
                    onClick = onRequestFullSpace,
                    modifier = Modifier.padding(32.dp)
                )
            }
        }
    }
}

@Composable
fun MainContent(modifier: Modifier = Modifier) {
    Text(text = stringResource(R.string.hello_android_xr), modifier = modifier)
}

@Composable
fun FullSpaceIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_full_space_switch),
            contentDescription = stringResource(R.string.switch_to_full_space),
        )
    }
}

@Composable
fun HomeSpaceIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_home_space_switch),
            contentDescription = stringResource(R.string.switch_to_home_space),
        )
    }
}

@PreviewLightDark
@Composable
fun My2dContentPreview() {
    ${escapeKotlinIdentifier(themeName)} {
        My2DContent {}
    }
}

@Preview(showBackground = true)
@Composable
fun FullSpaceButtonPreview() {
    ${escapeKotlinIdentifier(themeName)} {
        FullSpaceIconButton(onClick = {})
    }
}

@PreviewLightDark
@Composable
fun HomeSpaceButtonPreview() {
    ${escapeKotlinIdentifier(themeName)} {
        HomeSpaceIconButton(onClick = {})
    }
}

"""
