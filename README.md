# GoAI

9路盤の囲碁 AI アプリ (Android / Kotlin / Jetpack Compose)。
AI には [KataGo](https://github.com/lightvector/KataGo) を使い、端末上 (CPU, Eigen バックエンド) だけで動作します。

A free, open source 9x9 Go app for Android. The AI is [KataGo](https://github.com/lightvector/KataGo), running fully on-device.

## 機能
- 9路盤で KataGo と対局 (黒番 / 白番、置石 2〜5 子、日本ルール / 中国ルール、コミ選択)
- AI の強さ 6 段階 (探索数と手の選び方のランダムさで調整)
- 解析: 候補手を盤上と読み筋カードに表示。カードには初手と読んだ先の形勢 (勝率・目数) を出し、
  タップすると読み筋を色付きの分岐として展開し、盤上に手順の番号を付けて表示
- 形勢グラフ付きの樹形図 (分岐)。待ったして別の手を打つと分岐になる
- 待った・パス・投了。終局時は KataGo が死活を判定し、死に石と地を表示
- 棋譜を SGF (変化図付き) で共有・コピー
- 画面構成 (下部のボタン、左上のメニュー、読み筋カード、樹形図) は
  [ShogiGUI](https://github.com/tamaikeiichi/ShogiGUI) に合わせている

## ビルド
Android Studio で開いてビルドするだけです。

- KataGo のネットワーク `kata9x9-b18c384nbt-20231025` (約 98MB) はリポジトリに含めず、
  ビルド時に `downloadKataGoModel` タスクが
  [katagotraining.org](https://katagotraining.org/extra_networks/) から
  `app/src/main/assets/katago_model_9x9.bin.gz.dat` にダウンロードします。
- NDK (CMake 3.22.1) で `app/src/main/cpp` の KataGo をビルドします (arm64-v8a, x86_64)。

## 構成
| パス | 内容 |
|---|---|
| `app/src/main/cpp/KataGo/` | KataGo v1.18.2 の `cpp/` のうち GTP に必要な部分 (MIT) |
| `app/src/main/cpp/eigen/` | Eigen 3.4.0 の MPL2 部分のみ (`EIGEN_MPL2_ONLY`) |
| `app/src/main/cpp/native-lib-katago.cpp` | JNI。cin/cout を差し替えて `MainCmds::gtp()` を同一プロセスで動かす |
| `KataGoEngine.kt` / `GtpClient.kt` | エンジンの起動と GTP のやり取り |
| `GoGame.kt` | 盤面と囲碁のルール (取り・コウ・自殺手・パス) |
| `GameViewModel.kt` | 対局の進行、エンジンとの局面同期 |
| `GoNode.kt` | 棋譜の樹形図 (分岐・形勢) |
| `GoBoardView.kt` | 碁盤の描画 |
| `GoUIComponents.kt` | 読み筋カード・対局者表示・樹形図と形勢グラフ (ShogiGUI から移植) |

## ライセンス
GoAI 自体は [MIT License](LICENSE) です。同梱しているサードパーティのソフトウェアとデータのライセンスは
[THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) (アプリ内「情報」画面にも表示) を参照してください。

| コンポーネント | ライセンス |
|---|---|
| KataGo v1.18.2 | MIT |
| KataGo 9x9 network (kata9x9-b18c384nbt-20231025) | KataGo Neural Network License (MIT 相当) |
| Eigen 3.4.0 | MPL 2.0 |
| TCLAP, ghc::filesystem, half, nlohmann/json | MIT |
| sha2.cpp (Aaron D. Gifford) | BSD-3-Clause |
| AndroidX / Jetpack Compose | Apache 2.0 |

## Privacy
[privacyPolicy.txt](privacyPolicy.txt)
