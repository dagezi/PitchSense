# PitchSense

An Android app for practicing intonation by comparing pitch contours. Load or record a few
utterances (say, a native speaker's and your own), and PitchSense draws their pitch (F0) on one
graph so you can see where your intonation differs.

Pitch extraction is language-independent, so it works for English intonation, Mandarin tones,
Japanese pitch accent, and so on. Everything runs on the device; no audio leaves it.

[日本語](#日本語)

## Features

- Add up to four tracks from audio files, the microphone, or audio played by other apps
  (Android 10+). Audio shared from other apps opens directly.
- Pitch contours overlaid in one graph, in Hz or in semitones relative to each track's median.
- Phone transcription in IPA above the graph, using [ZIPA](https://github.com/lingjzhu/zipa)
  (optional; see below).
- Pinch to zoom and drag to scroll. Drag a contour to shift its track in time, or tap **Align** to
  line up speech onsets.
- Play a track with a cursor following the graph. Long-press and drag to select a range and play
  only that part.
- Recordings are saved to shared storage.

## Building

Requires Android Studio (or the Android SDK) and a device running Android 8.0 (API 26) or later.

```sh
./gradlew installDebug
```

Only `arm64-v8a` is packaged, to keep ONNX Runtime's native libraries small.

### Phone recognition model

The ZIPA model (about 310 MB) is not bundled. Without it, pitch still works and tracks show
"No phones". To enable phone transcription, get the int8 ONNX export of ZIPA large CR-CTC and push
it to the app's files directory:

```sh
adb push model.int8.onnx \
  /sdcard/Android/data/io.github.dagezi.pitchsense/files/zipa-large-crctc-500k.int8.onnx
```

## License

The source code is released under the [MIT License](LICENSE).

[app/src/main/assets/zipa_tokens.txt](app/src/main/assets/zipa_tokens.txt) is the token list of
[ZIPA](https://github.com/lingjzhu/zipa) by Jian Zhu et al., licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The ZIPA model, if you install it, is
under the same license.

---

## 日本語

発音・イントネーション練習のための、ピッチ曲線比較 Android アプリです。お手本と自分の発話などを
読み込み・録音すると、それぞれのピッチ（基本周波数 F0）を同じグラフに重ねて表示し、違いを目で
確認できます。

ピッチ抽出は言語に依存しないので、英語のイントネーション、中国語の声調、日本語の高低アクセントなどに
使えます。処理はすべて端末内で行い、音声を外部に送信しません。

### 機能

- 音声ファイル、マイク、他アプリの再生音（Android 10 以上）から最大 4 トラックを追加。
  他アプリからの共有でも開けます。
- ピッチ曲線を 1 つのグラフに重ねて表示。縦軸は Hz と半音（各トラックの中央値基準）を切替可能。
- [ZIPA](https://github.com/lingjzhu/zipa) による IPA 音素表記をグラフ上部に表示（任意、下記参照）。
- ピンチでズーム、ドラッグでスクロール。曲線をドラッグしてトラックの時間をずらせるほか、
  **Align** で発話の開始位置を揃えられます。
- トラックを再生すると、グラフ上のカーソルが再生位置に追従します。長押しからドラッグで区間を
  選択すると、その部分だけを再生します。
- 録音は共有ストレージに保存されます。

### ビルド

Android Studio（または Android SDK）と、Android 8.0（API 26）以上の端末が必要です。

```sh
./gradlew installDebug
```

ONNX Runtime のネイティブライブラリを小さく保つため、`arm64-v8a` のみをパッケージしています。

### 音素認識モデル

ZIPA のモデル（約 310 MB）は同梱していません。モデルがなくてもピッチ表示は動作し、トラックには
「No phones」と表示されます。音素表記を使うには、ZIPA large CR-CTC の int8 ONNX 版を入手し、
アプリのファイルディレクトリに置いてください。

```sh
adb push model.int8.onnx \
  /sdcard/Android/data/io.github.dagezi.pitchsense/files/zipa-large-crctc-500k.int8.onnx
```

### ライセンス

ソースコードは [MIT License](LICENSE) で公開しています。

[app/src/main/assets/zipa_tokens.txt](app/src/main/assets/zipa_tokens.txt) は Jian Zhu らによる
[ZIPA](https://github.com/lingjzhu/zipa) のトークン一覧で、
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) でライセンスされています。インストールする
ZIPA モデルも同じライセンスです。
