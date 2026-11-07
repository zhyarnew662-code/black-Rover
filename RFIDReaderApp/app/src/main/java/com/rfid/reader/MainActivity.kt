package com.rfid.reader

import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.Editable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    /* ───────── مۆدێلەکان ───────── */
    data class Res(val hex: String, val numHex: String, val value: String)
    data class Rec(val t: String, val raw: String, val num: String, val hx: String)

    companion object {
        const val ACTION_USB_PERMISSION = "com.rfid.reader.USB_PERMISSION"
        const val PREFS = "rfid"
        const val KEY_HISTORY = "history"
        val BAUDS = listOf("4800", "9600", "19200", "38400", "57600", "115200")
        val MODES = listOf("خۆکار (پاش دوا AA)", "3 بایتی کۆتایی", "دەستی (شوێن + درێژی)")
    }

    /* ───────── view ───────── */
    private lateinit var resultCard: LinearLayout
    private lateinit var tvCardNum: TextView
    private lateinit var tvNumHint: TextView
    private lateinit var tvRawHex: TextView
    private lateinit var tvScan: TextView
    private lateinit var tvPortStatus: TextView
    private lateinit var llHistory: LinearLayout
    private lateinit var llManual: LinearLayout
    private lateinit var etManual: EditText
    private lateinit var etAutoMs: EditText
    private lateinit var etOff: EditText
    private lateinit var etLen: EditText
    private lateinit var cbBeep: CheckBox
    private lateinit var cbAutoCopy: CheckBox
    private lateinit var cbBlockDup: CheckBox
    private lateinit var cbAutoCommit: CheckBox
    private lateinit var cbLittle: CheckBox
    private lateinit var cbDecimal: CheckBox
    private lateinit var spMode: Spinner
    private lateinit var spBaud: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    /* ───────── دۆخ ───────── */
    private val handler = Handler(Looper.getMainLooper())
    private var kbuf = ""
    private var lastNum: String? = null
    private var lastTime = 0L
    private val history = mutableListOf<Rec>()
    private var tone: ToneGenerator? = null
    private var toast: Toast? = null

    // Serial
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var sBuf = StringBuilder()

    private val autoCommitRunnable = Runnable { if (kbuf.isNotEmpty()) commit(kbuf) }
    private val serialCommitRunnable = Runnable {
        if (cleanHex(sBuf.toString()).length >= 2) commit(sBuf.toString())
        sBuf = StringBuilder()
        if (serialPort != null) setPortStatus("پەیوەست ✅")
    }

    /* ═══════════════ دروستکردن ═══════════════ */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        resultCard = findViewById(R.id.resultCard)
        tvCardNum = findViewById(R.id.tvCardNum)
        tvNumHint = findViewById(R.id.tvNumHint)
        tvRawHex = findViewById(R.id.tvRawHex)
        tvScan = findViewById(R.id.tvScan)
        tvPortStatus = findViewById(R.id.tvPortStatus)
        llHistory = findViewById(R.id.llHistory)
        llManual = findViewById(R.id.llManual)
        etManual = findViewById(R.id.etManual)
        etAutoMs = findViewById(R.id.etAutoMs)
        etOff = findViewById(R.id.etOff)
        etLen = findViewById(R.id.etLen)
        cbBeep = findViewById(R.id.cbBeep)
        cbAutoCopy = findViewById(R.id.cbAutoCopy)
        cbBlockDup = findViewById(R.id.cbBlockDup)
        cbAutoCommit = findViewById(R.id.cbAutoCommit)
        cbLittle = findViewById(R.id.cbLittle)
        cbDecimal = findViewById(R.id.cbDecimal)
        spMode = findViewById(R.id.spMode)
        spBaud = findViewById(R.id.spBaud)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Exception) {}

        // Spinners
        spMode.adapter = ArrayAdapter(this, R.layout.spinner_item, MODES)
        spBaud.adapter = ArrayAdapter(this, R.layout.spinner_item, BAUDS)
        spBaud.setSelection(BAUDS.indexOf("115200"))
        spMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                llManual.visibility = if (pos == 2) View.VISIBLE else View.GONE
                render(parseFrame(kbuf))
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // گۆڕانکاری ڕێکخستنەکان ← نوێکردنەوەی پیشاندان
        val rerender = { render(parseFrame(kbuf)) }
        cbLittle.setOnCheckedChangeListener { _, _ -> rerender() }
        cbDecimal.setOnCheckedChangeListener { _, _ -> rerender() }
        val tw = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { rerender() }
            override fun afterTextChanged(s: Editable?) {}
        }
        etOff.addTextChangedListener(tw)
        etLen.addTextChangedListener(tw)
        listOf(etAutoMs, etOff, etLen).forEach { et ->
            et.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { releaseFocus(); true } else false
            }
        }

        // نووسینی دەستی
        etManual.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submitManual(); true } else false
        }
        findViewById<Button>(R.id.btnManual).setOnClickListener { submitManual() }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            commit("AA0004EF55555555AA0198F7")
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val n = lastNum
            if (n == null) toastMsg("هیچ ژمارەیەک نییە")
            else { copyToClipboard(n); toastMsg("📋 کۆپی کرا: $n") }
        }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("هەموو مێژووەکە بسڕدرێتەوە؟")
                .setPositiveButton("بەڵێ") { _, _ -> history.clear(); saveHistory(); renderHistory() }
                .setNegativeButton("نەخێر", null)
                .show()
        }

        btnConnect.setOnClickListener { connectSerial() }
        btnDisconnect.setOnClickListener { disconnectSerial(); setPortStatus("داخرا") }

        ContextCompat.registerReceiver(
            this, usbReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        loadHistory()
        renderHistory()
        render(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectSerial()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        tone?.release()
    }

    /* ═══════════════ 🛡️ خوێندنەوەی کیبۆرد (سەربەخۆ لە زمان) ═══════════════
       KeyEvent.keyCode بە شوێنی فیزیکی کلیلەکەیە، نەک بە پیتی زمانی کیبۆرد */
    private fun keyChar(code: Int): Char? = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (code - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - KeyEvent.KEYCODE_NUMPAD_0)
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_F -> 'A' + (code - KeyEvent.KEYCODE_A)
        else -> null
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) {
            // بۆ کلیلەکانی خوێنەر، ACTION_UP ـیش قووت دەدەین
            if (currentFocus !is EditText && (keyChar(e.keyCode) != null || isEnterKey(e.keyCode))) return true
            return super.dispatchKeyEvent(e)
        }
        if (e.isCtrlPressed || e.isMetaPressed || e.isAltPressed) return super.dispatchKeyEvent(e)
        if (currentFocus is EditText) return super.dispatchKeyEvent(e) // لە خانەی ڕێکخستن دەنووسرێت

        if (e.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)

        if (isEnterKey(e.keyCode)) {
            if (kbuf.isNotEmpty()) commit(kbuf)
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_DEL) {
            kbuf = kbuf.dropLast(1); showBuf(); render(parseFrame(kbuf)); return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            kbuf = ""; showBuf(); render(null); return true
        }
        val ch = keyChar(e.keyCode)
        if (ch != null) {
            if (kbuf.length >= 64) kbuf = ""
            kbuf += ch
            showBuf(); render(parseFrame(kbuf))
            handler.removeCallbacks(autoCommitRunnable)
            if (cbAutoCommit.isChecked) {
                val ms = (etAutoMs.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(200L)
                handler.postDelayed(autoCommitRunnable, ms)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun isEnterKey(c: Int) =
        c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER || c == KeyEvent.KEYCODE_TAB

    private fun showBuf() { tvScan.text = if (kbuf.isEmpty()) "—" else kbuf }

    private fun releaseFocus() {
        currentFocus?.let {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private fun submitManual() {
        val t = etManual.text.toString()
        etManual.setText("")
        releaseFocus()
        if (t.isNotBlank()) commit(t)
    }

    /* ═══════════════ پارسکردن ═══════════════ */
    private fun cleanHex(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            val x = when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                else -> c
            }
            if (x in '0'..'9' || x in 'a'..'f' || x in 'A'..'F') sb.append(x.uppercaseChar())
        }
        return sb.toString()
    }

    private fun parseFrame(raw: String): Res? {
        if (cbDecimal.isChecked) {
            val d = raw.filter { it in '0'..'9' }
            if (d.isEmpty()) return null
            return Res(d, "", BigInteger(d).toString())
        }
        val hex = cleanHex(raw)
        if (hex.length < 2) return null
        var numHex = when (spMode.selectedItemPosition) {
            0 -> {
                val i = hex.lastIndexOf("AA")
                when {
                    i > 0 && i % 2 == 0 -> hex.substring(i + 2)
                    hex.startsWith("AA") && hex.length > 2 -> hex.substring(2)
                    else -> hex.takeLast(6)
                }
            }
            1 -> hex.takeLast(6)
            else -> {
                val off = (etOff.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                val len = (etLen.text.toString().toIntOrNull() ?: 3).coerceIn(1, 8)
                val a = (off * 2).coerceAtMost(hex.length)
                val b = (off * 2 + len * 2).coerceAtMost(hex.length)
                hex.substring(a, b)
            }
        }
        if (numHex.isEmpty()) return null
        if (cbLittle.isChecked && numHex.length % 2 == 0) {
            numHex = numHex.chunked(2).reversed().joinToString("")
        }
        return Res(hex, numHex, BigInteger(numHex, 16).toString())
    }

    /* ═══════════════ پیشاندان ═══════════════ */
    private fun render(res: Res?) {
        if (res == null) {
            tvCardNum.text = "—"; tvNumHint.text = "hex: —"; tvRawHex.text = "—"; return
        }
        tvCardNum.text = res.value
        tvNumHint.text = if (res.numHex.isNotEmpty()) "hex: 0x${res.numHex}" else "(decimal)"
        val idx = if (res.numHex.isNotEmpty()) res.hex.lastIndexOf(res.numHex) else -1
        if (idx >= 0) {
            val sp = SpannableString(res.hex)
            val end = idx + res.numHex.length
            sp.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.green)), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(BackgroundColorSpan(0x2234D399), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(StyleSpan(Typeface.BOLD), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvRawHex.text = sp
        } else tvRawHex.text = res.hex
    }

    private fun flash(color: Int) {
        val bg = resultCard.background.mutate() as? GradientDrawable ?: return
        val base = ContextCompat.getColor(this, R.color.card)
        ValueAnimator.ofArgb(color, base).apply {
            duration = 500
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun toastMsg(m: String) {
        toast?.cancel()
        toast = Toast.makeText(this, m, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun beep() { try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {} }

    private fun copyToClipboard(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("card", s))
    }

    /* ═══════════════ تۆمارکردن ═══════════════ */
    private fun commit(raw: String) {
        handler.removeCallbacks(autoCommitRunnable)
        kbuf = ""; showBuf()
        val res = parseFrame(raw)
        if (res == null) {
            flash(0x66F87171); toastMsg("⚠️ فۆرماتەکە نەناسراوە"); return
        }
        val now = System.currentTimeMillis()
        if (cbBlockDup.isChecked && res.value == lastNum && now - lastTime < 5000) {
            flash(0x66F87171); toastMsg("کارتەکە دووبارەیە!"); return
        }
        lastNum = res.value; lastTime = now
        history.add(0, Rec(
            SimpleDateFormat("dd/MM/yyyy, HH:mm:ss", Locale.ENGLISH).format(Date()),
            res.hex, res.value, res.numHex
        ))
        while (history.size > 500) history.removeAt(history.size - 1)
        saveHistory(); renderHistory(); render(res)
        if (cbBeep.isChecked) beep()
        flash(0x6034D399); toastMsg("✅ ${res.value}")
        if (cbAutoCopy.isChecked) copyToClipboard(res.value)
    }

    /* ═══════════════ مێژوو ═══════════════ */
    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach {
            arr.put(JSONObject().put("t", it.t).put("raw", it.raw).put("num", it.num).put("hx", it.hx))
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun loadHistory() {
        history.clear()
        try {
            val arr = JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HISTORY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(Rec(o.optString("t"), o.optString("raw"), o.optString("num"), o.optString("hx")))
            }
        } catch (_: Exception) {}
    }

    private fun cell(text: String, weight: Float, color: Int, bold: Boolean = false, mono: Boolean = false, size: Float = 13f) =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            this.text = text
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            setTextColor(color)
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(2, 10, 2, 10)
        }

    private fun renderHistory() {
        llHistory.removeAllViews()
        val muted = ContextCompat.getColor(this, R.color.muted)
        val green = ContextCompat.getColor(this, R.color.green)
        if (history.isEmpty()) {
            llHistory.addView(cell("هێشتا هیچ خوێندنێک نییە", 1f, muted))
            return
        }
        val header = LinearLayout(this)
        header.addView(cell("#", 0.5f, muted, size = 11f))
        header.addView(cell("کات", 2f, muted, size = 11f))
        header.addView(cell("ژمارەی کارت", 1.3f, muted, size = 11f))
        llHistory.addView(header)
        history.take(50).forEachIndexed { i, r ->
            val row = LinearLayout(this)
            row.addView(cell("${history.size - i}", 0.5f, muted))
            row.addView(cell(r.t, 2f, muted, size = 11f))
            row.addView(cell(r.num, 1.3f, green, bold = true, size = 15f))
            llHistory.addView(row)
            llHistory.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.stroke))
            })
        }
    }

    /* ═══════════════ CSV ═══════════════ */
    private fun exportCsv() {
        if (history.isEmpty()) { toastMsg("مێژوو بەتاڵە"); return }
        val sb = StringBuilder("﻿No,Time,CardNumber,HexNumber,RawData\n")
        history.reversed().forEachIndexed { i, r ->
            sb.append(i + 1).append(",\"").append(r.t).append("\",").append(r.num).append(',')
                .append(r.hx).append(',').append(r.raw).append('\n')
        }
        val name = "rfid_cards_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(Date()) + ".csv"
        try {
            val bytes = sb.toString().toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                Toast.makeText(this, "پاشەکەوت کرا: Downloads/$name", Toast.LENGTH_LONG).show()
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                java.io.File(dir, name).writeBytes(bytes)
                Toast.makeText(this, "پاشەکەوت کرا: ${dir.absolutePath}/$name", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "هەڵە: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /* ═══════════════ USB Serial ═══════════════ */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == ACTION_USB_PERMISSION) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connectSerial()
                else setPortStatus("مۆڵەتی USB ڕەتکرایەوە")
            }
        }
    }

    private fun setPortStatus(s: String) { tvPortStatus.text = s }

    private fun connectSerial() {
        try {
            val mgr = getSystemService(Context.USB_SERVICE) as UsbManager
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr)
            if (drivers.isEmpty()) {
                setPortStatus("هیچ ئامێرێکی USB Serial نەدۆزرایەوە (کابڵی OTG بپشکنە)")
                return
            }
            val driver = drivers[0]
            if (!mgr.hasPermission(driver.device)) {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
                )
                mgr.requestPermission(driver.device, pi)
                setPortStatus("چاوەڕێی مۆڵەت...")
                return
            }
            val conn = mgr.openDevice(driver.device)
            if (conn == null) { setPortStatus("نەتوانرا ئامێرەکە بکرێتەوە"); return }
            val port = driver.ports[0]
            port.open(conn)
            port.setParameters(
                spBaud.selectedItem.toString().toInt(), 8,
                UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
            )
            serialPort = port
            ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) { runOnUiThread { handleChunk(data) } }
                override fun onRunError(e: Exception) {
                    runOnUiThread { disconnectSerial(); setPortStatus("پەیوەندی پچڕا") }
                }
            }).also { it.start() }
            setPortStatus("پەیوەست ✅")
            btnConnect.visibility = View.GONE
            btnDisconnect.visibility = View.VISIBLE
        } catch (e: Exception) {
            disconnectSerial()
            setPortStatus("هەڵە: ${e.message}")
        }
    }

    private fun disconnectSerial() {
        try { ioManager?.stop() } catch (_: Exception) {}
        try { serialPort?.close() } catch (_: Exception) {}
        ioManager = null; serialPort = null
        btnConnect.visibility = View.VISIBLE
        btnDisconnect.visibility = View.GONE
    }

    /** داتای Serial: ئەگەر تێکستی hex بێت وەک تێکست، ئەگەرنا بایتەکان دەکرێن بە hex */
    private fun handleChunk(bytes: ByteArray) {
        val isText = bytes.all {
            val b = it.toInt() and 0xFF
            (b in 48..57) || (b in 65..70) || (b in 97..102) || b == 13 || b == 10 || b == 32 || b == 9
        }
        sBuf.append(
            if (isText) String(bytes, Charsets.US_ASCII)
            else bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        )
        setPortStatus("... داتا هات")
        handler.removeCallbacks(serialCommitRunnable)
        handler.postDelayed(serialCommitRunnable, 250)
    }
}
