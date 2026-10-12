package com.zzick.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import com.zzick.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 앱 시작 스플래시: 로고가 튀어나오듯 커졌다가 durationMs 뒤 본 화면으로. 화면 회전 등으로 다시 그려져도 한 번만. */
@Composable
fun SplashGate(durationMs: Long = 1500, content: @Composable () -> Unit) {
    var done by rememberSaveable { mutableStateOf(false) }
    if (done) {
        content()
        return
    }
    val scale = remember { Animatable(0.6f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, tween(250)) }
        launch { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        delay(durationMs)
        done = true
    }
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.solzzick_logo),
            contentDescription = "솔찍 로고",
            modifier = Modifier.fillMaxWidth(0.78f).graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            },
        )
    }
}
