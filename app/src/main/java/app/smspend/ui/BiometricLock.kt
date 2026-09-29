package app.smspend.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Paper

private const val LOCK_GRACE_MS = 30_000L

fun FragmentActivity.canUseBiometric(): Boolean {
    val manager = BiometricManager.from(this)
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
}

fun FragmentActivity.promptBiometric(
    title: String,
    subtitle: String = "Unlock SMSpend",
    onSuccess: () -> Unit,
    onError: (String) -> Unit = {},
) {
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (!canUseBiometric()) {
        onError("Biometric or device credential not available")
        return
    }
    val executor = ContextCompat.getMainExecutor(this)
    val prompt = BiometricPrompt(
        this,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    onError(errString.toString())
                }
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build(),
    )
}

@Composable
fun BiometricGate(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    var unlocked by remember { mutableStateOf(!enabled) }
    var lastStopAt by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> lastStopAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    if (enabled && lastStopAt > 0L &&
                        System.currentTimeMillis() - lastStopAt > LOCK_GRACE_MS
                    ) {
                        unlocked = false
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(enabled) {
        if (!enabled) unlocked = true
        else if (lastStopAt == 0L) unlocked = false
    }

    Box(Modifier.fillMaxSize()) {
        content()
        if (enabled && !unlocked) {
            UnlockScreen(
                onUnlockClick = {
                    val act = activity ?: return@UnlockScreen
                    act.promptBiometric(
                        title = "Unlock SMSpend",
                        onSuccess = { unlocked = true },
                    )
                },
            )
            LaunchedEffect(Unit) {
                val act = activity ?: return@LaunchedEffect
                act.promptBiometric(
                    title = "Unlock SMSpend",
                    onSuccess = { unlocked = true },
                )
            }
        }
    }
}

@Composable
private fun UnlockScreen(onUnlockClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SMSpend", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Locked", style = MaterialTheme.typography.bodyLarge, color = Mute)
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onUnlockClick,
            colors = ButtonDefaults.buttonColors(containerColor = MintDeep),
        ) {
            Text("Unlock")
        }
    }
}
