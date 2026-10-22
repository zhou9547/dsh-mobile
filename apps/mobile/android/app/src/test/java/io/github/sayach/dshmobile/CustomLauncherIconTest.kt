package io.github.sayach.dshmobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Android resources/bitmap rendering in a cloud JVM; not a physical launcher test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomLauncherIconTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearFixture() {
        File(context.filesDir, "custom-launcher-icon.zip").delete()
        context.getSharedPreferences("dsh_mobile", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun image(): Bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply {
            drawColor(Color.RED)
            drawRect(100f, 0f, 200f, 100f, Paint().apply { color = Color.BLUE })
        }
    }

    @Test fun exportUsesTheCropInsteadOfTheWholeImageOrPreviewMask() {
        val view = IconCropView(context)
        view.setImage(image())
        view.geometry!!.pan(-10000f, 0f)
        view.mask = IconCropView.Mask.ROUND
        val result = view.export()
        assertEquals(512, result.width)
        assertEquals(Color.BLUE, result.getPixel(256, 256))
        assertEquals(Color.BLUE, result.getPixel(10, 10))
    }

    @Test fun editableSourceAndCropSurviveReloadAndCorruptionIsRejected() {
        val view = IconCropView(context)
        view.setImage(image())
        view.geometry!!.scale(2f)
        view.geometry!!.pan(-40f, 0f)
        CustomLauncherIcon.save(context, view.bitmap!!, view.export(), view.geometry!!)
        val saved = CustomLauncherIcon.load(context)!!
        assertEquals(200, saved.source.width)
        assertEquals(512, saved.crop.width)
        assertEquals(2f, saved.zoom, 0f)
        assertEquals(view.geometry!!.centerX, saved.x, 0f)
        File(context.filesDir, "custom-launcher-icon.zip").writeText("damaged")
        assertNull(CustomLauncherIcon.load(context))
    }

    @Test fun everyBuiltInChoiceLoadsItsManifestArtworkAndShowsSelection() {
        val adapter = LauncherIconAdapter(context, Array(7) { "Choice $it" })
        val parent = LinearLayout(context)
        assertEquals(7, adapter.count)
        for (index in 0 until adapter.count) {
            val row = adapter.getView(index, null, parent) as ViewGroup
            assertNotNull((row.getChildAt(0) as ImageView).drawable)
            (row as android.widget.Checkable).isChecked = true
            assertTrue(row.isChecked)
            row.isChecked = false
            assertFalse(row.isChecked)
        }
    }

    @Test fun repeatedPinUpdatesOneStableShortcutWithAnExplicitAppIntent() {
        val manager = context.getSystemService(ShortcutManager::class.java)
        shadowOf(manager).setIsRequestPinShortcutSupported(true)
        assertFalse(CustomLauncherIcon.pin(context, image()))
        assertTrue(CustomLauncherIcon.pin(context, image()))
        val entry = manager.pinnedShortcuts.single()
        assertEquals("custom-launcher-icon", entry.id)
        assertEquals(ComponentName(context, MainActivity::class.java), entry.intent!!.component)
        val next = LauncherIconPolicy.icons[1]
        val pm = context.packageManager
        pm.setComponentEnabledSetting(ComponentName(context, "io.github.sayach.dshmobile.${next.alias}"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(ComponentName(context, "io.github.sayach.dshmobile.${LauncherIconPolicy.icons[0].alias}"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        assertTrue(CustomLauncherIcon.pin(context, image()))
        assertEquals(next.alias, manager.pinnedShortcuts.single().activity!!.shortClassName.substringAfterLast('.'))
    }

    @Test fun draftRotationAndCropSurviveActivityRecreationWithoutOverwritingSavedImage() {
        val initial = IconCropView(context).apply { setImage(image()) }
        CustomLauncherIcon.save(context, initial.bitmap!!, initial.export(), initial.geometry!!)
        val controller = Robolectric.buildActivity(IconEditorActivity::class.java).setup()
        var activity = controller.get()
        waitForImage(activity)
        button(activity.window.decorView, context.getString(R.string.custom_icon_rotate)).performClick()
        button(activity.window.decorView, context.getString(R.string.custom_icon_zoom_in)).performClick()
        controller.recreate()
        activity = controller.get()
        waitForImage(activity)
        val crop = cropView(activity.window.decorView)!!
        assertEquals(100, crop.bitmap!!.width)
        assertEquals(200, crop.bitmap!!.height)
        assertEquals(1.25f, crop.geometry!!.zoom, 0.001f)
        assertEquals(200, CustomLauncherIcon.load(context)!!.source.width)
        // Retain a genuine Android-rendered settings preview as cloud test evidence.
        val out = File(System.getProperty("user.dir"), "build/icon-ui-evidence")
        out.mkdirs()
        crop.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        crop.layout(0, 0, 360, 300)
        val screenshot = Bitmap.createBitmap(360, 300, Bitmap.Config.ARGB_8888)
        crop.draw(Canvas(screenshot))
        File(out, "crop-preview.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    private fun waitForImage(activity: IconEditorActivity) {
        val deadline = System.nanoTime() + 10_000_000_000L
        val busy = IconEditorActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }
        while (busy.getBoolean(activity) && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(busy.getBoolean(activity))
        assertNotNull(cropView(activity.window.decorView)?.bitmap)
    }

    private fun cropView(view: View): IconCropView? {
        if (view is IconCropView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            cropView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun button(view: View, text: String): Button {
        fun find(view: View): Button? {
            if (view is Button && view.text.toString() == text) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                find(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return requireNotNull(find(view))
    }
}
