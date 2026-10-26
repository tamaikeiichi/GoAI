package com.tksoft.goai

import androidx.compose.ui.graphics.Color

// 形勢グラフの棒の色 (ShogiGUI の senteBarColor / goteBarColor に相当)。
// グラフの背景 (graphBackgroundColor) はグレーなので、白はほぼ白にしても見分けられる。
// グラフ側で、中央のグレー (barMidColor) から上の端の黒・下の端の白へのグラデーションにする
val blackBarColor = Color(0xFF111111)
val whiteBarColor = Color(0xFFFFFFFF)
val barMidColor = Color(0xFF888888)

// 形勢グラフの背景 (グレー)
val graphBackgroundColor = Color(0xFF808080)

// 形勢グラフのスライダーの、現在の手を示す縦棒 (つまみ) の色
val sliderThumbColor = Color(0xFFFF6161)
val sliderThumbOutlineColor = Color(0xFFD63B3B)

// 形勢グラフに重ねる目数の折れ線の色と、振り切れる目数
val scoreLineColor = Color(0xFFD32F2F)
const val MAX_SCORE_LEAD_ON_GRAPH = 20.0
