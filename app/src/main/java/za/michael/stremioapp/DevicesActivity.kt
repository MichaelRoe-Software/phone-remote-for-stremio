package za.michael.stremioapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import com.google.zxing.integration.android.IntentIntegrator

/** Saved devices: add (QR scan or by hand), rename, delete, and pick the one to control. */
class DevicesActivity : Activity() {
    private lateinit var store: DeviceStore
    private lateinit var list: LinearLayout
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        val pad = (20 * dp).toInt()

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply {
                text = getString(R.string.devices)
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = "Tap a device to control it. Hold it to rename or delete."
                setTextColor(Color.GRAY)
                setPadding(0, pad / 4, 0, pad / 2)
            })
            addView(list)
            addView(button("Scan QR code", accent = true) { scan() })
            addView(button("Add manually", accent = false) { addManually() })
            addView(TextView(context).apply {
                text = "In Stremio open Settings → Phone remote to show the code. " +
                    "The phone must be on the same Wi-Fi as that computer."
                setTextColor(Color.GRAY)
                setPadding(0, pad / 2, 0, pad / 2)
            })
            addView(CheckBox(context).apply {
                text = "Phone volume keys control the device's volume"
                isChecked = store.volumeKeys
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, checked -> store.volumeKeys = checked }
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        })
        // On first run there's no remote behind this screen, so Back leaves the app
        // (predictive back on targetSdk 36 skips onBackPressed).
        if (Build.VERSION.SDK_INT >= 33 && intent.getBooleanExtra(EXTRA_FIRST_RUN, false)) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (store.all().isEmpty()) finishAffinity() else finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }


    private fun render() {
        list.removeAllViews()
        val current = store.current()
        val devices = store.all()
        if (devices.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No devices yet. Scan the code from Stremio to add one."
                setTextColor(Color.LTGRAY)
                textSize = 16f
                setPadding(0, (12 * dp).toInt(), 0, (16 * dp).toInt())
            })
        }
        devices.forEach { d -> list.addView(row(d, d.id == current?.id)) }
    }

    private fun row(d: Device, selected: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(Color.parseColor("#141414"))
            if (selected) setStroke((2 * dp).toInt(), Color.parseColor("#7B5BF5"))
        }
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (10 * dp).toInt()
        }
        addView(TextView(context).apply {
            text = if (selected) "${d.name}   ●" else d.name
            textSize = 18f
            setTextColor(Color.WHITE)
        })
        addView(TextView(context).apply {
            text = d.url
            setTextColor(Color.GRAY)
        })
        setOnClickListener {
            store.lastId = d.id
            finish()
        }
        setOnLongClickListener {
            AlertDialog.Builder(this@DevicesActivity)
                .setTitle(d.name)
                .setItems(arrayOf("Rename", "Delete")) { _, which ->
                    if (which == 0) rename(d) else delete(d)
                }
                .show()
            true
        }
    }

    private fun button(label: String, accent: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(Color.parseColor(if (accent) "#7B5BF5" else "#1F1F1F"))
        }
        layoutParams = LinearLayout.LayoutParams(-1, (52 * dp).toInt()).apply { topMargin = (10 * dp).toInt() }
        setOnClickListener { onClick() }
    }

    private fun rename(d: Device) {
        val input = editText(d.name, "Name").apply { setSelectAllOnFocus(true) }
        AlertDialog.Builder(this)
            .setTitle("Rename device")
            .setView(padded(input))
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) store.rename(d.id, name)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(d: Device) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${d.name}?")
            .setMessage("You can add it again by scanning its code.")
            .setPositiveButton("Delete") { _, _ ->
                store.delete(d.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Adding ----

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan the code in Stremio → Settings → Phone remote")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            scan()
        } else {
            Toast.makeText(this, "The camera is needed to scan the code. You can add the device by hand instead.",
                Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("ZXing's scanner still reports through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val contents = result.contents ?: return
        val pairing = PairingParser.parse(contents)
        if (pairing == null) {
            Toast.makeText(this, "That QR code isn't a Stremio pairing code", Toast.LENGTH_LONG).show()
            return
        }
        confirmPairing(this, store, pairing) { finish() }
    }

    private fun addManually() {
        val name = editText("", "Name, e.g. Dad's laptop")
        val url = editText("", "Address, e.g. http://192.168.1.50:11471").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = editText("", "Token").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(url)
            addView(token)
            addView(TextView(context).apply {
                text = "You can also paste the whole pairing code ({\"name\":…}) or a link with ?t= into Address."
                setTextColor(Color.GRAY)
                textSize = 13f
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Add device")
            .setView(padded(form))
            .setPositiveButton("Add") { _, _ ->
                val typed = url.text.toString()
                val pairing = PairingParser.parse(typed)?.let {
                    if (name.text.isBlank()) it else it.copy(name = name.text.toString().trim())
                } ?: PairingParser.pairing(name.text.toString(), typed, token.text.toString())
                if (pairing == null) {
                    Toast.makeText(this, "Enter an address and a token", Toast.LENGTH_LONG).show()
                } else {
                    store.addOrUpdate(pairing.name, pairing.url, pairing.token)
                    Toast.makeText(this, "Added ${pairing.name}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editText(value: String, hint: String) = EditText(this).apply {
        setText(value)
        this.hint = hint
        isSingleLine = true
    }

    private fun padded(view: android.view.View) = LinearLayout(this).apply {
        val p = (20 * dp).toInt()
        setPadding(p, p / 2, p, 0)
        gravity = Gravity.CENTER_VERTICAL
        addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    @Deprecated("On first run there's nothing to go back to")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_FIRST_RUN, false) && store.all().isEmpty()) finishAffinity()
        else super.onBackPressed()
    }

    companion object {
        const val EXTRA_FIRST_RUN = "first_run"
        private const val REQ_CAMERA = 1

        /** Asks for a name (prefilled from the code), then saves the device and makes it current. */
        fun confirmPairing(activity: Activity, store: DeviceStore, pairing: Pairing, onCancel: () -> Unit = {}, onDone: () -> Unit) {
            val existing = store.all().firstOrNull { it.url == pairing.url }
            if (existing != null) {
                store.addOrUpdate(existing.name, pairing.url, pairing.token)
                Toast.makeText(activity, "Updated pairing for ${existing.name}", Toast.LENGTH_SHORT).show()
                onDone()
                return
            }
            val dp = activity.resources.displayMetrics.density
            val input = EditText(activity).apply {
                setText(pairing.name)
                isSingleLine = true
                setSelectAllOnFocus(true)
            }
            val box = LinearLayout(activity).apply {
                val p = (20 * dp).toInt()
                setPadding(p, p / 2, p, 0)
                addView(input, LinearLayout.LayoutParams(-1, -2))
            }
            AlertDialog.Builder(activity)
                .setTitle("Name this device")
                .setMessage(pairing.url)
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim().ifEmpty { pairing.name }
                    store.addOrUpdate(name, pairing.url, pairing.token)
                    Toast.makeText(activity, "Added $name", Toast.LENGTH_SHORT).show()
                    onDone()
                }
                .setNegativeButton("Cancel") { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .show()
        }
    }
}
