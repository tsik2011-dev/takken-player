# takken-player

宅建「スマートまとめレジュメ」の読み上げプレーヤー（個人学習用のPWA）。

- ホーム画面にインストールでき、全体・章・項目ごとの連続再生、バックグラウンド再生、オフライン再生に対応
- 本文と音声（`pack/`）は AES-GCM で暗号化されており、合言葉を知っている端末でしか開けません
- 音声は Open JTalk（HTS voice "Mei"、CC BY 3.0 / 名古屋工業大学）による合成音声です

## Android アプリ版（`takken-player.apk`）

アプリを閉じても、アプリ一覧から消しても再生が続く Android 専用アプリ（宅建まとめ用）。

- 画面は上の Web 版をそのまま表示し、音声の再生だけを Android のフォアグラウンドサービス（MediaPlayer + MediaSession）で行う
- 通知・ロック画面・Bluetooth イヤホンから操作でき、音声は端末に保存されるのでオフラインでも再生できる
- ソースは `android/`。`main` に push すると GitHub Actions がビルドし、`takken-player.apk` を更新する
- Web 側からは `window.TakkenNative`（`configure` / `unlock` / `setQueue` / `play` / `toggle` / `seek` / `getState` など）で操作する

## スマートテキスト 読み上げ（`smart/`）

宅建「スマートテキスト2026」（民法等・宅建業法・法令上の制限ほか全37章）の読み上げプレーヤー（個人学習用のPWA）。

- `smart/` を開いてホーム画面に追加すると、アプリとして使えます
- 全体・部・章・節ごとの連続再生、要点（これでヒッタツ！）だけ／ミニテストだけの再生、バックグラウンド再生、オフライン再生に対応
- 本文と音声（`smart/a/`）は AES-GCM で暗号化されており、合言葉を知っている端末でしか開けません
- 音声は VOICEVOX（VOICEVOX:No.7）による合成音声です
