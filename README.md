# WARP CAM

音楽に写真を壊させるAndroid向けの音反応カメラです。

撮影時にスマホ内で再生されている音を解析し、低音・中域・高音・瞬間的なピーク・全体強度に応じて、歪み、ねじれ、RGBずれ、色、明るさ、彩度、グリッチ量を変化させます。

## 実装

- CameraX 1.6.2 のカメラプレビュー / 静止画撮影
- Android Audio Playback Capture によるスマホ内部音声取得
- 2048-point FFT + RMS / transient解析
- 音連動のワープ、ねじれ、RGB分離、色・明るさ・彩度変化
- Sensitivity 5%〜200%
- 前面 / 背面カメラ切替
- `Pictures/WarpCam` へのJPEG保存
- GitHub Releasesを使ったアプリ内アップデート
- 永続ログ + SAFログ書き出し。空ログでも診断ヘッダを書き込むため0Bになりません
- mainでは毎回署名済みRelease APKをActions artifactとして生成
- タグ `v*` で同じ署名のRelease APKをGitHub Releaseへ公開

## 内部音声

Android 10（API 29）以降が対象です。内部音声を開始するとAndroid標準のMediaProjection許可画面が出ます。キャプチャ元アプリがAudio Playback Captureを禁止している場合、その音は取得できません。

## Release署名

個人利用向けに、固定のRelease署名鍵をリポジトリ内へBase64形式で保持しています。GitHub Secretsの初期設定は不要です。

`main` へpushするとActionsが同じ鍵で署名したRelease APKを生成します。

`versionCode` / `versionName` を更新して `v0.1.1` のようなタグをpushすると、Actionsが署名済み `warp-cam-release.apk` をGitHub Releaseへ公開します。アプリの「アップデート確認」はこのReleaseを検出してAPKを取得します。

この方式は個人利用の手軽さを優先しています。公開リポジトリに署名材料が含まれるため、配布用・商用アプリでは使用しないでください。
