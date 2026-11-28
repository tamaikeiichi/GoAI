package com.tksoft.goai

import androidx.compose.ui.graphics.Color

// 形勢グラフの棒の色 (ShogiGUI の senteBarColor / goteBarColor に相当)。
// 背景は上 (黒有利側) が明るく下 (白有利側) が暗いグラデーションなので、黒の棒は上で、白の棒は下で背景と見分けられる
val blackBarColor = Color(0xFF0D0B0C)
val whiteBarColor = Color(0xFFF4F1DE)

// 形勢グラフの背景: 上から下へのグラデーション
val graphBackgroundTopColor = Color(0xFFF4F1DE)
val graphBackgroundBottomColor = Color(0xFF24171A)

// 形勢グラフのスライダーの、現在の手を示す縦棒 (つまみ) の色と、その外周の線の色
val sliderThumbColor = Color(0xFFFF4D4D)
val sliderThumbOutlineColor = Color(0xFFF4F1DE)
// 形勢グラフのスライダーの、各手の位置を示すドットの塗りと外周の線の色
val sliderDotColor = Color(0xFF24171A)
val sliderDotOutlineColor = Color(0xFFF4F1DE)

// 形勢グラフに重ねる目数の折れ線の色
val scoreLineColor = Color(0xFFD90429)

/** 形勢グラフで目数の折れ線が振り切れる目数。盤が小さいほど1目の重みが大きいので、小さくする */
fun maxScoreLeadOnGraph(boardSize: Int): Double = when {
    boardSize <= 9 -> 10.0
    boardSize <= 13 -> 20.0
    else -> 30.0
}

/**
 * 目数を形勢グラフの高さ (-1〜1、上が黒のリード) にする。
 * 小さな差も見えるよう対数で縮め (ln(1 + 目数) / ln(1 + 振り切れる目数))、振り切れる目数で 1 にする。
 * 9路なら 1目で 3割、5目で 7割半、19路なら 1目で 2割、5目で 5割、10目で 7割
 */
fun scoreLeadToGraph(lead: Double, boardSize: Int): Float {
    val max = maxScoreLeadOnGraph(boardSize)
    val v = kotlin.math.ln(1 + kotlin.math.abs(lead)) / kotlin.math.ln(1 + max)
    return (kotlin.math.sign(lead) * v).toFloat().coerceIn(-1f, 1f)
}
