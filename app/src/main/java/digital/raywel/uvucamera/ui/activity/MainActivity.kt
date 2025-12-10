package digital.raywel.uvucamera.ui.activity

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import digital.raywel.uvucamera.extensions.observeIn
import digital.raywel.uvucamera.ui.screen.home.HomeScreen
import digital.raywel.uvucamera.ui.theme.UVUCameraTheme
import digital.raywel.uvucamera.util.addTimestampToJpeg
import digital.raywel.uvucamera.util.nv21ToJpeg
import digital.raywel.uvucamera.util.saveJpegToCache
import digital.raywel.uvucamera.util.yuyvToNv21
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.nio.ByteBuffer

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private lateinit var usbMonitor: USBMonitor
    private var pendingCapture: CompletableDeferred<String>? = null

    @Volatile
    private var isCapturing = false

    private val usbManager: UsbManager by lazy {
        getSystemService(USB_SERVICE) as UsbManager
    }

    // -----------------------
    // Activity Lifecycle
    // -----------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbMonitor = USBMonitor(this, usbListener)

        observe()

        enableEdgeToEdge()
        setContent {
            UVUCameraTheme {
                HomeScreen(viewModel)
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

    private fun observe() {
        viewModel.captureState.observeIn(this) { state ->
            when (state) {
                CaptureState.Idle -> Unit
                CaptureState.Request -> {
                    try {
                        val path = captureUvcFilePath()
                        delay(1_000L)
                        viewModel.onCaptureSuccess(path)
                        Timber.i("onCaptureSuccess $path")
                    } catch (e: Exception) {
                        viewModel.onCaptureError(e.message ?: "Unknown error")
                    }
                }

                is CaptureState.Success -> {
                    Timber.i("Captured: ${state.filePath}")
                }

                is CaptureState.Error -> {
                    Timber.e("Capture error: ${state.message}")
                }
            }
        }
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
                captureFromCtrlBlock(ctrlBlock, deferred, 640, 480)
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

    suspend fun captureUvcFilePath(): String =
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

            Timber.i("Requesting permission for device: ${cameraDevice.productName}")
            usbMonitor.requestPermission(cameraDevice)

            lifecycleScope.launch(Dispatchers.IO) {
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
        lifecycleScope.launch(Dispatchers.IO) {
            var camera: UVCCamera? = null
            var surfaceTexture: SurfaceTexture? = null
            var surface: Surface? = null

            try {
                camera = UVCCamera()
                camera.open(ctrlBlock)

                Timber.i("Supported raw string = ${camera.supportedSize}")

                camera.setPreviewSize(
                    width,
                    height,
                    UVCCamera.FRAME_FORMAT_YUYV
                )

                val frameDeferred = CompletableDeferred<ByteArray>()
                var lab = 0

                camera.setFrameCallback({ frame ->
                    lab++
                    Timber.i("Frame Size ${frame.capacity()} with lab $lab")
                    val buffer = frame as ByteBuffer
                    val yuyv = ByteArray(buffer.remaining())
                    buffer.get(yuyv)
                    buffer.rewind()

                    if (lab == 2) {
                        if (!frameDeferred.isCompleted) {
                            frameDeferred.complete(yuyv)
                        }
                    }

                }, UVCCamera.FRAME_FORMAT_YUYV)

                // dummy surface
                surfaceTexture = SurfaceTexture(0).apply {
                    setDefaultBufferSize(width, height)
                }
                surface = Surface(surfaceTexture)
                camera.setPreviewDisplay(surface)

                camera.startPreview()

                val yuyv = withTimeout(2000) { frameDeferred.await() }

                val nv21 = yuyvToNv21(yuyv, width, height)
                val jpegRaw = nv21ToJpeg(nv21, width, height, jpegQuality)

                val jpeg = addTimestampToJpeg(jpegRaw)
                val uriImage = saveJpegToCache(context = this@MainActivity, image = jpeg)

                deferred.complete(uriImage.absolutePath)
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
}