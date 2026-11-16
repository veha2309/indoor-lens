# Indoor Lens

Indoor Lens is a standalone Android demo for offline door and indoor object
detection and sign reading. Point the rear camera at a doorway or sign to see
object boxes and text, with no setup or manual labels in the app.
Tap **Read aloud** to hear the current results with an installed offline English
voice. It announces door numbers and any attached sign text. Tap **Pause
camera** to stop scanning.

The app can detect doors, stairs, ramps, chairs, tables, couches, cabinets,
refrigerators, windows, and poles. OCR reads Latin-script text from the same
frame. Doors are numbered from left to right in the camera view. When sign
text clearly matches a door, the app outlines the sign and shows its text
next to the door number. Unmatched or ambiguous text appears under “Text in view.”
When the view stays steady, a door box can remain visible for one missed scan
as a dimmed result. Read aloud describes it as last seen and reads the current
text separately.

## Build and install

Use JDK 17 or 21 and Android SDK 36. The demo runs on Android 8 or later with a
rear camera and an ARM64 or x86-64 processor.

From the repository root, copy the navigation checkpoint described below to
`app/src/main/assets/indoor_detector.onnx` before building. The model weights
are kept outside this source repository. A build without the file can still
read signs but cannot detect objects.

```powershell
New-Item -ItemType Directory -Force app/src/main/assets | Out-Null
Copy-Item 'C:\path\to\best.onnx' app/src/main/assets/indoor_detector.onnx
# Optional. Use the separate 320 px door checkpoint described under Model and phone load.
Copy-Item 'C:\path\to\door\best.onnx' app/src/main/assets/door_detector.onnx
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Use `app-x86_64-debug.apk` for an x86-64 Android emulator. Launch **Indoor Lens**
and allow camera access. Both models ship in the APK;
there is no first-run model download, and frames aren't saved or uploaded.
The application ID is `org.vicentric.indoorlens`, so it installs alongside
Vision Helper.

## Model and phone load

The demo reuses the project's existing 12-class YOLO11s navigation model
(`vision_assistant/training_runs/yolo11s_navigation_finetune/weights/best.onnx`).
The optional 320 px YOLO11n checkpoint
(`vision_assistant/runs/detect/training_runs/door_yolo11n/weights/best.onnx`)
can look for narrow door panels when the main model finds fewer than two
doors. Its extra detections appear as “Possible door.” The extra pass is
skipped in Battery Saver or when Android reports moderate thermal throttling.

Door checkpoint SHA-256:
`6b605ee59abc0619b8f3cf0203e6040082c0a5a6b08ccea2fb94bfcebb446fd7`.

It keeps the original 640 px input to preserve small door details. CameraX
displays a continuous preview while one worker analyzes the latest frame with
two ONNX Runtime CPU threads. Object boxes can appear before text recognition
finishes on the same frame. The scanner aims for 700 ms between scan starts,
with at least 300 ms of idle time after each scan and more after slow scans.
Battery Saver or moderate thermal throttling gives the phone 2.4 seconds of
idle time after each scan. Pausing or leaving the app shuts down the camera and releases the
inference session once the current frame finishes.

Debug builds log time spent preparing each frame, detecting objects, and
reading text under the `IndoorLensLatency` logcat tag.

A 56-image desktop check from the existing navigation test split (36 images
with labeled doors, 20 without) found 33 of 39 labeled door boxes with 7 unmatched
predictions using the selected model. The faster 320 px door-only checkpoint
found 2 with 7 unmatched predictions. Both used a 0.40 score threshold and a
0.50 IoU match. Median model-only inference was about 242 ms versus 38 ms on the
desktop. This is a small comparison on the same images, and the smaller model
was trained for a different dataset. It does not measure phone performance.

On the demo handset, try a plain door, a room sign, two adjacent doors, a glass
door, a staircase, and a text-only poster. Check that the overlay stays aligned
and that Pause clears the results. Run for 10 minutes on the intended handset
to check camera responsiveness, heat, and battery use before relying on the
demo setup.

Detector SHA-256:
`95c8bd6d504dfdd77fee71919eadb0e3e7362c00c1b3780760c5cf43975d4acb`.
The export identifies its upstream license as
[Ultralytics AGPL-3.0](https://www.ultralytics.com/license). Text recognition
uses [bundled Google ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).
