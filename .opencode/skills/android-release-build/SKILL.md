---
name: android-release-build
description: Builds the WallHub Android Release APK on the configured Windows LAN build worker, receives the artifact in the LXC, and installs it on the connected ADB device. Use automatically after modifying Android source code, when validating Android changes, or when the user requests a Release APK. Do not run Gradle inside the LXC.
---

# Android Release Build And Device Install

The LXC is the source of truth. The Windows build worker synchronizes the current Android source from the LXC over SMB, runs `:app:assembleRelease`, then returns the APK and log to `/root/builds`.

After completing Android code changes, perform the complete build, artifact, install, and verification workflow below unless the user explicitly requests build only.

## 1. Confirm The ADB Target

Keep an existing ADB server running because restarting it clears wireless-debugging sessions. Check connected devices:

```bash
adb devices -l
```

The configured wireless device is currently `192.168.2.190:39519`. If it is not listed, reconnect it and check again:

```bash
adb connect 192.168.2.190:39519
adb devices -l
```

Use only a row whose state is exactly `device`. If it is `offline`, `unauthorized`, unreachable, or absent after reconnecting, report the ADB blocker. If multiple devices are online, use the user's explicitly selected serial; never install to every device implicitly.

## 2. Request The Remote Release Build

```bash
/usr/local/bin/request-android-release-build --wait
```

Do not invoke `gradlew` directly in the LXC and do not alter the Windows build worker directory.

On failure, inspect the log referenced by that job's status JSON under `/root/builds`, fix the code, and request a new build. Do not continue to installation after a failed build.

## 3. Resolve The Returned Artifact

Use the `artifact` value from the successful job's status JSON and resolve its relative path from `/root`. For example, `builds/wallhub-release-<job-id>.apk` resolves to `/root/builds/wallhub-release-<job-id>.apk`.

Verify that the file exists and is non-empty. Always use the artifact from the current job status; do not select an APK by directory modification time and do not reuse an older APK.

## 4. Install The APK

Install to the single selected device while preserving application data:

```bash
adb -s <serial> install -r /root/builds/wallhub-release-<job-id>.apk
```

Require the final install result to contain `Success`. If installation fails because of a version downgrade, retry with `adb -s <serial> install -r -d <apk>` only when the connected device is a development target. If it fails because of a signing mismatch, do not uninstall the existing application automatically because uninstalling deletes app data; report the conflict to the user.

## 5. Verify The Installed Package

WallHub's application ID is `com.wallhub.android`. Confirm that Android's package manager can resolve it and report the installed version:

```bash
adb -s <serial> shell pm path com.wallhub.android
adb -s <serial> shell dumpsys package com.wallhub.android | rg -m 2 'versionCode=|versionName='
```

The workflow is successful only when the remote build succeeds, the current job's APK is returned, `adb install` succeeds, and package verification succeeds. Report the build job ID, APK path, target serial/model, install result, and installed version to the user.
