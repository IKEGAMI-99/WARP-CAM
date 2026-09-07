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
- タグ `v*` で署名済みRelease APKを生成してGitHub Releaseへ公開

## 内部音声

Android 10（API 29）以降が対象です。内部音声を開始するとAndroid標準のMediaProjection許可画面が出ます。キャプチャ元アプリがAudio Playback Captureを禁止している場合、その音は取得できません。

## Release署名の初回設定

署名鍵は公開リポジトリへコミットしません。更新インストールを成立させるには、以後すべて同じ鍵で署名します。

```bash
export WARP_STORE_PASSWORD='任意の強いパスワード'
export WARP_KEY_PASSWORD='任意の強いパスワード'
./scripts/create-signing-key.sh
```

GitHubの `Settings > Secrets and variables > Actions` に以下を登録します。

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS` (`warpcam`)
- `SIGNING_KEY_PASSWORD`

`versionCode` / `versionName` を更新して `v0.1.1` のようなタグをpushすると、Actionsが署名済み `warp-cam-release.apk` をReleaseへ公開します。アプリの「アップデート確認」はこのReleaseを検出してAPKを取得します。

Release鍵を失うと既存アプリへ更新できません。鍵が漏れると第三者が正規更新として通るAPKを作れるため、`.jks` は公開リポジトリへ置かないでください。
