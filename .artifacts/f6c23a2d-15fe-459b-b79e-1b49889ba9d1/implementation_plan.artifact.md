# Implementation Plan - Split Screen App with Logcat Speedometer and Virtual Display

Create an Android application that features a resizable split-screen UI. The left side displays real-time vehicle speed parsed from system logs (logcat), and the right side hosts a virtual display rendered onto a `SurfaceView`.

## User Review Required

> [!IMPORTANT]
> The `READ_LOGS` permission and `DisplayManager.createVirtualDisplay` often require system-level privileges or specific signatures (Platform Key). Ensure the app is signed with the platform key or installed as a system app for full functionality.

> [!NOTE]
> The Logcat TAG for speed is currently set to a placeholder `VehicleSpeed`. Please confirm if there's a specific TAG I should use.

## Proposed Changes

### UI Layout

#### [MODIFY] [activity_main.xml](file:///C:/Users/JQK/AndroidStudioProjects/miao/app/src/main/res/layout/activity_main.xml)
- Replace the default layout with a `ConstraintLayout` containing:
    - `left_panel` (FrameLayout): Occupies the left portion.
    - `divider` (View): A thin vertical bar for dragging.
    - `right_panel` (SurfaceView): Occupies the right portion.
- Add a `TextView` inside the left panel to display the speed.

### Logic Implementation

#### [MODIFY] [MainActivity.kt](file:///C:/Users/JQK/AndroidStudioProjects/miao/app/src/main/java/com/example/miao/MainActivity.kt)
- **UI Resizing:** Implement `OnTouchListener` for the `divider` to update the horizontal bias or width of the panels.
- **Logcat Listener:**
    - Implement a background thread that executes `logcat -s VehicleSpeed`.
    - Parse the incoming stream for speed values.
    - Update the UI on the main thread.
- **Virtual Display:**
    - Implement `SurfaceHolder.Callback` for the `SurfaceView`.
    - In `surfaceCreated`, use `DisplayManager.createVirtualDisplay` to create a virtual display using the provided `Surface`.

### Manifest & Permissions

#### [MODIFY] [AndroidManifest.xml](file:///C:/Users/JQK/AndroidStudioProjects/miao/app/src/main/AndroidManifest.xml)
- Add `<uses-permission android:name="android.permission.READ_LOGS" />`.

## Verification Plan

### Automated Tests
- Build the project using `./gradlew assembleDebug` to ensure no syntax errors.

### Manual Verification
- **UI:** Verify the divider can be dragged to resize panels.
- **Logcat:** Check if speed updates when logs with the specified TAG are generated.
- **Virtual Display:** Verify that the `SurfaceView` initializes correctly (black screen initially, as no app is launched into it yet).
