# 作業前の確認事項

> クラウドが AWS かどうか：コードを確認済みです。API Gateway + Lambda + S3 (ap-northeast-1) で合っています。

---

## Q1. UIがなくなった後の「アプリの入口」はどうしますか？

現在 `SpotlightActivity` がすべての起動・管理を担っています。
UI を削除すると Activity がなくなりますが、新プロジェクトでは何を使いますか？

- **A) 空の MainActivity だけ残す**（タップして起動、画面は真っ黒でOK）
- **B) サービスのみで動かす**（アプリアイコンからではなく USB 接続などをトリガーに自動起動）
- **C) その他の方法**

---

## Q2. FusionController / PoseEstimatorMAP / GridRenderer は残しますか？

README の「姿勢推定/数値計算層」に含まれますが：

- `GridRenderer` は Canvas への描画コード → UI依存なので削除対象に見えます
- `FusionController` は ARCore セッションに依存しています
- `PoseEstimatorMAP` / `So3.kt` / `LinAlg6.kt` は純粋な数学コードです

これらも全部削除でよいですか？それとも数学ライブラリだけ残しますか？

---

## Q3. ARCore は削除しますか？

カメラ・UI・録画がなくなれば ARCore セッションは使えません。
依存ライブラリ (`com.google.ar:core`) ごと削除してよいですか？

---

## Q4. NativeAnalyzer（C++ JNI）は残しますか？

`NativeAnalyzer` と `libimgproc.so` はカメラ画像の解析用です。
カメラが不要なら削除対象だと思いますが、他の用途で使う予定はありますか？

---

## Q5. ScreenPatternLogger は残しますか？

「今どのパターンを画面に表示しているか」を記録するクラスです。
画面・UIがなくなると記録する対象がなくなりますが、削除でよいですか？

---

## Q6. モータのデューティーテストコードは残しますか？

現在 `onStart()` で -1.0 〜 +1.0 を往復させるテストが走っています。
新プロジェクトでも同じ動作を維持しますか？それとも削除しますか？

---

## Q7. USB接続のトリガーはどうしますか？

現在は `SpotlightActivity.onCreate()` のタイミングで USB デバイスを探して接続しています。
UI がなくなった場合、接続タイミングをどうしたいですか？

- **A) アプリ起動時に自動で探して接続する（現行と同じ）**
- **B) USB ケーブルを挿したタイミングで自動接続する（BroadcastReceiver）**

---

## Q8. 新プロジェクトのパッケージ名は何を使いますか？

コピー先の `tanakanobuaki` プロジェクトの namespace は
`com.example.tanakanobuaki` です。
このまま使いますか？それとも変更しますか？
