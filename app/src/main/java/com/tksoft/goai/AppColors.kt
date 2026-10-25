package com.tksoft.goai

import androidx.compose.ui.graphics.Color

// 形勢グラフの棒の色 (ShogiGUI の senteBarColor / goteBarColor に相当)。
// 白石側を白にするとグラフの背景に溶けるので、薄い灰色にする
val blackBarColor = Color(0xFF202020).copy(alpha = 0.6f)
val whiteBarColor = Color(0xFF9A9A9A).copy(alpha = 0.6f)
