package team.sopa.boar.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import team.sopa.boar.ui.t

/**
 * The one top bar for the secondary screens (Settings, About, Models, Browse, Knowledge): an M3 small TopAppBar with
 * the title in titleLarge (Baloo), an auto-mirrored back arrow when [onBack] is set, and [actions] at the end.
 *
 * It draws under the status bar on its own container (TopAppBarDefaults.windowInsets), so the screen must not add
 * statusBarsPadding() above it. Put it in Scaffold(topBar = { BoarTopBar(...) }); with a [scrollBehavior] (e.g.
 * TopAppBarDefaults.pinnedScrollBehavior()) also add Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) to
 * the Scaffold, and the bar tints to surfaceContainer once the content scrolls under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoarTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val scheme = MaterialTheme.colorScheme
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("common.back"))
                }
            }
        },
        actions = actions,
        windowInsets = TopAppBarDefaults.windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = scheme.surface,
            scrolledContainerColor = scheme.surfaceContainer,
            titleContentColor = scheme.onSurface,
            navigationIconContentColor = scheme.onSurface,
            actionIconContentColor = scheme.onSurfaceVariant,
        ),
        scrollBehavior = scrollBehavior,
    )
}
