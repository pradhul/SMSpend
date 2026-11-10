package app.smspend

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.smspend.ui.AI_STUDIO_API_KEY_URL
import app.smspend.ui.openAiStudio
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ApiKeyLinkTest {
    private val app: android.app.Application = ApplicationProvider.getApplicationContext()

    @Test
    fun opensAiStudioInBrowser() {
        openAiStudio(app)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AI_STUDIO_API_KEY_URL, intent.dataString)
    }

    @Test
    fun noBrowserShowsToastInsteadOfCrashing() {
        shadowOf(app).checkActivities(true) // nothing resolves → ActivityNotFoundException
        openAiStudio(app)
        assertEquals(
            app.getString(R.string.api_key_link_no_browser),
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
