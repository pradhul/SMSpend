package app.smspend.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.smspend.R
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.Mute

/** Where Google AI Studio creates Gemini API keys. */
const val AI_STUDIO_API_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * "Get a free API key from Google AI Studio" link plus a one-line how-to, shown under
 * the Gemini key field in onboarding and Settings.
 */
@Composable
fun GetApiKeyLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val openLabel = stringResource(R.string.api_key_link_open_label)
    Column(modifier = modifier) {
        TextButton(
            onClick = { openAiStudio(context) },
            modifier = Modifier.semantics {
                role = Role.Button
                // TalkBack: "Double-tap to open Google AI Studio in your browser".
                onClick(label = openLabel) {
                    openAiStudio(context)
                    true
                }
            },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MintDeep,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.api_key_link), color = MintDeep)
        }
        Text(
            stringResource(R.string.api_key_link_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Mute,
        )
    }
}

/** Opens AI Studio in a browser; without one, says where to go instead of crashing. */
fun openAiStudio(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_API_KEY_URL))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.api_key_link_no_browser, Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.api_key_link_no_browser, Toast.LENGTH_LONG).show()
    }
}
