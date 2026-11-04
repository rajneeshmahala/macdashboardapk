package com.macos.macdashboard

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(11, 17, 24)
    private val cardColor = Color.rgb(20, 29, 39)
    private val primary = Color.rgb(238, 245, 249)
    private val muted = Color.rgb(145, 161, 175)
    private val mint = Color.rgb(88, 225, 180)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var hostInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusText: TextView
    private lateinit var metricsBox: LinearLayout
    private var connected = false
    private var busy = false
    private val poll = object : Runnable {
        override fun run() {
            if (connected && !busy) fetchMetrics()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val prefs = getSharedPreferences("connection", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(36)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply { text = "MAC / LIVE SYSTEM MONITOR"; setTextColor(mint); textSize = 11f; letterSpacing = .13f; typeface = Typeface.DEFAULT_BOLD })
        content.addView(space(8))
        content.addView(TextView(this).apply { text = "Mac Dashboard"; setTextColor(primary); textSize = 30f; typeface = Typeface.create("sans-serif", Typeface.BOLD) })
        content.addView(space(6))
        content.addView(TextView(this).apply { text = "Live CPU, memory, disk, network and process activity from your Mac."; setTextColor(muted); textSize = 14f; setLineSpacing(dp(3).toFloat(), 1f) })
        content.addView(space(20))

        val setup = card().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        setup.addView(sectionTitle("CONNECT TO YOUR MAC"))
        setup.addView(space(12))
        hostInput = field("Mac IP address (example: 192.168.1.20:8765)").apply { setText(prefs.getString("host", "")) }
        setup.addView(hostInput, match())
        setup.addView(space(9))
        tokenInput = field("Pairing token from the Mac companion").apply { setText(prefs.getString("token", "")); inputType = 0x00000081 }
        setup.addView(tokenInput, match())
        setup.addView(space(12))
        setup.addView(button("Connect / refresh") { connect() }, match())
        setup.addView(space(9))
        setup.addView(TextView(this).apply {
            text = "Both devices must be on the same Wi-Fi. On the Mac, run mac-agent/mac_agent.py and copy its token here."
            setTextColor(muted); textSize = 12f; setLineSpacing(dp(2).toFloat(), 1f)
        })
        content.addView(setup, match())
        content.addView(space(13))
        statusText = TextView(this).apply { text = "Not connected"; setTextColor(muted); textSize = 12f }
        content.addView(statusText)
        content.addView(space(12))
        metricsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(metricsBox, match())
        setContentView(root)
        val savedHost = prefs.getString("host", "").orEmpty()
        if (savedHost.isNotBlank()) { connected = true; handler.post(poll) }
    }

    private fun connect() {
        val host = hostInput.text.toString().trim().removePrefix("http://").removeSuffix("/")
        val token = tokenInput.text.toString().trim()
        if (host.isBlank() || token.isBlank()) { statusText.text = "Enter the Mac address and pairing token."; statusText.setTextColor(Color.rgb(255, 176, 120)); return }
        getSharedPreferences("connection", MODE_PRIVATE).edit().putString("host", host).putString("token", token).apply()
        connected = true
        fetchMetrics()
    }

    private fun fetchMetrics() {
        if (busy) return
        busy = true
        val host = hostInput.text.toString().trim().removePrefix("http://").removeSuffix("/")
        val token = tokenInput.text.toString().trim()
        statusText.text = "Refreshing…"
        executor.execute {
            try {
                val connection = (URL("http://$host/api/v1/metrics").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 2500; readTimeout = 3500
                    setRequestProperty("Authorization", "Bearer $token")
                }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                connection.disconnect()
                if (code !in 200..299) throw IllegalStateException("Mac agent returned HTTP $code")
                val data = JSONObject(body)
                runOnUiThread { render(data); statusText.text = "● LIVE  ·  Updated ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())}"; statusText.setTextColor(mint) }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "Connection issue: ${e.message ?: "check Wi-Fi and token"}"; statusText.setTextColor(Color.rgb(255, 176, 120)) }
            } finally { busy = false }
        }
    }

    private fun render(d: JSONObject) {
        metricsBox.removeAllViews()
        val host = d.optString("hostname", "Mac")
        val uptime = d.optLong("uptime_seconds", 0)
        metricsBox.addView(card().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@MainActivity).apply { text = host; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(primary) })
            addView(space(5))
            addView(TextView(this@MainActivity).apply { text = "${d.optString("os_version")}   ·   ${d.optInt("cpu_count")} cores   ·   uptime ${duration(uptime)}"; textSize = 12f; setTextColor(muted) })
        }, match())
        metricsBox.addView(space(12))
        val grid1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid1.addView(metricCard("CPU", "${d.optDouble("cpu_percent", 0.0).fmt()}%", d.optDouble("cpu_percent", 0.0).toInt(), "${d.optInt("cpu_count")} logical cores"), LinearLayout.LayoutParams(0, -2, 1f))
        grid1.addView(space(10, true))
        val usedMem = d.optLong("memory_used_bytes", 0)
        val totalMem = d.optLong("memory_total_bytes", 0)
        grid1.addView(metricCard("MEMORY", "${bytes(usedMem)} / ${bytes(totalMem)}", d.optDouble("memory_percent", 0.0).toInt(), "${d.optDouble("memory_percent", 0.0).fmt()}% in use"), LinearLayout.LayoutParams(0, -2, 1f))
        metricsBox.addView(grid1, match())
        metricsBox.addView(space(10))
        val disks = d.optJSONArray("disks")
        if (disks != null && disks.length() > 0) {
            val diskCard = card().apply { setPadding(dp(16), dp(15), dp(16), dp(15)); addView(sectionTitle("STORAGE")); addView(space(10)) }
            for (i in 0 until disks.length()) {
                val disk = disks.getJSONObject(i)
                diskCard.addView(TextView(this).apply { text = "${disk.optString("mount")}   ·   ${bytes(disk.optLong("used_bytes"))} / ${bytes(disk.optLong("total_bytes"))}   (${disk.optDouble("percent", 0.0).fmt()}%)"; textSize = 12f; setTextColor(primary) })
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = disk.optDouble("percent", 0.0).toInt(); progressTintList = android.content.res.ColorStateList.valueOf(mint) }
                diskCard.addView(progress, matchHeight(dp(8))); diskCard.addView(space(9))
            }
            metricsBox.addView(diskCard, match()); metricsBox.addView(space(10))
        }
        val net = d.optJSONObject("network") ?: JSONObject()
        metricsBox.addView(card().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14)); addView(sectionTitle("NETWORK TOTALS")); addView(space(8))
            addView(TextView(this@MainActivity).apply { text = "↓  Received ${bytes(net.optLong("received_bytes", 0))}        ↑  Sent ${bytes(net.optLong("sent_bytes", 0))}"; textSize = 13f; setTextColor(primary) })
        }, match()); metricsBox.addView(space(10))
        val processes = d.optJSONArray("processes")
        val procCard = card().apply { setPadding(dp(16), dp(15), dp(16), dp(15)); addView(sectionTitle("TOP PROCESSES  ·  CPU")); addView(space(10)) }
        if (processes != null) for (i in 0 until processes.length()) {
            val p = processes.getJSONObject(i)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply { text = p.optString("name"); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(primary); maxLines = 1 })
                addView(TextView(this@MainActivity).apply { text = "PID ${p.optLong("pid")}  ·  ${p.optString("user")}  ·  RAM ${bytes(p.optLong("memory_bytes"))}"; textSize = 10f; setTextColor(muted) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply { text = "${p.optDouble("cpu_percent", 0.0).fmt()}%"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(mint) })
            procCard.addView(row)
            if (i < processes.length() - 1) procCard.addView(View(this).apply { setBackgroundColor(Color.rgb(37, 48, 61)) }, matchHeight(1))
        }
        metricsBox.addView(procCard, match())
        metricsBox.addView(space(10))
        metricsBox.addView(TextView(this).apply { text = "Telemetry stays on your local network. This dashboard does not send Mac data to a cloud service."; textSize = 11f; setTextColor(muted); setPadding(dp(3), dp(5), dp(3), dp(5)) })
    }

    private fun metricCard(name: String, value: String, percent: Int, detail: String): View = card().apply {
        setPadding(dp(14), dp(15), dp(14), dp(14))
        addView(sectionTitle(name)); addView(space(8))
        addView(TextView(this@MainActivity).apply { text = value; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(primary); maxLines = 1 })
        addView(space(4)); addView(TextView(this@MainActivity).apply { text = detail; textSize = 11f; setTextColor(muted) })
        addView(space(9))
        addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = percent.coerceIn(0, 100); progressTintList = android.content.res.ColorStateList.valueOf(mint) }, matchHeight(dp(7)))
    }

    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(cardColor, 18) }
    private fun sectionTitle(s: String) = TextView(this).apply { text = s; setTextColor(muted); textSize = 10f; letterSpacing = .1f; typeface = Typeface.DEFAULT_BOLD }
    private fun field(hintText: String) = EditText(this).apply { hint = hintText; setHintTextColor(muted); setTextColor(primary); textSize = 14f; singleLine = true; setPadding(dp(12), dp(11), dp(12), dp(11)); background = rounded(Color.rgb(29, 40, 52), 11) }
    private fun button(textValue: String, action: () -> Unit) = TextView(this).apply { text = textValue; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(5, 28, 24)); gravity = Gravity.CENTER; setPadding(dp(12), dp(13), dp(12), dp(13)); background = rounded(mint, 12); setOnClickListener { action() } }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun space(h: Int, horizontal: Boolean = false) = View(this).apply { layoutParams = if (horizontal) LinearLayout.LayoutParams(dp(h), 1) else LinearLayout.LayoutParams(1, dp(h)) }
    private fun match() = LinearLayout.LayoutParams(-1, -2)
    private fun matchHeight(h: Int) = LinearLayout.LayoutParams(-1, h)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun Long.fmt() = String.format(Locale.US, "%.1f", this.toDouble())
    private fun Double.fmt() = String.format(Locale.US, "%.1f", this)
    private fun bytes(n: Long): String { if (n <= 0) return "0 B"; val u = listOf("B", "KB", "MB", "GB", "TB"); val e = (kotlin.math.ln(n.toDouble()) / kotlin.math.ln(1024.0)).toInt().coerceIn(0, 4); return String.format(Locale.US, "%.1f %s", n / Math.pow(1024.0, e.toDouble()), u[e]) }
    private fun duration(s: Long): String { val days=s/86400; val hours=(s%86400)/3600; val mins=(s%3600)/60; return if(days>0) "${days}d ${hours}h" else if(hours>0) "${hours}h ${mins}m" else "${mins}m" }
    override fun onResume() { super.onResume(); handler.removeCallbacks(poll); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
