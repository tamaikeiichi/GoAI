# GoAI

囲碁 AI アプリ (Android / Kotlin / Jetpack Compose)。9路・13路・19路盤に対応。
AI には [KataGo](https://github.com/lightvector/KataGo) を使い、端末上 (CPU, Eigen バックエンド) だけで動作します。

A free, open source Go app for Android (9x9, 13x13, 19x19). The AI is
[KataGo](https://github.com/lightvector/KataGo), running fully on-device. The UI is available in English and Japanese.

## 機能
画面構成 (下部のボタン、左上のメニュー、読み筋カード、形勢グラフ付きの樹形図、過去の棋譜) は
[ShogiGUI](https://github.com/tamaikeiichi/ShogiGUI) に合わせています。

- 検討: 黒白どちらの石も自由に置ける。メニューの「黒番/白番を持って対局」で、今の局面から KataGo と対局
- 解析: 候補手を盤上と読み筋カードに表示。カードには初手と読んだ先の形勢 (勝率・目数) を出し、
  タップすると読み筋を色付きの分岐として盤上に番号付きで展開 (解析は続き、カードは更新され続ける。
  もう一度タップすると盤上の読み筋も更新)。石が取られた後に同じ点へ打った手は、新聞の棋譜と同じく盤の外に書く
- この局面から全て解析: 本譜の最後まで1手ずつ決まった時間 (設定のスライダー) だけ解析して形勢グラフを作る
- 形勢グラフ付きの樹形図 (分岐)。本譜ボタンで本譜に戻る
- 過去の棋譜: 着手のたびに自動保存。検索・削除 (24時間はゴミ箱から戻せる)。
  SGF を ZIP にまとめてエクスポート / インポート。クリップボードの SGF の読み込み、SGF の共有・コピー
- 設定: 盤の大きさ、石の置き方 (タップ / 指の上に半透明の石を出してドラッグ)、AI の強さ 6 段階、
  日本ルール / 中国ルール、置石、コミ、全て解析の思考時間
- 終局時は KataGo が死活を判定し、死に石と地を表示
- 碁盤は実物と同じく少し縦長。座標は盤の外の欄に、上辺が数字・左辺がアルファベット (左上が 1A)
- 折りたたみスマホを広げたとき・タブレットを横長にしたとき (幅 840dp 以上) は、盤と読み筋カードを左右に並べる

## ビルド
Android Studio で開いてビルドするだけです。

- KataGo のネットワークはリポジトリに含めず、ビルド時に `downloadKataGoModel` タスクが
  `app/src/main/assets/` にダウンロードします。
  - 9路盤: `kata9x9-b18c384nbt-20231025` (約 98MB, [katagotraining.org](https://katagotraining.org/extra_networks/))
  - 13路・19路: `g170e-b10c128-s1141046784-d204142634` (約 14MB, [KataGo v1.3](https://github.com/lightvector/KataGo/releases/tag/v1.3))
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
| `KifuHistory.kt` | 過去の棋譜の保存・ZIP のエクスポート/インポート (ShogiGUI から移植) |
| `Sgf.kt` / `SgfParser.kt` | SGF の書き出し・読み込み |
| `GoBoardView.kt` | 碁盤の描画 |
| `GoUIComponents.kt` | 読み筋カード・対局者表示・樹形図と形勢グラフ (ShogiGUI から移植) |

## ライセンス
GoAI 自体は [MIT License](LICENSE) です。同梱しているサードパーティのソフトウェアとデータのライセンスは
[THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) (アプリ内「情報」画面にも表示) を参照してください。

| コンポーネント | ライセンス |
|---|---|
| KataGo v1.18.2 | MIT |
| KataGo 9x9 network (kata9x9-b18c384nbt-20231025) | KataGo Neural Network License (MIT 相当) |
| KataGo network g170e-b10c128 (13路・19路) | CC0 1.0 |
| Eigen 3.4.0 | MPL 2.0 |
| TCLAP, ghc::filesystem, half, nlohmann/json | MIT |
| sha2.cpp (Aaron D. Gifford) | BSD-3-Clause |
| AndroidX / Jetpack Compose, Material Symbols | Apache 2.0 |

## Privacy
[privacyPolicy.txt](privacyPolicy.txt)
