package team.sopa.boar.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.sopa.boar.R
import team.sopa.boar.setup.SetupState
import team.sopa.boar.ui.icons.BoarIcons
import team.sopa.boar.ui.t

/**
 * Step 1: the mascot, one headline, one line, three reasons, the one-time download size, Get started.
 * No step counter and no device facts here: the models step shows the phone's numbers where they matter.
 */
@Composable
internal fun WelcomeStep(state: SetupState, onSkip: (() -> Unit)?, onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val motion = rememberMotionEnabled()
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to cs.primaryContainer, 0.55f to cs.surface, 1f to cs.surface),
        ),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Reopened from Settings: the way back to the chat, out of the hero's way.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (onSkip != null) TextButton(onSkip) { Text(t("flows.onboarding.backToApp")) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Gutter),
                verticalArrangement = Arrangement.spacedBy(Gap),
            ) {
                Staggered(0, motion) { Hero() }
                Spacer(Modifier.height(16.dp))
                Staggered(1, motion) {
                    Text(t("clean.onboarding.welcome.title"), style = displayStyle(), color = cs.onSurface,
                        modifier = Modifier.semantics { heading() })
                }
                Staggered(2, motion) {
                    Text(t("clean.onboarding.welcome.subtitle"), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Staggered(3, motion) { ValueRow(Icons.Outlined.Lock, t("clean.onboarding.welcome.private.title"), t("clean.onboarding.welcome.private.body")) }
                Staggered(4, motion) { ValueRow(BoarIcons.CloudDone, t("clean.onboarding.welcome.offline.title"), t("clean.onboarding.welcome.offline.body")) }
                Staggered(5, motion) { ValueRow(BoarIcons.MenuBook, t("clean.onboarding.welcome.cited.title"), t("clean.onboarding.welcome.cited.body")) }
                Spacer(Modifier.height(8.dp))
            }
            Staggered(6, motion, Modifier.padding(horizontal = Gutter, vertical = Gap)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (state.recommendedDownloadBytes > 0) BoarIcons.Download else BoarIcons.CloudDone, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                        Text(
                            if (state.recommendedDownloadBytes > 0) t("clean.onboarding.welcome.download", "size" to sizeText(state.recommendedDownloadBytes))
                            else t("clean.onboarding.welcome.downloadNone"),
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                    }
                    Button(onStart, Modifier.fillMaxWidth().height(56.dp)) {
                        Text(t("clean.onboarding.welcome.start"), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(ButtonDefaults.IconSize))
                    }
                }
            }
        }
    }
}

/** The mascot: the same transparent 3D boar as the chat, no disc, sized to sit under the headline, not compete with it. */
@Composable
private fun Hero() {
    Image(painterResource(R.drawable.mascot), contentDescription = t("clean.onboarding.welcome.mascotA11y"),
        modifier = Modifier.padding(top = 8.dp).size(128.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Surface(shape = MaterialTheme.shapes.medium, color = cs.secondaryContainer, contentColor = cs.onSecondaryContainer) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}
