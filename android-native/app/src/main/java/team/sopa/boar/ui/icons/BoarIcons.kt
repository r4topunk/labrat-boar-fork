/*
 * Copyright 2025 The Android Open Source Project
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
 *
 * The path data below is copied unchanged from androidx.compose.material:material-icons-extended-android 1.7.8
 * (sources jar on Google Maven); only the wrapping (one lazy property per icon on BoarIcons) is ours.
 */

package team.sopa.boar.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icons the app needs that live only in material-icons-extended. That artifact is ~30 MB of classes and release
 * has no minify, so the few we use are copied here; everything else comes from material-icons-core (Icons.*).
 *
 * One map for every screen: a text glyph or emoji used as an icon is replaced by the icon on its row, drawn with
 * Icon(imageVector, contentDescription) (null when the parent button already carries the label). Sizes: 24 dp in an
 * IconButton (the default), ButtonDefaults.IconSize (18 dp) inside a Button with ButtonDefaults.IconSpacing, 16 dp in
 * a badge or a meta line.
 *
 * | Glyph        | Meaning                              | Icon                                                         |
 * |--------------|--------------------------------------|--------------------------------------------------------------|
 * | ☰            | open the drawer                      | Icons.Filled.Menu                                            |
 * | ← ‹          | back (top bar)                       | Icons.AutoMirrored.Filled.ArrowBack (use BoarTopBar)         |
 * | →            | next / continue (as an icon)         | Icons.AutoMirrored.Filled.ArrowForward                       |
 * | ➤            | send                                 | Icons.AutoMirrored.Filled.Send                               |
 * | ■            | stop answering                       | BoarIcons.Stop                                               |
 * | ⏹            | "stopped" badge                      | BoarIcons.StopCircle                                         |
 * | ⏱            | "timed out" badge                    | BoarIcons.Timer                                              |
 * | ✂            | "cut at the length limit" badge      | BoarIcons.ContentCut                                         |
 * | ↻            | try again / restart                  | Icons.Filled.Refresh                                         |
 * | ⧉            | copy (Check once copied)             | BoarIcons.ContentCopy, then Icons.Filled.Check               |
 * | 💭           | thinking / reasoning                 | BoarIcons.Psychology                                         |
 * | 📚 ▤         | sources, knowledge, library          | BoarIcons.MenuBook                                           |
 * | 🔬           | deep research (off / on)             | BoarIcons.Science / BoarIcons.ScienceFilled                  |
 * | ⚡           | fast / quick answer                  | BoarIcons.Bolt                                               |
 * | ◆            | the answer model (setup category)    | BoarIcons.AutoAwesome                                        |
 * | ⌕ 🔍         | search                               | Icons.Outlined.Search                                        |
 * | 🗑           | delete                               | Icons.Outlined.Delete                                        |
 * | ✓            | selected / done                      | Icons.Filled.Check                                           |
 * | ✕ ✗          | close / dismiss / cancel             | Icons.Filled.Close                                           |
 * | ⓘ            | information                          | Icons.Outlined.Info                                          |
 * | ⚠            | warning                              | Icons.Outlined.Warning                                       |
 * | !            | error / failed                       | BoarIcons.ErrorOutline                                       |
 * | ▾ ▼ / ▴ ▲    | expand / collapse                    | BoarIcons.ExpandMore / BoarIcons.ExpandLess                  |
 * | ▸            | disclosure (row opens a screen)      | Icons.AutoMirrored.Filled.KeyboardArrowRight                 |
 * | ⌫            | PIN pad backspace                    | BoarIcons.Backspace                                          |
 * | ◷            | waiting / indexing                   | BoarIcons.Schedule                                           |
 * | ☑ / ☐        | checked / unchecked                  | prefer the M3 Checkbox; else BoarIcons.CheckBox / CheckBoxOutlineBlank |
 * | 🟢 🟡 🔴     | fits / may be slow / won't fit       | Icons.Filled.CheckCircle (primary) / Icons.Outlined.Warning (tertiary) / BoarIcons.ErrorOutline (error) |
 * | (none yet)   | download / downloaded                | BoarIcons.Download / BoarIcons.CloudDone                     |
 * | (none yet)   | import a document / a document       | BoarIcons.UploadFile / BoarIcons.Description                 |
 * | (none yet)   | memory (RAM)                         | BoarIcons.Memory                                             |
 *
 * Not icons, so they stay text: the "●" status dot (a Box with CircleShape), "…" and "≤" in copy, "→ model" in the
 * header subtitle, and the ✓/✗ feature list in the developer telemetry.
 */
object BoarIcons {
    /** Material Icons Outlined.ContentCopy (material-icons-extended outlined/ContentCopy.kt). */
    val ContentCopy: ImageVector by lazy {
        materialIcon(name = "Boar.ContentCopy") {
            materialPath {
                moveTo(16.0f, 1.0f)
                lineTo(4.0f, 1.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(2.0f)
                lineTo(4.0f, 3.0f)
                horizontalLineToRelative(12.0f)
                lineTo(16.0f, 1.0f)
                close()
                moveTo(19.0f, 5.0f)
                lineTo(8.0f, 5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(11.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(19.0f, 21.0f)
                lineTo(8.0f, 21.0f)
                lineTo(8.0f, 7.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
    }

    /** Material Icons Outlined.Science (material-icons-extended outlined/Science.kt). */
    val Science: ImageVector by lazy {
        materialIcon(name = "Boar.Science") {
            materialPath {
                moveTo(13.0f, 11.33f)
                lineTo(18.0f, 18.0f)
                horizontalLineTo(6.0f)
                lineToRelative(5.0f, -6.67f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(2.0f)
                moveTo(15.96f, 4.0f)
                horizontalLineTo(8.04f)
                curveTo(7.62f, 4.0f, 7.39f, 4.48f, 7.65f, 4.81f)
                lineTo(9.0f, 6.5f)
                verticalLineToRelative(4.17f)
                lineTo(3.2f, 18.4f)
                curveTo(2.71f, 19.06f, 3.18f, 20.0f, 4.0f, 20.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.82f, 0.0f, 1.29f, -0.94f, 0.8f, -1.6f)
                lineTo(15.0f, 10.67f)
                verticalLineTo(6.5f)
                lineToRelative(1.35f, -1.69f)
                curveTo(16.61f, 4.48f, 16.38f, 4.0f, 15.96f, 4.0f)
                lineTo(15.96f, 4.0f)
                close()
            }
        }
    }

    /** Material Icons Filled.Science (material-icons-extended filled/Science.kt). */
    val ScienceFilled: ImageVector by lazy {
        materialIcon(name = "Boar.ScienceFilled") {
            materialPath {
                moveTo(19.8f, 18.4f)
                lineTo(14.0f, 10.67f)
                verticalLineTo(6.5f)
                lineToRelative(1.35f, -1.69f)
                curveTo(15.61f, 4.48f, 15.38f, 4.0f, 14.96f, 4.0f)
                horizontalLineTo(9.04f)
                curveTo(8.62f, 4.0f, 8.39f, 4.48f, 8.65f, 4.81f)
                lineTo(10.0f, 6.5f)
                verticalLineToRelative(4.17f)
                lineTo(4.2f, 18.4f)
                curveTo(3.71f, 19.06f, 4.18f, 20.0f, 5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                curveTo(19.82f, 20.0f, 20.29f, 19.06f, 19.8f, 18.4f)
                close()
            }
        }
    }

    /** Material Icons Filled.Stop (material-icons-extended filled/Stop.kt). */
    val Stop: ImageVector by lazy {
        materialIcon(name = "Boar.Stop") {
            materialPath {
                moveTo(6.0f, 6.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(12.0f)
                horizontalLineTo(6.0f)
                close()
            }
        }
    }

    /** Material Icons Outlined.StopCircle (material-icons-extended outlined/StopCircle.kt). */
    val StopCircle: ImageVector by lazy {
        materialIcon(name = "Boar.StopCircle") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                curveTo(22.0f, 6.48f, 17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveTo(16.42f, 20.0f, 12.0f, 20.0f)
                close()
                moveTo(16.0f, 16.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(16.0f)
                close()
            }
        }
    }

    /** Material Icons Outlined.Timer (material-icons-extended outlined/Timer.kt). */
    val Timer: ImageVector by lazy {
        materialIcon(name = "Boar.Timer") {
            materialPath {
                moveTo(15.0f, 1.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(1.0f)
                close()
                moveTo(11.0f, 14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(19.03f, 7.39f)
                lineToRelative(1.42f, -1.42f)
                curveToRelative(-0.43f, -0.51f, -0.9f, -0.99f, -1.41f, -1.41f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(16.07f, 4.74f, 14.12f, 4.0f, 12.0f, 4.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                reflectiveCurveToRelative(4.02f, 9.0f, 9.0f, 9.0f)
                reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
                curveTo(21.0f, 10.88f, 20.26f, 8.93f, 19.03f, 7.39f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-3.87f, 0.0f, -7.0f, -3.13f, -7.0f, -7.0f)
                reflectiveCurveToRelative(3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                reflectiveCurveTo(15.87f, 20.0f, 12.0f, 20.0f)
                close()
            }
        }
    }

    /** Material Icons Outlined.ContentCut (material-icons-extended outlined/ContentCut.kt). */
    val ContentCut: ImageVector by lazy {
        materialIcon(name = "Boar.ContentCut") {
            materialPath {
                moveTo(9.64f, 7.64f)
                curveToRelative(0.23f, -0.5f, 0.36f, -1.05f, 0.36f, -1.64f)
                curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
                reflectiveCurveTo(2.0f, 3.79f, 2.0f, 6.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                curveToRelative(0.59f, 0.0f, 1.14f, -0.13f, 1.64f, -0.36f)
                lineTo(10.0f, 12.0f)
                lineToRelative(-2.36f, 2.36f)
                curveTo(7.14f, 14.13f, 6.59f, 14.0f, 6.0f, 14.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
                reflectiveCurveToRelative(1.79f, 4.0f, 4.0f, 4.0f)
                reflectiveCurveToRelative(4.0f, -1.79f, 4.0f, -4.0f)
                curveToRelative(0.0f, -0.59f, -0.13f, -1.14f, -0.36f, -1.64f)
                lineTo(12.0f, 14.0f)
                lineToRelative(7.0f, 7.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-1.0f)
                lineTo(9.64f, 7.64f)
                close()
                moveTo(6.0f, 8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.89f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.89f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                close()
                moveTo(6.0f, 20.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.89f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.89f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                close()
                moveTo(12.0f, 12.5f)
                curveToRelative(-0.28f, 0.0f, -0.5f, -0.22f, -0.5f, -0.5f)
                reflectiveCurveToRelative(0.22f, -0.5f, 0.5f, -0.5f)
                reflectiveCurveToRelative(0.5f, 0.22f, 0.5f, 0.5f)
                reflectiveCurveToRelative(-0.22f, 0.5f, -0.5f, 0.5f)
                close()
                moveTo(19.0f, 3.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(2.0f, 2.0f)
                lineToRelative(7.0f, -7.0f)
                lineTo(22.0f, 3.0f)
                horizontalLineToRelative(-3.0f)
                close()
            }
        }
    }

    /** Material Icons AutoMirrored.Outlined.MenuBook (material-icons-extended automirrored/outlined/MenuBook.kt). */
    val MenuBook: ImageVector by lazy {
        materialIcon(name = "Boar.MenuBook", autoMirror = true) {
            materialPath {
                moveTo(21.0f, 5.0f)
                curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
                curveToRelative(-1.95f, 0.0f, -4.05f, 0.4f, -5.5f, 1.5f)
                curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
                reflectiveCurveTo(2.45f, 4.9f, 1.0f, 6.0f)
                verticalLineToRelative(14.65f)
                curveToRelative(0.0f, 0.25f, 0.25f, 0.5f, 0.5f, 0.5f)
                curveToRelative(0.1f, 0.0f, 0.15f, -0.05f, 0.25f, -0.05f)
                curveTo(3.1f, 20.45f, 5.05f, 20.0f, 6.5f, 20.0f)
                curveToRelative(1.95f, 0.0f, 4.05f, 0.4f, 5.5f, 1.5f)
                curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                curveToRelative(1.65f, 0.0f, 3.35f, 0.3f, 4.75f, 1.05f)
                curveToRelative(0.1f, 0.05f, 0.15f, 0.05f, 0.25f, 0.05f)
                curveToRelative(0.25f, 0.0f, 0.5f, -0.25f, 0.5f, -0.5f)
                verticalLineTo(6.0f)
                curveTo(22.4f, 5.55f, 21.75f, 5.25f, 21.0f, 5.0f)
                close()
                moveTo(21.0f, 18.5f)
                curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
                curveToRelative(-1.7f, 0.0f, -4.15f, 0.65f, -5.5f, 1.5f)
                verticalLineTo(8.0f)
                curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
                curveToRelative(1.2f, 0.0f, 2.4f, 0.15f, 3.5f, 0.5f)
                verticalLineTo(18.5f)
                close()
            }
            materialPath {
                moveTo(17.5f, 10.5f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineTo(9.24f)
                curveTo(19.21f, 9.09f, 18.36f, 9.0f, 17.5f, 9.0f)
                curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
                verticalLineToRelative(1.66f)
                curveTo(14.13f, 10.85f, 15.7f, 10.5f, 17.5f, 10.5f)
                close()
            }
            materialPath {
                moveTo(13.0f, 12.49f)
                verticalLineToRelative(1.66f)
                curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineTo(11.9f)
                curveToRelative(-0.79f, -0.15f, -1.64f, -0.24f, -2.5f, -0.24f)
                curveTo(15.8f, 11.66f, 14.26f, 11.96f, 13.0f, 12.49f)
                close()
            }
            materialPath {
                moveTo(17.5f, 14.33f)
                curveToRelative(-1.7f, 0.0f, -3.24f, 0.29f, -4.5f, 0.83f)
                verticalLineToRelative(1.66f)
                curveToRelative(1.13f, -0.64f, 2.7f, -0.99f, 4.5f, -0.99f)
                curveToRelative(0.88f, 0.0f, 1.73f, 0.09f, 2.5f, 0.26f)
                verticalLineToRelative(-1.52f)
                curveTo(19.21f, 14.41f, 18.36f, 14.33f, 17.5f, 14.33f)
                close()
            }
        }
    }

    /** Material Icons Outlined.Psychology (material-icons-extended outlined/Psychology.kt). */
    val Psychology: ImageVector by lazy {
        materialIcon(name = "Boar.Psychology") {
            materialPath {
                moveTo(15.82f, 7.22f)
                lineToRelative(-1.0f, 0.4f)
                curveToRelative(-0.21f, -0.16f, -0.43f, -0.29f, -0.67f, -0.39f)
                lineTo(14.0f, 6.17f)
                curveTo(13.98f, 6.07f, 13.9f, 6.0f, 13.8f, 6.0f)
                horizontalLineToRelative(-1.6f)
                curveToRelative(-0.1f, 0.0f, -0.18f, 0.07f, -0.19f, 0.17f)
                lineToRelative(-0.15f, 1.06f)
                curveToRelative(-0.24f, 0.1f, -0.47f, 0.23f, -0.67f, 0.39f)
                lineToRelative(-1.0f, -0.4f)
                curveToRelative(-0.09f, -0.03f, -0.2f, 0.0f, -0.24f, 0.09f)
                lineToRelative(-0.8f, 1.38f)
                curveToRelative(-0.05f, 0.09f, -0.03f, 0.2f, 0.05f, 0.26f)
                lineToRelative(0.85f, 0.66f)
                curveTo(10.02f, 9.73f, 10.0f, 9.87f, 10.0f, 10.0f)
                curveToRelative(0.0f, 0.13f, 0.01f, 0.26f, 0.03f, 0.39f)
                lineToRelative(-0.84f, 0.66f)
                curveToRelative(-0.08f, 0.06f, -0.1f, 0.17f, -0.05f, 0.25f)
                lineToRelative(0.8f, 1.39f)
                curveToRelative(0.05f, 0.09f, 0.15f, 0.12f, 0.25f, 0.09f)
                lineToRelative(0.99f, -0.4f)
                curveToRelative(0.21f, 0.16f, 0.43f, 0.29f, 0.68f, 0.39f)
                lineTo(12.0f, 13.83f)
                curveToRelative(0.02f, 0.1f, 0.1f, 0.17f, 0.2f, 0.17f)
                horizontalLineToRelative(1.6f)
                curveToRelative(0.1f, 0.0f, 0.18f, -0.07f, 0.2f, -0.17f)
                lineToRelative(0.15f, -1.06f)
                curveToRelative(0.24f, -0.1f, 0.47f, -0.23f, 0.67f, -0.39f)
                lineToRelative(0.99f, 0.4f)
                curveToRelative(0.09f, 0.04f, 0.2f, 0.0f, 0.24f, -0.09f)
                lineToRelative(0.8f, -1.39f)
                curveToRelative(0.05f, -0.09f, 0.03f, -0.19f, -0.05f, -0.25f)
                lineToRelative(-0.83f, -0.66f)
                curveTo(15.99f, 10.26f, 16.0f, 10.13f, 16.0f, 10.0f)
                curveToRelative(0.0f, -0.14f, -0.01f, -0.27f, -0.03f, -0.39f)
                lineToRelative(0.85f, -0.66f)
                curveToRelative(0.08f, -0.06f, 0.1f, -0.17f, 0.05f, -0.26f)
                lineToRelative(-0.8f, -1.38f)
                curveTo(16.02f, 7.22f, 15.91f, 7.19f, 15.82f, 7.22f)
                close()
                moveTo(13.0f, 11.43f)
                curveToRelative(-0.79f, 0.0f, -1.43f, -0.64f, -1.43f, -1.43f)
                reflectiveCurveTo(12.21f, 8.57f, 13.0f, 8.57f)
                reflectiveCurveToRelative(1.43f, 0.64f, 1.43f, 1.43f)
                reflectiveCurveTo(13.79f, 11.43f, 13.0f, 11.43f)
                close()
            }
            materialPath {
                moveTo(19.94f, 9.06f)
                curveToRelative(-0.43f, -3.27f, -3.23f, -5.86f, -6.53f, -6.05f)
                curveTo(13.27f, 3.0f, 13.14f, 3.0f, 13.0f, 3.0f)
                curveTo(9.47f, 3.0f, 6.57f, 5.61f, 6.08f, 9.0f)
                lineToRelative(-1.93f, 3.48f)
                curveTo(3.74f, 13.14f, 4.22f, 14.0f, 5.0f, 14.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(-4.68f)
                curveTo(18.62f, 15.07f, 20.35f, 12.24f, 19.94f, 9.06f)
                close()
                moveTo(14.89f, 14.63f)
                lineTo(14.0f, 15.05f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineTo(6.7f)
                lineToRelative(1.33f, -2.33f)
                curveTo(8.21f, 7.06f, 10.35f, 5.0f, 13.0f, 5.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
                curveTo(18.0f, 12.09f, 16.71f, 13.88f, 14.89f, 14.63f)
                close()
            }
        }
    }

    /** Material Icons Filled.ExpandMore (material-icons-extended filled/ExpandMore.kt). */
    val ExpandMore: ImageVector by lazy {
        materialIcon(name = "Boar.ExpandMore") {
            materialPath {
                moveTo(16.59f, 8.59f)
                lineTo(12.0f, 13.17f)
                lineTo(7.41f, 8.59f)
                lineTo(6.0f, 10.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(6.0f, -6.0f)
                close()
            }
        }
    }

    /** Material Icons Filled.ExpandLess (material-icons-extended filled/ExpandLess.kt). */
    val ExpandLess: ImageVector by lazy {
        materialIcon(name = "Boar.ExpandLess") {
            materialPath {
                moveTo(12.0f, 8.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(12.0f, 10.83f)
                lineToRelative(4.59f, 4.58f)
                lineTo(18.0f, 14.0f)
                close()
            }
        }
    }

    /** Material Icons AutoMirrored.Outlined.Backspace (material-icons-extended automirrored/outlined/Backspace.kt). */
    val Backspace: ImageVector by lazy {
        materialIcon(name = "Boar.Backspace", autoMirror = true) {
            materialPath {
                moveTo(22.0f, 3.0f)
                lineTo(7.0f, 3.0f)
                curveToRelative(-0.69f, 0.0f, -1.23f, 0.35f, -1.59f, 0.88f)
                lineTo(0.0f, 12.0f)
                lineToRelative(5.41f, 8.11f)
                curveToRelative(0.36f, 0.53f, 0.9f, 0.89f, 1.59f, 0.89f)
                horizontalLineToRelative(15.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(24.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(22.0f, 19.0f)
                lineTo(7.07f, 19.0f)
                lineTo(2.4f, 12.0f)
                lineToRelative(4.66f, -7.0f)
                lineTo(22.0f, 5.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(10.41f, 17.0f)
                lineTo(14.0f, 13.41f)
                lineTo(17.59f, 17.0f)
                lineTo(19.0f, 15.59f)
                lineTo(15.41f, 12.0f)
                lineTo(19.0f, 8.41f)
                lineTo(17.59f, 7.0f)
                lineTo(14.0f, 10.59f)
                lineTo(10.41f, 7.0f)
                lineTo(9.0f, 8.41f)
                lineTo(12.59f, 12.0f)
                lineTo(9.0f, 15.59f)
                close()
            }
        }
    }

    /** Material Icons Outlined.Schedule (material-icons-extended outlined/Schedule.kt). */
    val Schedule: ImageVector by lazy {
        materialIcon(name = "Boar.Schedule") {
            materialPath {
                moveTo(11.99f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
                moveTo(12.5f, 7.0f)
                lineTo(11.0f, 7.0f)
                verticalLineToRelative(6.0f)
                lineToRelative(5.25f, 3.15f)
                lineToRelative(0.75f, -1.23f)
                lineToRelative(-4.5f, -2.67f)
                close()
            }
        }
    }

    /** Material Icons Filled.CheckBox (material-icons-extended filled/CheckBox.kt). */
    val CheckBox: ImageVector by lazy {
        materialIcon(name = "Boar.CheckBox") {
            materialPath {
                moveTo(19.0f, 3.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.11f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.89f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(10.0f, 17.0f)
                lineToRelative(-5.0f, -5.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(10.0f, 14.17f)
                lineToRelative(7.59f, -7.59f)
                lineTo(19.0f, 8.0f)
                lineToRelative(-9.0f, 9.0f)
                close()
            }
        }
    }

    /** Material Icons Filled.CheckBoxOutlineBlank (material-icons-extended filled/CheckBoxOutlineBlank.kt). */
    val CheckBoxOutlineBlank: ImageVector by lazy {
        materialIcon(name = "Boar.CheckBoxOutlineBlank") {
            materialPath {
                moveTo(19.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(14.0f)
                moveToRelative(0.0f, -2.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }
    }

    /** Material Icons Filled.ErrorOutline (material-icons-extended filled/ErrorOutline.kt). */
    val ErrorOutline: ImageVector by lazy {
        materialIcon(name = "Boar.ErrorOutline") {
            materialPath {
                moveTo(11.0f, 15.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(11.0f, 7.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(11.99f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }
    }

    /** Material Icons Outlined.Download (material-icons-extended outlined/Download.kt). */
    val Download: ImageVector by lazy {
        materialIcon(name = "Boar.Download") {
            materialPath {
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(15.0f, 3.0f)
                lineTo(9.0f, 3.0f)
                verticalLineToRelative(6.0f)
                lineTo(5.0f, 9.0f)
                lineToRelative(7.0f, 7.0f)
                lineToRelative(7.0f, -7.0f)
                close()
                moveTo(11.0f, 11.0f)
                lineTo(11.0f, 5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(1.17f)
                lineTo(12.0f, 13.17f)
                lineTo(9.83f, 11.0f)
                lineTo(11.0f, 11.0f)
                close()
                moveTo(5.0f, 18.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                lineTo(5.0f, 20.0f)
                close()
            }
        }
    }

    /** Material Icons Filled.Bolt (material-icons-extended filled/Bolt.kt). */
    val Bolt: ImageVector by lazy {
        materialIcon(name = "Boar.Bolt") {
            materialPath {
                moveTo(11.0f, 21.0f)
                horizontalLineToRelative(-1.0f)
                lineToRelative(1.0f, -7.0f)
                horizontalLineTo(7.5f)
                curveToRelative(-0.58f, 0.0f, -0.57f, -0.32f, -0.38f, -0.66f)
                curveToRelative(0.19f, -0.34f, 0.05f, -0.08f, 0.07f, -0.12f)
                curveTo(8.48f, 10.94f, 10.42f, 7.54f, 13.0f, 3.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(-1.0f, 7.0f)
                horizontalLineToRelative(3.5f)
                curveToRelative(0.49f, 0.0f, 0.56f, 0.33f, 0.47f, 0.51f)
                lineToRelative(-0.07f, 0.15f)
                curveTo(12.96f, 17.55f, 11.0f, 21.0f, 11.0f, 21.0f)
                close()
            }
        }
    }

    /** Material Icons Outlined.Memory (material-icons-extended outlined/Memory.kt). */
    val Memory: ImageVector by lazy {
        materialIcon(name = "Boar.Memory") {
            materialPath {
                moveTo(15.0f, 9.0f)
                lineTo(9.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                lineTo(15.0f, 9.0f)
                close()
                moveTo(13.0f, 13.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(21.0f, 11.0f)
                lineTo(21.0f, 9.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(19.0f, 7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(15.0f, 3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-2.0f)
                lineTo(11.0f, 3.0f)
                lineTo(9.0f, 3.0f)
                verticalLineToRelative(2.0f)
                lineTo(7.0f, 5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(2.0f)
                lineTo(3.0f, 9.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(3.0f, 13.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                close()
                moveTo(17.0f, 17.0f)
                lineTo(7.0f, 17.0f)
                lineTo(7.0f, 7.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }
    }

    /** Material Icons Outlined.CloudDone (material-icons-extended outlined/CloudDone.kt). */
    val CloudDone: ImageVector by lazy {
        materialIcon(name = "Boar.CloudDone") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(19.0f, 18.0f)
                lineTo(6.0f, 18.0f)
                curveToRelative(-2.21f, 0.0f, -4.0f, -1.79f, -4.0f, -4.0f)
                curveToRelative(0.0f, -2.05f, 1.53f, -3.76f, 3.56f, -3.97f)
                lineToRelative(1.07f, -0.11f)
                lineToRelative(0.5f, -0.95f)
                curveTo(8.08f, 7.14f, 9.94f, 6.0f, 12.0f, 6.0f)
                curveToRelative(2.62f, 0.0f, 4.88f, 1.86f, 5.39f, 4.43f)
                lineToRelative(0.3f, 1.5f)
                lineToRelative(1.53f, 0.11f)
                curveToRelative(1.56f, 0.1f, 2.78f, 1.41f, 2.78f, 2.96f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(10.0f, 14.18f)
                lineToRelative(-2.09f, -2.09f)
                lineTo(6.5f, 13.5f)
                lineTo(10.0f, 17.0f)
                lineToRelative(6.01f, -6.01f)
                lineToRelative(-1.41f, -1.41f)
                close()
            }
        }
    }

    /** Material Icons Outlined.UploadFile (material-icons-extended outlined/UploadFile.kt). */
    val UploadFile: ImageVector by lazy {
        materialIcon(name = "Boar.UploadFile") {
            materialPath {
                moveTo(14.0f, 2.0f)
                horizontalLineTo(6.0f)
                curveTo(4.9f, 2.0f, 4.01f, 2.9f, 4.01f, 4.0f)
                lineTo(4.0f, 20.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                horizontalLineTo(18.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(8.0f)
                lineTo(14.0f, 2.0f)
                close()
                moveTo(18.0f, 20.0f)
                horizontalLineTo(6.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(5.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(8.0f, 15.01f)
                lineToRelative(1.41f, 1.41f)
                lineTo(11.0f, 14.84f)
                verticalLineTo(19.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.16f)
                lineToRelative(1.59f, 1.59f)
                lineTo(16.0f, 15.01f)
                lineTo(12.01f, 11.0f)
                lineTo(8.0f, 15.01f)
                close()
            }
        }
    }

    /** Material Icons Outlined.AutoAwesome (material-icons-extended outlined/AutoAwesome.kt). */
    val AutoAwesome: ImageVector by lazy {
        materialIcon(name = "Boar.AutoAwesome") {
            materialPath {
                moveTo(19.0f, 9.0f)
                lineToRelative(1.25f, -2.75f)
                lineToRelative(2.75f, -1.25f)
                lineToRelative(-2.75f, -1.25f)
                lineToRelative(-1.25f, -2.75f)
                lineToRelative(-1.25f, 2.75f)
                lineToRelative(-2.75f, 1.25f)
                lineToRelative(2.75f, 1.25f)
                close()
            }
            materialPath {
                moveTo(19.0f, 15.0f)
                lineToRelative(-1.25f, 2.75f)
                lineToRelative(-2.75f, 1.25f)
                lineToRelative(2.75f, 1.25f)
                lineToRelative(1.25f, 2.75f)
                lineToRelative(1.25f, -2.75f)
                lineToRelative(2.75f, -1.25f)
                lineToRelative(-2.75f, -1.25f)
                close()
            }
            materialPath {
                moveTo(11.5f, 9.5f)
                lineTo(9.0f, 4.0f)
                lineTo(6.5f, 9.5f)
                lineTo(1.0f, 12.0f)
                lineToRelative(5.5f, 2.5f)
                lineTo(9.0f, 20.0f)
                lineToRelative(2.5f, -5.5f)
                lineTo(17.0f, 12.0f)
                lineTo(11.5f, 9.5f)
                close()
                moveTo(9.99f, 12.99f)
                lineTo(9.0f, 15.17f)
                lineToRelative(-0.99f, -2.18f)
                lineTo(5.83f, 12.0f)
                lineToRelative(2.18f, -0.99f)
                lineTo(9.0f, 8.83f)
                lineToRelative(0.99f, 2.18f)
                lineTo(12.17f, 12.0f)
                lineTo(9.99f, 12.99f)
                close()
            }
        }
    }

    /** Material Icons Outlined.Description (material-icons-extended outlined/Description.kt). */
    val Description: ImageVector by lazy {
        materialIcon(name = "Boar.Description") {
            materialPath {
                moveTo(8.0f, 16.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                lineTo(8.0f, 18.0f)
                close()
                moveTo(8.0f, 12.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                lineTo(8.0f, 14.0f)
                close()
                moveTo(14.0f, 2.0f)
                lineTo(6.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 1.99f, 2.0f)
                lineTo(18.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 8.0f)
                lineToRelative(-6.0f, -6.0f)
                close()
                moveTo(18.0f, 20.0f)
                lineTo(6.0f, 20.0f)
                lineTo(6.0f, 4.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(11.0f)
                close()
            }
        }
    }
}
