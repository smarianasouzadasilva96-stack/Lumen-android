package com.lumen.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lumen Terminal — independent Android shell console.
 * Uses Android's /system/bin/sh, NOT the Termux bootstrap or package manager.
 */
class MainActivity : Activity() {
    private val bg = Color.rgb(9, 13, 18)
    private val panel = Color.rgb(17, 24, 32)
    private val accent = Color.rgb(91, 236, 169)
    private val fg = Color.rgb(213, 235, 226)
    private val muted = Color.rgb(115, 141, 136)
    private val danger = Color.rgb(255, 139, 130)
    private val executor = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<String>()
    private var historyIndex = 0
    private lateinit var cwd: File
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var entry: EditText
    private lateinit var location: TextView
    private lateinit var status: TextView
    private var fontSize = 13f
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cwd = filesDir
        window.statusBarColor = bg
        window.navigationBarColor = bg
        fontSize = getPreferences(MODE_PRIVATE).getFloat("fontSize", 13f)
        buildInterface()
        write("LUMEN TERMINAL  •  v2.0\n", accent)
        write("Android shell /system/bin/sh  •  ambiente local\n", muted)
        write("Digite help para ver os comandos disponíveis.\n\n")
        refreshLocation()
    }

    private fun rounded(color: Int, radius: Float = 16f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun label(text: String, color: Int = fg, size: Float = 13f): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14f), dp(12f), dp(14f), dp(8f))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(12f))
        }
        val mark = label("◈", accent, 27f)
        header.addView(mark, LinearLayout.LayoutParams(dp(42f), dp(45f)))
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        identity.addView(label("L U M E N", fg, 18f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        identity.addView(label("TERMINAL  /  ANDROID", muted, 10f))
        header.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menu = label("⋮", fg, 27f).apply {
            gravity = Gravity.CENTER
            setOnClickListener { showMenu(it) }
            contentDescription = "Opções do terminal"
        }
        header.addView(menu, LinearLayout.LayoutParams(dp(40f), dp(45f)))
        root.addView(header)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 12f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        status = label("●  PRONTO", accent, 11f)
        info.addView(status)
        location = label("~", muted, 11f).apply { gravity = Gravity.END; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.START }
        info.addView(location, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(info)

        output = label("", fg, fontSize).apply {
            setTextIsSelectable(true)
            setPadding(dp(6f), dp(18f), dp(6f), dp(14f))
            gravity = Gravity.TOP or Gravity.START
            setLineSpacing(dp(3f).toFloat(), 1f)
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(output)
            fillViewport = true
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(10f))
        }
        val keySpecs = listOf("ESC", "TAB", "CTRL", "↑", "↓", "/", "-", "|")
        keySpecs.forEach { key ->
            val b = label(key, accent, 11f).apply {
                gravity = Gravity.CENTER
                background = rounded(panel, 9f)
                setOnClickListener { quickKey(key) }
                setOnLongClickListener { if (key == "CTRL") { write("CTRL: use teclado externo para combinações.\n", muted); true } else false }
            }
            val p = LinearLayout.LayoutParams(0, dp(38f), 1f)
            p.setMargins(dp(2f), 0, dp(2f), 0)
            keys.addView(b, p)
        }
        root.addView(keys)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 14f)
            setPadding(dp(12f), dp(4f), dp(6f), dp(4f))
        }
        inputRow.addView(label("❯", accent, 19f))
        entry = EditText(this).apply {
            setSingleLine(true)
            setTextColor(fg)
            setHintTextColor(muted)
            hint = "Digite um comando..."
            textSize = fontSize
            typeface = Typeface.MONOSPACE
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    submit(); true
                } else false
            }
            setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false
                else when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { recall(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { recall(1); true }
                    else -> false
                }
            }
        }
        inputRow.addView(entry, LinearLayout.LayoutParams(0, dp(52f), 1f))
        val send = label("↵", bg, 23f).apply {
            gravity = Gravity.CENTER
            background = rounded(accent, 10f)
            contentDescription = "Executar comando"
            setOnClickListener { submit() }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(dp(46f), dp(42f)))
        root.addView(inputRow)
        setContentView(root)
    }

    private fun showMenu(anchor: View) {
        val items = arrayOf("Nova sessão (limpar)", "Copiar saída", "Colar", "Aumentar fonte", "Diminuir fonte", "Sobre o Lumen")
        PopupMenu(this, anchor).apply {
            items.forEachIndexed { i, title -> menu.add(0, i, i, title) }
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> { output.text = ""; cwd = filesDir; refreshLocation() }
                    1 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Lumen", output.text))
                        Toast.makeText(this@MainActivity, "Saída copiada", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
                        entry.text.insert(entry.selectionStart.coerceAtLeast(0), pasted)
                    }
                    3 -> changeFont(1f)
                    4 -> changeFont(-1f)
                    5 -> android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Lumen Terminal 2.0")
                        .setMessage("Terminal Android com identidade visual própria. Usa o shell nativo do Android. Não inclui apt, pkg ou o ambiente Linux do Termux.")
                        .setPositiveButton("Entendi", null).show()
                }
                true
            }
            show()
        }
    }

    private fun changeFont(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(10f, 22f)
        output.textSize = fontSize
        entry.textSize = fontSize
        getPreferences(MODE_PRIVATE).edit().putFloat("fontSize", fontSize).apply()
    }

    private fun quickKey(key: String) {
        when (key) {
            "↑" -> recall(-1)
            "↓" -> recall(1)
            "ESC" -> entry.setText("")
            "CTRL" -> Toast.makeText(this, "CTRL disponível com teclado físico", Toast.LENGTH_SHORT).show()
            "TAB" -> entry.text.insert(entry.selectionStart.coerceAtLeast(0), "    ")
            else -> entry.text.insert(entry.selectionStart.coerceAtLeast(0), key)
        }
        entry.requestFocus()
    }

    private fun recall(step: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + step).coerceIn(0, history.size)
        val value = if (historyIndex == history.size) "" else history[historyIndex]
        entry.setText(value)
        entry.setSelection(value.length)
    }

    private fun refreshLocation() {
        location.text = cwd.absolutePath.replace(filesDir.absolutePath, "~")
    }

    private fun write(value: String, color: Int = fg) {
        if (value.isEmpty()) return
        val safe = if (value.length > 120000) value.takeLast(120000) else value
        val span = SpannableString(safe)
        span.setSpan(ForegroundColorSpan(color), 0, safe.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        output.append(span)
        if (output.length() > 180000) output.text = output.text.subSequence(output.length() - 120000, output.length())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun submit() {
        val command = entry.text.toString().trim()
        if (command.isEmpty()) return
        if (running) { Toast.makeText(this, "Aguarde o comando atual", Toast.LENGTH_SHORT).show(); return }
        entry.setText("")
        history.add(command)
        if (history.size > 300) history.removeAt(0)
        historyIndex = history.size
        write("❯ $command\n", accent)
        when (command.substringBefore(' ')) {
            "clear", "cls" -> output.text = ""
            "exit" -> finish()
            "help" -> write("Comandos Lumen: help, clear, cd, pwd, history, about, exit\nComandos Android: ls, cat, echo, date, uname, getprop, sh...\nO Lumen não inclui pkg/apt.\n", muted)
            "about" -> write("Lumen Terminal 2.0 • Android shell\n", muted)
            "history" -> write(history.mapIndexed { i, s -> "${i + 1}  $s" }.joinToString("\n") + "\n")
            "pwd" -> write(cwd.absolutePath + "\n")
            "cd" -> changeDirectory(command.substringAfter(' ', "").trim())
            else -> execute(command)
        }
    }

    private fun changeDirectory(raw: String) {
        val target = runCatching {
            when {
                raw.isBlank() || raw == "~" -> filesDir
                raw.startsWith("/") -> File(raw)
                else -> File(cwd, raw)
            }.canonicalFile
        }.getOrNull()
        if (target?.isDirectory == true) { cwd = target; refreshLocation() }
        else write("Pasta não encontrada: $raw\n", danger)
    }

    private fun execute(command: String) {
        running = true
        status.text = "●  EXECUTANDO"
        val directory = cwd
        executor.execute {
            var code = -1
            var response = ""
            try {
                val process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(directory).redirectErrorStream(true).start()
                val watchdog = Thread {
                    if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
                }.apply { isDaemon = true; start() }
                response = process.inputStream.bufferedReader().use { it.readText() }
                code = process.waitFor()
                watchdog.interrupt()
            } catch (e: Exception) {
                response = e.message ?: "Falha ao executar"
            }
            val result = response
            val exitCode = code
            runOnUiThread {
                write(result + if (result.isNotEmpty() && !result.endsWith("\n")) "\n" else "", if (exitCode == 0) fg else danger)
                if (exitCode != 0) write("[saída $exitCode]\n", muted)
                running = false
                status.text = "●  PRONTO"
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
