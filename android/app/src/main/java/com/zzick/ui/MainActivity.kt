package com.zzick.ui

import android.content.Intent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import com.zzick.ui.quote.QuoteView
import com.zzick.ui.quote.QuoteFeed
import com.zzick.ui.quote.Quote
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.produceState
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
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi

@UnstableApi
private const val MAX_BATCH = 20

class MainActivity : ComponentActivity() {
    /** 공유로 받은 영상(처리하면 비운다). 화면 회전 등으로 다시 만들어질 때는 다시 처리하지 않는다. */
    private var shared by mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuoteFeed.prefetch(lifecycleScope) // 스플래시 동안 첫 명언을 미리 받아둔다
        if (savedInstanceState == null) shared = SharedVideos.from(intent, contentResolver::getType).take(MAX_BATCH)
        setContent {
            SolzzickTheme {
                // Android 15+는 화면 끝까지 그려지므로 상태바·내비게이션바 영역을 비운다(배경은 끝까지)
                Surface(Modifier.fillMaxSize().brandBackground(), color = Color.Transparent) { Box(Modifier.safeDrawingPadding()) { SplashGate {
                    val processor = remember { BatchProcessor.get(applicationContext) }
                    var batch by rememberSaveable { mutableStateOf(false) }
                    var batchJob by rememberSaveable { mutableStateOf<Int?>(null) } // 열어 둔 작업(null이면 작업 목록)
                    val editor: EditorViewModel = viewModel()
                    LaunchedEffect(shared) {
                        when (shared.size) {
                            0 -> return@LaunchedEffect
                            1 -> { batch = false; editor.onVideoPicked(shared[0]) }
                            else -> processor.add(shared)?.let { batchJob = it; batch = true }
                        }
                        shared = emptyList()
                    }
                    if (batch) {
                        BackHandler { if (batchJob != null) batchJob = null else batch = false }
                        BatchScreen(processor, batchJob, onOpenJob = { batchJob = it }) { batch = false }
                    } else {
                        EditorScreen(editor, processor) { job -> batchJob = job; batch = true }
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
private fun EditorScreen(vm: EditorViewModel, processor: BatchProcessor, openBatch: (job: Int?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val batchQueue by processor.state.collectAsStateWithLifecycle()
    // 알림 권한 대화상자 동안 화면이 다시 만들어져도 고른 영상을 잃지 않게(잃으면 빈 작업이 생겼다)
    var pending by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // 알림 권한(13+)은 진행 상황 표시용 — 거절해도 처리는 계속된다
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val job = processor.add(pending.map(Uri::parse))
        pending = emptyList()
        job?.let(openBatch)
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (Build.VERSION.SDK_INT >= 33) {
            pending = uris.map(Uri::toString)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            processor.add(uris)?.let(openBatch)
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BouncyLogo(Modifier.height(96.dp))
                Spacer(Modifier.weight(1f))
                val quote by produceState<Quote?>(null) { value = QuoteFeed.next() }
                quote?.let {
                    BrandCard(Modifier.fillMaxWidth()) { QuoteView(it, Modifier.padding(horizontal = 20.dp, vertical = 28.dp)) }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "고정 촬영한 등반 영상을\n누가 들고 찍어준 것처럼!",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                SunsetButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("영상 선택", fontSize = 18.sp)
                }
                PillOutlinedButton(onClick = {
                    multiPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }, modifier = Modifier.fillMaxWidth(), color = Brand.HoldBlue) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("여러 영상 일괄 편집")
                }
                if (batchQueue.items.isNotEmpty()) {
                    TextButton(onClick = { openBatch(null) }) { Text("일괄 편집 작업 보기 (작업 ${batchQueue.jobs.size}개)", color = Brand.HoldBlue) }
                }
            }
            EditorState.Scanning -> {
                BouncyLogo(Modifier.height(80.dp))
                CircularProgressIndicator(color = Brand.Orange, trackColor = Brand.Peach, strokeWidth = 6.dp, strokeCap = StrokeCap.Round)
                Text("등반자를 찾는 중…", style = MaterialTheme.typography.titleMedium)
                PillOutlinedButton(onClick = vm::cancel) { Text("취소") }
            }
            is EditorState.Select -> SelectClimber(s, vm)
            is EditorState.Analyzing -> Progress("등반자 추적 중", s.progress, vm::cancel)
            is EditorState.Edit -> EditScreen(s, vm)
            is EditorState.Rendering -> Progress("영상 만드는 중", s.progress, vm::cancel)
            is EditorState.Done -> Done(s, vm)
            is EditorState.Failed -> {
                Text("앗, 미끄러졌어요!", style = MaterialTheme.typography.titleLarge, color = Brand.HoldRed)
                Text(s.message, textAlign = TextAlign.Center, color = Brand.InkSoft)
                SunsetButton(onClick = vm::reset) { Text("처음으로") }
            }
        }
    }
}

@UnstableApi
@Composable
private fun SelectClimber(s: EditorState.Select, vm: EditorViewModel) {
    Text(if (s.preview.boxes.size > 1) "따라갈 등반자를 탭하세요" else "이 사람을 따라갈게요", style = MaterialTheme.typography.titleLarge)
    PersonPicker(s.preview, s.selected, vm::onTap)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillOutlinedButton(onClick = vm::reset) { Text("취소") }
        SunsetButton(onClick = vm::onStart, enabled = s.selected != null) { Text("시작!") }
    }
}

@Composable
private fun Progress(label: String, progress: Float, onCancel: () -> Unit) {
    BouncyLogo(Modifier.height(80.dp))
    Text(label, style = MaterialTheme.typography.titleLarge)
    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Brand.Orange)
    SunsetProgress(progress)
    Text("화면을 켜둔 채 기다려 주세요", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    PillOutlinedButton(onClick = onCancel) { Text("취소") }
}

@UnstableApi
@Composable
private fun ColumnScope.Done(s: EditorState.Done, vm: EditorViewModel) {
    val context = LocalContext.current
    val player = remember(s.galleryUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(s.galleryUri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            play()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        // 홈으로 나가거나 공유 창이 뜨면 소리와 함께 멈춤
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = false } },
        update = { it.player = player },
        modifier = Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.large),
    )
    PlayerSeekBar(player)
    Text("완등! 🎉", style = MaterialTheme.typography.headlineSmall, color = Brand.Orange)
    Text("갤러리(Movies/솔찍)에 저장했어요", style = MaterialTheme.typography.bodySmall, color = Brand.InkSoft)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillOutlinedButton(onClick = vm::reset) { Text("새 영상") }
        SunsetButton(brush = Brand.Ocean, onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, s.galleryUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "공유"))
        }) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("공유")
        }
    }
    Spacer(Modifier.height(8.dp))
}
