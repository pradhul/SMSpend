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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
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

/**
 * Unlock state lives for the process, not the composition: rotating or recreating the
 * activity keeps you unlocked, while a cold start (or process death) always asks again.
 * It is deliberately not saved in instance state, which would survive process death.
 */
internal object AppLockState {
    @Volatile var unlocked: Boolean = false
    @Volatile var everUnlocked: Boolean = false
    @Volatile var lastStopAt: Long = 0L
}

@Composable
fun BiometricGate(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    var unlocked by remember { mutableStateOf(!enabled || AppLockState.unlocked) }
    var everUnlocked by remember { mutableStateOf(!enabled || AppLockState.everUnlocked) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    fun setUnlocked(value: Boolean) {
        unlocked = value
        AppLockState.unlocked = value
        if (value) {
            everUnlocked = true
            AppLockState.everUnlocked = true
        }
    }

    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> AppLockState.lastStopAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val stoppedAt = AppLockState.lastStopAt
                    if (enabled && stoppedAt > 0L &&
                        System.currentTimeMillis() - stoppedAt > LOCK_GRACE_MS
                    ) {
                        setUnlocked(false)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(enabled) {
        // Turning the lock on from Settings already required a successful prompt.
        if (!enabled) setUnlocked(true)
    }

    val locked = enabled && !unlocked
    Box(
        Modifier
            .fillMaxSize()
            // Swallow hardware-key / D-pad input so focus can't reach hidden content.
            .onPreviewKeyEvent { locked },
    ) {
        // Until the first unlock nothing behind the lock is composed at all, so no
        // amounts are loaded, drawn, or exposed. After that, re-locking keeps the
        // screen state (navigation, scroll) but neither draws it nor exposes it to
        // accessibility services.
        if (everUnlocked) {
            Box(
                if (locked) {
                    Modifier
                        .fillMaxSize()
                        .clearAndSetSemantics { }
                        .drawWithContent { }
                } else {
                    Modifier.fillMaxSize()
                },
            ) {
                content()
            }
        }
        if (locked) {
            UnlockScreen(
                onUnlockClick = {
                    val act = activity ?: return@UnlockScreen
                    act.promptBiometric(
                        title = "Unlock SMSpend",
                        onSuccess = { setUnlocked(true) },
                    )
                },
            )
            LaunchedEffect(Unit) {
                val act = activity ?: return@LaunchedEffect
                act.promptBiometric(
                    title = "Unlock SMSpend",
                    onSuccess = { setUnlocked(true) },
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
