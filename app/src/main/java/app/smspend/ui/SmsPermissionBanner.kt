package app.smspend.ui

import app.smspend.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.smspend.ui.theme.Ink
import app.smspend.ui.theme.MintDeep
import app.smspend.ui.theme.MintWash
import app.smspend.ui.theme.Mute

/**
 * Shown above the app when SMS access is missing, so a denied or revoked
 * permission is visible instead of the app silently showing stale data.
 */
@Composable
fun SmsPermissionBanner(
    blocked: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MintWash)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "SMS access is off",
            style = MaterialTheme.typography.titleSmall,
            color = Ink,
        )
        Text(
            if (blocked) {
                "SMSpend can't read new bank SMS. Open Settings → Permissions → SMS and choose Allow."
            } else {
                "SMSpend can't read new bank SMS until you allow SMS access."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Mute,
        )
        if (blocked) {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.sms_permission_banner_open_app_settings), color = MintDeep) }
        } else {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.sms_permission_banner_allow_sms_access), color = MintDeep) }
        }
    }
}
