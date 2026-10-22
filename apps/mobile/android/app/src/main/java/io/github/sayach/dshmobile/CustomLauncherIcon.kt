package io.github.sayach.dshmobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.AtomicFile
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** One atomic private document keeps the editable image, crop and geometry in sync. */
internal object CustomLauncherIcon {
    private const val SHORTCUT_ID = "custom-launcher-icon"
    private const val MAX_ENTRY_BYTES = 20 * 1024 * 1024
    data class Saved(val source: Bitmap, val crop: Bitmap, val x: Float, val y: Float, val zoom: Float)

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "custom-launcher-icon.zip"))

    fun save(context: Context, source: Bitmap, crop: Bitmap, geometry: IconCropGeometry) {
        val atomic = file(context)
        val stream = atomic.startWrite()
        try {
            // Do not close the atomic stream before finishWrite has synced it.
            val zip = ZipOutputStream(stream)
            for ((name, bitmap) in listOf("source.png" to source, "icon.png" to crop)) {
                zip.putNextEntry(ZipEntry(name))
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("crop.json"))
            zip.write(JSONObject().put("x", geometry.centerX).put("y", geometry.centerY)
                .put("zoom", geometry.zoom).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
            zip.flush()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun entries(context: Context): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(file(context).openRead()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(entry.name in listOf("source.png", "icon.png", "crop.json") && !result.containsKey(entry.name))
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = zip.read(buffer)
                while (count != -1) {
                    require(output.size() + count <= MAX_ENTRY_BYTES)
                    output.write(buffer, 0, count)
                    count = zip.read(buffer)
                }
                result[entry.name] = output.toByteArray()
                entry = zip.nextEntry
            }
        }
        return result
    }

    fun load(context: Context): Saved? = try {
        val data = entries(context)
        fun decode(name: String): Bitmap {
            val bytes = requireNotNull(data[name])
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
            return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        val json = JSONObject(requireNotNull(data["crop.json"]).toString(Charsets.UTF_8))
        Saved(decode("source.png"), decode("icon.png"), json.getDouble("x").toFloat(),
            json.getDouble("y").toFloat(), json.getDouble("zoom").toFloat())
    } catch (_: Exception) { null }

    fun supported(context: Context): Boolean =
        context.getSystemService(ShortcutManager::class.java)?.isRequestPinShortcutSupported == true

    private fun enabledLauncher(context: Context): ComponentName {
        val pm = context.packageManager
        val selected = LauncherIconPolicy.selectedKey(context.getSharedPreferences("dsh_mobile", Context.MODE_PRIVATE)
            .getString("app_icon", null)) {
            pm.getComponentEnabledSetting(ComponentName(context, "${MainActivity::class.java.name.substringBeforeLast('.')}.${it.alias}"))
        }
        val alias = LauncherIconPolicy.icons.first { it.key == selected }.alias
        return ComponentName(context, "${MainActivity::class.java.name.substringBeforeLast('.')}.$alias")
    }

    private fun shortcut(context: Context, bitmap: Bitmap): ShortcutInfo =
        ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.app_name))
            .setLongLabel(context.getString(R.string.app_name))
            .setActivity(enabledLauncher(context))
            .setIcon(Icon.createWithAdaptiveBitmap(bitmap))
            .setIntent(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }).build()

    /** Returns true for an existing pinned entry, false when the launcher must ask for confirmation. */
    fun pin(context: Context, bitmap: Bitmap): Boolean {
        val manager = requireNotNull(context.getSystemService(ShortcutManager::class.java))
        check(manager.isRequestPinShortcutSupported)
        val info = shortcut(context, bitmap)
        if (manager.pinnedShortcuts.any { it.id == SHORTCUT_ID }) {
            check(manager.updateShortcuts(listOf(info)))
            manager.enableShortcuts(listOf(SHORTCUT_ID))
            return true
        }
        val callback = PendingIntent.getBroadcast(context, 0,
            Intent(context, IconPinnedReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        check(manager.requestPinShortcut(info, callback.intentSender))
        return false
    }

    /** A shortcut's associated launcher alias can change without changing its stable launch intent. */
    fun refreshPinned(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        if (manager.pinnedShortcuts.none { it.id == SHORTCUT_ID }) return
        val saved = load(context) ?: return
        check(manager.updateShortcuts(listOf(shortcut(context, saved.crop))))
        manager.enableShortcuts(listOf(SHORTCUT_ID))
    }
}

/** Only the explicit, immutable pin callback reports completion; request acceptance is not success. */
class IconPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Toast.makeText(context, R.string.custom_icon_added, Toast.LENGTH_LONG).show()
    }
}
