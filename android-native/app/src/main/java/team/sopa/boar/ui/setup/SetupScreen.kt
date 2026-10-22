package team.sopa.boar.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import team.sopa.boar.setup.IndexPhase
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupSteps
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.t

/** How long the all-checked completion state stays before the chat opens. */
private const val COMPLETION_HOLD_MS = 800L

/**
 * First-run setup: welcome, the answer models (any number), the default model (only with two or more), the knowledge
 * libraries, then a transparent install list. There is no Done screen: once every file is in and the library is
 * organized, the install step shows all rows checked for a moment and [onDone] opens the chat.
 * [onSkip] is set when setup was reopened from Settings and the app already works.
 */
@Composable
fun SetupScreen(state: SetupState, vm: SetupViewModel, onSkip: (() -> Unit)?, onBrowse: () -> Unit = {}, onDone: () -> Unit) {
    var confirmBack by remember { mutableStateOf(false) }
    val motion = rememberMotionEnabled()

    // Leaving the install step: never while the index builds (it would hide it) or once finished; ask while files move.
    val requestBack: () -> Unit = {
        when {
            state.step == SetupSteps.INSTALL && (state.indexPhase == IndexPhase.BUILDING || state.finished) -> Unit
            state.step == SetupSteps.INSTALL && state.transferring -> confirmBack = true
            state.step > SetupSteps.WELCOME -> vm.back()
            else -> onSkip?.invoke()
        }
    }
    BackHandler(state.step > SetupSteps.WELCOME || onSkip != null) { requestBack() }

    // Completion: all rows checked for a moment, then the chat. Called once per finished install.
    val done by rememberUpdatedState(onDone)
    var completed by rememberSaveable { mutableStateOf(false) }
    val finishedHere = state.step == SetupSteps.INSTALL && state.finished
    LaunchedEffect(finishedHere) {
        if (finishedHere && !completed) {
            delay(COMPLETION_HOLD_MS)
            completed = true
            done()
        }
    }

    AnimatedContent(
        targetState = state.step,
        transitionSpec = { stepTransition(initialState, targetState, motion) },
        label = "setupStep",
    ) { step ->
        when (step) {
            SetupSteps.WELCOME -> WelcomeStep(state, onSkip) { vm.next() }
            SetupSteps.MODELS -> ModelsStep(state, vm, onBrowse, onBack = requestBack)
            SetupSteps.DEFAULT_MODEL -> DefaultModelStep(state, vm, onBack = requestBack)
            SetupSteps.KNOWLEDGE -> KnowledgeStep(state, vm, onBack = requestBack)
            else -> InstallStep(state, vm, onBack = requestBack)
        }
    }

    if (confirmBack) {
        AlertDialog(
            onDismissRequest = { confirmBack = false },
            title = { Text(t("clean.onboarding.leave.title")) },
            text = { Text(t("clean.onboarding.leave.body")) },
            confirmButton = { TextButton({ confirmBack = false; vm.back() }) { Text(t("clean.onboarding.leave.confirm")) } },
            dismissButton = { TextButton({ confirmBack = false }) { Text(t("clean.onboarding.leave.stay")) } },
        )
    }
}

/** Forward slides in from the end, back from the start; a plain cut when the phone has animations off. */
private fun stepTransition(from: Int, to: Int, motion: Boolean): ContentTransform {
    if (!motion) return EnterTransition.None togetherWith ExitTransition.None
    val forward = to > from
    val enter = slideInHorizontally(tween(260)) { w -> if (forward) w / 6 else -w / 6 } + fadeIn(tween(220, delayMillis = 40))
    val exit = slideOutHorizontally(tween(200)) { w -> if (forward) -w / 10 else w / 10 } + fadeOut(tween(160))
    return enter togetherWith exit
}
