package app.smspend.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.smspend.MainActivity
import app.smspend.smSpend
import app.smspend.util.paiseToCompactRupees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpendWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = withContext(Dispatchers.IO) {
            runCatching { context.smSpend.repository.widgetSnapshot() }.getOrNull()
        }
        provideContent {
            GlanceTheme {
                WidgetContent(snap)
            }
        }
    }
}

@Composable
private fun WidgetContent(snap: app.smspend.data.WidgetSnapshot?) {
    val paper = Color(0xFFF4F1E8)
    val ink = Color(0xFF3D4A2E)
    val mute = Color(0xFF6F7268)
    val debit = Color(0xFFB54A3A)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(paper, paper))
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "THIS MONTH",
            style = TextStyle(color = ColorProvider(mute, mute), fontSize = 11.sp),
        )
        Text(
            (snap?.monthSpendPaise ?: 0L).paiseToCompactRupees(),
            style = TextStyle(
                color = ColorProvider(debit, debit),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        val budget = snap?.budgetPaise
        if (budget != null && budget > 0 && snap != null) {
            val left = budget - snap.monthSpendPaise
            Text(
                if (left >= 0) "${left.paiseToCompactRupees()} left this month"
                else "${(-left).paiseToCompactRupees()} over this month",
                style = TextStyle(color = ColorProvider(ink, ink), fontSize = 12.sp),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        snap?.topCategories.orEmpty().forEach { (label, paise) ->
            Text(
                "$label · ${paise.paiseToCompactRupees()}",
                style = TextStyle(color = ColorProvider(ink, ink), fontSize = 12.sp),
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
