package se.relay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() { super.onCreate(); instance = this }
    companion object {
        lateinit var instance: App
        val log = StringBuilder()
        var onLog: (() -> Unit)? = null
        fun add(line: String) {
            log.insert(0, line + "\n")
            if (log.length > 6000) log.setLength(6000)
            onLog?.invoke()
        }
    }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("relay", Context.MODE_PRIVATE)
    var base: String
        get() = sp(App.instance).getString("base", "") ?: ""
        set(v) = sp(App.instance).edit().putString("base", v.trimEnd('/')).apply()
    var session: String
        get() = sp(App.instance).getString("session", "") ?: ""
        set(v) = sp(App.instance).edit().putString("session", v).apply()
    var lastPushAt: Long
        get() = sp(App.instance).getLong("lastPushAt", 0L)
        set(v) = sp(App.instance).edit().putLong("lastPushAt", v).apply()
}

object Relay {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    fun push(code: String, token: String): String {
        val body = JSONObject().put("code", code).put("token", token)
            .toString().toRequestBody(json)
        val req = Request.Builder().url(Prefs.base + "/api/push").post(body).build()
        return runCatching {
            client.newCall(req).execute().use { "HTTP " + it.code }
        }.getOrElse { "FEL " + (it.message ?: "okänt") }
    }

    fun newSession(): String? {
        val req = Request.Builder().url(Prefs.base + "/api/session")
            .post("{}".toRequestBody(json)).build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body!!.string()).getString("code") else null
            }
        }.getOrNull()
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.SV)

class CatchActivity : Activity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); grab(intent) }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); grab(i) }

    private fun grab(i: Intent) {
        val raw = i.dataString ?: ""
        val from = referrer?.host ?: "okänd avsändare"
        val token = i.data?.getQueryParameter("autostarttoken")

        if (token.isNullOrBlank()) {
            App.add("${clock.format(Date())}  INGEN TOKEN\n  $from\n  $raw")
            finish(); return
        }

        val session = Prefs.session
        if (session.isBlank()) {
            App.add("${clock.format(Date())}  TOKEN MEN INGEN SESSION\n  $token")
            finish(); return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - Prefs.lastPushAt < 1500) {
            App.add("${clock.format(Date())}  SPÄRRAD (för tätt)\n  ${token.take(8)}…")
            finish(); return
        }
        Prefs.lastPushAt = now

        App.add("${clock.format(Date())}  FÅNGAD\n  $from\n  $token")

        App.instance.scope.launch {
            val res = Relay.push(session, token)
            App.add("${clock.format(Date())}  PUSH $res")
            runOnUiThread {
                Toast.makeText(this@CatchActivity, res, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}

class MainActivity : Activity() {

    private lateinit var out: TextView
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, pad)
        }

        val base = EditText(this).apply {
            hint = "https://din-server"
            setText(Prefs.base)
        }

        fun btn(label: String, tap: () -> Unit) =
            Button(this).apply { text = label; setOnClickListener { tap() } }

        out = TextView(this).apply {
            textSize = 11f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }

        root.addView(status)
        root.addView(base, ViewGroup.LayoutParams(-1, -2))
        root.addView(btn("Spara server") {
            Prefs.base = base.text.toString()
            App.add("server sparad: " + Prefs.base)
            refresh()
        })
        root.addView(btn("Skapa session") {
            Prefs.base = base.text.toString()
            App.instance.scope.launch {
                val code = Relay.newSession()
                runOnUiThread {
                    if (code == null) App.add("KUNDE INTE SKAPA SESSION")
                    else { Prefs.session = code; App.add("session: $code"); refresh() }
                }
            }
        })
        root.addView(btn("Skicka testlänk") { fireTest() })
        root.addView(btn("Öppna standardapp-inställningar") {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        })
        root.addView(btn("Rensa logg") { App.log.setLength(0); refresh() })

        root.addView(ScrollView(this).apply {
            addView(out)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })

        setContentView(root)

        App.onLog = { refresh() }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("bankid:///?autostarttoken=probe"))
        val r = packageManager.resolveActivity(probe, 0)
        val mine = r?.activityInfo?.packageName == packageName

        status.text = buildString {
            append(if (mine) "✓ Appen tar emot bankid://" else "✗ Appen är INTE standard")
            append("\n")
            append(if (Prefs.session.isBlank()) "✗ Ingen session" else "✓ Session ${Prefs.session}")
        }
        status.setTextColor(if (mine && Prefs.session.isNotBlank()) 0xFF2FD18A.toInt() else 0xFFE0663F.toInt())
        out.text = App.log.toString()
    }

    private fun fireTest() {
        if (Prefs.session.isBlank()) { Toast.makeText(this, "Skapa session först", Toast.LENGTH_SHORT).show(); return }
        val token = UUID.randomUUID().toString()
        App.add("${clock.format(Date())}  TESTLÄNK SKICKAD\n  $token")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("bankid:///?autostarttoken=$token")))
    }
}
