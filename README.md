# inkit

A simple, fast note-taking app for Android with an eye toward bullet journaling. inkit is designed to be used with [Bigme](https://bigme.vn/) e-ink devices and built on top of [inksdk](https://github.com/imedwei/inksdk) for low-latency stylus input.

Special thanks to [imedwei/inksdk](https://github.com/imedwei/inksdk) for the underlying low-latency ink library that makes this possible.

## Philosophy

inkit aims to stay out of your way:

- **Simple** — pen, eraser, page nav. Nothing else to learn.
- **Fast** — low-latency stylus input via inksdk's vendor-specific ink controllers.
- **Bullet-journal friendly** — quick page creation and navigation for daily logs, collections, and rapid logging.
- **E-ink first** — designed and tuned for Bigme devices, with fallback support for other Android tablets.

## Features

- **Pen / finger differentiation** — uses `MotionEvent.getToolType()` to distinguish stylus from finger.
- **Toggle finger touch** — disable finger drawing while pen input remains active, so you can rest your hand on the screen.
- **Low-latency ink** — leverages vendor-specific ink controllers for pen input.
- **Touch-through buttons** — control buttons remain tappable even when finger touch is disabled on the canvas.
- **Multi-page canvas** — flip between pages for journaling-style workflows.

## Building

### Prerequisites

1. **Android SDK** (API level 34 or higher)
2. **Java 17+** (JDK)
3. Set `ANDROID_HOME` environment variable pointing to your SDK

### Setup

```bash
git clone <repo-url>
cd inkit

export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

## Usage

1. **Draw with stylus** — always works (pen input bypasses the finger touch toggle).
2. **Draw with finger** — works when touch is enabled.
3. **Toggle button** — enables/disables finger touch on the canvas.
4. **Pen / eraser / clear** — switch tools or wipe the current page.
5. **Prev / next / new** — navigate or add pages.

## Project Structure

```
app/
├── src/main/java/com/merrythieves/inkit/
│   ├── MainActivity.kt      # Activity with toolbar and page controls
│   ├── InkSurfaceView.kt    # Custom SurfaceView with pen/finger differentiation
│   ├── CanvasStore.kt       # Page persistence
│   └── InkitApp.kt          # Application class
├── src/main/res/layout/activity_main.xml
└── build.gradle.kts
inksdk/                       # Embedded inksdk library (https://github.com/imedwei/inksdk)
└── src/main/java/com/inksdk/ink/
    ├── InkController.kt     # Interface for ink controllers
    ├── BigmeInkController.kt
    ├── OnyxInkController.kt
    └── NoopInkController.kt
```

## How it Works

### Input Differentiation

```kotlin
private fun isPenInput(event: MotionEvent): Boolean {
    val toolType = event.getToolType(0)
    return toolType == MotionEvent.TOOL_TYPE_STYLUS ||
           toolType == MotionEvent.TOOL_TYPE_ERASER
}

private fun isFingerInput(event: MotionEvent): Boolean {
    val toolType = event.getToolType(0)
    return toolType == MotionEvent.TOOL_TYPE_FINGER ||
           toolType == MotionEvent.TOOL_TYPE_MOUSE
}
```

### Touch Event Handling

1. **Pen input** — always handled (bypasses the touchEnabled flag).
2. **Finger input** — only handled if `touchEnabled = true`.
3. When finger touch is disabled, `onTouchEvent` returns `false`, allowing events to bubble up to parent views (buttons can still be clicked).

## Architecture Notes

- Uses the `InkController` interface for vendor abstraction.
- Supports Bigme (via `HandwrittenClient`), Onyx (via `TouchHelper`), and a fallback (standard `MotionEvent` path).
- Ink is mirrored to a bitmap for compatibility with system UI composes.
- Onyx `TouchHelper` owns the surface during raw drawing; Bigme paints to a separate ION buffer.

## Acknowledgements

Huge thanks to [@imedwei](https://github.com/imedwei) and the [inksdk](https://github.com/imedwei/inksdk) project — inkit would not exist without it.

## License

Apache 2.0 (same as inksdk)
