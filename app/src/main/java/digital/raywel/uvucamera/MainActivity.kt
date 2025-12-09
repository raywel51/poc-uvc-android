package digital.raywel.uvucamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import digital.raywel.uvucamera.ui.theme.UVUCameraTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {

    private lateinit var usbMonitor: USBMonitor
    private var pendingCapture: CompletableDeferred<String>? = null

    @Volatile
    private var isCapturing = false

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbMonitor = USBMonitor(this, usbListener)

        enableEdgeToEdge()
        setContent {
            UVUCameraTheme {
                Letmein_UVUCameraApp(this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        usbMonitor.register()
    }

    override fun onStop() {
        usbMonitor.unregister()
        super.onStop()
    }

    override fun onDestroy() {
        usbMonitor.destroy()
        super.onDestroy()
    }

    // -----------------------
    // USBMonitor Listener
    // -----------------------

    private val usbListener = object : USBMonitor.OnDeviceConnectListener {

        override fun onAttach(device: UsbDevice?) {
            device ?: return

            Timber.i("USB Attached: ${device.deviceName}")

            val isVideoDevice = (0 until device.interfaceCount).any { i ->
                val usbInterface = device.getInterface(i)
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }

            if (!isVideoDevice) {
                Timber.i("Ignored non-video USB device: ${device.deviceName}")
                return
            }

            if (!usbManager.hasPermission(device)) {
                Timber.i("Requesting permission for VIDEO device: ${device.deviceName}")
                usbMonitor.requestPermission(device)
            } else {
                Timber.i("Already has permission for VIDEO device: ${device.deviceName}")
            }
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            Timber.i("USB Connected: ${device.deviceName}")

            pendingCapture?.let { deferred ->
                captureFromCtrlBlock(ctrlBlock, deferred)
            }
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            Timber.i("USB Disconnected")
        }

        override fun onDettach(device: UsbDevice?) {
            Timber.i("USB Detached")
        }

        override fun onCancel(device: UsbDevice?) {
            Timber.w("USB Permission Cancelled")
        }
    }


    // -----------------------
    // Public suspend API
    // -----------------------

    suspend fun captureUvcBase64(): String =
        suspendCancellableCoroutine { cont ->

            Timber.i("Requested UVC capture")

            if (isCapturing) {
                Timber.w("Ignored – already capturing")
                cont.resume("") { cause, _, _ ->
                    Timber.w("Capture cancelled: $cause")
                }
                return@suspendCancellableCoroutine
            }

            isCapturing = true
            Timber.i("Capture started")

            val deferred = CompletableDeferred<String>()
            pendingCapture = deferred

            val devices = usbMonitor.deviceList

            val cameraDevice = devices.firstOrNull { device ->
                val interfaces = (0 until device.interfaceCount).map { i ->
                    device.getInterface(i)
                }
                interfaces.any { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO }
            }

            if (cameraDevice == null) {
                Timber.e("No USB camera found")
                isCapturing = false
                pendingCapture = null
                cont.resume("") { cause, _, _ ->
                    Timber.w("Capture cancelled: $cause")
                }
                return@suspendCancellableCoroutine
            }

            Timber.i("Requesting permission for device: ${devices[0].deviceName}")
            usbMonitor.requestPermission(devices[0])

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Timber.i("Waiting for capture result…")
                    val result = deferred.await()

                    Timber.i("Capture completed → returning Base64")
                    cont.resume(result) { cause, _, _ ->
                        Timber.w("Capture cancelled: $cause")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Capture error")
                    cont.resume("") { cause, _, _ ->
                        Timber.w("Capture cancelled: $cause")
                    }

                } finally {
                    isCapturing = false
                    pendingCapture = null
                }
            }
        }



    // -----------------------
    // Capture Logic
    // -----------------------

    private fun captureFromCtrlBlock(
        ctrlBlock: USBMonitor.UsbControlBlock,
        deferred: CompletableDeferred<String>,
        width: Int = 1280,
        height: Int = 720,
        jpegQuality: Int = 90
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            var camera: UVCCamera? = null
            var surfaceTexture: SurfaceTexture? = null
            var surface: Surface? = null

            try {
                camera = UVCCamera()
                camera.open(ctrlBlock)   // ✔ ใช้ ctrlBlock จาก USBMonitor

                val supported = camera.supportedSize  // เป็น String
                Timber.i("Supported raw string = $supported")


                camera.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG)

                val frameDeferred = CompletableDeferred<ByteArray>()

                camera.setFrameCallback({ frame ->
                    val buffer = frame as ByteBuffer
                    val jpeg = ByteArray(buffer.remaining())
                    buffer.get(jpeg)
                    buffer.rewind()
                    frameDeferred.complete(jpeg)
                }, UVCCamera.PIXEL_FORMAT_RAW)

                // dummy surface
                surfaceTexture = SurfaceTexture(0).apply {
                    setDefaultBufferSize(width, height)
                }
                surface = Surface(surfaceTexture)
                camera.setPreviewDisplay(surface)

                camera.startPreview()

                val nv21 = withTimeout(2000) { frameDeferred.await() }
                val jpeg = nv21ToJpeg(nv21, width, height, jpegQuality)
                val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

                deferred.complete(base64)

            } catch (e: Exception) {
                Timber.e(e, "Capture failed")

                if (!deferred.isCompleted) deferred.complete("")

            } finally {
                try { camera?.stopPreview() } catch (_: Exception) {}
                try { camera?.destroy() } catch (_: Exception) {}
                try { surface?.release() } catch (_: Exception) {}
                try { surfaceTexture?.release() } catch (_: Exception) {}
            }
        }
    }


    // -----------------------
    // NV21 → JPEG Helper
    // -----------------------

    private fun nv21ToJpeg(
        nv21: ByteArray,
        w: Int,
        h: Int,
        q: Int
    ): ByteArray {
        val yuv = YuvImage(nv21, ImageFormat.NV21, w, h, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, w, h), q, out)
        return out.toByteArray()
    }

}
@Composable
fun Letmein_UVUCameraApp(activity: MainActivity) {

    var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // -------------------------
            // Preview Image (Top)
            // -------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                lastBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                } ?: run {
                    Text(
                        "No image captured yet",
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // -------------------------
            // Capture Button (Bottom)
            // -------------------------
            Button(
                onClick = {
                    if (isCapturing) return@Button
                    isCapturing = true

                    val startTime = SystemClock.elapsedRealtime()

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val base64 = activity.captureUvcBase64()

                            val elapsed = SystemClock.elapsedRealtime() - startTime
                            Timber.i("Capture time = $elapsed ms")

                            if (base64.isNotEmpty()) {
                                val bmp = base64ToBitmap(base64)
                                withContext(Dispatchers.Main) {
                                    lastBitmap = bmp
                                }
                            }

                        } catch (e: Exception) {
                            Timber.e(e, "Capture exception")
                        } finally {
                            withContext(Dispatchers.Main) { isCapturing = false }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                enabled = !isCapturing
            ) {
                if (isCapturing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp
                    )
                } else {
                    Text("Capture Now")
                }
            }
        }
    }
}

fun base64ToBitmap(base64: String): Bitmap {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}