package com.godcore.realrimescan

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    primary = Color(0xFF156C5B),
                    secondary = Color(0xFF42526E),
                    surface = Color(0xFFF8FAF9),
                    background = Color(0xFFF1F4F2),
                ),
            ) {
                RealRimeScanApp()
            }
        }
    }
}

data class ScanUiState(
    val documentText: String = "",
    val liveText: String = "",
    val autoCapture: Boolean = true,
    val ocrPaused: Boolean = false,
    val status: String = "카메라 준비 중",
    val lastSavedAt: String = "",
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("real_rime_scan", Context.MODE_PRIVATE)
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private var lastCommittedText = ""
    private var lastCommitAt = 0L

    private val _uiState = MutableStateFlow(
        ScanUiState(
            documentText = preferences.getString(KEY_DOCUMENT_TEXT, "").orEmpty(),
            lastSavedAt = preferences.getString(KEY_LAST_SAVED_AT, "").orEmpty(),
        ),
    )
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun onTextRecognized(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            _uiState.update { it.copy(liveText = "", status = "글씨를 찾는 중") }
            return
        }

        _uiState.update { it.copy(liveText = cleanText, status = "실시간 인식 중") }
        val now = System.currentTimeMillis()
        val shouldCommit = _uiState.value.autoCapture &&
            !_uiState.value.ocrPaused &&
            cleanText != lastCommittedText &&
            now - lastCommitAt > AUTO_COMMIT_INTERVAL_MS

        if (shouldCommit) {
            appendRecognizedText(cleanText)
            lastCommittedText = cleanText
            lastCommitAt = now
        }
    }

    fun onOcrError(message: String) {
        _uiState.update { it.copy(status = message) }
    }

    fun setAutoCapture(enabled: Boolean) {
        _uiState.update { it.copy(autoCapture = enabled) }
    }

    fun setOcrPaused(paused: Boolean) {
        _uiState.update {
            it.copy(
                ocrPaused = paused,
                status = if (paused) "OCR 일시정지" else "실시간 인식 중",
            )
        }
    }

    fun appendLiveText() {
        appendRecognizedText(_uiState.value.liveText)
    }

    fun updateDocumentText(text: String) {
        persist(text)
        _uiState.update {
            it.copy(
                documentText = text,
                lastSavedAt = formatter.format(Date()),
                status = "수정 내용 저장됨",
            )
        }
    }

    fun clearDocument() {
        persist("")
        lastCommittedText = ""
        _uiState.update {
            it.copy(
                documentText = "",
                lastSavedAt = formatter.format(Date()),
                status = "본문 초기화 완료",
            )
        }
    }

    private fun appendRecognizedText(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) return
        val current = _uiState.value.documentText.trimEnd()
        val next = if (current.isBlank()) cleanText else "$current\n\n$cleanText"
        updateDocumentText(next)
    }

    private fun persist(text: String) {
        val savedAt = formatter.format(Date())
        preferences.edit()
            .putString(KEY_DOCUMENT_TEXT, text)
            .putString(KEY_LAST_SAVED_AT, savedAt)
            .apply()
    }

    companion object {
        private const val KEY_DOCUMENT_TEXT = "document_text"
        private const val KEY_LAST_SAVED_AT = "last_saved_at"
        private const val AUTO_COMMIT_INTERVAL_MS = 3_000L
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealRimeScanApp(viewModel: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var permissionGranted by remember { androidx.compose.runtime.mutableStateOf(hasCameraPermission) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        viewModel.onOcrError(if (granted) "카메라 권한 허용됨" else "카메라 권한이 필요합니다")
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Real Rime Scan", fontWeight = FontWeight.Bold)
                        Text(uiState.status, style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .imePadding(),
        ) {
            if (permissionGranted) {
                CameraOcrPreview(
                    paused = uiState.ocrPaused,
                    onTextRecognized = viewModel::onTextRecognized,
                    onError = viewModel::onOcrError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                )
            } else {
                PermissionPanel(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                )
            }

            EditorPanel(
                uiState = uiState,
                onAutoCaptureChanged = viewModel::setAutoCapture,
                onPausedChanged = viewModel::setOcrPaused,
                onAppendLiveText = viewModel::appendLiveText,
                onDocumentChanged = viewModel::updateDocumentText,
                onClearDocument = viewModel::clearDocument,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraOcrPreview(
    paused: Boolean,
    onTextRecognized: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val recognizer = remember {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val inFlight = remember { AtomicBoolean(false) }
    var lastAnalyzedAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                bindCameraUseCases(
                    context = viewContext,
                    lifecycleOwner = lifecycleOwner,
                    previewView = this,
                    analyzer = { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (paused || inFlight.get() || now - lastAnalyzedAt < 850L) {
                            imageProxy.close()
                            return@bindCameraUseCases
                        }
                        lastAnalyzedAt = now
                        processFrame(imageProxy, recognizer, inFlight, onTextRecognized, onError)
                    },
                    onError = onError,
                )
            }
        },
        update = { previewView ->
            previewView.keepScreenOn = !paused
        },
    )
}

private fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analyzer: (ImageProxy) -> Unit,
    onError: (String) -> Unit,
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (exception: Exception) {
                onError("카메라 시작 실패: ${exception.message.orEmpty()}")
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}

@ExperimentalGetImage
private fun processFrame(
    imageProxy: ImageProxy,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    inFlight: AtomicBoolean,
    onTextRecognized: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    inFlight.set(true)
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    recognizer.process(image)
        .addOnSuccessListener { result ->
            onTextRecognized(result.text.orEmpty())
        }
        .addOnFailureListener { exception ->
            onError("OCR 실패: ${exception.message.orEmpty()}")
        }
        .addOnCompleteListener {
            inFlight.set(false)
            imageProxy.close()
        }
}

@Composable
fun PermissionPanel(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xFF0E1715)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("카메라 권한이 필요합니다", color = Color.White)
            Button(onClick = onRequestPermission) {
                Text("권한 허용")
            }
        }
    }
}

@Composable
fun EditorPanel(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("자동 저장", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (uiState.lastSavedAt.isBlank()) "아직 저장 전" else "${uiState.lastSavedAt} 저장됨",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF51615C),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("실시간 추가", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.size(8.dp))
                Switch(checked = uiState.autoCapture, onCheckedChange = onAutoCaptureChanged)
            }
        }

        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("방금 인식된 글", fontWeight = FontWeight.Bold)
                Text(
                    text = uiState.liveText.ifBlank { "책 글씨를 카메라에 맞추면 여기에 표시됩니다." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.liveText.isBlank()) Color(0xFF6B7280) else Color(0xFF17211F),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPausedChanged(!uiState.ocrPaused) }) {
                        Text(if (uiState.ocrPaused) "OCR 재개" else "OCR 정지")
                    }
                    Button(
                        onClick = onAppendLiveText,
                        enabled = uiState.liveText.isNotBlank(),
                    ) {
                        Text("본문에 추가")
                    }
                }
            }
        }

        OutlinedTextField(
            value = uiState.documentText,
            onValueChange = onDocumentChanged,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            label = { Text("저장되는 본문") },
            placeholder = { Text("인식된 글이 자동으로 저장됩니다. 오타는 여기서 바로 수정하세요.") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(uiState.documentText))
                },
                enabled = uiState.documentText.isNotBlank(),
            ) {
                Text("복사")
            }
            OutlinedButton(
                onClick = onClearDocument,
                enabled = uiState.documentText.isNotBlank(),
            ) {
                Text("본문 초기화")
            }
        }
    }
}
