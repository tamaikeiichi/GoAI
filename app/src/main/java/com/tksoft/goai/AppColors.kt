package com.tksoft.goai

import androidx.compose.ui.graphics.Color

// 形勢グラフの棒の色 (ShogiGUI の senteBarColor / goteBarColor に相当)。
// グラフの背景は淡いベージュ (surfaceContainerHighest) なので、白はほぼ白にしても見分けられる。
// 濃さ (透明度) はグラフ側で、中央から上下の端へのグラデーションにする
val blackBarColor = Color(0xFF111111)
val whiteBarColor = Color(0xFFFFFFFF)

// 形勢グラフに重ねる目数の折れ線の色と、振り切れる目数
val scoreLineColor = Color(0xFFD32F2F)
const val MAX_SCORE_LEAD_ON_GRAPH = 20.0
