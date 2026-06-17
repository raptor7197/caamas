package com.main.agent.tools.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.resume

class ScreenshotTool : Tool {

    override val name = "take_screenshot"
    override val description = "Capture a screenshot of the current screen."
    override val schema = """{"type":"function","function":{"name":"take_screenshot","description":"$description",
        "parameters":{"type":"object","properties":{
        "save_to_folder":{"type":"boolean","description":"Save to agent folder","default":true}},"required":[]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        return try {
            val path = captureScreenshot(context)
            if (path != null) {
                ToolResult.Success("Screenshot saved: $path")
            } else {
                ToolResult.Error("Screenshot permission denied or capture failed", ToolResult.ErrorCode.PERMISSION_DENIED)
            }
        } catch (e: SecurityException) {
            ToolResult.Error("MediaProjection permission denied: ${e.message}", ToolResult.ErrorCode.PERMISSION_DENIED)
        } catch (e: Exception) {
            ToolResult.Error("Screenshot failed: ${e.message}", ToolResult.ErrorCode.UNKNOWN)
        }
    }

    private suspend fun captureScreenshot(context: Context): String? = suspendCancellableCoroutine { continuation ->
        val latch = CountDownLatch(1)
        var capturedPath: String? = null

        ScreenshotCaptureActivity.setCallback { path ->
            capturedPath = path
            latch.countDown()
        }

        val intent = Intent(context, ScreenshotCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        Thread {
            try {
                latch.await()
            } catch (e: InterruptedException) {
                // Continue even if interrupted
            }
            continuation.resume(capturedPath)
        }.start()
    }
}

class ScreenshotCaptureActivity : AppCompatActivity() {

    private var mediaProjection: MediaProjection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_SCREENSHOT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SCREENSHOT) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                val path = captureScreen()
                callback?.invoke(path)
            } else {
                callback?.invoke(null)
            }
            finish()
        }
    }

    private fun captureScreen(): String? {
        val projection = mediaProjection ?: return null

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val density = displayMetrics.densityDpi

        return try {
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            val virtualDisplay = projection.createVirtualDisplay(
                "caamas_screenshot",
                width, height, density,
                0x00000004, // VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                imageReader.surface,
                null, null
            )

            val image = imageReader.acquireLatestImage() ?: run {
                virtualDisplay.release()
                projection.stop()
                return null
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else {
                bitmap
            }

            image.close()
            virtualDisplay.release()
            projection.stop()
            imageReader.close()

            saveBitmap(croppedBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val screenshotsDir = File(filesDir, "screenshots").also { it.mkdirs() }
        val fileName = "screenshot_${System.currentTimeMillis()}.png"
        val file = File(screenshotsDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        return file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        callback = null
    }

    companion object {
        private const val REQUEST_SCREENSHOT = 1001
        private var callback: ((String?) -> Unit)? = null

        fun setCallback(cb: (String?) -> Unit) {
            callback = cb
        }
    }
}