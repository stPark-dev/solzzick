package com.zzick.ui.quote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zzick.R
import com.zzick.ui.Brand

/** 나눔손글씨 붓(OFL) — 명언용 필기체 */
val BrushScript = FontFamily(Font(R.font.nanum_brush_script))

/** 명언 + 줄 바꿔 "-말한 사람-" */
@Composable
fun QuoteView(quote: Quote, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            quote.message,
            fontFamily = BrushScript,
            fontSize = 34.sp,
            lineHeight = 42.sp,
            textAlign = TextAlign.Center,
            color = Brand.Ink,
        )
        Text("-${quote.author}-", fontFamily = BrushScript, fontSize = 26.sp, color = MaterialTheme.colorScheme.primary)
    }
}
