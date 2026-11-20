# Mk Player

シンプルなDAWライクな音楽プレーヤー「MK PLAYER（MK-PLYAR）」のモバイル版です。
PC版（PySide6、仕様v0.6）のUIと思想を継承した、ローカル音源専用のAndroidプレーヤー。

- バージョン: **V1.2**
  - V1.2: OS標準メディアコントロールの標準化（開始済みサービス化、メディアボタンからの復帰再生、AudioFocus の自動再開／ダッキング、イヤホン抜きで一時停止、セッション位置の周期同期）と高速化（リスト再描画の削減、アートワーク縮小デコード）

## 特徴

- ライブラリ / プレイヤー / プレイリスト / 設定 の領域分離（タブ）
- ライブラリ検索（曲名・アーティスト・ファイル名）、ファイル／フォルダ単位の追加
- 曲名（タグ、無ければファイル名）＋アーティスト＋再生時間のリスト表示
- Album Art は枠を常時表示し、左テキスト／右アートの本家レイアウト
- シークは 経過／-残り／総時間 の3点表示
- デュアルレベルメーター（Meter A/B 独立、方式 dBFS/Peak・LUFS・Average・RMS・Peak、
  オフセット、バーの応答、連続/セグメントLED、軽量モード）
  - 計測はデコードした PCM の実測。LUFS は K-weighting の Momentary
- プレイリストの保存・読み込み、並べ替え、ライブラリ全削除
- 前回状態の復元（再生曲・位置・音量・メーター設定）
- フォアグラウンドサービスによるバックグラウンド再生と通知コントロール
- MediaSession 連携: 通知領域／クイック設定のメディアプレーヤー、ロック画面、Bluetooth・ヘッドセットボタンから操作可能（アートワーク・シーク対応）
- GEQ / VUメーターは搭載しない（仕様決定）

## 動作環境

- Android 8.0（API 26）以上。　Xperia 1 II実機で動作確認済み
- 対応形式は端末デコーダ依存（MP3 / AAC / M4A / WAV / FLAC / Ogg / Opus 等）

## ビルド

通常のAndroidプロジェクトとして Gradle でビルドできます。

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Gradleデーモンが使えない環境向けに、Android SDK build-tools のチェーンで
等価なデバッグAPKを作る `build-manual.sh` も同梱しています。

GitHub Actions 用ワークフローは `github-workflow-build.yml` に下書きを置いてあります
（`.github/workflows/build.yml` への配置は別途行います）。
タグ `v*` を打つとCIがビルドし、リリースに `MkPlayer-v<版数>.apk` を添付します。

## 仕様

継承元とスマホ向け適応の詳細は [SPEC.md](SPEC.md) を参照。

- パッケージ: `jp.mk.player`
- 外部ライブラリ依存なし（Androidフレームワークのみ）
