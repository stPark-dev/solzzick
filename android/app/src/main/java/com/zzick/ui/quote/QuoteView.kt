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

/** 고운바탕(OFL) — 명언용 명조(사용자 선택: 더 세련된 글꼴) */
val QuoteSerif = FontFamily(Font(R.font.gowun_batang))

/** 명언 + 줄 바꿔 "-말한 사람-" */
@Composable
fun QuoteView(quote: Quote, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            quote.message,
            fontFamily = QuoteSerif,
            fontSize = 22.sp,
            lineHeight = 34.sp,
            textAlign = TextAlign.Center,
            color = Brand.Ink,
        )
        Text("-${quote.author}-", fontFamily = QuoteSerif, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
    }
}
