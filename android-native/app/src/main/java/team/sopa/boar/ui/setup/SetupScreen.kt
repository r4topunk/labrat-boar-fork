package team.sopa.boar.ui.setup

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import team.sopa.boar.R
import team.sopa.boar.setup.SetupState
import team.sopa.boar.setup.SetupViewModel
import team.sopa.boar.ui.t

/**
 * First run, stub: BoarLite cannot download anything, so this only says which required files are missing and checks
 * the phone again; [onDone] opens the chat once they are there. [onSkip] is set when it was reopened from Settings and
 * the app already works. The installer that fills the phone from the app's bundle replaces this screen.
 */
@Composable
fun SetupScreen(state: SetupState, vm: SetupViewModel, onSkip: (() -> Unit)?, onDone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Gutter, vertical = Gap),
        verticalArrangement = Arrangement.spacedBy(Gap), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.mascot), contentDescription = t("clean.onboarding.welcome.mascotA11y"), modifier = Modifier.size(128.dp))
        Text(t("clean.setup.stubTitle"), style = displayStyle(), color = cs.onSurface, modifier = Modifier.semantics { heading() })
        Text(t("clean.setup.stubBody"), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        state.missing.forEach { Text("• ${it.shortName} (${sizeText(it.sizeBytes)})", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
        Button({ vm.refresh(); if (vm.ready()) onDone() }, Modifier.fillMaxWidth().height(56.dp)) { Text(t("clean.setup.stubCheck"), style = MaterialTheme.typography.titleMedium) }
        if (onSkip != null) TextButton(onSkip) { Text(t("flows.onboarding.backToApp")) }
    }
}
