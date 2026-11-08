package com.meowgi.launcher710.ai

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.text.method.MetaKeyKeyListener
import android.text.method.QwertyKeyListener
import android.text.method.TextKeyListener
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.meowgi.launcher710.LauncherActivity
import com.meowgi.launcher710.R
import com.meowgi.launcher710.ui.search.SearchOverlay
import com.meowgi.launcher710.util.AppRepository
import com.meowgi.launcher710.util.LauncherPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSearchUiTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ready = AiConfigResult.Ready(AiConfig("https://x/v1", "sk-test", "m", ""))
    private val release = CountDownLatch(1)
    private val lastCall = AtomicReference<AiCall>()
    private lateinit var overlay: SearchOverlay

    @Before
    fun setUp() {
        LauncherPrefs(context).searchWebSuggestions = true
        // Create mock progress indicator and magnifier for unified indicator
        val mockProgressIndicator = ProgressBar(context)
        val mockMagnifier = ImageView(context)
        overlay = SearchOverlay(context).apply {
            repository = AppRepository.get(context)
            contactModeProvider = { 1 }
            webSuggestions.fetcher = { q -> listOf("$q one", "$q two") }
            aiConfigProvider = { ready }
            aiSession.streamer = { _, _, call, emit ->
                lastCall.set(call)
                release.await(5, TimeUnit.SECONDS)
                if (!call.isCancelled) {
                    emit(AiEvent.Token("**Hello** from "))
                    emit(AiEvent.Token("the model"))
                    emit(AiEvent.Done)
                }
            }
            // Set up unified progress indicator (normally done by LauncherActivity)
            setProgressIndicator(mockProgressIndicator, mockMagnifier)
        }
        overlay.show()
    }

    private fun settle() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idleFor(Duration.ofMillis(300))
        repeat(50) {
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    private fun type(q: String) {
        overlay.applyQuery(q)
        settle()
    }

    private val panel get() = (0 until overlay.childCount).map { overlay.getChildAt(it) }.filterIsInstance<ScrollView>().single()
    private val panelContent get() = panel.getChildAt(0) as LinearLayout
    // Unified progress indicator now in search button, not in panel. Check via voiceProgressVisibility.
    private val progress get() = overlay.voiceProgressVisibility
    private val answer get() = panelContent.getChildAt(0) as TextView
    private val aiContentContainer get() = panelContent.getChildAt(1) as LinearLayout
    private val error get() = panelContent.getChildAt(2) as TextView
    private val settingsLink get() = panelContent.getChildAt(3) as TextView
    private val results get() = (0 until overlay.childCount).map { overlay.getChildAt(it) }.filterIsInstance<RecyclerView>().single()

    private fun suggestions(): List<String> {
        val box = (0 until overlay.childCount).map { overlay.getChildAt(it) }.filterIsInstance<LinearLayout>().last()
        if (box.visibility != View.VISIBLE) return emptyList()
        return (0 until box.childCount).map { (box.getChildAt(it) as TextView).text.toString() }
    }

    @Test
    fun triggerClearsSuggestionsShowsProgressThenTheStreamedAnswer() {
        type("hjkl")
        assertEquals(listOf("hjkl one", "hjkl two"), suggestions())

        assertTrue(overlay.startAiAnswer())
        assertEquals(emptyList<String>(), suggestions())
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(View.GONE, results.visibility)
        assertEquals(View.VISIBLE, progress)

        release.countDown()
        settle()
        assertEquals(View.GONE, progress)
        assertEquals("Hello from the model", answer.text.toString())
        assertEquals(AiState.Answer("hjkl", "**Hello** from the model", done = true), overlay.aiSession.state)
        // Suggestions for the same query must not come back over the answer.
        assertEquals(emptyList<String>(), suggestions())
    }

    @Test
    fun typingCancelsAndReturnsToNormalSearch() {
        type("hjkl")
        overlay.startAiAnswer()
        settle()
        overlay.applyQuery("hjklm")
        assertEquals(AiState.Idle, overlay.aiSession.state)
        assertEquals(View.GONE, panel.visibility)
        assertEquals(View.VISIBLE, results.visibility)
        settle()
        assertTrue(lastCall.get().isCancelled)
        assertEquals(listOf("hjklm one", "hjklm two"), suggestions())
        release.countDown()
    }

    @Test
    fun closingSearchCancels() {
        type("hjkl")
        overlay.startAiAnswer()
        settle()
        overlay.dismiss()
        settle()
        assertEquals(AiState.Idle, overlay.aiSession.state)
        assertTrue(lastCall.get().isCancelled)
        release.countDown()
    }

    @Test
    fun missingConfigShowsAHintAndTheSettingsLink() {
        overlay.aiConfigProvider = { AiConfigResult.Unavailable("Pick a model for AI answers.") }
        type("hjkl")
        overlay.startAiAnswer()
        assertEquals("Pick a model for AI answers.", error.text.toString())
        assertEquals(View.VISIBLE, settingsLink.visibility)
        assertEquals(context.getString(R.string.ai_open_settings), settingsLink.text.toString())
        assertNull(lastCall.get())
    }

    @Test
    fun noQueryMeansNoTrigger() {
        assertFalse(overlay.startAiAnswer())
        type("   ")
        assertFalse(overlay.startAiAnswer())
        assertEquals(AiState.Idle, overlay.aiSession.state)
    }

    // ------------------------------------------------------------------ launcher triggers

    private fun launcherWithQuery(aiEnabled: Boolean, altEnter: Boolean = true): Pair<LauncherActivity, SearchOverlay> {
        LauncherPrefs(context).apply {
            searchWebSuggestions = false
            this.aiEnabled = aiEnabled
            aiAltEnter = altEnter
        }
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        for (code in listOf(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_J)) {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            looper.idle()
        }
        val o = activity.findViewById<SearchOverlay>(R.id.searchOverlay)
        assertEquals(View.VISIBLE, o.visibility)
        assertEquals("hj", searchField(activity).text.toString())
        o.aiConfigProvider = { AiConfigResult.Unavailable("AI answers are off.") }
        return activity to o
    }

    private fun searchField(activity: LauncherActivity) = activity.findViewById<EditText>(R.id.searchInputInBar)

    private val altMeta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    private fun key(activity: LauncherActivity, code: Int, downMeta: Int = 0, upMeta: Int = downMeta) {
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, downMeta))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, upMeta))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun altEnterAsksAi_withoutWebSearchOrNewline() {
        val (activity, o) = launcherWithQuery(aiEnabled = true)
        key(activity, KeyEvent.KEYCODE_ENTER, altMeta)
        assertEquals(AiState.Failed("hj", "AI answers are off."), o.aiSession.state)
        assertNull("Alt+Enter must not also run the Google fallback", shadowOf(activity).nextStartedActivity)
        assertEquals("hj", searchField(activity).text.toString())
        assertEquals(View.VISIBLE, o.visibility)

        key(activity, KeyEvent.KEYCODE_NUMPAD_ENTER, altMeta)
        assertTrue(o.aiSession.isActive)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun releasingAltBeforeEnterStillSwallowsTheKeyUp() {
        val (activity, o) = launcherWithQuery(aiEnabled = true)
        key(activity, KeyEvent.KEYCODE_ENTER, downMeta = altMeta, upMeta = 0)
        assertTrue(o.aiSession.isActive)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("hj", searchField(activity).text.toString())
    }

    @Test
    fun plainEnterStillRunsTheWebSearch() {
        val (activity, o) = launcherWithQuery(aiEnabled = true)
        key(activity, KeyEvent.KEYCODE_ENTER)
        assertFalse(o.aiSession.isActive)
        assertNotNull("plain Enter runs the normal web search", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun altTrackedByTheFieldCounts_andIsSpentByEnter() {
        val (activity, o) = launcherWithQuery(aiEnabled = true)
        // The field's key listener tracks Alt on the text itself. On the device's alphabetic
        // keyboard a tapped Alt stays there as sticky (state 1) or locked (2) after release;
        // Robolectric's virtual keyboard drops it on release, so leave it in the pressed state
        // (also 1) and send Enter without Alt in the event's own meta state.
        val field = searchField(activity)
        QwertyKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
            .onKeyDown(field, field.text, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0, altMeta))
        assertEquals(1, MetaKeyKeyListener.getMetaState(field.text, MetaKeyKeyListener.META_ALT_ON))

        key(activity, KeyEvent.KEYCODE_ENTER)
        assertEquals(AiState.Failed("hj", "AI answers are off."), o.aiSession.state)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("hj", field.text.toString())

        QwertyKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE)
            .onKeyUp(field, field.text, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0, 0))
        assertEquals(0, MetaKeyKeyListener.getMetaState(field.text, MetaKeyKeyListener.META_ALT_ON))
        key(activity, KeyEvent.KEYCODE_ENTER)
        assertNotNull("with Alt gone, Enter is plain again", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun withTheSettingOffAltEnterDoesNotAskAi() {
        val (activity, o) = launcherWithQuery(aiEnabled = true, altEnter = false)
        key(activity, KeyEvent.KEYCODE_ENTER, altMeta)
        assertFalse(o.aiSession.isActive)
    }

    @Test
    fun longPressEnterNoLongerTriggersAi() {
        val (activity, o) = launcherWithQuery(aiEnabled = true)
        val looper = shadowOf(Looper.getMainLooper())
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        looper.idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 1))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        looper.idle()
        assertFalse(o.aiSession.isActive)
    }

    @Test
    fun longPressOnTheSearchIconAsksAiAboutTheQuery() {
        val (activity, o) = launcherWithQuery(aiEnabled = true, altEnter = false)
        val icon = activity.findViewById<View>(R.id.btnSearchContainer)
        assertTrue(icon.performLongClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AiState.Failed("hj", "AI answers are off."), o.aiSession.state)
        assertEquals(View.VISIBLE, o.visibility)
        assertEquals("hj", searchField(activity).text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun longPressWhenAnswerExistsCopiesInsteadOfResending() {
        type("test query")
        assertTrue(overlay.startAiAnswer())
        release.countDown()
        settle()
        assertEquals(AiState.Answer("test query", "**Hello** from the model", done = true), overlay.aiSession.state)
        
        assertTrue(overlay.copyCurrentAiAnswer())
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertNotNull(clipText)
        assertTrue(clipText!!.contains("Hello"))
        assertTrue(clipText.contains("from the model"))
        assertEquals(context.getString(R.string.ai_answer_copied), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun copyReturnsFalseWhenNoAnswerExists() {
        type("test query")
        assertFalse(overlay.copyCurrentAiAnswer())
    }

    @Test
    fun copyReturnsFalseWhenQueryChanged() {
        type("original query")
        assertTrue(overlay.startAiAnswer())
        release.countDown()
        settle()
        
        overlay.applyQuery("different query")
        assertFalse(overlay.copyCurrentAiAnswer())
    }

    @Test
    fun longPressOnTheSearchIconWithoutAQueryOnlyHints() {
        LauncherPrefs(context).searchWebSuggestions = false
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val o = activity.findViewById<SearchOverlay>(R.id.searchOverlay)
        assertTrue(activity.findViewById<View>(R.id.btnSearchContainer).performLongClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.ai_no_query), ShadowToast.getTextOfLatestToast())
        assertFalse(o.aiSession.isActive)
        assertEquals(View.GONE, o.visibility)
    }

    @Test
    fun tappingTheSearchIconStillOpensSearch() {
        LauncherPrefs(context).searchWebSuggestions = false
        val activity = Robolectric.buildActivity(LauncherActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val o = activity.findViewById<SearchOverlay>(R.id.searchOverlay)
        assertTrue(activity.findViewById<View>(R.id.btnSearchContainer).performClick())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, o.visibility)
        assertFalse(o.aiSession.isActive)
    }

    @Test
    fun altDetection() {
        assertTrue(AiSearchTrigger.altActive(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON, 0))
        assertTrue("system Alt lock", AiSearchTrigger.altActive(MetaKeyKeyListener.META_ALT_LOCKED, 0))
        assertTrue("sticky in the field", AiSearchTrigger.altActive(0, 1))
        assertTrue("locked in the field", AiSearchTrigger.altActive(0, 2))
        assertFalse(AiSearchTrigger.altActive(KeyEvent.META_SHIFT_ON, 0))
        assertFalse(AiSearchTrigger.altActive(0, 0))
    }

    @Test
    fun keyMapperIntentAndBroadcastActOnTheOpenQuery() {
        val (activity, o) = launcherWithQuery(aiEnabled = true)
        val looper = shadowOf(Looper.getMainLooper())

        val trampoline = Robolectric.buildActivity(AiSearchActivity::class.java, Intent(AiSearchTrigger.ACTION)).setup()
        looper.idle()
        assertTrue(trampoline.get().isFinishing)
        assertEquals(AiState.Failed("hj", "AI answers are off."), o.aiSession.state)

        o.applyQuery("hjk")
        assertFalse(o.aiSession.isActive)
        activity.sendBroadcast(Intent(AiSearchTrigger.ACTION))
        looper.idle()
        assertEquals(AiState.Failed("hjk", "AI answers are off."), o.aiSession.state)
    }

    @Test
    fun trampolineIsExportedWithTheDocumentedAction() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val block = manifest.substringAfter("android:name=\".ai.AiSearchActivity\"").substringBefore("</activity>")
        assertTrue(block.contains("android:exported=\"true\""))
        assertTrue(block.contains("<action android:name=\"com.meowgi.launcher710.action.AI_SEARCH\" />"))
        assertFalse("must share the launcher task, not open a new one", block.contains("taskAffinity"))
    }

    // ------------------------------------------------------------------ key storage

    private class XorCipher : SecretCipher {
        var fail = false
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(sealed: ByteArray): ByteArray {
            if (fail) throw javax.crypto.AEADBadTagException()
            return encrypt(sealed)
        }
    }

    @Test
    fun apiKeyIsStoredEncryptedInItsOwnFile() {
        val cipher = XorCipher()
        val secrets = AiSecrets(context, cipher)
        assertFalse(secrets.hasApiKey)
        secrets.apiKey = "  sk-live-abcdef  "
        assertEquals("sk-live-abcdef", secrets.apiKey)

        val raw = context.getSharedPreferences(AiSecrets.PREFS_NAME, Context.MODE_PRIVATE).all
        assertEquals(setOf("api_key"), raw.keys)
        assertFalse(raw.values.single().toString().contains("sk-live"))
        val settings = context.getSharedPreferences("meowgi_launcher710_settings", Context.MODE_PRIVATE).all
        assertFalse(settings.values.any { it.toString().contains("sk-live") })

        cipher.fail = true
        assertNull(secrets.apiKey)
        assertTrue(secrets.hasApiKey)

        secrets.apiKey = " "
        assertFalse(secrets.hasApiKey)
    }

    @Test
    fun androidBackupsExcludeTheKeyFile() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val exclude = "<exclude domain=\"sharedpref\" path=\"${AiSecrets.PREFS_NAME}.xml\" />"
        assertEquals(1, File("src/main/res/xml/backup_rules.xml").readText().split(exclude).size - 1)
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>").contains(exclude))
        assertTrue(rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>").contains(exclude))
    }
}
