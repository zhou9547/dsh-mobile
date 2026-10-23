package io.github.sayach.dshmobile

import android.content.Context
import android.content.pm.ShortcutManager
import android.util.AtomicFile
import java.io.File

/** Upgrade-only cleanup for the removed photo shortcut feature; never creates shortcuts. */
internal object LegacyIconShortcutCleanup {
    fun retire(context: Context) {
        try {
            val manager = context.getSystemService(ShortcutManager::class.java)
            if (manager?.pinnedShortcuts?.any { it.id == "custom-launcher-icon" && it.isEnabled } == true) {
                // Only the launcher/user can remove a pinned entry. Disable our retired entry.
                manager.disableShortcuts(listOf("custom-launcher-icon"), context.getString(R.string.legacy_icon_shortcut_removed))
            }
        } catch (_: Exception) { /* Launcher limitations must not block opening paired computers. */ }
        try {
            AtomicFile(File(context.filesDir, "custom-launcher-icon.zip")).delete()
        } catch (_: Exception) { /* Retry cleanup on a later launch; paired data is never touched. */ }
    }
}
