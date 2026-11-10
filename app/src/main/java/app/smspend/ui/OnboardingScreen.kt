package app.smspend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.Line
import app.smspend.ui.theme.Mint
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute
import app.smspend.ui.theme.Paper
import app.smspend.ui.theme.PaperRaised

// First-run onboarding. (Split out of the former Screens.kt.)

@Composable
fun OnboardingScreen(
    hasSmsPermission: Boolean,
    smsBlocked: Boolean,
    onRequestSms: () -> Unit,
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text("₹", color = Mint, style = MaterialTheme.typography.displayLarge)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("SMSpend", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(
            "See this week’s spending from your bank SMS — private, on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = Mute,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(modifier = Modifier.height(28.dp))
        val smsStepBody = when {
            hasSmsPermission -> "Permission granted"
            smsBlocked -> "SMS access was denied. Open app settings → Permissions → SMS → Allow."
            else -> "Read debit alerts only. Nothing is uploaded."
        }
        OnboardStep("1. Allow SMS", smsStepBody) {
            if (!hasSmsPermission) {
                // When blocked, onRequestSms opens the app's settings page instead.
                TextButton(onClick = onRequestSms) {
                    Text(if (smsBlocked) "Open app settings" else "Grant SMS access", color = MintDeep)
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        OnboardStep("2. Add Gemini key", "Smart categories for unknown merchants (optional now).") {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                if (!hasSmsPermission) {
                    onRequestSms()
                    return@Button
                }
                busy = true
                viewModel.complete(apiKey) {
                    busy = false
                    onFinished()
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Mint),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(if (busy) "Scanning…" else "Continue")
        }
    }
}

@Composable
internal fun OnboardStep(title: String, body: String, extra: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PaperRaised)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Mute)
        extra()
    }
}
