package com.lumen.terminal

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val APP_NAME = "Lumen"
private const val VERSION = "1.0"
private const val COMMAND_TIMEOUT_S = 60L
private const val HISTORY_LIMIT = 500

// Shell do próprio Android. Em Android não existe /bin/sh garantido.
private const val SHELL = "/system/bin/sh"

// Paleta própria do Lumen
private val COLOR_BG = 0xFF0B0F14.toInt()
private val COLOR_PANEL = 0xFF111820.toInt()
private val COLOR_FG = 0xFFBFF5D8.toInt()
private val COLOR_ACCENT = 0xFF7CF2B0.toInt()
private val COLOR_DIM = 0xFF3A5A4B.toInt()
private val COLOR_ERR = 0xFFFF8A7A.toInt()

class MainActivity : Activity() {

    // Uma única thread de trabalho mantém a ordem dos comandos.
    private val worker = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<String>()
    private var historyPos = 0
    private lateinit var cwd: File

    private lateinit var screen: TextView
    private lateinit var scroll: ScrollView
    private lateinit var prompt: TextView
    private lateinit var input: EditText

    private data class Outcome(val output: String, val code: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cwd = filesDir
        window.statusBarColor = COLOR_BG
        window.navigationBarColor = COLOR_PANEL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }

        root.addView(TextView(this).apply {
            text = "◖ $APP_NAME ◗   v$VERSION"
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })

        screen = TextView(this).apply {
            setTextColor(COLOR_FG)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(screen)
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_PANEL)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        prompt = TextView(this).apply {
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 13f
            setPadding(0, 0, dp(8), 0)
        }

        input = EditText(this).apply {
            setTextColor(COLOR_FG)
            setHintTextColor(COLOR_DIM)
            hint = "comando"
            typeface = Typeface.MONOSPACE
            textSize = 13f
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) {
                    submit()
                    true
                } else {
                    false
                }
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { recallHistory(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { recallHistory(1); true }
                    else -> false
                }
            }
        }

        footer.addView(prompt)
        footer.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(footer)
        setContentView(root)

        append("$APP_NAME $VERSION\n", COLOR_ACCENT)
        append("comandos internos: cd <pasta>, clear, exit\n\n", COLOR_DIM)
        refreshPrompt()
        input.requestFocus()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun folderName(dir: File): String = dir.name.ifEmpty { "/" }

    private fun refreshPrompt() {
        prompt.text = "${folderName(cwd)} ❯"
    }

    private fun append(text: String, color: Int = COLOR_FG) {
        val span = SpannableString(text)
        span.setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        screen.append(span)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun clearScreen() {
        screen.text = ""
    }

    private fun submit() {
        val line = input.text.toString().trim()
        input.setText("")
        if (line.isEmpty()) return

        history.add(line)
        if (history.size > HISTORY_LIMIT) history.removeAt(0)
        historyPos = history.size

        append("${folderName(cwd)} ❯ $line\n", COLOR_ACCENT)

        when (line.substringBefore(' ')) {
            "clear", "cls" -> clearScreen()
            "exit" -> finish()
            "cd" -> changeDir(line.substringAfter(' ', "").trim())
            else -> runCommand(line)
        }
    }

    private fun changeDir(raw: String) {
        val target = runCatching {
            when {
                raw.isEmpty() || raw == "~" -> filesDir
                raw.startsWith("/") -> File(raw)
                else -> File(cwd, raw)
            }.canonicalFile
        }.getOrNull()

        if (target != null && target.isDirectory) {
            cwd = target
            refreshPrompt()
        } else {
            append("cd: pasta não encontrada: ${raw.ifEmpty { "~" }}\n", COLOR_ERR)
        }
    }

    private fun runCommand(line: String) {
        val dir = cwd
        worker.execute {
            val outcome = executeShell(line, dir)
            runOnUiThread { showOutcome(outcome) }
        }
    }

    // Executa em background. O watchdog mata o processo se passar do tempo,
    // o que também faz o readText() retornar, pois o stdout fecha.
    private fun executeShell(line: String, dir: File): Outcome = try {
        val process = ProcessBuilder(SHELL, "-c", line)
            .directory(dir)
            .redirectErrorStream(true)
            .start()

        val timedOut = AtomicBoolean(false)
        Thread {
            if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
                timedOut.set(true)
                process.destroyForcibly()
            }
        }.apply { isDaemon = true; start() }

        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()

        if (timedOut.get()) {
            Outcome("$output\ntempo esgotado (${COMMAND_TIMEOUT_S}s)\n", -1)
        } else {
            Outcome(output, code)
        }
    } catch (e: Exception) {
        Outcome(e.message ?: "falha ao executar o comando", -1)
    }

    private fun showOutcome(outcome: Outcome) {
        if (outcome.output.isNotEmpty()) {
            val text = if (outcome.output.endsWith("\n")) outcome.output else outcome.output + "\n"
            append(text, if (outcome.code == 0) COLOR_FG else COLOR_ERR)
        }
        if (outcome.code != 0) {
            append("[saída ${outcome.code}]\n", COLOR_DIM)
        }
    }

    private fun recallHistory(step: Int) {
        if (history.isEmpty()) return
        historyPos = (historyPos + step).coerceIn(0, history.size)
        val text = if (historyPos < history.size) history[historyPos] else ""
        input.setText(text)
        input.setSelection(text.length)
    }
}
