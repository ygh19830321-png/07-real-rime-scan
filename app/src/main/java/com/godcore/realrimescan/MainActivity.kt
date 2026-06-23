package com.godcore.realrimescan

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.google.mlkit.vision.text.Text
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    primary = Color(0xFF156C5B),
                    secondary = Color(0xFF42526E),
                    surface = Color(0xFF0E1715),
                    background = Color(0xFF0E1715),
                    onSurface = Color.White,
                    onBackground = Color.White,
                    onPrimary = Color.White,
                ),
            ) {
                RealRimeScanApp()
            }
        }
    }
}

data class ScanUiState(
    val documentText: String = "",
    val pages: List<String> = listOf(""),
    val currentPageIndex: Int = 0,
    val documents: List<ScanDocument> = emptyList(),
    val selectedDocumentId: String? = null,
    val documentSearchQuery: String = "",
    val liveText: String = "",
    val recognitionRate: Int = 0,
    val bookAreaDetected: Boolean = false,
    val bookArea: BookArea? = null,
    val convertedOnce: Boolean = false,
    val autoCapture: Boolean = true,
    val ocrPaused: Boolean = false,
    val status: String = "카메라 준비 중",
    val lastSavedAt: String = "",
)

data class ScanDocument(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val pages: List<String>,
)

data class OcrFrameResult(
    val text: String,
    val recognitionRate: Int,
    val bookAreaDetected: Boolean,
    val bookArea: BookArea?,
)

data class BookArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("real_rime_scan", Context.MODE_PRIVATE)
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val documentFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
    private val savedPages = decodePages(
        pagesJson = preferences.getString(KEY_DOCUMENT_PAGES, null),
        legacyText = preferences.getString(KEY_DOCUMENT_TEXT, "").orEmpty(),
    )
    private var documents = decodeDocuments(
        documentsJson = preferences.getString(KEY_DOCUMENTS, null),
        fallbackPages = savedPages,
        fallbackUpdatedAt = preferences.getString(KEY_LAST_SAVED_AT, "").orEmpty(),
    )
    private var lastCommittedText = ""
    private var lastCommitAt = 0L
    private var pendingStableText = ""
    private var pendingStableKey = ""
    private var pendingStableCount = 0
    private var replaceCurrentPageOnNextScan = false

    private val _uiState = MutableStateFlow(
        ScanUiState(
            documentText = "",
            pages = listOf(""),
            currentPageIndex = 0,
            documents = documents,
            selectedDocumentId = null,
            lastSavedAt = preferences.getString(KEY_LAST_SAVED_AT, "").orEmpty(),
        ),
    )
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    fun createDocument() {
        val now = documentFormatter.format(Date())
        val document = ScanDocument(
            id = "doc_${System.currentTimeMillis()}",
            title = "새 문서 ${documents.size + 1}",
            createdAt = now,
            updatedAt = now,
            pages = listOf(""),
        )
        documents = listOf(document) + documents
        persistDocuments()
        _uiState.update {
            it.copy(
                documents = documents,
                selectedDocumentId = document.id,
                documentText = "",
                pages = document.pages,
                currentPageIndex = 0,
                liveText = "",
                convertedOnce = false,
                ocrPaused = false,
                lastSavedAt = now,
                status = "새 문서 생성됨",
            )
        }
    }

    fun selectDocument(documentId: String) {
        val document = documents.firstOrNull { it.id == documentId } ?: return
        val pages = document.pages.ifEmpty { listOf("") }
        _uiState.update {
            it.copy(
                selectedDocumentId = document.id,
                documentText = pages.firstOrNull().orEmpty(),
                pages = pages,
                currentPageIndex = 0,
                liveText = "",
                recognitionRate = 0,
                bookAreaDetected = false,
                bookArea = null,
                convertedOnce = false,
                ocrPaused = false,
                lastSavedAt = document.updatedAt,
                status = "${document.title} 열림",
            )
        }
    }

    fun closeDocument() {
        _uiState.update {
            it.copy(
                selectedDocumentId = null,
                liveText = "",
                recognitionRate = 0,
                bookAreaDetected = false,
                bookArea = null,
                convertedOnce = false,
                ocrPaused = true,
            )
        }
    }

    fun updateDocumentSearchQuery(query: String) {
        _uiState.update { it.copy(documentSearchQuery = query) }
    }

    fun renameDocument(documentId: String, title: String) {
        val cleanTitle = title.trim().ifBlank { return }
        val now = documentFormatter.format(Date())
        documents = documents.map {
            if (it.id == documentId) it.copy(title = cleanTitle, updatedAt = now) else it
        }
        persistDocuments()
        _uiState.update {
            it.copy(
                documents = documents,
                lastSavedAt = if (it.selectedDocumentId == documentId) now else it.lastSavedAt,
            )
        }
    }

    fun deleteDocument(documentId: String) {
        documents = documents.filterNot { it.id == documentId }
        persistDocuments()
        _uiState.update {
            it.copy(
                documents = documents,
                selectedDocumentId = if (it.selectedDocumentId == documentId) null else it.selectedDocumentId,
                documentText = if (it.selectedDocumentId == documentId) "" else it.documentText,
                pages = if (it.selectedDocumentId == documentId) listOf("") else it.pages,
                currentPageIndex = if (it.selectedDocumentId == documentId) 0 else it.currentPageIndex,
            )
        }
    }

    fun onFrameRecognized(result: OcrFrameResult) {
        if (_uiState.value.convertedOnce || _uiState.value.ocrPaused) {
            return
        }

        val cleanText = cleanRecognizedText(result.text)
        if (cleanText.isBlank()) {
            resetPendingText()
            _uiState.update {
                it.copy(
                    liveText = "",
                    recognitionRate = 0,
                    bookAreaDetected = false,
                    bookArea = null,
                    status = "책 영역을 찾는 중",
                )
            }
            return
        }

        val stableText = updateStableCandidate(cleanText)
        val status = if (_uiState.value.convertedOnce) {
            "텍스트 변환 완료"
        } else if (!result.bookAreaDetected) {
            "책 영역 감지 중 · 인식률 ${result.recognitionRate}%"
        } else if (result.recognitionRate < REQUIRED_RECOGNITION_RATE) {
            "인식률 ${result.recognitionRate}% · 90% 이상 필요"
        } else if (stableText == null) {
            "인식 안정화 중 ($pendingStableCount/$MIN_STABLE_OBSERVATIONS) · ${result.recognitionRate}%"
        } else {
            "인식률 ${result.recognitionRate}% · 변환 준비 완료"
        }

        _uiState.update {
            it.copy(
                liveText = cleanText,
                recognitionRate = result.recognitionRate,
                bookAreaDetected = result.bookAreaDetected,
                bookArea = result.bookArea,
                status = status,
            )
        }
    }

    fun onOcrError(message: String) {
        _uiState.update { it.copy(status = message) }
    }

    fun setAutoCapture(enabled: Boolean) {
        _uiState.update { it.copy(autoCapture = enabled) }
    }

    fun setOcrPaused(paused: Boolean) {
        if (paused) {
            resetPendingText()
        } else {
            _uiState.update {
                it.copy(
                    convertedOnce = false,
                    recognitionRate = 0,
                    bookAreaDetected = false,
                    bookArea = null,
                )
            }
        }
        _uiState.update {
            it.copy(
                ocrPaused = paused,
                status = if (paused) "OCR 일시정지" else "실시간 인식 중",
            )
        }
    }

    fun onRecognitionButtonClicked() {
        setOcrPaused(false)
    }

    fun onStillImageRecognized(result: OcrFrameResult) {
        if (_uiState.value.convertedOnce) return

        val cleanText = cleanRecognizedText(result.text)
        if (cleanText.isBlank()) {
            _uiState.update { it.copy(status = "촬영한 사진에서 변환할 글씨를 찾지 못했습니다") }
            return
        }

        appendRecognizedText(cleanText)
        lastCommittedText = cleanText
        lastCommitAt = System.currentTimeMillis()
        resetPendingText()
        _uiState.update {
            it.copy(
                convertedOnce = true,
                ocrPaused = true,
                recognitionRate = result.recognitionRate,
                bookAreaDetected = result.bookAreaDetected,
                bookArea = result.bookArea,
                liveText = cleanText,
                status = "고해상도 사진으로 텍스트 변환 완료 · 재개 버튼을 누르면 다시 인식합니다",
            )
        }
    }

    fun appendLiveText() {
        appendRecognizedText(_uiState.value.liveText)
    }

    fun updateDocumentText(text: String) {
        val state = _uiState.value
        val pages = state.pages.ifEmpty { listOf("") }.toMutableList()
        val pageIndex = state.currentPageIndex.coerceIn(0, pages.lastIndex)
        pages[pageIndex] = text
        persistPages(pages)
        _uiState.update {
            it.copy(
                documentText = text,
                pages = pages,
                currentPageIndex = pageIndex,
                lastSavedAt = formatter.format(Date()),
                status = "수정 내용 저장됨",
            )
        }
    }

    fun clearDocument() {
        persistPages(listOf(""))
        lastCommittedText = ""
        replaceCurrentPageOnNextScan = false
        resetPendingText()
        _uiState.update {
            it.copy(
                documentText = "",
                pages = listOf(""),
                currentPageIndex = 0,
                recognitionRate = 0,
                bookAreaDetected = false,
                bookArea = null,
                convertedOnce = false,
                lastSavedAt = formatter.format(Date()),
                status = "본문 초기화 완료",
            )
        }
    }

    fun clearCurrentPageAndResumeRecognition() {
        val state = _uiState.value
        val pages = state.pages.ifEmpty { listOf("") }.toMutableList()
        val pageIndex = state.currentPageIndex.coerceIn(0, pages.lastIndex)
        pages[pageIndex] = ""
        persistPages(pages)
        lastCommittedText = ""
        replaceCurrentPageOnNextScan = true
        resetPendingText()
        _uiState.update {
            it.copy(
                documentText = "",
                pages = pages,
                currentPageIndex = pageIndex,
                liveText = "",
                recognitionRate = 0,
                bookAreaDetected = false,
                bookArea = null,
                convertedOnce = false,
                ocrPaused = false,
                lastSavedAt = formatter.format(Date()),
                status = "현재 페이지를 비우고 텍스트 인식을 재개합니다",
            )
        }
    }

    fun addPage() {
        val pages = _uiState.value.pages.ifEmpty { listOf("") } + ""
        persistPages(pages)
        _uiState.update {
            it.copy(
                documentText = "",
                pages = pages,
                currentPageIndex = pages.lastIndex,
                lastSavedAt = formatter.format(Date()),
                status = "새 페이지 추가됨",
            )
        }
    }

    fun deleteCurrentPage() {
        val state = _uiState.value
        val pages = state.pages.ifEmpty { listOf("") }.toMutableList()
        val pageIndex = state.currentPageIndex.coerceIn(0, pages.lastIndex)
        if (pages.size == 1) {
            clearDocument()
            return
        }

        pages.removeAt(pageIndex)
        val nextIndex = pageIndex.coerceAtMost(pages.lastIndex)
        persistPages(pages)
        _uiState.update {
            it.copy(
                documentText = pages[nextIndex],
                pages = pages,
                currentPageIndex = nextIndex,
                lastSavedAt = formatter.format(Date()),
                status = "현재 페이지 삭제됨",
            )
        }
    }

    fun goToPreviousPage() {
        val state = _uiState.value
        val pages = state.pages.ifEmpty { listOf("") }
        val nextIndex = (state.currentPageIndex - 1).coerceAtLeast(0)
        if (nextIndex == state.currentPageIndex) return

        _uiState.update {
            it.copy(
                documentText = pages[nextIndex],
                currentPageIndex = nextIndex,
                status = "페이지 ${nextIndex + 1} 표시 중",
            )
        }
    }

    fun goToNextPage() {
        val state = _uiState.value
        val pages = state.pages.ifEmpty { listOf("") }
        val nextIndex = (state.currentPageIndex + 1).coerceAtMost(pages.lastIndex)
        if (nextIndex == state.currentPageIndex) return

        _uiState.update {
            it.copy(
                documentText = pages[nextIndex],
                currentPageIndex = nextIndex,
                status = "페이지 ${nextIndex + 1} 표시 중",
            )
        }
    }

    fun exportAllText(): String {
        return formatPagesForExport(_uiState.value.pages)
    }

    private fun appendRecognizedText(text: String) {
        val cleanText = cleanRecognizedText(text)
        if (cleanText.isBlank()) return
        val state = _uiState.value
        val currentPages = state.pages.ifEmpty { listOf("") }
        val pages = if (replaceCurrentPageOnNextScan) {
            currentPages.toMutableList().also {
                val pageIndex = state.currentPageIndex.coerceIn(0, it.lastIndex)
                it[pageIndex] = cleanText
            }
        } else if (currentPages.size == 1 && currentPages.first().isBlank()) {
            listOf(cleanText)
        } else {
            currentPages + cleanText
        }
        val pageIndex = if (replaceCurrentPageOnNextScan) {
            state.currentPageIndex.coerceIn(0, pages.lastIndex)
        } else {
            pages.lastIndex
        }
        replaceCurrentPageOnNextScan = false
        persistPages(pages)
        _uiState.update {
            it.copy(
                documentText = cleanText,
                pages = pages,
                currentPageIndex = pageIndex,
                lastSavedAt = formatter.format(Date()),
                status = "페이지 ${pageIndex + 1} 저장됨",
            )
        }
    }

    private fun updateStableCandidate(text: String): String? {
        val key = normalizedRecognitionKey(text)
        if (key.isBlank()) {
            resetPendingText()
            return null
        }

        val isSameCandidate = pendingStableKey.isNotBlank() &&
            similarity(pendingStableKey, key) >= STABLE_SIMILARITY_THRESHOLD

        if (isSameCandidate) {
            pendingStableCount += 1
            if (text.length >= pendingStableText.length) {
                pendingStableText = text
                pendingStableKey = key
            }
        } else {
            pendingStableText = text
            pendingStableKey = key
            pendingStableCount = 1
        }

        return pendingStableText.takeIf { pendingStableCount >= MIN_STABLE_OBSERVATIONS }
    }

    private fun resetPendingText() {
        pendingStableText = ""
        pendingStableKey = ""
        pendingStableCount = 0
    }

    private fun persistPages(pages: List<String>) {
        val savedAt = documentFormatter.format(Date())
        val selectedDocumentId = _uiState.value.selectedDocumentId
        if (selectedDocumentId != null) {
            documents = documents.map {
                if (it.id == selectedDocumentId) it.copy(pages = pages, updatedAt = savedAt) else it
            }
            persistDocuments()
        }
        preferences.edit()
            .putString(KEY_DOCUMENT_TEXT, pages.joinToString("\n\n"))
            .putString(KEY_DOCUMENT_PAGES, encodePages(pages))
            .putString(KEY_LAST_SAVED_AT, savedAt)
            .apply()
    }

    private fun persistDocuments() {
        preferences.edit()
            .putString(KEY_DOCUMENTS, encodeDocuments(documents))
            .apply()
    }

    companion object {
        private const val KEY_DOCUMENT_TEXT = "document_text"
        private const val KEY_DOCUMENT_PAGES = "document_pages"
        private const val KEY_DOCUMENTS = "documents"
        private const val KEY_LAST_SAVED_AT = "last_saved_at"
        private const val AUTO_COMMIT_INTERVAL_MS = 3_000L
        private const val MIN_STABLE_OBSERVATIONS = 2
        private const val STABLE_SIMILARITY_THRESHOLD = 0.92
        private const val REQUIRED_RECOGNITION_RATE = 90
    }
}

@Composable
fun DocumentListScreen(
    uiState: ScanUiState,
    onSearchChanged: (String) -> Unit,
    onCreateDocument: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onRenameDocument: (String, String) -> Unit,
    onDeleteDocument: (String) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ScanDocument?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ScanDocument?>(null) }
    var deleteSlideValue by remember { mutableStateOf(0f) }
    val filteredDocuments = uiState.documents.filter {
        uiState.documentSearchQuery.isBlank() ||
            it.title.contains(uiState.documentSearchQuery, ignoreCase = true) ||
            it.pages.any { page -> page.contains(uiState.documentSearchQuery, ignoreCase = true) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF7FF)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("북스캔 OCR", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color(0xFF17121B))
                OutlinedButton(onClick = { }) {
                    Text("설정", color = Color(0xFF17121B))
                }
            }

            OutlinedTextField(
                value = uiState.documentSearchQuery,
                onValueChange = onSearchChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                placeholder = { Text("저장 텍스트 검색", color = Color(0xFF4D4753)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF17121B),
                    unfocusedTextColor = Color(0xFF17121B),
                    focusedBorderColor = Color(0xFF6F6874),
                    unfocusedBorderColor = Color(0xFF6F6874),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )

            Text("문서 목록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF17121B))

            filteredDocuments.forEach { document ->
                DocumentListItem(
                    document = document,
                    onOpen = { onOpenDocument(document.id) },
                    onRename = {
                        renameTarget = document
                        renameText = document.title
                    },
                    onDelete = {
                        deleteTarget = document
                        deleteSlideValue = 0f
                    },
                )
            }
        }

        FloatingActionButton(
            onClick = onCreateDocument,
            containerColor = Color(0xFFE9D8FF),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(22.dp),
        ) {
            Text("+", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF17121B))
        }
    }

    val target = renameTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("이름 변경") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameDocument(target.id, renameText)
                        renameTarget = null
                    },
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { renameTarget = null }) {
                    Text("취소")
                }
            },
        )
    }

    val deleteDocument = deleteTarget
    if (deleteDocument != null) {
        AlertDialog(
            onDismissRequest = {
                deleteTarget = null
                deleteSlideValue = 0f
            },
            title = { Text("문서 삭제 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("\"${deleteDocument.title}\" 문서를 삭제합니다.")
                    Text("삭제하려면 슬라이더를 끝까지 밀어 주세요.")
                    Slider(
                        value = deleteSlideValue,
                        onValueChange = { value ->
                            deleteSlideValue = value
                            if (value >= 0.98f) {
                                onDeleteDocument(deleteDocument.id)
                                deleteTarget = null
                                deleteSlideValue = 0f
                            }
                        },
                        valueRange = 0f..1f,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        deleteTarget = null
                        deleteSlideValue = 0f
                    },
                ) {
                    Text("취소")
                }
            },
        )
    }
}

@Composable
fun DocumentListItem(
    document: ScanDocument,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE7E1E8)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF7FF))
                    .clickable(onClick = onOpen)
                    .padding(18.dp),
            ) {
                Text(document.title, style = MaterialTheme.typography.titleLarge, color = Color(0xFF17121B))
                Text("${document.updatedAt} · ${document.pages.size}페이지", style = MaterialTheme.typography.bodyLarge, color = Color(0xFF4D4753))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onRename) {
                    Text("이름 변경", color = Color(0xFF6C45AE))
                }
                Button(onClick = onDelete) {
                    Text("삭제")
                }
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealRimeScanApp(viewModel: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    if (uiState.selectedDocumentId == null) {
        DocumentListScreen(
            uiState = uiState,
            onSearchChanged = viewModel::updateDocumentSearchQuery,
            onCreateDocument = viewModel::createDocument,
            onOpenDocument = viewModel::selectDocument,
            onRenameDocument = viewModel::renameDocument,
            onDeleteDocument = viewModel::deleteDocument,
        )
        return
    }

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
        containerColor = Color(0xFF0E1715),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                ),
                title = {
                    Column {
                        Text("Real Rime Scan", fontWeight = FontWeight.Bold, color = Color.White)
                        Text(uiState.status, style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                },
                navigationIcon = {
                    OutlinedButton(onClick = viewModel::closeDocument) {
                        Text("목록", color = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0E1715))
                .padding(padding)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            if (permissionGranted) {
                CameraOcrPreview(
                    paused = uiState.ocrPaused,
                    recognitionRate = uiState.recognitionRate,
                    bookAreaDetected = uiState.bookAreaDetected,
                    bookArea = uiState.bookArea,
                    convertedOnce = uiState.convertedOnce,
                    onRecognitionButtonClick = viewModel::onRecognitionButtonClicked,
                    onStillImageRecognized = viewModel::onStillImageRecognized,
                    onFrameRecognized = viewModel::onFrameRecognized,
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

            EditorPanel8(
                uiState = uiState,
                onAutoCaptureChanged = viewModel::setAutoCapture,
                onPausedChanged = viewModel::setOcrPaused,
                onAppendLiveText = viewModel::appendLiveText,
                onDocumentChanged = viewModel::updateDocumentText,
                onClearDocument = viewModel::clearCurrentPageAndResumeRecognition,
                onAddPage = viewModel::addPage,
                onDeletePage = viewModel::deleteCurrentPage,
                onPreviousPage = viewModel::goToPreviousPage,
                onNextPage = viewModel::goToNextPage,
                allPagesText = viewModel.exportAllText(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraOcrPreview(
    paused: Boolean,
    recognitionRate: Int,
    bookAreaDetected: Boolean,
    bookArea: BookArea?,
    convertedOnce: Boolean,
    onRecognitionButtonClick: () -> Unit,
    onStillImageRecognized: (OcrFrameResult) -> Unit,
    onFrameRecognized: (OcrFrameResult) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val recognizer = remember {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val inFlight = remember { AtomicBoolean(false) }
    val captureInFlight = remember { AtomicBoolean(false) }
    var lastAnalyzedAt by remember { mutableLongStateOf(0L) }
    var lastAutoCapturedAt by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            analysisExecutor.shutdown()
            captureExecutor.shutdown()
        }
    }

    LaunchedEffect(recognitionRate, convertedOnce, paused) {
        val now = System.currentTimeMillis()
        if (!convertedOnce && !paused && recognitionRate >= 100 && now - lastAutoCapturedAt > 3_000L) {
            lastAutoCapturedAt = now
            captureStillFrame(
                imageCapture = imageCapture,
                recognizer = recognizer,
                inFlight = captureInFlight,
                executor = captureExecutor,
                onFrameRecognized = onStillImageRecognized,
                onError = onError,
            )
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    bindCameraUseCases(
                        context = viewContext,
                        lifecycleOwner = lifecycleOwner,
                        previewView = this,
                        imageCapture = imageCapture,
                        analyzer = { imageProxy ->
                            val now = System.currentTimeMillis()
                            if (paused || inFlight.get() || now - lastAnalyzedAt < 850L) {
                                imageProxy.close()
                                return@bindCameraUseCases
                            }
                            lastAnalyzedAt = now
                            processFrame(imageProxy, recognizer, inFlight, onFrameRecognized, onError)
                        },
                        onError = onError,
                    )
                }
            },
            update = { previewView ->
                previewView.keepScreenOn = !paused
            },
        )
        Text(
            text = if (bookAreaDetected) "책 영역 감지 · 인식률 $recognitionRate%" else "책 영역 찾는 중 · $recognitionRate%",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(if (recognitionRate >= 90) Color(0xCC156C5B) else Color(0xCC0E1715))
                .border(
                    width = 2.dp,
                    color = if (bookAreaDetected) Color(0xFF8EE6C9) else Color(0xFF6B7280),
                )
                .padding(10.dp),
        )
        BookAreaOverlay(
            bookArea = bookArea,
            detected = bookAreaDetected,
            modifier = Modifier.fillMaxSize(),
        )
        Button(
            onClick = {
                if (convertedOnce) {
                    onRecognitionButtonClick()
                } else {
                    captureStillFrame(
                        imageCapture = imageCapture,
                        recognizer = recognizer,
                        inFlight = captureInFlight,
                        executor = captureExecutor,
                        onFrameRecognized = onStillImageRecognized,
                        onError = onError,
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
        ) {
            Text(if (convertedOnce) "재개" else "인식")
        }
    }
}

@Composable
private fun BookAreaOverlay(
    bookArea: BookArea?,
    detected: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val color = if (detected) Color(0xFF8EE6C9) else Color(0xFFFFD166)
        val stroke = Stroke(width = 4.dp.toPx())
        val area = bookArea

        if (area != null) {
            val left = area.left.coerceIn(0f, 1f) * size.width
            val top = area.top.coerceIn(0f, 1f) * size.height
            val right = area.right.coerceIn(0f, 1f) * size.width
            val bottom = area.bottom.coerceIn(0f, 1f) * size.height
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(
                    width = (right - left).coerceAtLeast(1f),
                    height = (bottom - top).coerceAtLeast(1f),
                ),
                style = stroke,
            )
        } else {
            val horizontalPadding = size.width * 0.06f
            val verticalPadding = size.height * 0.08f
            drawRect(
                color = color,
                topLeft = Offset(horizontalPadding, verticalPadding),
                size = Size(
                    width = size.width - horizontalPadding * 2f,
                    height = size.height - verticalPadding * 2f,
                ),
                style = stroke,
            )
        }
    }
}

private fun bindCameraUseCases(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    imageCapture: ImageCapture,
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
                    imageCapture,
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
    onFrameRecognized: (OcrFrameResult) -> Unit,
    onError: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    inFlight.set(true)
    val rotation = imageProxy.imageInfo.rotationDegrees
    val detectedBookArea = detectBookArea(imageProxy, rotation)
    val image = InputImage.fromMediaImage(mediaImage, rotation)
    recognizer.process(image)
        .addOnSuccessListener { result ->
            val displayWidth = if (rotation == 90 || rotation == 270) mediaImage.height else mediaImage.width
            val displayHeight = if (rotation == 90 || rotation == 270) mediaImage.width else mediaImage.height
            onFrameRecognized(
                buildOcrFrameResult(
                    result = result,
                    imageWidth = displayWidth,
                    imageHeight = displayHeight,
                    detectedBookArea = detectedBookArea,
                ),
            )
        }
        .addOnFailureListener { exception ->
            onError("OCR 실패: ${exception.message.orEmpty()}")
        }
        .addOnCompleteListener {
            inFlight.set(false)
            imageProxy.close()
        }
}

@ExperimentalGetImage
private fun captureStillFrame(
    imageCapture: ImageCapture,
    recognizer: com.google.mlkit.vision.text.TextRecognizer,
    inFlight: AtomicBoolean,
    executor: java.util.concurrent.Executor,
    onFrameRecognized: (OcrFrameResult) -> Unit,
    onError: (String) -> Unit,
) {
    if (!inFlight.compareAndSet(false, true)) return

    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    inFlight.set(false)
                    imageProxy.close()
                    onError("사진 캡처 실패: 이미지가 비어 있습니다")
                    return
                }

                val rotation = imageProxy.imageInfo.rotationDegrees
                val displayWidth = if (rotation == 90 || rotation == 270) mediaImage.height else mediaImage.width
                val displayHeight = if (rotation == 90 || rotation == 270) mediaImage.width else mediaImage.height
                val detectedBookArea = detectBookArea(imageProxy, rotation)
                val image = InputImage.fromMediaImage(mediaImage, rotation)
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        onFrameRecognized(
                            buildOcrFrameResult(
                                result = result,
                                imageWidth = displayWidth,
                                imageHeight = displayHeight,
                                detectedBookArea = detectedBookArea,
                            ),
                        )
                    }
                    .addOnFailureListener { exception ->
                        onError("사진 OCR 실패: ${exception.message.orEmpty()}")
                    }
                    .addOnCompleteListener {
                        inFlight.set(false)
                        imageProxy.close()
                    }
            }

            override fun onError(exception: ImageCaptureException) {
                inFlight.set(false)
                onError("사진 촬영 실패: ${exception.message.orEmpty()}")
            }
        },
    )
}

private fun buildOcrFrameResult(
    result: Text,
    imageWidth: Int,
    imageHeight: Int,
    detectedBookArea: BookArea?,
): OcrFrameResult {
    val cleanText = cleanRecognizedText(result.text.orEmpty())
    val normalizedText = normalizedRecognitionKey(cleanText)
    val blocks = result.textBlocks
    val lineCount = blocks.sumOf { it.lines.size }
    val textBounds = mergedBounds(blocks.mapNotNull { it.boundingBox })
    val textAreaRatio = if (textBounds == null) {
        0.0
    } else {
        val frameArea = max(1, imageWidth * imageHeight)
        (textBounds.width() * textBounds.height()).toDouble() / frameArea.toDouble()
    }
    val textArea = textBounds?.let {
        BookArea(
            left = it.left.toFloat() / max(1, imageWidth).toFloat(),
            top = it.top.toFloat() / max(1, imageHeight).toFloat(),
            right = it.right.toFloat() / max(1, imageWidth).toFloat(),
            bottom = it.bottom.toFloat() / max(1, imageHeight).toFloat(),
        )
    }
    val bookArea = detectedBookArea ?: textArea
    val hasEnoughText = normalizedText.length >= 8 && lineCount >= 2
    val bookAreaDetected = hasEnoughText
    val recognitionRate = estimateRecognitionRate(
        textLength = normalizedText.length,
        lineCount = lineCount,
        blockCount = blocks.size,
        textAreaRatio = textAreaRatio,
        bookAreaDetected = bookAreaDetected,
    )

    return OcrFrameResult(
        text = cleanText,
        recognitionRate = recognitionRate,
        bookAreaDetected = bookAreaDetected,
        bookArea = bookArea,
    )
}

private fun detectBookArea(imageProxy: ImageProxy, rotationDegrees: Int): BookArea? {
    val plane = imageProxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate()
    val width = imageProxy.width
    val height = imageProxy.height
    if (width <= 0 || height <= 0) return null

    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val stepX = max(4, width / 80)
    val stepY = max(4, height / 120)

    fun luminanceAt(x: Int, y: Int): Int {
        val index = y * rowStride + x * pixelStride
        if (index < 0 || index >= buffer.limit()) return 0
        return buffer.get(index).toInt() and 0xFF
    }

    var borderSum = 0L
    var borderCount = 0
    for (x in 0 until width step stepX) {
        borderSum += luminanceAt(x, 0)
        borderSum += luminanceAt(x, height - 1)
        borderCount += 2
    }
    for (y in 0 until height step stepY) {
        borderSum += luminanceAt(0, y)
        borderSum += luminanceAt(width - 1, y)
        borderCount += 2
    }
    if (borderCount == 0) return null

    val background = (borderSum / borderCount).toInt()
    val threshold = 28
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    var hitCount = 0

    for (y in 0 until height step stepY) {
        for (x in 0 until width step stepX) {
            val diff = kotlin.math.abs(luminanceAt(x, y) - background)
            if (diff >= threshold) {
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                hitCount += 1
            }
        }
    }

    if (hitCount < 24 || maxX <= minX || maxY <= minY) return null

    val expandX = width * 0.03f
    val expandY = height * 0.03f
    val area = BookArea(
        left = ((minX - expandX) / width).coerceIn(0f, 1f),
        top = ((minY - expandY) / height).coerceIn(0f, 1f),
        right = ((maxX + expandX) / width).coerceIn(0f, 1f),
        bottom = ((maxY + expandY) / height).coerceIn(0f, 1f),
    )

    return rotateBookArea(area, rotationDegrees)
}

private fun rotateBookArea(area: BookArea, rotationDegrees: Int): BookArea {
    val corners = listOf(
        area.left to area.top,
        area.right to area.top,
        area.right to area.bottom,
        area.left to area.bottom,
    ).map { (x, y) ->
        when (rotationDegrees) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
    }

    return BookArea(
        left = corners.minOf { it.first }.coerceIn(0f, 1f),
        top = corners.minOf { it.second }.coerceIn(0f, 1f),
        right = corners.maxOf { it.first }.coerceIn(0f, 1f),
        bottom = corners.maxOf { it.second }.coerceIn(0f, 1f),
    )
}

private fun mergedBounds(bounds: List<Rect>): Rect? {
    if (bounds.isEmpty()) return null
    val merged = Rect(bounds.first())
    bounds.drop(1).forEach { merged.union(it) }
    return merged
}

private fun estimateRecognitionRate(
    textLength: Int,
    lineCount: Int,
    blockCount: Int,
    textAreaRatio: Double,
    bookAreaDetected: Boolean,
): Int {
    if (textLength == 0) return 0

    val textScore = (min(textLength, 80) / 80.0) * 35.0
    val lineScore = (min(lineCount, 8) / 8.0) * 20.0
    val areaScore = (min(textAreaRatio, 0.35) / 0.35) * 25.0
    val blockScore = if (blockCount > 0) 10.0 else 0.0
    val bookAreaScore = if (bookAreaDetected) 10.0 else 0.0
    val rawScore = textScore + lineScore + areaScore + blockScore + bookAreaScore

    return rawScore
        .roundToInt()
        .coerceIn(0, if (bookAreaDetected) 100 else 89)
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
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
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
                Text(
                    "페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddPage) {
                Text("페이지 추가")
            }
            OutlinedButton(onClick = onDeletePage) {
                Text("페이지 삭제")
            }
            OutlinedButton(
                onClick = { exportText(context, allPagesText) },
                enabled = allPagesText.isNotBlank(),
            ) {
                Text("전체 내보내기")
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
                Text("방금 인식한 글", fontWeight = FontWeight.Bold)
                Text(
                    text = if (uiState.bookAreaDetected) {
                        "책 영역 자동 감지됨 · 인식률 ${uiState.recognitionRate}%"
                    } else {
                        "책 영역 자동 감지 대기 · 인식률 ${uiState.recognitionRate}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.recognitionRate >= 90) Color(0xFF156C5B) else Color(0xFF51615C),
                )
                Text(
                    text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
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
            label = { Text("저장된 본문") },
            placeholder = { Text("인식한 글이 자동으로 저장됩니다. 오타가 있으면 여기서 바로 수정하세요") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White,
            ),
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

@Composable
fun EditorPanel2(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                Text(
                    "페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onPreviousPage,
                enabled = uiState.currentPageIndex > 0,
            ) {
                Text("이전")
            }
            OutlinedButton(
                onClick = onNextPage,
                enabled = uiState.currentPageIndex < uiState.pages.lastIndex,
            ) {
                Text("다음")
            }
            Button(onClick = onAddPage) {
                Text("페이지 추가")
            }
            OutlinedButton(onClick = onDeletePage) {
                Text("페이지 삭제")
            }
            OutlinedButton(
                onClick = { exportText(context, allPagesText) },
                enabled = allPagesText.isNotBlank(),
            ) {
                Text("전체 내보내기")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(
                    modifier = Modifier
                        .height(280.dp)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("방금 인식한 글", fontWeight = FontWeight.Bold)
                    Text(
                        text = if (uiState.bookAreaDetected) {
                            "책 영역 감지됨 · 인식률 ${uiState.recognitionRate}%"
                        } else {
                            "책 영역 감지 대기 · 인식률 ${uiState.recognitionRate}%"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.recognitionRate >= 90) Color(0xFF156C5B) else Color(0xFF51615C),
                    )
                    Text(
                        text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (uiState.liveText.isBlank()) Color(0xFF6B7280) else Color(0xFF17211F),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
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
                    .weight(1f)
                    .height(280.dp),
                label = { Text("저장된 본문") },
                placeholder = { Text("이 페이지의 텍스트가 저장됩니다") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                ),
            )
        }

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

@Composable
fun EditorPanel3(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                Text(
                    "페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPreviousPage, enabled = uiState.currentPageIndex > 0) {
                Text("이전")
            }
            OutlinedButton(onClick = onNextPage, enabled = uiState.currentPageIndex < uiState.pages.lastIndex) {
                Text("다음")
            }
            Button(onClick = onAddPage) {
                Text("페이지 추가")
            }
            OutlinedButton(onClick = onDeletePage) {
                Text("페이지 삭제")
            }
            OutlinedButton(
                onClick = { exportText(context, allPagesText) },
                enabled = allPagesText.isNotBlank(),
            ) {
                Text("전체 내보내기")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(280.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("방금 인식한 글", fontWeight = FontWeight.Bold)
                Text(
                    text = if (uiState.bookAreaDetected) {
                        "책 영역 감지됨 · 인식률 ${uiState.recognitionRate}%"
                    } else {
                        "책 영역 감지 대기 · 인식률 ${uiState.recognitionRate}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.recognitionRate >= 90) Color(0xFF156C5B) else Color(0xFF51615C),
                )
                Text(
                    text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.liveText.isBlank()) Color(0xFF6B7280) else Color(0xFF17211F),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.documentText,
                    onValueChange = onDocumentChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp),
                    label = { Text("저장된 본문") },
                    placeholder = { Text("이 페이지의 텍스트가 저장됩니다") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(uiState.documentText)) },
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
    }
}

@Composable
fun EditorPanel4(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                Text(
                    "페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onDeletePage) {
                Text("페이지 삭제")
            }
            OutlinedButton(
                onClick = { exportText(context, allPagesText) },
                enabled = allPagesText.isNotBlank(),
            ) {
                Text("전체 내보내기")
            }
            Button(onClick = onAddPage) {
                Text("페이지 추가")
            }
            OutlinedButton(onClick = onPreviousPage, enabled = uiState.currentPageIndex > 0) {
                Text("이전")
            }
            OutlinedButton(onClick = onNextPage, enabled = uiState.currentPageIndex < uiState.pages.lastIndex) {
                Text("다음")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(280.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("방금 인식한 글", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    text = if (uiState.bookAreaDetected) {
                        "책 영역 감지됨 · 인식률 ${uiState.recognitionRate}%"
                    } else {
                        "책 영역 감지 대기 · 인식률 ${uiState.recognitionRate}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Text(
                    text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.documentText,
                    onValueChange = onDocumentChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp),
                    label = { Text("저장된 본문") },
                    placeholder = { Text("이 페이지의 텍스트가 저장됩니다") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(uiState.documentText)) },
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
    }
}

@Composable
fun EditorPanel5(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                Text(
                    "페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    if (uiState.lastSavedAt.isBlank()) "아직 저장 전" else "${uiState.lastSavedAt} 저장됨",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("실시간 추가", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(Modifier.size(8.dp))
                Switch(checked = uiState.autoCapture, onCheckedChange = onAutoCaptureChanged)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(
                onClick = { exportText(context, allPagesText) },
                enabled = allPagesText.isNotBlank(),
            ) {
                Text("전체 내보내기", color = Color.White)
            }
            OutlinedButton(onClick = onDeletePage) {
                Text("페이지 삭제", color = Color.White)
            }
            OutlinedButton(onClick = onAddPage) {
                Text("페이지 추가", color = Color.White)
            }
            OutlinedButton(onClick = onPreviousPage, enabled = uiState.currentPageIndex > 0) {
                Text("이전", color = Color.White)
            }
            OutlinedButton(onClick = onNextPage, enabled = uiState.currentPageIndex < uiState.pages.lastIndex) {
                Text("다음", color = Color.White)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(280.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("방금 인식한 글", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    text = if (uiState.bookAreaDetected) {
                        "책 영역 감지됨 · 인식률 ${uiState.recognitionRate}%"
                    } else {
                        "책 영역 감지 대기 · 인식률 ${uiState.recognitionRate}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Text(
                    text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPausedChanged(!uiState.ocrPaused) }) {
                        Text(if (uiState.ocrPaused) "OCR 재개" else "OCR 정지", color = Color.White)
                    }
                    Button(
                        onClick = onAppendLiveText,
                        enabled = uiState.liveText.isNotBlank(),
                    ) {
                        Text("본문에 추가", color = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.documentText,
                    onValueChange = onDocumentChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp),
                    label = { Text("저장된 본문", color = Color.White) },
                    placeholder = { Text("이 페이지의 텍스트가 저장됩니다", color = Color.White) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.White,
                        focusedPlaceholderColor = Color.White,
                        unfocusedPlaceholderColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(uiState.documentText)) },
                        enabled = uiState.documentText.isNotBlank(),
                    ) {
                        Text("복사", color = Color.White)
                    }
                    OutlinedButton(
                        onClick = onClearDocument,
                        enabled = uiState.documentText.isNotBlank(),
                    ) {
                        Text("본문 초기화", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EditorPanel6(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                Text(
                    "페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    if (uiState.lastSavedAt.isBlank()) "아직 저장 전" else "${uiState.lastSavedAt} 저장됨",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("실시간 추가", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(Modifier.size(8.dp))
                Switch(checked = uiState.autoCapture, onCheckedChange = onAutoCaptureChanged)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onDeletePage) {
                Text("페이지 삭제", color = Color.White)
            }
            OutlinedButton(
                onClick = { exportText(context, allPagesText) },
                enabled = allPagesText.isNotBlank(),
            ) {
                Text("전체 내보내기", color = Color.White)
            }
            OutlinedButton(onClick = onAddPage) {
                Text("페이지 추가", color = Color.White)
            }
            OutlinedButton(onClick = onPreviousPage, enabled = uiState.currentPageIndex > 0) {
                Text("이전", color = Color.White)
            }
            OutlinedButton(onClick = onNextPage, enabled = uiState.currentPageIndex < uiState.pages.lastIndex) {
                Text("다음", color = Color.White)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(280.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("방금 인식한 글", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    text = if (uiState.bookAreaDetected) {
                        "책 영역 감지됨 · 인식률 ${uiState.recognitionRate}%"
                    } else {
                        "책 영역 감지 대기 · 인식률 ${uiState.recognitionRate}%"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Text(
                    text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPausedChanged(!uiState.ocrPaused) }) {
                        Text(if (uiState.ocrPaused) "OCR 재개" else "OCR 정지", color = Color.White)
                    }
                    Button(
                        onClick = onAppendLiveText,
                        enabled = uiState.liveText.isNotBlank(),
                    ) {
                        Text("본문에 추가", color = Color.White)
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = uiState.documentText,
                    onValueChange = onDocumentChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp),
                    label = { Text("페이지 ${uiState.currentPageIndex + 1} 본문", color = Color.White) },
                    placeholder = { Text("이 페이지의 텍스트가 저장됩니다", color = Color.White) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.White,
                        focusedPlaceholderColor = Color.White,
                        unfocusedPlaceholderColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(uiState.documentText)) },
                        enabled = uiState.documentText.isNotBlank(),
                    ) {
                        Text("복사", color = Color.White)
                    }
                    OutlinedButton(onClick = onClearDocument) {
                        Text("재개", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EditorPanel7(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                Text("페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Text(if (uiState.lastSavedAt.isBlank()) "아직 저장 전" else "${uiState.lastSavedAt} 저장됨", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("실시간 추가", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Spacer(Modifier.size(8.dp))
                Switch(checked = uiState.autoCapture, onCheckedChange = onAutoCaptureChanged)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { exportText(context, allPagesText) }, enabled = allPagesText.isNotBlank()) {
                Text("전체 내보내기", color = Color.White)
            }
            OutlinedButton(onClick = onDeletePage) {
                Text("페이지 삭제", color = Color.White)
            }
            OutlinedButton(onClick = onAddPage) {
                Text("페이지 추가", color = Color.White)
            }
            OutlinedButton(onClick = onPreviousPage, enabled = uiState.currentPageIndex > 0) {
                Text("이전", color = Color.White)
            }
            OutlinedButton(onClick = onNextPage, enabled = uiState.currentPageIndex < uiState.pages.lastIndex) {
                Text("다음", color = Color.White)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(280.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("방금 인식한 글", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    text = if (uiState.bookAreaDetected) "책 영역 감지됨 · 인식률 ${uiState.recognitionRate}%" else "책 영역 감지 대기 · 인식률 ${uiState.recognitionRate}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Text(
                    text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPausedChanged(!uiState.ocrPaused) }) {
                        Text(if (uiState.ocrPaused) "OCR 재개" else "OCR 정지", color = Color.White)
                    }
                    Button(onClick = onAppendLiveText, enabled = uiState.liveText.isNotBlank()) {
                        Text("본문에 추가", color = Color.White)
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = uiState.documentText,
                    onValueChange = onDocumentChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp),
                    label = { Text("페이지 ${uiState.currentPageIndex + 1} 본문", color = Color.White) },
                    placeholder = { Text("이 페이지의 텍스트가 저장됩니다", color = Color.White) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.White,
                        focusedPlaceholderColor = Color.White,
                        unfocusedPlaceholderColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clipboard.setText(AnnotatedString(uiState.documentText)) }, enabled = uiState.documentText.isNotBlank()) {
                        Text("복사", color = Color.White)
                    }
                    OutlinedButton(onClick = onClearDocument) {
                        Text("다시 스캔", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EditorPanel8(
    uiState: ScanUiState,
    onAutoCaptureChanged: (Boolean) -> Unit,
    onPausedChanged: (Boolean) -> Unit,
    onAppendLiveText: () -> Unit,
    onDocumentChanged: (String) -> Unit,
    onClearDocument: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    allPagesText: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

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
                Text(
                    "페이지 ${uiState.currentPageIndex + 1} / ${uiState.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    if (uiState.lastSavedAt.isBlank()) "아직 저장 전" else "${uiState.lastSavedAt} 저장됨",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { exportText(context, allPagesText) }, enabled = allPagesText.isNotBlank()) {
                    Text("전체 내보내기", color = Color.White)
                }
                OutlinedButton(onClick = onDeletePage) {
                    Text("페이지 삭제", color = Color.White)
                }
                OutlinedButton(onClick = onAddPage) {
                    Text("페이지 추가", color = Color.White)
                }
                OutlinedButton(onClick = onPreviousPage, enabled = uiState.currentPageIndex > 0) {
                    Text("이전", color = Color.White)
                }
                OutlinedButton(onClick = onNextPage, enabled = uiState.currentPageIndex < uiState.pages.lastIndex) {
                    Text("다음", color = Color.White)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(280.dp)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("방금 인식한 글", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    text = if (uiState.bookAreaDetected) "책 영역 감지됨 · 인식률 ${uiState.recognitionRate}%" else "책 영역 감지 대기 · 인식률 ${uiState.recognitionRate}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                Text(
                    text = uiState.liveText.ifBlank { "책 글자를 카메라에 맞추면 여기에 표시됩니다" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPausedChanged(!uiState.ocrPaused) }) {
                        Text(if (uiState.ocrPaused) "OCR 재개" else "OCR 정지", color = Color.White)
                    }
                    Button(onClick = onAppendLiveText, enabled = uiState.liveText.isNotBlank()) {
                        Text("본문에 추가", color = Color.White)
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = uiState.documentText,
                    onValueChange = onDocumentChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(232.dp),
                    label = { Text("페이지 ${uiState.currentPageIndex + 1} 본문", color = Color.White) },
                    placeholder = { Text("이 페이지의 텍스트가 저장됩니다", color = Color.White) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.White,
                        focusedPlaceholderColor = Color.White,
                        unfocusedPlaceholderColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clipboard.setText(AnnotatedString(uiState.documentText)) }, enabled = uiState.documentText.isNotBlank()) {
                        Text("복사", color = Color.White)
                    }
                    OutlinedButton(onClick = onClearDocument) {
                        Text("다시 스캔", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun cleanRecognizedText(text: String): String {
    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .map { line -> line.trim().replace(Regex("\\s+"), " ") }
        .filter { line -> line.isNotBlank() }
        .joinToString("\n")
        .trim()
}

private fun normalizedRecognitionKey(text: String): String {
    return cleanRecognizedText(text)
        .lowercase(Locale.KOREA)
        .replace(Regex("[\\p{Punct}\\s]+"), "")
}

private fun similarity(first: String, second: String): Double {
    if (first == second) return 1.0
    val longerLength = max(first.length, second.length)
    if (longerLength == 0) return 1.0
    val distance = levenshteinDistance(first, second)
    return 1.0 - distance.toDouble() / longerLength
}

private fun levenshteinDistance(first: String, second: String): Int {
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length

    var previous = IntArray(second.length + 1) { it }
    var current = IntArray(second.length + 1)

    for (i in first.indices) {
        current[0] = i + 1
        for (j in second.indices) {
            val insertion = current[j] + 1
            val deletion = previous[j + 1] + 1
            val substitution = previous[j] + if (first[i] == second[j]) 0 else 1
            current[j + 1] = min(min(insertion, deletion), substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }

    return previous[second.length]
}

private fun encodePages(pages: List<String>): String {
    val jsonArray = JSONArray()
    pages.forEach { page -> jsonArray.put(page) }
    return jsonArray.toString()
}

private fun encodeDocuments(documents: List<ScanDocument>): String {
    val jsonArray = JSONArray()
    documents.forEach { document ->
        jsonArray.put(
            JSONObject()
                .put("id", document.id)
                .put("title", document.title)
                .put("createdAt", document.createdAt)
                .put("updatedAt", document.updatedAt)
                .put("pages", JSONArray().also { pages ->
                    document.pages.forEach { page -> pages.put(page) }
                }),
        )
    }
    return jsonArray.toString()
}

private fun decodeDocuments(
    documentsJson: String?,
    fallbackPages: List<String>,
    fallbackUpdatedAt: String,
): List<ScanDocument> {
    if (!documentsJson.isNullOrBlank()) {
        runCatching {
            val jsonArray = JSONArray(documentsJson)
            return List(jsonArray.length()) { index ->
                val item = jsonArray.getJSONObject(index)
                val pagesArray = item.optJSONArray("pages") ?: JSONArray()
                ScanDocument(
                    id = item.optString("id", "doc_$index"),
                    title = item.optString("title", "문서 ${index + 1}"),
                    createdAt = item.optString("createdAt", fallbackUpdatedAt),
                    updatedAt = item.optString("updatedAt", fallbackUpdatedAt),
                    pages = List(pagesArray.length()) { pageIndex -> pagesArray.optString(pageIndex) }.ifEmpty { listOf("") },
                )
            }
        }
    }

    if (fallbackPages.all { it.isBlank() }) return emptyList()
    val savedAt = fallbackUpdatedAt.ifBlank { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date()) }
    return listOf(
        ScanDocument(
            id = "doc_migrated",
            title = "기존 문서",
            createdAt = savedAt,
            updatedAt = savedAt,
            pages = fallbackPages.ifEmpty { listOf("") },
        ),
    )
}

private fun decodePages(pagesJson: String?, legacyText: String): List<String> {
    if (!pagesJson.isNullOrBlank()) {
        runCatching {
            val jsonArray = JSONArray(pagesJson)
            return List(jsonArray.length()) { index -> jsonArray.optString(index) }
                .ifEmpty { listOf("") }
        }
    }

    return if (legacyText.isBlank()) listOf("") else listOf(legacyText)
}

private fun formatPagesForExport(pages: List<String>): String {
    return pages
        .mapIndexedNotNull { index, page ->
            val text = page.trim()
            if (text.isBlank()) null else "Page ${index + 1}\n$text"
        }
        .joinToString("\n\n")
}

private fun exportText(context: Context, text: String) {
    if (text.isBlank()) return

    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Real Rime Scan 전체 텍스트")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(sendIntent, "전체 텍스트 내보내기"))
}
