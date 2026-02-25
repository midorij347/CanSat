# MyApplicationplp (Spotlight) — アプリケーション全体図

## 概要

Android スマートフォンを使って **ロボット制御 + マルチセンサテレメトリ収集** を行うアプリ。
画面に「北固定グリッド」を表示しながら、IMU・モータエンコーダ・カメラ映像・画面録画を同時収録し、AWSにリアルタイム送信する。

---

## アーキテクチャ全体図

```
┌────────────────────────────────────────────────────────────────────┐
│  Android アプリ (com.example.myapplicationplp)                      │
│                                                                    │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │  TelemetryApp  (Application)                                 │  │
│  │   ├── TimeBase          センサ時刻 → Unix ms 変換            │  │
│  │   └── TelemetryLogger   全テレメトリの統合ロガー             │  │
│  └──────────────────────────────┬──────────────────────────────┘  │
│                                 │ (シングルトン)                   │
│  ┌──────────────────────────────▼──────────────────────────────┐  │
│  │  SpotlightActivity  (メイン UI)                              │  │
│  │   ├── NorthFixedGrid (Jetpack Compose Canvas)                │  │
│  │   │     磁気センサで yaw を取得し北固定グリッドを描画        │  │
│  │   │                                                          │  │
│  │   ├── ImuTelemetryManager      加速度 + 磁気 → RPY ログ     │  │
│  │   ├── EspMotorTelemetryManager USB経由でエンコーダ受信       │  │
│  │   ├── MotorCommandSender       USB経由でDuty指令送信         │  │
│  │   ├── FrontCameraTelemetry     前面カメラ録画 + アップロード │  │
│  │   └── ScreenPatternLogger      表示パターンIDのログ          │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  ScreenCaptureService  (フォアグラウンドサービス)            │  │
│  │   └── ScreenCaptureTelemetry  MediaProjection で画面録画     │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  VideoUploadWorker  (WorkManager)                            │  │
│  │   └── AWS API Gateway → Lambda → S3 presigned PUT           │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
          │ USB CDC-ACM (115200bps)
          ▼
   ESP32-S3 (外部ロボット)
    ├── 左右モータ PWM 制御
    └── エンコーダパルス送信
```

---

## コンポーネント一覧

### Application 層

| クラス | 役割 |
|---|---|
| `TelemetryApp` | `Application` サブクラス。`TimeBase` と `TelemetryLogger` を保持し全コンポーネントで共有する。 |
| `TimeBase` | 起動時の `elapsedRealtimeNanos()` と `System.currentTimeMillis()` を記録。センサタイムスタンプ(ns) を推定 Unix ms に変換する。 |

### UI 層

| クラス | 役割 |
|---|---|
| `SpotlightActivity` | メイン Activity。ARCoreセッション管理、権限要求、各マネージャの起動・停止を担当。Compose で `NorthFixedGrid` を描画する。 |
| `NorthFixedGrid` (composable) | 磁気センサの azimuth を使い、キャンバスを `-yaw` 回転させることで「常に北向きの格子」を画面に描く。 |

### センサ / デバイス層

| クラス | 役割 |
|---|---|
| `ImuTelemetryManager` | `SensorEventListener` として加速度計・磁気センサを購読。`SensorManager.getRotationMatrix` でロール・ピッチ・ヨーを算出し `TelemetryLogger.logImu()` に流す。 |
| `UsbCdcRepo` | Android USB Host API で ESP32-S3 に USB CDC-ACM 接続する低レベルドライバ。Bulk IN/OUT エンドポイントで読み書き。 |
| `EspMotorTelemetryManager` | `UsbCdcRepo` から `"PULSE,<left>,<right>\n"` 形式の行を IO コルーチンで受信し、`logMotorPulse()` に渡す。 |
| `MotorCommandSender` | `"DUTY,<left>,<right>\n"` を USB に書き込み、同時に `logMotorDuty()` でログを残す。 |

### 録画層

| クラス | 役割 |
|---|---|
| `FrontCameraTelemetry` | CameraX `VideoCapture` で前面カメラを録画。完了時に `VideoUploadWorker` をエンキューする。 |
| `ScreenCaptureService` | `mediaProjection` タイプのフォアグラウンドサービス。`MediaProjectionManager` から `MediaProjection` を取得して `ScreenCaptureTelemetry` を開始する。 |
| `ScreenCaptureTelemetry` | `MediaRecorder` + `VirtualDisplay` で画面をMP4録画。停止時に `VideoUploadWorker` をエンキューする。 |
| `ScreenPatternLogger` | スクリーンに表示しているパターン ID が変化したときだけ `logScreenPattern()` を呼ぶ差分ロガー。 |

### クラウド連携層

| クラス | 役割 |
|---|---|
| `TelemetryLogger` | 全テレメトリを `telemetry_<sessionId>.ndjson` に書き出す。50行 or 3秒ごとに gzip 圧縮して API Gateway へ POST する。 |
| `VideoUploadWorker` | WorkManager の `Worker`。API Gateway (Lambda) から S3 presigned URL を取得し、MP4 を PUT アップロードする。 |
| `AwsApiClient` | presigned URL 取得用 API Gateway クライアント (OkHttp)。 |
| `S3Uploader` | presigned URL への PUT ヘルパ (OkHttp)。 |

### 姿勢推定 / 数値計算層

| クラス | 役割 |
|---|---|
| `GridRenderer` | `Intrinsics`（カメラ内部パラメータ）と `Rcw` / `tcw` からホモグラフィを構築し、Android `Canvas` に北基準の格子を投影描画する。 |
| `FusionController` | ARCore の姿勢 + 重力 + 地磁気を `PoseEstimatorWorker` に送り、最新の MAP 推定結果で `GridRenderer` を駆動する。 |
| `PoseEstimatorMAP` | Gauss-Newton 法で SE(3) 上の MAP 推定を解く。ARCore姿勢・重力・地磁気を同時に残差として扱う。 |
| `PoseEstimatorWorker` | `PoseEstimatorMAP` をバックグラウンドスレッドで動かすワーカーラッパー。 |
| `So3.kt` | SO(3) の対数写像 (`logSO3`)・指数写像・行列積など回転群の演算ユーティリティ。 |
| `LinAlg6.kt` | 6×6 Cholesky 分解・後退代入・逆行列などの線形代数ユーティリティ。 |
| `NativeAnalyzer` | JNI 経由で `libimgproc.so` (C++) の `processYuv420()` を呼び出す抽象クラス。 |
| `AnalyzerImpl` | `NativeAnalyzer` の具象実装。CameraX `ImageAnalysis.Analyzer` として使用。 |

---

## データフロー

### テレメトリ (NDJSON) の流れ

```
センサ (IMU / USB PULSE) ──► TelemetryLogger ──► ローカルファイル
                                              └──► API Gateway (gzip POST)
                                                        └──► AWS Lambda
                                                                └──► S3
```

### 動画の流れ

```
前面カメラ  ──► FrontCameraTelemetry ──► MP4 (ExternalFiles)
                                     └──► VideoUploadWorker
                                               └──► API Gateway (presigned URL取得)
                                                         └──► S3 PUT

画面録画   ──► ScreenCaptureService
                └── ScreenCaptureTelemetry ──► MP4 (MediaStore)
                                           └──► VideoUploadWorker ──► S3
```

### モータ制御の流れ

```
SpotlightActivity.onStart()
  └── MotorCommandSender.sendMotorDuty(duty, duty)
        ├── TelemetryLogger.logMotorDuty()   ← ログ
        └── UsbCdcRepo.write("DUTY,x,x\n")  ← ESP32へ送信

ESP32 → UsbCdcRepo.read() → EspMotorTelemetryManager
          → TelemetryLogger.logMotorPulse()  ← エンコーダログ
```

---

## テレメトリの型一覧 (NDJSON `type` フィールド)

| type | 内容 |
|---|---|
| `imu` | 加速度 (ax/ay/az) + roll / pitch / yaw |
| `motor_pulse` | エンコーダパルス (left / right) |
| `motor_duty` | Duty 指令値 (left / right) |
| `screen` | 表示パターン ID |
| `video_start` | 録画開始 (role / uri) |
| `video_end` | 録画終了 (role / uri) |
| `video_frame_feature` | フレーム単位の解析結果 |

---

## 必要なパーミッション

| パーミッション | 用途 |
|---|---|
| `CAMERA` | 前面カメラ録画 |
| `RECORD_AUDIO` | 音声録音 |
| `INTERNET` | API Gateway / S3 アップロード |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 画面録画サービス |
| USB Host feature | ESP32-S3 との USB CDC-ACM 通信 |

---

## 外部システム依存

| 外部システム | 役割 |
|---|---|
| ESP32-S3 | 左右モータ PWM 制御 / エンコーダパルス送信 |
| AWS API Gateway (ap-northeast-1) | テレメトリ NDJSON の受信エンドポイント |
| AWS Lambda (`getPresignedUri`) | 動画アップロード用 S3 presigned URL 発行 |
| AWS S3 | NDJSON / MP4 の永続ストレージ |
| ARCore (optional) | カメラ姿勢推定 (端末非対応時はスキップ) |
