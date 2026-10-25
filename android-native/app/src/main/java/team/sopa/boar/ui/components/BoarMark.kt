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
 * The LabRat mark (R.drawable.mascot) in the accent color, the theme's primary (r4to 2026-10-24, general rule for the
 * app; the launcher icon keeps its own colors). The vector pads the mark inside its box: it fills about 3/4 of [modifier]'s size.
 */
@Composable
fun BoarMark(contentDescription: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) =
    Image(painterResource(R.drawable.mascot), contentDescription, modifier, contentScale = contentScale,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
