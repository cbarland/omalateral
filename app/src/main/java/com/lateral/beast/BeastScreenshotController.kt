package com.lateral.beast

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Captures the composed external display or a focused, non-protected hosted app. */
internal class BeastScreenshotController(
    private val activity: Activity,
    private val focusedAppBitmap: () -> Bitmap?,
    private val sharedDesktopConnected: () -> Boolean,
    private val composedDisplayCapture: (Int, (ParcelFileDescriptor?) -> Unit) -> Unit,
    private val onResult: (Result) -> Unit,
) : AutoCloseable {
    enum class ScreenshotTarget {
        FULL_DISPLAY,
        FOCUSED_APP,
    }

    sealed class Result {
        data class Saved(
            val target: ScreenshotTarget,
            val location: String,
            val galleryVisible: Boolean,
        ) : Result()

        data class Failed(
            val target: ScreenshotTarget,
            val message: String,
        ) : Result()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lateral-screenshot").apply { isDaemon = true }
    }
    private val captureInFlight = AtomicBoolean(false)

    /** Must be called from the BeastActivity main thread. */
    fun request(target: ScreenshotTarget): Boolean {
        if (!captureInFlight.compareAndSet(false, true)) return false

        when (target) {
            ScreenshotTarget.FULL_DISPLAY -> requestFullDisplay()
            ScreenshotTarget.FOCUSED_APP -> requestFocusedApp()
        }
        return true
    }

    private fun requestFullDisplay() {
        val displayId = activity.display?.displayId ?: -1
        if (displayId >= 0) {
            composedDisplayCapture(displayId) { descriptor ->
                if (descriptor != null) {
                    try {
                        captureExecutor.execute {
                            val result = try {
                                val bitmap = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                                    BitmapFactory.decodeStream(input) ?: throw IOException("Display capture was empty")
                                }
                                try { saveBitmap(ScreenshotTarget.FULL_DISPLAY, bitmap) }
                                finally { bitmap.recycle() }
                            } catch (error: Exception) {
                                Result.Failed(ScreenshotTarget.FULL_DISPLAY, error.message ?: "Display capture failed")
                            } catch (error: OutOfMemoryError) {
                                Result.Failed(ScreenshotTarget.FULL_DISPLAY, "Not enough memory for capture")
                            }
                            finish(result)
                        }
                    } catch (_: RejectedExecutionException) {
                        descriptor.close()
                        captureInFlight.set(false)
                    }
                } else if (sharedDesktopConnected()) {
                    finish(Result.Failed(ScreenshotTarget.FULL_DISPLAY, "Full display capture is unavailable"))
                } else {
                    requestOwnedWindow()
                }
            }
            return
        }
        requestOwnedWindow()
    }

    /** Local Compose desktop fallback; a remote child surface must use the composed capture. */
    private fun requestOwnedWindow() {
        val decor = activity.window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) {
            finish(Result.Failed(ScreenshotTarget.FULL_DISPLAY, "Beast display is not ready"))
            return
        }

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (error: OutOfMemoryError) {
            finish(Result.Failed(ScreenshotTarget.FULL_DISPLAY, "Not enough memory for capture"))
            return
        }

        try {
            PixelCopy.request(
                activity.window,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        enqueueSave(ScreenshotTarget.FULL_DISPLAY, bitmap)
                    } else {
                        bitmap.recycle()
                        finish(
                            Result.Failed(
                                ScreenshotTarget.FULL_DISPLAY,
                                "Display capture failed ($copyResult)",
                            ),
                        )
                    }
                },
                mainHandler,
            )
        } catch (error: RuntimeException) {
            bitmap.recycle()
            finish(Result.Failed(ScreenshotTarget.FULL_DISPLAY, "Display capture unavailable"))
        }
    }

    private fun requestFocusedApp() {
        val bitmap = try {
            focusedAppBitmap()
        } catch (error: RuntimeException) {
            null
        }
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            finish(Result.Failed(ScreenshotTarget.FOCUSED_APP, "Focused app surface is not ready"))
            return
        }
        enqueueSave(ScreenshotTarget.FOCUSED_APP, bitmap)
    }

    private fun enqueueSave(target: ScreenshotTarget, bitmap: Bitmap) {
        captureExecutor.execute {
            val result = try {
                saveBitmap(target, bitmap)
            } catch (error: Exception) {
                Result.Failed(target, error.message ?: "Could not save screenshot")
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            finish(result)
        }
    }

    private fun saveBitmap(target: ScreenshotTarget, bitmap: Bitmap): Result.Saved {
        val name = "LATERAL_-beast-${if (target == ScreenshotTarget.FULL_DISPLAY) "full" else "app"}-" +
            "${System.currentTimeMillis()}.png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/LATERAL_",
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = activity.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IOException("Could not create Gallery entry")

            try {
                val written = activity.contentResolver.openOutputStream(uri)?.use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                } ?: false
                if (!written) throw IOException("Could not encode screenshot")

                val published = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                activity.contentResolver.update(uri, published, null, null)
                return Result.Saved(target, uri.toString(), galleryVisible = true)
            } catch (error: Exception) {
                activity.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val directory = File(
            activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: activity.filesDir,
            "LATERAL_",
        ).apply { mkdirs() }
        val file = File(directory, name)
        FileOutputStream(file).use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Could not encode screenshot")
            }
        }
        return Result.Saved(target, Uri.fromFile(file).toString(), galleryVisible = false)
    }

    private fun finish(result: Result) {
        captureInFlight.set(false)
        mainHandler.post { onResult(result) }
    }

    override fun close() {
        captureExecutor.shutdownNow()
    }
}
