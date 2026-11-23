package com.vm2st.ip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var settings: IpSettings
    private lateinit var model: IpViewModel
    private lateinit var appliedTheme: AppTheme
    private lateinit var ipView: TextView
    private lateinit var locationView: TextView
    private lateinit var errorView: TextView
    private lateinit var copyButton: Button
    private lateinit var refreshButton: Button
    private lateinit var configureButton: Button
    private lateinit var sourceSpinner: Spinner
    private lateinit var geoSwitch: SwitchCompat
    private lateinit var progress: View
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        settings = IpSettings(this)
        appliedTheme = settings.theme
        setTheme(appliedTheme.style)
        delegate.localNightMode = appliedTheme.nightMode
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = appliedTheme == AppTheme.LIGHT
            isAppearanceLightNavigationBars = appliedTheme == AppTheme.LIGHT
        }
        configureLegacySystemBars()
        setContentView(R.layout.activity_main)
        installInsets()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                IpViewModel(settings, (application as IpApplication).repository) as T
        })[IpViewModel::class.java]

        ipView = findViewById(R.id.tvIpAddress)
        locationView = findViewById(R.id.tvLocation)
        errorView = findViewById(R.id.tvError)
        copyButton = findViewById(R.id.btnCopy)
        refreshButton = findViewById(R.id.btnRefresh)
        configureButton = findViewById(R.id.btnConfigure)
        sourceSpinner = findViewById(R.id.spinnerSource)
        geoSwitch = findViewById(R.id.switchGeo)
        progress = findViewById(R.id.progressBar)
        val labels = IpSources.all.map { if (it.id == IpSources.CUSTOM) getString(R.string.source_custom) else it.title }
        sourceSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, labels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        sourceSpinner.setSelection(IpSources.all.indexOfFirst { it.id == model.state.value.sourceId })
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                IpSources.all.getOrNull(position)?.let { model.selectSource(it.id) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        findViewById<View>(R.id.btnTheme).setOnClickListener { showThemeChooser() }
        refreshButton.setOnClickListener { model.refresh() }
        copyButton.setOnClickListener { copyAddress() }
        configureButton.setOnClickListener { showCustomSource() }
        geoSwitch.setOnCheckedChangeListener { _, checked -> model.setGeoEnabled(checked) }
        render(model.state.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) }
        }
    }

    override fun onStart() {
        super.onStart()
        model.refreshIfStale()
    }

    @Suppress("DEPRECATION") // Light system-bar icons are unavailable on these older releases.
    private fun configureLegacySystemBars() {
        if (appliedTheme != AppTheme.LIGHT) return
        val colors = obtainStyledAttributes(intArrayOf(R.attr.appPrimary))
        val primary = colors.getColor(0, android.graphics.Color.BLACK)
        colors.recycle()
        if (Build.VERSION.SDK_INT < 23) window.statusBarColor = primary
        if (Build.VERSION.SDK_INT < 26) window.navigationBarColor = primary
    }

    private fun installInsets() {
        val root = findViewById<View>(R.id.rootScroll)
        val left = root.paddingLeft; val top = root.paddingTop
        val right = root.paddingRight; val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + max(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun render(state: IpUiState) {
        // Soft line-break opportunities after IPv6 colons; clipboard/accessibility keep the exact literal.
        ipView.text = state.ip?.value?.replace(":", ":\u200B")
            ?: getString(if (state.loadingIp) R.string.loading else R.string.ip_unavailable)
        ipView.contentDescription = state.ip?.value
        copyButton.isEnabled = state.ip != null
        refreshButton.isEnabled = !state.loadingIp
        progress.isVisible = state.loadingIp || state.loadingGeo
        configureButton.isVisible = state.sourceId == IpSources.CUSTOM
        geoSwitch.isChecked = state.geoEnabled
        val selection = IpSources.all.indexOfFirst { it.id == state.sourceId }
        if (sourceSpinner.selectedItemPosition != selection) sourceSpinner.setSelection(selection)
        locationView.isVisible = state.geoEnabled && state.ip != null
        locationView.text = when {
            state.loadingGeo -> getString(R.string.loading_geo)
            state.geoFailed -> getString(R.string.geo_unavailable)
            else -> state.location?.label?.ifBlank { getString(R.string.unknown_location) }.orEmpty()
        }
        errorView.isVisible = state.error != null
        errorView.text = state.error?.let(::errorText).orEmpty()
    }

    private fun errorText(failure: LookupFailure): String = when (failure.error) {
        LookupError.INVALID_URL -> getString(R.string.invalid_url)
        LookupError.TIMEOUT -> getString(R.string.error_timeout)
        LookupError.HTTP -> getString(R.string.error_http, failure.status ?: 0)
        LookupError.INVALID_IP -> getString(R.string.error_invalid_ip)
        LookupError.INVALID_RESPONSE -> getString(R.string.error_response)
        LookupError.RESPONSE_TOO_LARGE -> getString(R.string.error_response_large)
        LookupError.REDIRECT -> getString(R.string.error_redirect)
        LookupError.NETWORK -> getString(R.string.error_network)
    }

    private fun showThemeChooser() {
        if (dialog?.isShowing == true) return
        val labels = arrayOf(getString(R.string.theme_light), getString(R.string.theme_dark), getString(R.string.theme_burgundy))
        dialog = AlertDialog.Builder(this).setTitle(R.string.theme_title)
            .setSingleChoiceItems(labels, appliedTheme.ordinal) { chooser, index ->
                val theme = AppTheme.entries.getOrNull(index) ?: return@setSingleChoiceItems
                chooser.dismiss()
                if (theme != appliedTheme) {
                    settings.theme = theme
                    recreate() // Retained ViewModel: no extra request, address or location lost.
                }
            }.setNegativeButton(R.string.dialog_cancel, null).create().also {
                it.setOnDismissListener { dialog = null }
                it.show()
            }
    }

    @Suppress("DEPRECATION") // adjustResize remains necessary for older Android dialog windows.
    private fun showCustomSource() {
        if (dialog?.isShowing == true) return
        val input = AppCompatEditText(this).apply {
            id = R.id.customUrlInput
            setText(model.customUrl())
            hint = getString(R.string.dialog_hint)
            contentDescription = getString(R.string.dialog_title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(UrlPolicy.MAX_URL_LENGTH))
            textSize = 16f
            maxLines = 4
            isSingleLine = false
            isSaveEnabled = false
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setSelectAllOnFocus(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dialog = AlertDialog.Builder(this).setTitle(R.string.dialog_title).setView(container)
            .setPositiveButton(R.string.dialog_save, null).setNegativeButton(R.string.dialog_cancel, null)
            .create().also { alert ->
                alert.setOnDismissListener { dialog = null }
                alert.show()
                alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                input.doAfterTextChanged { input.error = null }
                // Install immediately after show(): never let the default button dismiss invalid input.
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (model.saveCustomUrl(input.text?.toString().orEmpty())) alert.dismiss()
                    else input.error = getString(R.string.invalid_url)
                }
            }
    }

    private fun copyAddress() {
        val address = model.state.value.ip ?: return
        try {
            val clip = ClipData.newPlainText(getString(R.string.label_your_ip), address.value)
            if (Build.VERSION.SDK_INT >= 24) {
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            // Android 13+ shows its own clipboard confirmation.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.copy_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
