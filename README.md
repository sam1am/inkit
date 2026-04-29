# InkTouch Demo

A simple Android app demonstrating the [inksdk](https://github.com/imedwei/inksdk) library for low-latency stylus input on e-ink devices (Bigme, Onyx Boox).

## Features

- **Pen/Finger Differentiation**: Uses `MotionEvent.getToolType()` to distinguish between stylus and finger input
- **Toggle Finger Touch**: Enable/disable finger drawing while pen input remains active
- **Low-latency ink**: Leverages the vendor-specific ink controllers for pen input
- **Touch-through buttons**: Control buttons remain tappable even when finger touch is disabled on the canvas

## Building

### Prerequisites

1. **Android SDK** (API level 34 or higher)
2. **Java 17+** (JDK)
3. Set `ANDROID_HOME` environment variable pointing to your SDK

### Setup

```bash
# Clone the repo
git clone <repo-url>
cd inkit

# Set Android SDK path (adjust to your setup)
export ANDROID_HOME=/path/to/android-sdk

# Build debug APK
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

## Usage

1. **Draw with finger** - works when touch is enabled
2. **Draw with stylus** - always works (pen input bypasses finger touch toggle)
3. **Toggle button** - enables/disables finger touch on canvas
4. **Clear button** - wipes the canvas
5. Status indicator shows current touch/pen state

## Project Structure

```
app/
├── src/main/java/com/example/inktouchdemo/
│   ├── MainActivity.kt      # Activity with toggle and clear controls
│   └── InkSurfaceView.kt    # Custom SurfaceView with pen/finger differentiation
├── src/main/res/layout/activity_main.xml
└── build.gradle.kts
inksdk/                       # Embedded inksdk library
├── build.gradle.kts
└── src/main/java/com/inksdk/ink/
    ├── InkController.kt     # Interface for ink controllers
    ├── BigmeInkController.kt
    ├── OnyxInkController.kt
    └── NoopInkController.kt
```

## How it Works

### Input Differentiation

```kotlin
// Check if input is from a stylus/pen
private fun isPenInput(event: MotionEvent): Boolean {
    val toolType = event.getToolType(0)
    return toolType == MotionEvent.TOOL_TYPE_STYLUS || 
           toolType == MotionEvent.TOOL_TYPE_ERASER
}

// Check if input is from a finger
private fun isFingerInput(event: MotionEvent): Boolean {
    val toolType = event.getToolType(0)
    return toolType == MotionEvent.TOOL_TYPE_FINGER || 
           toolType == MotionEvent.TOOL_TYPE_MOUSE
}
```

### Touch Event Handling

1. **Pen input**: Always handled (bypasses touchEnabled flag)
2. **Finger input**: Only handled if `touchEnabled = true`
3. When finger touch is disabled, `onTouchEvent` returns `false`, allowing events to bubble up to parent views (buttons can still be clicked)

## Architecture Notes

- Uses `InkController` interface for vendor abstraction
- Supports Bigme (via `HandwrittenClient`), Onyx (via `TouchHelper`), and fallback (standard `MotionEvent` path)
- Ink is mirrored to a bitmap for compatibility with system UI composes
- Onyx `TouchHelper` owns the surface during raw drawing; Bigme paints to a separate ION buffer

## License

Apache 2.0 (same as inksdk)