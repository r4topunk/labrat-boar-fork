package team.sopa.boar.ui.components

import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import team.sopa.boar.R

/**
 * The BoarLite mark (R.drawable.mascot) in the text color: ink on the light theme, light on the dark one (r4to
 * 2026-10-24: the boar goes in black in the app, as a general rule; the launcher icon keeps its own colors).
 */
@Composable
fun BoarMark(contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) =
    Image(painterResource(R.drawable.mascot), contentDescription, modifier, contentScale = contentScale,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
