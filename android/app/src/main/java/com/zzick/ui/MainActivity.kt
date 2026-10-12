package com.zzick.ui

import android.content.Intent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import com.zzick.ui.quote.QuoteView
import com.zzick.ui.quote.QuoteFeed
import com.zzick.ui.quote.Quote
import com.zzick.R
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import com.zzick.pipeline.BatchProcessor
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import android.os.Build
import android.net.Uri
import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
private const val MAX_BATCH = 20

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuoteFeed.prefetch(lifecycleScope) // 스플래시 동안 첫 명언을 미리 받아둔다
        setContent {
            MaterialTheme {
                // Android 15+는 화면 끝까지 그려지므로 상태바·내비게이션바 영역을 비운다
                Surface(Modifier.fillMaxSize()) { Box(Modifier.safeDrawingPadding()) { SplashGate {
                    val processor = remember { BatchProcessor.get(applicationContext) }
                    var batch by rememberSaveable { mutableStateOf(false) }
                    if (batch) {
                        BackHandler { batch = false }
                        BatchScreen(processor) { batch = false }
                    } else {
                        EditorScreen(viewModel(), processor) { batch = true }
                    }
                } } }
            }
        }
    }

    /** 분석·렌더 중에는 화면이 꺼지지 않게(처리 중단 방지). */
    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@UnstableApi
@Composable
private fun EditorScreen(vm: EditorViewModel, processor: BatchProcessor, openBatch: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val batchQueue by processor.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // 알림 권한(13+)은 진행 상황 표시용 — 거절해도 처리는 계속된다
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        processor.add(pending)
        pending = emptyList()
        openBatch()
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= 33) {
            pending = uris
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            processor.add(uris)
            openBatch()
        }
    }
    val activity = LocalContext.current as MainActivity
    LaunchedEffect(state::class) {
        activity.keepScreenOn(state is EditorState.Scanning || state is EditorState.Analyzing || state is EditorState.Rendering)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onVideoPicked(uri)
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            EditorState.Idle -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(painterResource(R.drawable.solzzick_logo), contentDescription = "솔찍 로고", modifier = Modifier.height(56.dp))
                Spacer(Modifier.weight(1f))
                val quote by produceState<Quote?>(null) { value = QuoteFeed.next() }
                quote?.let { QuoteView(it) }
                Spacer(Modifier.weight(1f))
                Text("고정 촬영한 등반 영상을 누가 들고 찍어준 것처럼", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) { Text("영상 선택") }
                OutlinedButton(onClick = {
                    multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) { Text("여러 영상 일괄 편집") }
                if (batchQueue.items.isNotEmpty()) {
                    TextButton(onClick = openBatch) { Text("일괄 편집 이어서 보기 (${batchQueue.items.size}개)") }
                }
            }
            EditorState.Scanning -> {
                CircularProgressIndicator()
                Text("등반자를 찾는 중…")
                OutlinedButton(onClick = vm::cancel) { Text("취소") }
            }
            is EditorState.Select -> SelectClimber(s, vm)
            is EditorState.Analyzing -> Progress("등반자 추적 중", s.progress, vm::cancel)
            is EditorState.Edit -> EditScreen(s, vm)
            is EditorState.Rendering -> Progress("영상 만드는 중", s.progress, vm::cancel)
            is EditorState.Done -> Done(s, vm)
            is EditorState.Failed -> {
                Text(s.message)
                Button(onClick = vm::reset) { Text("처음으로") }
            }
        }
    }
}

@UnstableApi
@Composable
private fun SelectClimber(s: EditorState.Select, vm: EditorViewModel) {
    Text(if (s.preview.boxes.size > 1) "따라갈 등반자를 탭하세요" else "이 사람을 따라갈게요")
    PersonPicker(s.preview, s.selected, vm::onTap)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = vm::reset) { Text("취소") }
        Button(onClick = vm::onStart, enabled = s.selected != null) { Text("시작") }
    }
}

@Composable
private fun Progress(label: String, progress: Float, onCancel: () -> Unit) {
    Text(label)
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    Text("${(progress * 100).toInt()}%  ·  화면을 켜둔 채 기다려 주세요", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onCancel) { Text("취소") }
}

@UnstableApi
@Composable
private fun ColumnScope.Done(s: EditorState.Done, vm: EditorViewModel) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(s.galleryUri)
                setOnPreparedListener { it.isLooping = true; start() }
            }
        },
        modifier = Modifier.weight(1f).aspectRatio(9f / 16f),
    )
    Text("갤러리(Movies/솔찍)에 저장했어요", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = vm::reset) { Text("새 영상") }
        Button(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, s.galleryUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "공유"))
        }) { Text("공유") }
    }
    Spacer(Modifier.height(8.dp))
}
