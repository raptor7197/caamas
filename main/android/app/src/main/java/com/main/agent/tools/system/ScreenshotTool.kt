package com.main.agent.tools.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import android.view.WindowMetrics
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.main.agent.tools.base.Tool
import com.main.agent.tools.base.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class ScreenshotTool(private val agentFolderUri: String? = null) : Tool {

    override val name = "take_screenshot"
    override val description = "Capture a screenshot of the current screen."
    override val schema = """{"type":"function","function":{"name":"take_screenshot","description":"$description",
        "parameters":{"type":"object","properties":{
        "save_to_folder":{"type":"boolean","description":"Save to agent folder","default":true}},"required":[]}}}"""

    override suspend fun execute(context: Context, args: JsonObject): ToolResult {
        val saveToFolder = args["save_to_folder"]?.jsonPrimitive?.booleanOrNull ?: true

        return try {
            val internalPath = captureScreenshot(context)
                ?: return ToolResult.Error("Screenshot permission denied or capture failed", ToolResult.ErrorCode.PERMISSION_DENIED)

            if (!saveToFolder) return ToolResult.Success("Screenshot saved: $internalPath")

            withContext(Dispatchers.IO) { moveToAgentFolder(context, internalPath) }
        } catch (e: SecurityException) {
            ToolResult.Error("MediaProjection permission denied: ${e.message}", ToolResult.ErrorCode.PERMISSION_DENIED)
        } catch (e: Exception) {
            ToolResult.Error("Screenshot failed: ${e.message}", ToolResult.ErrorCode.UNKNOWN)
        }
    }

    // save_to_folder defaults true per schema, so a screenshot must actually land in the
    // agent folder (not just internal storage) for that promise to hold.
    private fun moveToAgentFolder(context: Context, internalPath: String): ToolResult {
        val folderUri = agentFolderUri
        if (folderUri.isNullOrBlank()) {
            return ToolResult.Success("Screenshot saved (agent folder not configured): $internalPath")
        }
        val srcFile = File(internalPath)
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            ?: return ToolResult.Error("Cannot access agent folder", ToolResult.ErrorCode.PERMISSION_DENIED)
        val destFile = folder.createFile("image/png", srcFile.name)
            ?: return ToolResult.Error("Failed to create file in agent folder")

        context.contentResolver.openOutputStream(destFile.uri)?.use { out ->
            srcFile.inputStream().use { it.copyTo(out) }
        } ?: return ToolResult.Error("Failed to write file")
        srcFile.delete()

        return ToolResult.Success("Screenshot saved: ${destFile.uri}")
    }

    private suspend fun captureScreenshot(context: Context): String? =
        suspendCancellableCoroutine { continuation ->
            val requestId = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String?>()

            // Keyed by request ID (not a single shared slot) so concurrent captures each get
            // their own screenshot back instead of one request stealing another's result.
            ScreenshotCaptureActivity.pending[requestId] = deferred

            val intent = Intent(context, ScreenshotCaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ScreenshotCaptureActivity.EXTRA_REQUEST_ID, requestId)
            }
            context.startActivity(intent)

            deferred.invokeOnCompletion { cause ->
                if (cause != null) {
                    continuation.resume(null)
                } else {
                    continuation.resume(deferred.getCompleted())
                }
            }

            continuation.invokeOnCancellation {
                deferred.cancel()
                ScreenshotCaptureActivity.pending.remove(requestId, deferred)
            }
        }
}

class ScreenshotCaptureActivity : AppCompatActivity() {

    private var mediaProjection: MediaProjection? = null
    private var handlerThread: HandlerThread? = null
    private var requestId: String? = null

    companion object {
        const val REQUEST_SCREENSHOT = 1001
        const val EXTRA_REQUEST_ID = "request_id"
        // Keyed by request ID so each activity instance completes only its own caller,
        // even if multiple screenshot requests are in flight concurrently.
        val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    }

    private fun completeSelf(path: String?) {
        requestId?.let { pending.remove(it) }?.complete(path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_SCREENSHOT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREENSHOT) return

        if (resultCode == Activity.RESULT_OK && data != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            captureScreen()
        } else {
            completeSelf(null)
            finish()
        }
    }

    private fun captureScreen() {
        val projection = mediaProjection ?: run {
            completeSelf(null)
            finish()
            return
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (width, height, density) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds  = metrics.bounds
            Triple(bounds.width(), bounds.height(), resources.displayMetrics.densityDpi)
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        handlerThread = HandlerThread("screenshot_thread").also { it.start() }
        val handler = Handler(handlerThread!!.looper)

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener

            val path = try {
                val planes     = image.planes
                val buffer     = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride  = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                val finalBitmap = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
                } else {
                    bitmap
                }

                image.close()
                saveBitmap(finalBitmap)
            } catch (e: Exception) {
                image.close()
                null
            } finally {
                imageReader.close()
                projection.stop()
            }

            completeSelf(path)
            finish()
        }, handler)

        projection.createVirtualDisplay(
            "caamas_screenshot",
            width, height, density,
            0x00000004, // VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            imageReader.surface,
            null, null
        )
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val dir  = File(filesDir, "screenshots").also { it.mkdirs() }
        val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file.absolutePath
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        completeSelf(null)
    }
}
