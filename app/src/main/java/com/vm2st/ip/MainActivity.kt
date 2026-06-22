package com.vm2st.ip

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var tvIpAddress: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var spinnerSource: Spinner
    private lateinit var btnConfigure: Button
    private lateinit var btnRefresh: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var customUrl: String = "https://api.ipify.org"

    private val sourceUrls = arrayOf(
        "https://api.ipify.org",
        "https://icanhazip.com",
        "https://ifconfig.me/ip",
        "https://ident.me",
        "https://api.seeip.org",
        "https://wtfismyip.com/text",
        "https://v4.ident.me",
        "https://checkip.amazonaws.com",
        "https://ip.tyk.nu",
        "https://wgetip.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация View элементов
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvLocation = findViewById(R.id.tvLocation)
        tvError = findViewById(R.id.tvError)
        progressBar = findViewById(R.id.progressBar)
        spinnerSource = findViewById(R.id.spinnerSource)
        btnConfigure = findViewById(R.id.btnConfigure)
        btnRefresh = findViewById(R.id.btnRefresh)

        // Восстановление кастомного URL, если он был сохранен ранее
        val prefs = getSharedPreferences("IP_PREFS", Context.MODE_PRIVATE)
        customUrl = prefs.getString("custom_url", "https://api.ipify.org") ?: "https://api.ipify.org"

        // Логика переключения источников в Spinner
        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 10) { // Индекс пункта "Custom..."
                    btnConfigure.visibility = View.VISIBLE
                } else {
                    btnConfigure.visibility = View.GONE
                }
                fetchIpData()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Кнопка "Настроить..." для пользовательского URL
        btnConfigure.setOnClickListener {
            showCustomUrlDialog()
        }

        // Кнопка "Обновить"
        btnRefresh.setOnClickListener {
            fetchIpData()
        }

        // Первичный запуск при входе
        fetchIpData()
    }

    private fun showCustomUrlDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.dialog_title))

        val input = EditText(this)
        input.setText(customUrl)
        input.hint = getString(R.string.dialog_hint)
        builder.setView(input)

        builder.setPositiveButton(getString(R.string.dialog_ok)) { dialog, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                customUrl = text
                getSharedPreferences("IP_PREFS", Context.MODE_PRIVATE)
                    .edit()
                    .putString("custom_url", customUrl)
                    .apply()
                fetchIpData()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton(getString(R.string.dialog_cancel)) { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun fetchIpData() {
        // Сброс UI перед запросом
        tvError.text = ""
        progressBar.visibility = View.VISIBLE
        tvIpAddress.text = "..."
        tvLocation.text = "..."

        val selectedPosition = spinnerSource.selectedItemPosition
        val targetUrl = if (selectedPosition == 10) customUrl else sourceUrls[selectedPosition]

        thread {
            try {
                // 1. Получаем IP-адрес из выбранного источника
                val ip = executeHttpGet(targetUrl).trim()

                // Валидация на случай, если вернулся пустой или некорректный ответ
                if (ip.isEmpty() || ip.length > 45) {
                    throw Exception("Invalid response from source")
                }

                // 2. Получаем геопозицию по найденному IP через geojs.io
                val geoUrl = "https://get.geojs.io/v1/ip/geo/$ip.json"
                val geoJsonString = executeHttpGet(geoUrl)

                val json = JSONObject(geoJsonString)
                val countryName = json.optString("country", "")
                val countryCode = json.optString("country_code", "")
                val region = json.optString("region", "")

                val flagEmoji = getFlagEmoji(countryCode)

                // Формируем красивую строку локации: Флаг Country, Region
                val locationBuilder = StringBuilder()
                if (flagEmoji.isNotEmpty()) locationBuilder.append("$flagEmoji ")
                if (countryName.isNotEmpty()) locationBuilder.append(countryName)
                if (region.isNotEmpty()) {
                    if (locationBuilder.isNotEmpty()) locationBuilder.append(", ")
                    locationBuilder.append(region)
                }

                val locationResult = if (locationBuilder.isEmpty()) getString(R.string.unknown_location) else locationBuilder.toString()

                // Обновляем UI в главном потоке
                mainHandler.post {
                    tvIpAddress.text = ip
                    tvLocation.text = locationResult
                    progressBar.visibility = View.INVISIBLE
                }

            } catch (e: SocketTimeoutException) {
                // Обработка таймаута в 5 секунд
                mainHandler.post {
                    tvError.text = getString(R.string.error_timeout)
                    progressBar.visibility = View.INVISIBLE
                }
            } catch (e: Exception) {
                // Любая другая ошибка сети или парсинга
                mainHandler.post {
                    tvError.text = String.format(getString(R.string.error_generic), e.localizedMessage ?: "Unknown error")
                    progressBar.visibility = View.INVISIBLE
                }
            }
        }
    }

    // Универсальный легковесный HTTP GET метод со строгим таймаутом в 5000мс
    private fun executeHttpGet(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.doInput = true

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line).append("\n")
            }
            reader.close()
            connection.disconnect()
            return response.toString()
        } else {
            connection.disconnect()
            throw Exception("HTTP Error code: $responseCode")
        }
    }

    // Магическая конвертация ISO кода страны (например, "US") в соответствующий флаг-эмодзи
    private fun getFlagEmoji(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val codeUppercase = countryCode.uppercase(Locale.US)
        val firstLetter = Character.codePointAt(codeUppercase, 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(codeUppercase, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
    }
}