package io.github.sayach.dshmobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
@Config(sdk = [29, 35])
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
        val labels = arrayOf(R.string.icon_choice_whale_girl, R.string.icon_choice_official_whale,
            R.string.icon_choice_official_whale_dark, R.string.icon_choice_official_whale_teal,
            R.string.icon_choice_official_whale_mono, R.string.icon_choice_official_whale_black,
            R.string.icon_choice_official_whale_white).map { context.getString(it) }.toTypedArray()
        val adapter = LauncherIconAdapter(context, labels)
        val parent = LinearLayout(context)
        parent.orientation = LinearLayout.VERTICAL
        parent.setBackgroundColor(context.getColor(R.color.app_surface))
        assertEquals(7, adapter.count)
        for (index in 0 until adapter.count) {
            val row = adapter.getView(index, null, parent) as ViewGroup
            assertNotNull((row.getChildAt(0) as ImageView).drawable)
            (row as android.widget.Checkable).isChecked = true
            assertTrue(row.isChecked)
            row.isChecked = false
            assertFalse(row.isChecked)
            parent.addView(row)
        }
        (parent.getChildAt(0) as android.widget.Checkable).isChecked = true
        writeEvidence(parent, "built-in-previews", 390, 600)
    }

    @Test fun failedImageEncodingRollsBackToThePreviousSavedIcon() {
        val view = IconCropView(context).apply { setImage(image()) }
        CustomLauncherIcon.save(context, view.bitmap!!, view.export(), view.geometry!!)
        val invalid = image().apply { recycle() }
        assertThrows(IllegalStateException::class.java) {
            CustomLauncherIcon.save(context, invalid, view.export(), view.geometry!!)
        }
        val restored = CustomLauncherIcon.load(context)!!
        assertEquals(200, restored.source.width)
        assertEquals(Color.RED, restored.source.getPixel(10, 10))
    }

    @Test @Config(sdk = [35], qualifiers = "night")
    fun nightModePreviewsRemainVisible() { everyBuiltInChoiceLoadsItsManifestArtworkAndShowsSelection() }

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
        writeEvidence(crop, "crop-preview", 360, 300)
        controller.pause().stop().destroy()
    }

    @Test fun pickerCancellationAndDamagedImagesPreserveTheCurrentImage() {
        val initial = IconCropView(context).apply { setImage(image()) }
        CustomLauncherIcon.save(context, initial.bitmap!!, initial.export(), initial.geometry!!)
        val controller = Robolectric.buildActivity(IconEditorActivity::class.java).setup()
        val activity = controller.get()
        waitForImage(activity)
        val before = cropView(activity.window.decorView)!!.bitmap
        deliverImage(activity, Activity.RESULT_CANCELED, null)
        assertSame(before, cropView(activity.window.decorView)!!.bitmap)
        val uri = Uri.parse("content://icon-test/damaged")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("bad image".toByteArray()))
        deliverImage(activity, Activity.RESULT_OK, Intent().setData(uri))
        waitForImage(activity)
        assertSame(before, cropView(activity.window.decorView)!!.bitmap)
        assertEquals(200, CustomLauncherIcon.load(context)!!.source.width)
        controller.pause().stop().destroy()
    }

    @Test fun imageDecoderAcceptsANormalPngFromPrivateCache() {
        val controller = Robolectric.buildActivity(IconEditorActivity::class.java).setup()
        val activity = controller.get()
        waitUntilIdle(activity)
        val file = File(context.cacheDir, "decoder-fixture.png")
        file.outputStream().use { image().compress(Bitmap.CompressFormat.PNG, 100, it) }
        try {
            val decoded = IconEditorActivity::class.java.getDeclaredMethod("decode", File::class.java)
                .apply { isAccessible = true }.invoke(activity, file) as Bitmap
            assertEquals(200, decoded.width)
            assertEquals(100, decoded.height)
        } finally { file.delete(); controller.pause().stop().destroy() }
    }

    @Test fun selectingAnExifRotatedJpegCorrectsOrientationAndDoesNotSaveBeforeConfirmation() {
        val controller = Robolectric.buildActivity(IconEditorActivity::class.java).setup()
        val activity = controller.get()
        waitUntilIdle(activity)
        val bytes = ByteArrayOutputStream().also { image().compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        // EXIF little-endian TIFF, one orientation=6 entry (90 degrees clockwise).
        val exif = intArrayOf(255,225,0,34,69,120,105,102,0,0,73,73,42,0,8,0,0,0,
            1,0,18,1,3,0,1,0,0,0,6,0,0,0,0,0,0,0).map { it.toByte() }.toByteArray()
        val rotated = bytes.copyOfRange(0, 2) + exif + bytes.copyOfRange(2, bytes.size)
        val uri = Uri.parse("content://icon-test/rotated.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(rotated))
        deliverImage(activity, Activity.RESULT_OK, Intent().setData(uri))
        waitForImage(activity)
        val crop = cropView(activity.window.decorView)!!
        assertEquals(100, crop.bitmap!!.width)
        assertEquals(200, crop.bitmap!!.height)
        assertNull(CustomLauncherIcon.load(context))
        button(activity.window.decorView, context.getString(R.string.custom_icon_save)).performClick()
        waitForImage(activity)
        assertEquals(100, CustomLauncherIcon.load(context)!!.source.width)
        controller.pause().stop().destroy()
    }

    private fun deliverImage(activity: IconEditorActivity, result: Int, intent: Intent?) {
        IconEditorActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(activity, 7301, result, intent)
    }

    private fun writeEvidence(view: View, name: String, width: Int, height: Int) {
        val out = File(System.getProperty("user.dir"), "build/icon-ui-evidence").apply { mkdirs() }
        val density = context.resources.displayMetrics.density
        val pixelsWide = (width * density).toInt()
        val pixelsHigh = (height * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(pixelsWide, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(pixelsHigh, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, pixelsWide, pixelsHigh)
        val screenshot = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(screenshot))
        val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        File(out, "$name-api${android.os.Build.VERSION.SDK_INT}-${if (night) "night" else "day"}.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun waitForImage(activity: IconEditorActivity) {
        waitUntilIdle(activity)
        assertNotNull(cropView(activity.window.decorView)?.bitmap)
    }

    private fun waitUntilIdle(activity: IconEditorActivity) {
        val deadline = System.nanoTime() + 10_000_000_000L
        val busy = IconEditorActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }
        while (busy.getBoolean(activity) && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(busy.getBoolean(activity))
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
