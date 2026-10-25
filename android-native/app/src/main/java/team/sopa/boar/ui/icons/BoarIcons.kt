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
 * a badge or a meta line (named in ui/theme/IconAlign.kt: IconSize). One family: Outlined for chrome; Filled only for an
 * on/selected state or the primary send.
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
 * | 📚 ▤         | sources, knowledge, library          | BoarIcons.LibraryBooks                                       |
 * | 🔬           | evaluation (developer menu)          | BoarIcons.Science                                            |
 * | ⌕ 🔍         | search                               | Icons.Outlined.Search                                        |
 * | 🗑           | delete                               | Icons.Outlined.Delete                                        |
 * | ✓            | selected / done                      | Icons.Filled.Check                                           |
 * | ✕ ✗          | close / dismiss / cancel             | Icons.Filled.Close                                           |
 * | ⓘ            | information                          | Icons.Outlined.Info                                          |
 * | ⚠            | warning                              | Icons.Outlined.Warning                                       |
 * | !            | error / failed                       | BoarIcons.ErrorOutline                                       |
 * | ▾ ▼ / ▴ ▲    | expand / collapse                    | BoarIcons.ExpandMore / BoarIcons.ExpandLess                  |
 * | ▸            | disclosure (row opens a screen)      | Icons.AutoMirrored.Filled.KeyboardArrowRight                 |
 * | 🟢 🟡 🔴     | fits / may be slow / won't fit       | Icons.Filled.CheckCircle (primary) / Icons.Outlined.Warning (tertiary) / BoarIcons.ErrorOutline (error) |
 * | (none yet)   | import a document / a document       | BoarIcons.UploadFile / BoarIcons.Description                 |
 * | (none yet)   | memory (RAM)                         | BoarIcons.Memory                                             |
 *
 * Not icons, so they stay text: the "●" status dot (a Box with CircleShape), "…" and "≤" in copy, "→ model" in the
 * header subtitle, and the ✓/✗ feature list in the developer telemetry.
 */
object BoarIcons {
    /** Material Icons Outlined.Tune (material-icons-extended outlined/Tune.kt): the chat's answer options. */
    val Tune: ImageVector by lazy {
        materialIcon(name = "Boar.Tune") {
            materialPath {
                moveTo(3.0f, 17.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 17.0f)
                close()
                moveTo(3.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                lineTo(13.0f, 5.0f)
                lineTo(3.0f, 5.0f)
                close()
                moveTo(13.0f, 21.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                close()
                moveTo(7.0f, 9.0f)
                verticalLineToRelative(2.0f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                lineTo(9.0f, 9.0f)
                lineTo(7.0f, 9.0f)
                close()
                moveTo(21.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                close()
                moveTo(15.0f, 9.0f)
                horizontalLineToRelative(2.0f)
                lineTo(17.0f, 7.0f)
                horizontalLineToRelative(4.0f)
                lineTo(21.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(17.0f, 3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                close()
            }
        }
    }

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

    /** Material Icons AutoMirrored.Outlined.LibraryBooks (material-icons-extended automirrored/outlined/LibraryBooks.kt): sources, knowledge. */
    val LibraryBooks: ImageVector by lazy {
        materialIcon(name = "Boar.LibraryBooks", autoMirror = true) {
            materialPath {
                moveTo(4.0f, 6.0f)
                lineTo(2.0f, 6.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                lineTo(4.0f, 20.0f)
                lineTo(4.0f, 6.0f)
                close()
                moveTo(20.0f, 2.0f)
                lineTo(8.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 16.0f)
                lineTo(8.0f, 16.0f)
                lineTo(8.0f, 4.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(10.0f, 9.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-8.0f)
                close()
                moveTo(10.0f, 12.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-4.0f)
                close()
                moveTo(10.0f, 6.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-8.0f)
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
