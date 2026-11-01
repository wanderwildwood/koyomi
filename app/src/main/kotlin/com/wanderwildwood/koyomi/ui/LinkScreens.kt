package com.wanderwildwood.koyomi.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.Screen
import com.wanderwildwood.koyomi.data.CalendarLink
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** The words for a link that cannot be followed, or null when it can. */
@Composable
private fun linkProblem(result: CalendarLink.Result): String? = when (result) {
    is CalendarLink.Result.Ok -> null
    CalendarLink.Result.Empty -> null
    CalendarLink.Result.PlainHttp -> stringResource(R.string.link_http_refused)
    CalendarLink.Result.NotALink -> stringResource(R.string.link_not_a_link)
}

/**
 * Follow a calendar by its link: paste it, or scan the QR code that holds it. The link is a
 * secret and is not kept anywhere but the app's own storage, and not shown again afterwards.
 */
@Composable
fun AddLinkScreen(model: AppModel) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(model.scannedLink.orEmpty()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A scan hands its result back through the model, and the field takes it.
    val scanned = model.scannedLink
    LaunchedEffect(scanned) {
        if (scanned != null) {
            text = scanned
            model.scannedLink = null
        }
    }
    val parsed = CalendarLink.normalise(text)
    val problem = if (text.isBlank()) null else linkProblem(parsed)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.link_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            TextMMD(
                text = stringResource(R.string.link_why),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextFieldMMD(
                value = text,
                onValueChange = { text = it; error = null },
                label = { TextMMD(text = stringResource(R.string.link_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            (problem ?: error)?.let {
                TextMMD(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            }
            if (working) {
                TextMMD(text = stringResource(R.string.link_fetching), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButtonMMD(
                onClick = { model.push(Screen.ScanLink) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { TextMMD(text = stringResource(R.string.link_scan), style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            ButtonMMD(
                onClick = {
                    val ok = parsed as? CalendarLink.Result.Ok ?: return@ButtonMMD
                    if (working) return@ButtonMMD
                    working = true
                    error = null
                    scope.launch {
                        val failed = model.followLink(ok.url)
                        working = false
                        if (failed == null) model.pop() else error = failed
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { TextMMD(text = stringResource(R.string.link_add), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Looks through the camera for a QR code with a calendar link in it. The camera is switched on
 * only while this screen is open and let go when it closes. Reads with zxing, which needs no
 * Google services.
 */
@Composable
fun ScanLinkScreen(model: AppModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var allowed by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        asked = true
    }
    val notLink = stringResource(R.string.link_scan_not_link)
    val httpRefused = stringResource(R.string.link_http_refused)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.link_scan_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (!allowed) {
                TextMMD(
                    text = stringResource(if (asked) R.string.link_camera_denied else R.string.link_camera_why),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(16.dp))
                ButtonMMD(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    TextMMD(text = stringResource(R.string.link_camera_allow), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                TextMMD(
                    text = note ?: stringResource(R.string.link_scan_hold),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                val executor = remember { Executors.newSingleThreadExecutor() }
                var done by remember { mutableStateOf(false) }
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
                    factory = { ctx ->
                        val view = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
                        val future = ProcessCameraProvider.getInstance(context)
                        future.addListener({
                            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor) { image ->
                                val text = QrReader.read(image)
                                if (text != null && !done) {
                                    view.post {
                                        if (done) return@post
                                        when (CalendarLink.normalise(text)) {
                                            is CalendarLink.Result.Ok -> {
                                                done = true
                                                model.scannedLink = text.trim()
                                                model.pop()
                                            }
                                            CalendarLink.Result.PlainHttp -> note = httpRefused
                                            else -> note = notLink
                                        }
                                    }
                                }
                            }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            }
                        }, ContextCompat.getMainExecutor(context))
                        view
                    },
                )
                DisposableEffect(Unit) {
                    onDispose {
                        // The camera is let go the moment the scanner closes.
                        runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
                        executor.shutdown()
                    }
                }
            }
        }
    }
}

/** Reads a QR code from a camera frame. */
internal object QrReader {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")

    /** The text of the QR code in a frame, or null. [luma] is the Y plane, [rowStride] bytes to a row. */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: com.google.zxing.ReaderException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    fun read(image: ImageProxy): String? {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
            return decode(data, plane.rowStride, image.width, image.height)
        } finally {
            image.close()
        }
    }
}
