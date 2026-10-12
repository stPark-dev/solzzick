package com.zzick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 앱 아이콘(solzzick.png)·로고(solzzick_logo.png)에서 뽑은 색. */
object Brand {
    val Orange = Color(0xFFFF7A1A)
    val Sunny = Color(0xFFFFC233)
    val HoldRed = Color(0xFFE8402C)
    val HoldBlue = Color(0xFF1E74F0)
    val Viewfinder = Color(0xFF2EC8FF)
    val Cream = Color(0xFFFFF7EA)
    val Peach = Color(0xFFFFE6C4)
    val Ink = Color(0xFF2B1D14)
    val InkSoft = Color(0xFF7A6252)
    val Success = Color(0xFF1FA463)

    /** 로고 "솔" 글자처럼 노랑 → 주황 → 빨강 */
    val Sunset = Brush.horizontalGradient(listOf(Sunny, Orange, HoldRed))
    val Ocean = Brush.horizontalGradient(listOf(Viewfinder, HoldBlue))
}

private val SolzzickColors = lightColorScheme(
    primary = Brand.Orange,
    onPrimary = Color.White,
    primaryContainer = Brand.Peach,
    onPrimaryContainer = Brand.Ink,
    secondary = Brand.HoldBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBFF),
    onSecondaryContainer = Color(0xFF0B3A80),
    tertiary = Brand.Viewfinder,
    onTertiary = Brand.Ink,
    error = Brand.HoldRed,
    background = Brand.Cream,
    onBackground = Brand.Ink,
    surface = Brand.Cream,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.Peach,
    onSurfaceVariant = Brand.InkSoft,
    surfaceContainerHighest = Color(0xFFFFE9CC),
    outline = Color(0xFFF2B98A),
    outlineVariant = Color(0xFFF6D9BC),
)

private val SolzzickShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val SolzzickType = base.copy(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp),
)

@Composable
fun SolzzickTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SolzzickColors, shapes = SolzzickShapes, typography = SolzzickType, content = content)
}

/** 크림색 벽에 아이콘처럼 따뜻한 빛이 번진 배경. */
fun Modifier.brandBackground() = drawBehind {
    drawRect(Brand.Cream)
    drawCircle(
        Brush.radialGradient(listOf(Brand.Sunny.copy(alpha = 0.35f), Color.Transparent), Offset(size.width, 0f), size.maxDimension * 0.7f),
        radius = size.maxDimension * 0.7f, center = Offset(size.width, 0f),
    )
    drawCircle(
        Brush.radialGradient(listOf(Brand.Orange.copy(alpha = 0.18f), Color.Transparent), Offset(0f, size.height), size.maxDimension * 0.6f),
        radius = size.maxDimension * 0.6f, center = Offset(0f, size.height),
    )
}

/** 아이콘의 네온 뷰파인더 모서리(┌ ┐ └ ┘). 등반자를 잡은 화면 테두리에 쓴다. */
fun DrawScope.viewfinderCorners(topLeft: Offset, size: Size, color: Color = Brand.Viewfinder, stroke: Float = 6f) {
    val arm = minOf(size.width, size.height) * 0.28f
    val r = topLeft + Offset(size.width, 0f)
    val b = topLeft + Offset(0f, size.height)
    val br = topLeft + Offset(size.width, size.height)
    val glow = color.copy(alpha = 0.35f)
    for ((c, w) in listOf(glow to stroke * 2.6f, color to stroke)) {
        drawLine(c, topLeft, topLeft + Offset(arm, 0f), w, StrokeCap.Round)
        drawLine(c, topLeft, topLeft + Offset(0f, arm), w, StrokeCap.Round)
        drawLine(c, r, r - Offset(arm, 0f), w, StrokeCap.Round)
        drawLine(c, r, r + Offset(0f, arm), w, StrokeCap.Round)
        drawLine(c, b, b + Offset(arm, 0f), w, StrokeCap.Round)
        drawLine(c, b, b - Offset(0f, arm), w, StrokeCap.Round)
        drawLine(c, br, br - Offset(arm, 0f), w, StrokeCap.Round)
        drawLine(c, br, br - Offset(0f, arm), w, StrokeCap.Round)
    }
}

/** 노랑→빨강 그라데이션 알약 버튼(주요 동작). */
@Composable
fun SunsetButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    brush: Brush = Brand.Sunset,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
            contentColor = Color.White, disabledContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(if (enabled) 8.dp else 0.dp, shape, ambientColor = Brand.Orange, spotColor = Brand.Orange)
            .background(brush, shape),
        content = content,
    )
}

/** 테두리만 있는 보조 알약 버튼. */
@Composable
fun PillOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Brand.Orange, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        border = BorderStroke(2.dp, color),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = 0.7f), contentColor = color),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        content = content,
    )
}

/** 굵고 둥근 진행 막대(채워지는 부분은 노랑→빨강). */
@Composable
fun SunsetProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Brand.Peach)) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(height)
                .clip(RoundedCornerShape(50)).background(Brand.Sunset),
        )
    }
}

/** 흰 카드(살짝 떠 있는 느낌). */
@Composable
fun BrandCard(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .shadow(6.dp, shape, ambientColor = Brand.Orange.copy(alpha = 0.3f), spotColor = Brand.Orange.copy(alpha = 0.3f))
            .background(Color.White, shape)
            .clip(shape)
            .drawBehind {
                if (accent != null) drawRoundRect(accent, size = Size(6.dp.toPx(), size.height), cornerRadius = CornerRadius(0f))
            },
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/** 작은 상태 배지. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
