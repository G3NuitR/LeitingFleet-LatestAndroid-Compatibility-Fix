# Fleet ServiceConnection Fix (LSPosed module)

Fixes a **stable startup crash** in the Unity game `com.Leiting.Fleet` on **Android 16
QPR (API 36.1)**:

```
FATAL EXCEPTION: main
java.lang.NoSuchMethodError: public default void
  android.content.ServiceConnection.onServiceConnected(
      android.content.ComponentName, android.os.IBinder, android.app.IBinderSession)
    at bitter.jnibridge.JNIBridge.invoke(Native Method)
    at bitter.jnibridge.JNIBridge$a.invoke(Unknown Source:20)
    at java.lang.reflect.Proxy.invoke(Proxy.java:1006)
    at $Proxy2.onServiceConnected(Unknown Source)
    at android.app.LoadedApk$ServiceDispatcher.doConnected(LoadedApk.java:2420)
```

## Root cause

Android 16 QPR added a new **`default`** method to the public `android.content.ServiceConnection`
interface and a backing system AIDL interface `android.app.IBinderSession`:

```java
// new in API 36.1
default void onServiceConnected(ComponentName name, IBinder service, IBinderSession session) {
    onServiceConnected(name, service);   // delegates to the legacy 2-arg form
}
```

`LoadedApk$ServiceDispatcher.doConnected` now invokes this **3-arg** variant when a bound
service connects.

For normal apps this is source- and binary-compatible — the default body forwards to the
2-arg method they override. But it **breaks dynamic-proxy implementations**: Unity implements
`ServiceConnection` via a `java.lang.reflect.Proxy` (`AndroidJavaProxy` / `bitter.jnibridge`).
The proxy intercepts the new default method, routes it to its `InvocationHandler`, which has no
handler registered for that signature, and throws `NoSuchMethodError` on the main thread.

The change is **not gated on `targetSdkVersion`** (the game targets SDK 30 and still crashes),
so any older-Unity app that binds a service crashes on this OS revision. The phone is fine —
only proxy-based `ServiceConnection` implementors (older Unity, some Mono/Xamarin/RPC libs) are
affected.

## What this module does

It hooks the proxy dispatch and rewrites the new 3-arg `onServiceConnected(name, service,
IBinderSession)` call back to the legacy 2-arg `onServiceConnected(name, service)` that Unity's
proxy already handles — so the service-connected callback is delivered normally and the crash
is averted.

- **Primary hook:** `bitter.jnibridge.JNIBridge$a.invoke` (Unity's `InvocationHandler`,
  app-scoped, lowest overhead).
- **Fallback hook:** libcore's `java.lang.reflect.Proxy.invoke`, used only if Unity's internal
  class name ever changes.

See [`HookEntry.java`](app/src/main/java/com/leiting/fleet/lsposedfix/HookEntry.java).

## Requirements

- Rooted device with a Zygisk provider (Magisk / KernelSU / **SukiSU** + ZygiskSU).
- **LSPosed** installed and active.
- Verified on: Pixel 8 Pro (`husky`), Android 16, build `CP1A.260505.005`, SukiSU + LSPosed.

## Build

### Option A — no Gradle (uses installed SDK build-tools + Android Studio JBR)

```bash
./build-local.sh
# -> dist/com.leiting.fleet.lsposedfix.apk
```

The script auto-downloads the Xposed API jar to `libs/` on first run. It expects the Android
SDK at `$ANDROID_HOME` (default `~/Library/Android/sdk`) with `build-tools/36.1.0` and the
`android-36.1` platform, and the Android Studio bundled JDK (override with `JBR_HOME`).

### Option B — Android Studio / Gradle

Open the project in Android Studio and build, or:

```bash
./gradlew assembleDebug
```

(The Gradle build pulls the Xposed API from the `api.xposed.info` maven repo as a `compileOnly`
dependency.)

## Install & enable

```bash
adb install -r dist/com.leiting.fleet.lsposedfix.apk
```

1. Open **LSPosed Manager → Modules**, enable **Fleet ServiceConnection Fix**.
2. Set its **scope** to `com.Leiting.Fleet` (pre-suggested via `xposedscope`).
3. Force-stop and relaunch the game — **no reboot needed** (app-level hook):
   ```bash
   adb shell am force-stop com.Leiting.Fleet
   ```

## Verify

```bash
adb logcat -c
# launch the game, then:
adb logcat -d | grep -E "FleetLSPosedFix|NoSuchMethodError|AndroidRuntime"
```

Expected: a `FleetLSPosedFix: hooked … JNIBridge$a.invoke` line at load, a
`FleetLSPosedFix: rewrote 3-arg onServiceConnected -> 2-arg (crash averted)` line when the
service connects, and **no** `FATAL EXCEPTION` / `NoSuchMethodError`.

## Project layout

```
app/src/main/java/com/leiting/fleet/lsposedfix/HookEntry.java   the hook
app/src/main/AndroidManifest.xml                                Xposed module metadata
app/src/main/assets/xposed_init                                 entry-point declaration
app/src/main/res/values/arrays.xml                             suggested scope
build-local.sh                                                  Gradle-free APK build
settings.gradle / build.gradle / app/build.gradle              Android Studio project
```

## Notes

This is a runtime workaround. The proper fix is on the engine side: the game should be rebuilt
on a newer Unity whose `AndroidJavaProxy` tolerates unknown/new default interface methods. The
underlying Android change is also a legitimate compatibility regression worth reporting upstream
(a new public-interface `default` method breaking `java.lang.reflect.Proxy` implementors).
