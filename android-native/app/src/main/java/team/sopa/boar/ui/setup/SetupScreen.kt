package team.sopa.boar.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import team.sopa.boar.ui.components.BoarMark
import team.sopa.boar.setup.SetupPhase
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.t

/**
 * First run: "Unpacking LabRat". The app's data comes out of its own bundle (about 14 s for 3 GB on a Pixel 8a), with
 * the file and bytes shown; when it is done the caller moves on to the tour ([SetupTour]) by itself. When there is no
 * bundle to unpack, the screen says so plainly (with the adb command for testers); a full phone or a failed copy gets a
 * retry. [onSkip] is set when it was reopened from Settings and the app already works.
 */
@Composable
fun SetupScreen(state: SetupState, vm: SetupViewModel, onSkip: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { vm.start() }
    // A multi-GB copy must not stop because the screen went dark.
    val view = LocalView.current
    val working = state.phase in setOf(SetupPhase.CHECKING, SetupPhase.COPYING, SetupPhase.INDEXING)
    DisposableEffect(working) { view.keepScreenOn = working; onDispose { view.keepScreenOn = false } }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = Gap),
        verticalArrangement = Arrangement.spacedBy(Gap), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        BoarMark(contentDescription = t("clean.onboarding.welcome.mascotA11y"), modifier = Modifier.size(128.dp))
        val title = when (state.phase) {
            SetupPhase.NO_DATA -> t("clean.setup.noDataTitle")
            SetupPhase.NO_SPACE -> t("clean.setup.noSpaceTitle")
            SetupPhase.FAILED -> t("clean.setup.failedTitle")
            else -> t("clean.setup.unpackTitle")
        }
        Text(title, style = displayStyle(), color = cs.onSurface, modifier = Modifier.semantics { heading() })
        when (state.phase) {
            SetupPhase.CHECKING, SetupPhase.COPYING, SetupPhase.INDEXING, SetupPhase.DONE -> Progress(state)
            SetupPhase.NO_DATA -> {
                Body(t("clean.setup.noDataBody"))
                Body(t("clean.setup.noDataTesters"))
                SelectionContainer {
                    Text(state.detail.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface,
                        modifier = Modifier.fillMaxWidth())
                }
                Button(vm::retry, Modifier.fillMaxWidth().height(56.dp)) { Text(t("clean.setup.stubCheck"), style = MaterialTheme.typography.titleMedium) }
            }
            SetupPhase.NO_SPACE -> {
                Body(t("clean.setup.noSpaceBody", "needed" to sizeText(state.neededBytes), "free" to sizeText(state.freeBytes)))
                Button(vm::retry, Modifier.fillMaxWidth().height(56.dp)) { Text(t("clean.setup.retry"), style = MaterialTheme.typography.titleMedium) }
            }
            SetupPhase.FAILED -> {
                Body(t("clean.setup.failedBody"))
                Text(state.detail.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                Button(vm::retry, Modifier.fillMaxWidth().height(56.dp)) { Text(t("clean.setup.retry"), style = MaterialTheme.typography.titleMedium) }
            }
        }
        if (onSkip != null && !working && state.phase != SetupPhase.DONE) TextButton(onSkip) { Text(t("flows.onboarding.backToApp")) }
    }
}

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())

/** The copy's progress: a bar over all bytes, then the file being copied and the bytes so far. */
@Composable
private fun Progress(state: SetupState) {
    val cs = MaterialTheme.colorScheme
    Body(t("clean.setup.unpackBody"))
    val fraction = if (state.bytesTotal > 0) (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f) else 0f
    val label = t("clean.onboarding.progressA11y", "pct" to (fraction * 100).toInt())
    if (state.phase == SetupPhase.COPYING) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            trackColor = cs.surfaceContainerHighest, drawStopIndicator = {})
        Text(t("clean.setup.unpackFile", "name" to (state.file ?: ""), "index" to state.fileIndex, "count" to state.fileCount),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, modifier = Modifier.fillMaxWidth())
        Text(t("clean.setup.unpackBytes", "done" to sizeText(state.bytesDone), "total" to sizeText(state.bytesTotal)),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = cs.surfaceContainerHighest)
        Text(t(if (state.phase == SetupPhase.INDEXING) "clean.setup.unpackIndex" else "clean.setup.unpackChecking"),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
}
