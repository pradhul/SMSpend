package app.smspend.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.LocalContext
import app.smspend.MainActivity
import app.smspend.R
import app.smspend.smSpend
import app.smspend.ui.theme.DarkColors
import app.smspend.ui.theme.LightColors
import app.smspend.ui.theme.SmSpendColors
import app.smspend.ui.theme.ThemeMode
import app.smspend.util.paiseToCompactRupees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import app.smspend.util.runCatchingCancellable

class SpendWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.smSpend
        val snap = withContext(Dispatchers.IO) {
            runCatchingCancellable { app.repository.widgetSnapshot() }.getOrNull()
        }
        val themeMode = runCatchingCancellable { ThemeMode.fromStored(app.prefs.themeMode.first()) }
            .getOrDefault(ThemeMode.SYSTEM)
        // With app lock on, amounts stay out of the home screen (and its screenshots).
        val locked = runCatchingCancellable { app.prefs.biometricLockEnabled.first() }.getOrDefault(true)
        provideContent {
            GlanceTheme {
                val palette = WidgetPalette.forMode(themeMode)
                if (locked) LockedWidgetContent(palette) else WidgetContent(snap, palette)
            }
        }
    }
}

/**
 * Widget colours mirror the in-app appearance setting. For [ThemeMode.SYSTEM] each provider
 * carries both a day and a night colour so the launcher swaps them with the system theme.
 */
private class WidgetPalette(light: SmSpendColors, dark: SmSpendColors) {
    val paper = ColorProvider(light.paper, dark.paper)
    val ink = ColorProvider(light.ink, dark.ink)
    val mute = ColorProvider(light.mute, dark.mute)
    val debit = ColorProvider(light.debit, dark.debit)

    companion object {
        fun forMode(mode: ThemeMode): WidgetPalette = when (mode) {
            ThemeMode.SYSTEM -> WidgetPalette(LightColors, DarkColors)
            ThemeMode.LIGHT -> WidgetPalette(LightColors, LightColors)
            ThemeMode.DARK -> WidgetPalette(DarkColors, DarkColors)
        }
    }
}

@Composable
private fun LockedWidgetContent(palette: WidgetPalette) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(palette.paper)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            LocalContext.current.getString(R.string.widget_locked_title),
            style = TextStyle(color = palette.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            LocalContext.current.getString(R.string.widget_locked_body),
            style = TextStyle(color = palette.mute, fontSize = 12.sp),
        )
    }
}

@Composable
private fun WidgetContent(snap: app.smspend.data.WidgetSnapshot?, palette: WidgetPalette) {
    val paper = palette.paper
    val ink = palette.ink
    val mute = palette.mute
    val debit = palette.debit
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(paper)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    "TODAY",
                    style = TextStyle(color = mute, fontSize = 11.sp),
                )
                Text(
                    (snap?.todaySpendPaise ?: 0L).paiseToCompactRupees(),
                    style = TextStyle(
                        color = debit,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    "THIS MONTH",
                    style = TextStyle(color = mute, fontSize = 11.sp),
                )
                Text(
                    (snap?.monthSpendPaise ?: 0L).paiseToCompactRupees(),
                    style = TextStyle(
                        color = debit,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
        val budget = snap?.budgetPaise
        if (snap != null && budget != null && budget > 0) {
            Spacer(GlanceModifier.height(6.dp))
            val left = budget - snap.monthSpendPaise
            Text(
                if (left >= 0) "${left.paiseToCompactRupees()} left this month"
                else "${(-left).paiseToCompactRupees()} over this month",
                style = TextStyle(color = ink, fontSize = 12.sp),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        snap?.topCategories.orEmpty().forEach { (label, paise) ->
            Text(
                "$label · ${paise.paiseToCompactRupees()}",
                style = TextStyle(color = ink, fontSize = 12.sp),
            )
        }
    }
}

class SpendWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpendWidget()
}

object SpendWidgetUpdater {
    suspend fun update(context: Context) {
        SpendWidget().updateAll(context)
    }
}
