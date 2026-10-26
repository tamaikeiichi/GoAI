package com.tksoft.goai

import androidx.compose.ui.graphics.Color

// 形勢グラフの棒の色 (ShogiGUI の senteBarColor / goteBarColor に相当)。
// 背景は上 (黒有利側) が明るく下 (白有利側) が暗いグラデーションなので、黒の棒は上で、白の棒は下で背景と見分けられる
val blackBarColor = Color(0xFF0D0B0C)
val whiteBarColor = Color(0xFFF4F1DE)

// 形勢グラフの背景: 上から下へのグラデーション
val graphBackgroundTopColor = Color(0xFFF4F1DE)
val graphBackgroundBottomColor = Color(0xFF0D0B0C)

// 形勢グラフのスライダーの、現在の手を示す縦棒 (つまみ) の色と、その外周の線の色
val sliderThumbColor = Color(0xFF6D1020)
val sliderThumbOutlineColor = Color(0xFFF4F1DE)

// 形勢グラフに重ねる目数の折れ線の色と、振り切れる目数
val scoreLineColor = Color(0xFFD90429)
const val MAX_SCORE_LEAD_ON_GRAPH = 20.0
