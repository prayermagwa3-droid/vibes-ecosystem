# Vibes Vyron X — Android build

## Requirements

- JDK 17 or newer (JDK 21 is suitable)
- Android SDK platform API 37
- Android Build Tools 36.0.0 or newer
- Gradle 9.6.1 compatible with Android Gradle Plugin 9.4.0
- Internet access for the initial Google Maven and Maven Central dependency resolution

This project uses the Android Gradle Plugin's built-in Kotlin support and does not apply `org.jetbrains.kotlin.android` separately.

## Build commands

From this directory:

```bash
gradle :app:assembleDebug
```

To run local JVM unit tests:

```bash
gradle :app:testDebugUnitTest
```

The checked-in `gradlew` is a lightweight Termux-friendly launcher that delegates to an installed `gradle` executable. This archive does not contain `gradle-wrapper.jar`; therefore the launcher does not bootstrap a Gradle distribution by itself. Install Gradle 9.6.1 or newer and ensure it is on `PATH`, or open the project in an Android Studio environment configured with a compatible Gradle installation.

## APK output

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Project identity

- Display name: **Vibes Vyron X**
- Android application ID: `com.vibes.only`
- Version name: `19.3`
- Version code: `26`
- Minimum Android: API 26 (Android 8.0)
- Compile/target Android API: 37

## Notes about verification

The source package has not been assembled into an APK in the current editing environment because it does not contain an Android SDK or an installed Gradle 9.6.1 distribution. Source-level structural checks were performed, but these are not substitutes for `gradle :app:assembleDebug`, `gradle :app:testDebugUnitTest`, and an on-device smoke test. Do not treat the new WebView bridge or server session route as device-verified until those tests pass.
