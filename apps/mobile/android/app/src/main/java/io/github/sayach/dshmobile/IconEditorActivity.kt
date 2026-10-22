package io.github.sayach.dshmobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Local-only image intake/crop flow, isolated from WebView uploads and pairing callbacks. */
class IconEditorActivity : Activity() {
    private lateinit var crop: IconCropView
    private lateinit var status: TextView
    private val controls = mutableListOf<Button>()
    private val executor = Executors.newSingleThreadExecutor()
    private var workingFile: File? = null
    private var turns = 0
    private var busy = false
    private var dirty = false
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdgeWindow(window)
        applyStatusBarIconContrast(window, getColor(R.color.app_background))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(getColor(R.color.app_background))
        }
        root.addView(Button(this).apply {
            setText(R.string.custom_icon_back)
            minHeight = dp(48)
            setOnClickListener { leave() }
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body); isFillViewport = true },
            LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(TextView(this).apply {
            setText(R.string.custom_icon_title); textSize = 22f
            setTextColor(getColor(R.color.app_foreground)); setPadding(0, dp(12), 0, dp(8))
        })
        body.addView(TextView(this).apply {
            setText(R.string.custom_icon_explanation); textSize = 15f
            setTextColor(getColor(R.color.app_secondary))
        })
        crop = IconCropView(this).apply { contentDescription = getString(R.string.custom_icon_crop_hint) }
        body.addView(crop, LinearLayout.LayoutParams(-1, dp(300)))
        body.addView(TextView(this).apply {
            setText(R.string.custom_icon_crop_hint); textSize = 14f
            setTextColor(getColor(R.color.app_secondary))
        })
        fun button(label: Int, owner: LinearLayout = body, action: () -> Unit): Button {
            val view = Button(this).apply {
                setText(label); minHeight = dp(48); isAllCaps = false
                setOnClickListener { action() }
            }
            controls += view
            owner.addView(view, if (owner === body) LinearLayout.LayoutParams(-1, -2)
                else LinearLayout.LayoutParams(0, -2, 1f))
            return view
        }
        button(R.string.custom_icon_choose) { chooseImage() }
        val zoomRow = LinearLayout(this)
        body.addView(zoomRow)
        button(R.string.custom_icon_zoom_out, zoomRow) { crop.zoom(1 / 1.25f); dirty = true }
        button(R.string.custom_icon_zoom_in, zoomRow) { crop.zoom(1.25f); dirty = true }
        val editRow = LinearLayout(this)
        body.addView(editRow)
        button(R.string.custom_icon_rotate, editRow) {
            crop.bitmap?.let {
                val rotated = Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { postRotate(90f) }, true)
                crop.setImage(rotated); turns = (turns + 1) % 4; dirty = true
            }
        }
        button(R.string.custom_icon_reset, editRow) { crop.reset(); dirty = true }
        button(R.string.custom_icon_shape) {
            crop.mask = if (crop.mask == IconCropView.Mask.ROUND) IconCropView.Mask.SQUARE else IconCropView.Mask.ROUND
        }
        button(R.string.custom_icon_save) { save(false) }
        button(R.string.custom_icon_pin) { save(true) }
        status = TextView(this).apply {
            textSize = 15f; setTextColor(getColor(R.color.app_secondary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(12), 0, dp(12))
        }
        body.addView(status)
        setContentView(root)
        applySafeAreaInsets(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
        }
        // Motion changes belong to the draft even if the user only drags, without pressing a button.
        crop.setOnTouchListener { _, event ->
            if (!busy && event.actionMasked == android.view.MotionEvent.ACTION_MOVE && crop.bitmap != null) dirty = true
            false
        }
        val candidate = savedInstanceState?.getString("working")?.let(::File)
            ?.takeIf { it.parentFile?.canonicalFile == cacheDir.canonicalFile && it.name.matches(Regex("icon-edit-[a-f0-9-]+\\.png")) && it.isFile }
        workingFile = candidate
        turns = savedInstanceState?.getInt("turns") ?: 0
        dirty = savedInstanceState?.getBoolean("dirty") ?: false
        crop.mask = if (savedInstanceState?.getBoolean("round") == true) IconCropView.Mask.ROUND else IconCropView.Mask.SQUARE
        loadInitial(candidate, savedInstanceState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun setBusy(value: Boolean) {
        busy = value
        controls.forEach { it.isEnabled = !value }
        crop.isEnabled = !value
        // Touch handling lives in the custom View, so explicitly suppress its events while saving.
        crop.visibility = if (value && crop.bitmap == null) View.INVISIBLE else View.VISIBLE
        if (value) status.setText(R.string.custom_icon_processing)
    }

    private fun loadInitial(candidate: File?, state: Bundle?) {
        setBusy(true)
        val owner = ++generation
        executor.execute {
            var generated: File? = null
            val result = runCatching {
                if (candidate != null) {
                    var image = decode(candidate)
                    if (turns != 0) image = Bitmap.createBitmap(image, 0, 0, image.width, image.height,
                        Matrix().apply { postRotate(turns * 90f) }, true)
                    CustomLauncherIcon.Saved(image, image, state?.getFloat("x") ?: image.width / 2f,
                        state?.getFloat("y") ?: image.height / 2f, state?.getFloat("zoom") ?: 1f)
                } else CustomLauncherIcon.load(this)?.also { saved ->
                    generated = File(cacheDir, "icon-edit-${UUID.randomUUID()}.png").also { file ->
                        file.outputStream().use { check(saved.source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || owner != generation) { generated?.delete(); return@runOnUiThread }
                setBusy(false)
                result.getOrNull()?.let { saved ->
                    if (candidate == null) { workingFile = generated; turns = 0 }
                    crop.setImage(saved.source)
                    crop.geometry?.restore(saved.x, saved.y, saved.zoom)
                    crop.invalidate()
                }
                if (result.isFailure) generated?.delete()
                status.setText(if (result.isFailure) R.string.custom_icon_image_failed else R.string.custom_icon_local_only)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun chooseImage() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, PICK_IMAGE)
        } catch (_: Exception) { status.setText(R.string.custom_icon_picker_failed) }
    }

    @Deprecated("Platform Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri.scheme != "content") { status.setText(R.string.custom_icon_image_failed); return }
        setBusy(true)
        val owner = ++generation
        executor.execute {
            val raw = File(cacheDir, "icon-input-${UUID.randomUUID()}")
            val normalized = File(cacheDir, "icon-edit-${UUID.randomUUID()}.png")
            val result = runCatching {
                requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                    raw.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        var count = input.read(buffer)
                        while (count != -1) {
                            total += count
                            require(total <= 20 * 1024 * 1024)
                            output.write(buffer, 0, count)
                            count = input.read(buffer)
                        }
                    }
                }
                val image = decode(raw)
                normalized.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                image
            }
            raw.delete()
            runOnUiThread {
                if (isDestroyed || isFinishing || owner != generation) { normalized.delete(); return@runOnUiThread }
                setBusy(false)
                result.onSuccess {
                    workingFile?.delete(); workingFile = normalized
                    turns = 0; crop.setImage(it); dirty = true
                    status.setText(R.string.custom_icon_local_only)
                }.onFailure { normalized.delete(); status.setText(R.string.custom_icon_image_failed) }
            }
        }
    }

    /** ImageDecoder applies EXIF orientation before returning a bounded software bitmap. */
    private fun decode(file: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        require(info.mimeType in listOf("image/png", "image/jpeg", "image/webp"))
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000L)
        val scale = minOf(1.0, 2048.0 / maxOf(width, height))
        decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setOnPartialImageListener { false }
    }

    private fun save(pin: Boolean) {
        val source = crop.bitmap ?: run { status.setText(R.string.custom_icon_select_first); return }
        if (pin && !CustomLauncherIcon.supported(this)) { status.setText(R.string.custom_icon_unsupported); return }
        val geometry = requireNotNull(crop.geometry).let { g -> IconCropGeometry(g.width, g.height).apply {
            restore(g.centerX, g.centerY, g.zoom)
        } }
        val bitmap = crop.export()
        setBusy(true)
        val owner = ++generation
        executor.execute {
            val result = runCatching { CustomLauncherIcon.save(this, source, bitmap, geometry) }
            runOnUiThread {
                if (isDestroyed || isFinishing || owner != generation) return@runOnUiThread
                setBusy(false)
                if (result.isFailure) { status.setText(R.string.custom_icon_save_failed); return@runOnUiThread }
                dirty = false
                status.setText(R.string.custom_icon_saved)
                if (pin) {
                    try {
                        val updated = CustomLauncherIcon.pin(this, bitmap)
                        status.setText(if (updated) R.string.custom_icon_updated else R.string.custom_icon_confirm)
                    } catch (_: Exception) { status.setText(R.string.custom_icon_pin_failed) }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        workingFile?.let { outState.putString("working", it.absolutePath) }
        crop.geometry?.let { outState.putFloat("x", it.centerX); outState.putFloat("y", it.centerY); outState.putFloat("zoom", it.zoom) }
        outState.putInt("turns", turns)
        outState.putBoolean("dirty", dirty)
        outState.putBoolean("round", crop.mask == IconCropView.Mask.ROUND)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity back fallback")
    override fun onBackPressed() { leave() }

    private fun leave() {
        if (busy) { Toast.makeText(this, R.string.custom_icon_processing, Toast.LENGTH_SHORT).show(); return }
        if (!dirty) { finish(); return }
        AlertDialog.Builder(this).setMessage(R.string.custom_icon_discard)
            .setPositiveButton(R.string.custom_icon_discard_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onDestroy() {
        generation++
        executor.shutdown()
        if (isFinishing) workingFile?.delete()
        super.onDestroy()
    }

    private companion object { const val PICK_IMAGE = 7301 }
}
