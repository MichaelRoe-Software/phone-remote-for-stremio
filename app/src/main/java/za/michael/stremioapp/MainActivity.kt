package za.michael.stremioapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The remote. The UI is a bundled web page (assets/remote.html); it sends every
 * command through [Bridge], which calls the current device natively, so any
 * device address works without CORS or mixed-content limits.
 */
class MainActivity : Activity() {
    private lateinit var store: DeviceStore
    private lateinit var web: WebView
    private var device: Device? = null
    private var pageReady = false
    // A pairing link is being confirmed; don't jump to the devices screen meanwhile.
    private var pairingPending = false
    // One queue keeps commands in order (trackpad motion, typed text).
    private val commands = Executors.newSingleThreadExecutor()
    private val background = Executors.newCachedThreadPool()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addJavascriptInterface(Bridge(), "Remote")
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        // targetSdk 36 is edge-to-edge: pad the WebView clear of the status and nav bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        web.loadUrl("file:///android_asset/remote.html")
        handlePairingIntent(intent)
        // targetSdk 36 uses predictive back, which skips onBackPressed.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { showBackMenu() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Auto-connect to the last device; with none saved, go straight to adding one.
        val current = store.current()
        if (current == null) {
            if (pairingPending) return
            startActivity(Intent(this, DevicesActivity::class.java).putExtra(DevicesActivity.EXTRA_FIRST_RUN, true))
            return
        }
        if (current != device) {
            device = current
            store.lastId = current.id
            if (pageReady) web.evaluateJavascript("window.deviceChanged && window.deviceChanged()", null)
        }
    }

    override fun onDestroy() {
        commands.shutdownNow()
        background.shutdownNow()
        super.onDestroy()
    }

    /** stremioapp://pair?data=<json>: the same data as the QR code. */
    private fun handlePairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "stremioapp") return
        val pairing = PairingParser.parse(data.toString())
        if (pairing == null) {
            Toast.makeText(this, "That pairing link isn't valid", Toast.LENGTH_LONG).show()
            return
        }
        pairingPending = true
        val done = { pairingPending = false; onResume() }
        DevicesActivity.confirmPairing(this, store, pairing, onCancel = done, onDone = done)
    }

    private fun showSwitcher() {
        val list = store.all()
        val labels = list.map { if (it.id == device?.id) "✓  ${it.name}" else "     ${it.name}" } + "Manage devices…"
        AlertDialog.Builder(this)
            .setTitle("Control which device?")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == list.size) {
                    startActivity(Intent(this, DevicesActivity::class.java))
                } else {
                    store.lastId = list[which].id
                    onResume()
                }
            }
            .show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun device(): String {
            val d = device ?: return "null"
            return JSONObject().put("name", d.name).put("url", d.url).toString()
        }

        @JavascriptInterface
        fun ready() = runOnUiThread { pageReady = true }

        /** Answers through window.__reply(id, status, body); status 0 = unreachable. */
        @JavascriptInterface
        fun request(id: Int, method: String, path: String, body: String) {
            val target = device ?: return reply(id, HttpResult(0, "no device"))
            val pool = if (method == "GET") background else commands
            pool.execute { reply(id, Http.request(target, method, path, body)) }
        }

        @JavascriptInterface
        fun switchDevice() = runOnUiThread { showSwitcher() }

        @JavascriptInterface
        fun manageDevices() = runOnUiThread { startActivity(Intent(this@MainActivity, DevicesActivity::class.java)) }

        private fun reply(id: Int, r: HttpResult) = runOnUiThread {
            web.evaluateJavascript("window.__reply($id, ${r.status}, ${JSONObject.quote(r.body)})", null)
        }
    }

    // Phone volume keys drive the device's volume instead of the phone's.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (store.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val dir = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"
            web.evaluateJavascript("window.remoteVolume && window.remoteVolume('$dir')", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (store.volumeKeys && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("Back opens a small menu instead of leaving straight away")
    override fun onBackPressed() = showBackMenu()

    private fun showBackMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Switch device", "Manage devices", "Exit")) { _, which ->
                when (which) {
                    0 -> showSwitcher()
                    1 -> startActivity(Intent(this, DevicesActivity::class.java))
                    2 -> finish()
                }
            }
            .show()
    }
}
