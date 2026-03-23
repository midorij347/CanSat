# CanSat

Androidスマートフォンを中心に、**CanSat / 小型ローバー向けの制御・計測・記録・クラウド連携**をまとめて扱う実験プロジェクトです。  
このリポジトリには、Androidアプリ本体だけでなく、構造化光による地形推定の研究メモ・論文草稿も含まれています。

## 概要

このプロジェクトの中心は、Androidアプリ **Spotlight** です。  
スマートフォンをローバー側の統合端末として使い、以下をまとめて扱う構成になっています。

- IMU（加速度・磁気）による姿勢取得
- USB CDC-ACM 経由での ESP32-S3 との通信
- モータ指令送信とエンコーダ系テレメトリ受信
- 前面カメラ録画
- 画面録画
- 取得データのログ化と AWS へのアップロード
- 北固定グリッド表示を使った実験 UI
- NDK / C++ による画像処理系の土台

さらに `thesis/` 配下には、**スマートフォン単体の構造化光による地形センシング**と、**Topology-Constrained 2D-DTW** を用いたグリッド対応付けに関する論文草稿・図・PDF が含まれています。

---

## このプロジェクトでやろうとしていること

CanSat や小型ローバーでは、軽量・低コスト・省電力な構成で、姿勢・走行・地形の情報をできるだけ多く集める必要があります。  
このリポジトリでは、そのために **スマートフォンをセンサハブ兼 UI 兼記録装置兼クラウド連携端末として使う** 方向で開発が進められています。

特に研究面では、スマートフォンの**画面に高コントラストなグリッドを表示**し、**フロントカメラでその変形を撮影**することで、地面の凹凸や局所的な傾きを推定する構想がまとめられています。

---

## 主な機能

### 1. ローバー制御とセンサ取得

- `UsbCdcRepo` により Android USB Host API で ESP32-S3 と接続
- `MotorCommandSender` により `DUTY,<left>,<right>` 形式でモータ指令を送信
- `EspMotorTelemetryManager` により `PULSE,<left>,<right>` 形式のテレメトリを受信
- `ImuTelemetryManager` により加速度計・磁気センサからロール・ピッチ・ヨーを算出

### 2. 実験用の可視化 UI

- `SpotlightActivity` がメイン画面
- Jetpack Compose の Canvas 描画で **北固定グリッド** を表示
- 磁気センサの方位に応じて描画系を回転し、常に北基準の見え方を保つ構成

### 3. 録画・ログ化

- `FrontCameraTelemetry` による前面カメラ録画
- `ScreenCaptureService` / `ScreenCaptureTelemetry` による画面録画
- `TelemetryLogger` によりテレメトリを `ndjson` ベースで統合ログ化

### 4. クラウド連携

- `VideoUploadWorker` を使って録画ファイルをバックグラウンド送信
- `AwsApiClient` と `S3Uploader` を通じて、API Gateway / Lambda / S3 を前提にしたアップロード系の構成

### 5. 画像処理・推定の基盤

- `app/src/main/cpp/` に NDK / C++ の処理系を配置
- `imgproc` ライブラリとして `native_analyzer.cpp` と `filter_min.cpp` をビルド
- Kotlin 側には `NativeAnalyzer.kt`、`PoseEstimator.kt`、`FusionController.kt` などのクラスがあり、センサ融合や推定処理へつながる構成になっています

---

## リポジトリ構成

```text
CanSat/
├─ app/                         # Androidアプリ本体
│  └─ src/main/
│     ├─ java/com/example/myapplicationplp/
│     │  ├─ SpotlightActivity.kt
│     │  ├─ ImuTelemetryManager.kt
│     │  ├─ UsbCdcRepo.kt
│     │  ├─ EspMotorTelemetryManager.kt
│     │  ├─ MotorCommandSender.kt
│     │  ├─ FrontCameraTelemetry.kt
│     │  ├─ ScreenCaptureService.kt
│     │  ├─ ScreenCaptureTelemetry.kt
│     │  ├─ TelemetryLogger.kt
│     │  ├─ AwsApiClient.kt
│     │  ├─ S3Uploader.kt
│     │  ├─ PoseEstimator.kt
│     │  └─ ...
│     ├─ cpp/                   # NDK / C++ 画像処理
│     ├─ res/
│     └─ AndroidManifest.xml
├─ thesis/                      # 構造化光・2D-DTW 関連の論文草稿と資料
├─ gradle/
├─ build.gradle.kts
└─ settings.gradle.kts
```

---

## 技術スタック

### Android

- Kotlin
- Jetpack Compose
- Android ViewBinding
- CameraX
- WorkManager
- OkHttp
- ARCore
- Android USB Host API
- MediaProjection / MediaRecorder

### Native

- C++17
- CMake
- Android NDK

### Cloud / Backend 想定

- AWS API Gateway
- AWS Lambda
- Amazon S3
- SageMaker（論文草稿・研究構想側）

---

## 研究要素

`thesis/README.md` と `thesis/eccv2022submission_rewriten.tex` では、以下の方向性が示されています。

- スマートフォン単体での構造化光による地形センシング
- 画面上に表示した矩形グリッドの変形を前面カメラで観測
- グリッド交点対応付けのための **Topology-Constrained 2D-DTW**
- 可視光のみ・専用 IR センサ不要の低コスト構成
- CanSat / rover の地形計測・GPS 非依存ナビゲーションへの応用

つまりこのリポジトリは、
**「ローバーを動かすためのアプリ」** と  
**「スマホだけで地形を測る研究」** が同居しているのが特徴です。

---

## 必要環境

最低限、以下の前提が必要です。

- Android Studio
- Android SDK 36
- minSdk 26 以上の Android 端末
- ARCore 対応端末
- USB Host 対応端末
- ESP32-S3 など USB CDC-ACM で通信できる外部デバイス
- AWS 側の API / バケット / 認証情報などの整備

---

## セットアップ

### 1. クローン

```bash
git clone https://github.com/midorij347/CanSat.git
cd CanSat
```

### 2. Android Studio で開く

Gradle Sync を実行します。

### 3. SDK / NDK を確認

このプロジェクトは CMake / NDK を使うため、Android Studio 側で以下を導入してください。

- Android SDK 36
- CMake 3.22.1
- Android NDK

### 4. 実機要件を確認

実行には、用途に応じて次が必要です。

- カメラ権限
- 録音権限
- USB 接続
- MediaProjection の許可
- ARCore 利用可能な実機

### 5. AWS 側設定

録画アップロード系を使う場合は、`AwsApiClient` / `VideoUploadWorker` / `S3Uploader` に対応する API Gateway / Lambda / S3 側の設定が必要です。  
このリポジトリ単体では、クラウド側の完全なデプロイ手順までは含まれていません。

---

## AndroidManifest からわかる要件

このアプリでは以下を利用しています。

- `CAMERA`
- `INTERNET`
- `RECORD_AUDIO`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- `android.hardware.usb.host`
- `android.hardware.camera.ar`
- `ScreenCaptureService`
- USB device attached の intent filter

そのため、**ただのエミュレータ実行より、実機検証前提のアプリ**です。

---

## 現状の見どころ

このプロジェクトの強みは、単に CanSat の制御アプリというだけでなく、

1. **スマホをローバーの統合端末として使おうとしていること**  
2. **センサ・映像・制御・クラウド送信を一つの Android アプリにまとめていること**  
3. **構造化光 + 2D-DTW という研究テーマまでつながっていること**

にあります。

アプリ実装・実験基盤・研究草稿が同じリポジトリに入っているため、**開発ログ兼研究プロトタイプ**として見ると非常に面白い構成です。

---

## 今後 README に追加すると良さそうなもの

このリポジトリを公開ポートフォリオとしてさらに強くするなら、次を追加すると伝わりやすくなります。

- 実機写真（スマホ搭載状態、ESP32-S3、ローバー外観）
- システム構成図
- 画面スクリーンショット
- 通信プロトコル仕様（`DUTY`, `PULSE` など）
- AWS 構成図
- 実験結果（地形推定例、ログ例、動画リンク）
- どこまで完成していて、どこが研究中かの整理

---
