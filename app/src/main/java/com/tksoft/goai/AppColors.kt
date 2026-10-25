package com.tksoft.goai

import androidx.compose.ui.graphics.Color

// 形勢グラフの棒の色 (ShogiGUI の senteBarColor / goteBarColor に相当)。
// グラフの背景は淡いベージュ (surfaceContainerHighest) なので、白はほぼ白にしても見分けられる
val blackBarColor = Color(0xFF111111).copy(alpha = 0.85f)
val whiteBarColor = Color(0xFFFFFFFF).copy(alpha = 0.95f)
