package com.vm2st.ip

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

class UiIpApplication : IpApplication() {
    val fake = UiIpRepository()
    override fun createRepository(): IpDataSource = fake
}
class UiIpRepository : IpDataSource {
    var ip = "8.8.8.8"
    var loadingIp = false
    var loadingGeo = false
    var failIp = false
    var failGeo = false
    var ipCalls = 0
    var geoCalls = 0
    fun reset() { ip = "8.8.8.8"; loadingIp = false; loadingGeo = false; failIp = false; failGeo = false; ipCalls = 0; geoCalls = 0 }
    override suspend fun lookupIp(url: String): IpAddress {
        ipCalls++
        if (loadingIp) delay(Long.MAX_VALUE)
        if (failIp) throw LookupFailure(LookupError.NETWORK)
        return IpAddress.parsePublic(ip)!!
    }
    override suspend fun lookupLocation(ip: IpAddress): GeoInfo {
        geoCalls++
        if (loadingGeo) delay(Long.MAX_VALUE)
        if (failGeo) throw LookupFailure(LookupError.TIMEOUT)
        return GeoInfo("Соединённые Штаты", "Калифорния", "US")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = UiIpApplication::class, qualifiers = "ru-rRU-w360dp-h720dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityUiTest {
    private lateinit var app: UiIpApplication
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("IP_PREFS", 0).edit().clear().commit()
        app.fake.reset()
    }
    private fun activity(theme: AppTheme = AppTheme.LIGHT): ActivityController<MainActivity> {
        IpSettings(app).theme = theme
        return Robolectric.buildActivity(MainActivity::class.java).setup().visible().also { idle() }
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun model(activity: MainActivity): IpViewModel = ViewModelProvider(activity)[IpViewModel::class.java]
    private fun render(view: View, name: String, width: Int = 360, height: Int = 720) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val attrs = view.context.obtainStyledAttributes(intArrayOf(R.attr.appBackground))
        canvas.drawColor(attrs.getColor(0, Color.BLACK)); attrs.recycle()
        // Root.draw(Canvas) bypasses the parent draw path that normally applies its own scroll.
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        val folder = File("build/ui-previews").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        assertNoClipping(view)
    }
    private fun assertNoClipping(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView && view.text.isNotEmpty()) {
            val layout = view.layout
            assertNotNull("Text has no layout: ${view.text}", layout)
            if (layout != null) {
                for (line in 0 until layout.lineCount) assertEquals("Ellipsis: ${view.text}", 0, layout.getEllipsisCount(line))
                assertTrue("Vertical clipping: ${view.text}", view.height >= layout.height + view.compoundPaddingTop + view.compoundPaddingBottom)
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) assertNoClipping(view.getChildAt(i))
    }
    private fun destroy(controller: ActivityController<MainActivity>) { controller.pause().stop().destroy(); idle() }

    @Test fun lightDarkBurgundyMainAndThemeDialogsRender() {
        for (theme in AppTheme.entries) {
            val controller = activity(theme)
            val activity = controller.get()
            assertEquals(1, app.fake.ipCalls)
            assertEquals(0, app.fake.geoCalls)
            assertTrue(activity.findViewById<Button>(R.id.btnCopy).isEnabled)
            render(activity.findViewById(R.id.rootScroll), "${theme.name.lowercase()}-ip")
            activity.findViewById<View>(R.id.btnTheme).performClick()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(3, dialog.listView.adapter.count)
            val decor = dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(328, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
            render(decor, "${theme.name.lowercase()}-theme-dialog", 328, decor.measuredHeight)
            dialog.dismiss()
            destroy(controller)
            app.fake.reset()
        }
    }
    @Test fun loadingDisablesCopyAndRefreshButLeavesSourceAndThemeAvailable() {
        app.fake.loadingIp = true
        val controller = activity()
        val activity = controller.get()
        assertFalse(activity.findViewById<Button>(R.id.btnCopy).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.btnRefresh).isEnabled)
        assertTrue(activity.findViewById<Spinner>(R.id.spinnerSource).isEnabled)
        assertEquals(1, app.fake.ipCalls)
        render(activity.findViewById(R.id.rootScroll), "light-loading")
        destroy(controller)
    }
    @Test fun copyUsesValidatedModelAndMarksClipboardSensitive() {
        val controller = activity()
        val activity = controller.get()
        activity.findViewById<TextView>(R.id.tvIpAddress).text = "malicious changed view"
        activity.findViewById<Button>(R.id.btnCopy).performClick()
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("8.8.8.8", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        destroy(controller)
    }
    @Test fun recreationRetainsIpAndMakesNoExtraRequest() {
        val controller = activity()
        val before = model(controller.get())
        IpSettings(app).theme = AppTheme.BURGUNDY
        controller.recreate(); idle()
        assertSame(before, model(controller.get()))
        assertEquals("8.8.8.8", controller.get().findViewById<TextView>(R.id.tvIpAddress).text.toString())
        assertEquals(1, app.fake.ipCalls)
        render(controller.get().findViewById(R.id.rootScroll), "burgundy-recreated")
        destroy(controller)
    }
    @Test fun themeChoiceIsPersistedAndCheckedOnNextDialog() {
        val controller = activity()
        controller.get().findViewById<View>(R.id.btnTheme).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(0, dialog.listView.checkedItemPosition)
        dialog.listView.performItemClick(null, 2, 2)
        idle()
        assertEquals(AppTheme.BURGUNDY, IpSettings(app).theme)
        assertFalse(dialog.isShowing)
        destroy(controller)
    }
    @Test fun customUrlRejectsInvalidInputWithoutClosingDialog() {
        val controller = activity(AppTheme.BURGUNDY)
        val activity = controller.get()
        activity.findViewById<Spinner>(R.id.spinnerSource).setSelection(10); idle()
        assertEquals("custom", model(activity).state.value.sourceId)
        activity.findViewById<View>(R.id.btnConfigure).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val input = dialog.findViewById<AppCompatEditText>(R.id.customUrlInput)!!
        input.setText("http://127.0.0.1")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertTrue(dialog.isShowing)
        assertNotNull(input.error)
        input.setText("https://example.com/ip")
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(328, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        render(decor, "burgundy-custom-dialog", 328, decor.measuredHeight)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(dialog.isShowing)
        assertEquals("https://example.com/ip", IpSettings(app).customUrl)
        assertEquals("custom", IpSettings(app).selectedSource)
        destroy(controller)
    }
    @Test fun optionalGeolocationFailureDoesNotDisableCopy() {
        app.fake.failGeo = true
        val controller = activity(AppTheme.DARK)
        val activity = controller.get()
        activity.findViewById<SwitchCompat>(R.id.switchGeo).isChecked = true; idle()
        assertTrue(activity.findViewById<Button>(R.id.btnCopy).isEnabled)
        assertTrue(model(activity).state.value.geoFailed)
        assertEquals(1, app.fake.ipCalls)
        render(activity.findViewById(R.id.rootScroll), "dark-geo-failed")
        destroy(controller)
    }
    @Test fun ipv6AndGeoRemainReadableOnSmallScreens() {
        app.fake.ip = "2001:4860:1234:5678:abcd:ef01:2345:6789"
        IpSettings(app).geoEnabled = true
        val controller = activity(AppTheme.BURGUNDY)
        assertEquals(1, app.fake.geoCalls)
        render(controller.get().findViewById(R.id.rootScroll), "burgundy-ipv6-geo")
        destroy(controller)
    }
    @Test fun errorStateDoesNotOfferToCopyPlaceholder() {
        app.fake.failIp = true
        val controller = activity()
        assertFalse(controller.get().findViewById<Button>(R.id.btnCopy).isEnabled)
        assertTrue(controller.get().findViewById<Button>(R.id.btnRefresh).isEnabled)
        render(controller.get().findViewById(R.id.rootScroll), "light-network-error")
        destroy(controller)
    }
    @Test fun sourcePickerHasAllProvidersAndCustomEntry() {
        val controller = activity(AppTheme.DARK)
        controller.get().findViewById<Spinner>(R.id.spinnerSource).performClick(); idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(11, dialog.listView.adapter.count)
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(328, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST))
        render(decor, "dark-sources-dialog", 328, decor.measuredHeight)
        dialog.listView.setSelection(10); idle()
        render(decor, "dark-sources-dialog-bottom", 328, decor.measuredHeight)
        dialog.dismiss()
        destroy(controller)
    }
    @Test @Config(qualifiers = "ru-rRU-w320dp-h480dp-port-mdpi") fun smallScreenScrollsToRefresh() {
        val controller = activity()
        val scroll = controller.get().findViewById<NestedScrollView>(R.id.rootScroll)
        render(scroll, "light-small", 320, 480)
        scroll.isSmoothScrollingEnabled = false
        scroll.fullScroll(View.FOCUS_DOWN); idle()
        assertTrue("Small screen should have a scroll range", scroll.scrollY > 0)
        render(scroll, "light-small-scrolled", 320, 480)
        val refresh = controller.get().findViewById<Button>(R.id.btnRefresh)
        val bounds = android.graphics.Rect()
        assertTrue(refresh.getGlobalVisibleRect(bounds))
        assertEquals(refresh.height, bounds.height())
        destroy(controller)
    }
    @Test @Config(qualifiers = "ru-rRU-w640dp-h360dp-land-mdpi") fun landscapeCanReachAllControls() {
        val controller = activity(AppTheme.DARK)
        val scroll = controller.get().findViewById<NestedScrollView>(R.id.rootScroll)
        render(scroll, "dark-landscape", 640, 360)
        val card = controller.get().findViewById<View>(R.id.ipCard)
        val controls = controller.get().findViewById<View>(R.id.controls)
        assertTrue(controls.left >= card.right)
        assertTrue(controller.get().findViewById<Button>(R.id.btnRefresh).getGlobalVisibleRect(android.graphics.Rect()))
        scroll.isSmoothScrollingEnabled = false
        scroll.fullScroll(View.FOCUS_DOWN); idle()
        render(scroll, "dark-landscape-scrolled", 640, 360)
        val refresh = controller.get().findViewById<Button>(R.id.btnRefresh)
        val bounds = android.graphics.Rect()
        assertTrue(refresh.getGlobalVisibleRect(bounds))
        assertEquals(refresh.height, bounds.height())
        destroy(controller)
    }
    @Test fun font200PercentDoesNotClipIpOrButtons() {
        RuntimeEnvironment.setFontScale(2f)
        val controller = activity()
        val scroll = controller.get().findViewById<NestedScrollView>(R.id.rootScroll)
        render(scroll, "light-font200")
        scroll.isSmoothScrollingEnabled = false
        scroll.fullScroll(View.FOCUS_DOWN); idle()
        render(scroll, "light-font200-scrolled")
        val refresh = controller.get().findViewById<Button>(R.id.btnRefresh)
        val bounds = android.graphics.Rect()
        assertTrue(refresh.getGlobalVisibleRect(bounds))
        assertEquals(refresh.height, bounds.height())
        destroy(controller)
    }
    @Test fun systemInsetsAreAppliedOnceIncludingCutout() {
        val controller = activity()
        val root = controller.get().findViewById<View>(R.id.rootScroll)
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(), Insets.of(32,24,0,48)).build()
        assertTrue((root as ViewGroup).clipToPadding)
        repeat(3) { ViewCompat.dispatchApplyWindowInsets(root, insets) }
        assertEquals(48, root.paddingLeft); assertEquals(40, root.paddingTop)
        assertEquals(16, root.paddingRight); assertEquals(64, root.paddingBottom)
        destroy(controller)
    }

    @Test fun geoLoadingKeepsTheIpAndCopyButtonAvailable() {
        app.fake.loadingGeo = true
        IpSettings(app).geoEnabled = true
        val controller = activity(AppTheme.DARK)
        assertTrue(model(controller.get()).state.value.loadingGeo)
        assertTrue(controller.get().findViewById<Button>(R.id.btnCopy).isEnabled)
        assertTrue(controller.get().findViewById<Button>(R.id.btnRefresh).isEnabled)
        render(controller.get().findViewById(R.id.rootScroll), "dark-geo-loading")
        destroy(controller)
    }
    @Test fun keyboardFocusKeepsPrimaryButtonReadable() {
        val controller = activity()
        val refresh = controller.get().findViewById<Button>(R.id.btnRefresh)
        refresh.isFocusableInTouchMode = true
        assertTrue(refresh.requestFocus())
        idle()
        render(controller.get().findViewById(R.id.rootScroll), "light-keyboard-focus")
        destroy(controller)
    }
    @Test fun legacySettingsAreReadAndCorruptTypesDoNotCrash() {
        val preferences = app.getSharedPreferences("IP_PREFS", 0)
        preferences.edit().putString("custom_url", "https://example.com/ip").commit()
        assertEquals("https://example.com/ip", IpSettings(app).customUrl)
        preferences.edit().putInt("custom_url", 5).putString("geo_enabled", "true").putInt("source_id", 20).putBoolean("theme", true).commit()
        val settings = IpSettings(app)
        assertEquals("https://api64.ipify.org", settings.customUrl)
        assertEquals("ipify", settings.selectedSource)
        assertFalse(settings.geoEnabled)
        assertEquals(AppTheme.LIGHT, settings.theme)
        val controller = activity()
        assertEquals(1, app.fake.ipCalls)
        destroy(controller)
    }
    @Suppress("DEPRECATION")
    @Test @Config(sdk = [23]) @GraphicsMode(GraphicsMode.Mode.LEGACY) fun olderLightNavigationBarHasContrastForWhiteSystemIcons() {
        val controller = activity(AppTheme.LIGHT)
        val values = controller.get().obtainStyledAttributes(intArrayOf(R.attr.appPrimary))
        assertEquals(values.getColor(0, 0), controller.get().window.navigationBarColor)
        values.recycle()
        destroy(controller)
    }

    @Test @Config(sdk = [23]) @GraphicsMode(GraphicsMode.Mode.LEGACY) fun android6StartsAndOpensDialog() {
        val controller = activity(AppTheme.BURGUNDY)
        assertTrue(controller.get().findViewById<Button>(R.id.btnCopy).isEnabled)
        controller.get().findViewById<View>(R.id.btnTheme).performClick()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        destroy(controller)
    }
    @Test @Config(sdk = [36]) @GraphicsMode(GraphicsMode.Mode.LEGACY) fun android16StartsAndKeepsValidatedIp() {
        val controller = activity(AppTheme.DARK)
        assertEquals("8.8.8.8", model(controller.get()).state.value.ip?.value)
        destroy(controller)
    }
}
