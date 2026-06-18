package com.leiting.fleet.lsposedfix;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Work around an Android 16 QPR (API 36.1) source-compatible-but-proxy-breaking change.
 *
 * <p>{@code android.content.ServiceConnection} gained a new <b>default</b> method
 * <pre>onServiceConnected(ComponentName, IBinder, android.app.IBinderSession)</pre>
 * and {@code LoadedApk$ServiceDispatcher.doConnected} now invokes that 3-arg variant.
 *
 * <p>Apps that implement {@code ServiceConnection} through a {@link java.lang.reflect.Proxy}
 * (Unity's {@code AndroidJavaProxy} / {@code bitter.jnibridge}) never run the new default
 * body; the proxy routes the new method to its {@code InvocationHandler}, which has no
 * handler registered for it and throws {@code java.lang.NoSuchMethodError}, crashing the
 * main thread on startup:
 *
 * <pre>
 * FATAL EXCEPTION: main
 * java.lang.NoSuchMethodError: public default void
 *   android.content.ServiceConnection.onServiceConnected(
 *       android.content.ComponentName, android.os.IBinder, android.app.IBinderSession)
 *     at bitter.jnibridge.JNIBridge.invoke(Native Method)
 *     at bitter.jnibridge.JNIBridge$a.invoke(Unknown Source:20)
 *     at java.lang.reflect.Proxy.invoke(Proxy.java:1006)
 *     at $Proxy2.onServiceConnected(Unknown Source)
 *     at android.app.LoadedApk$ServiceDispatcher.doConnected(LoadedApk.java:2420)
 * </pre>
 *
 * <p>This module intercepts the proxy dispatch and rewrites the 3-arg call back to the
 * legacy 2-arg {@code onServiceConnected(name, service)} that the app's proxy already
 * knows how to handle, so the callback is delivered normally and the crash is averted.
 * No game APIs are referenced reflectively beyond stock framework classes, so it compiles
 * against any recent SDK and is forward/backward compatible with the engine.
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "FleetLSPosedFix";
    private static final String TARGET_PKG = "com.Leiting.Fleet";

    /** Unity's proxy InvocationHandler, as seen in the crash stack (not obfuscated). */
    private static final String UNITY_HANDLER = "bitter.jnibridge.JNIBridge$a";

    /** Cached canonical 2-arg method we redirect the call to. */
    private Method legacyTwoArg;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        try {
            legacyTwoArg = ServiceConnection.class.getMethod(
                    "onServiceConnected", ComponentName.class, IBinder.class);
        } catch (NoSuchMethodException e) {
            XposedBridge.log(TAG + ": FATAL - legacy 2-arg onServiceConnected missing: " + e);
            return;
        }

        if (!hookUnityHandler(lpparam)) {
            // Fall back to a process-wide hook on the proxy dispatch path so the fix still
            // works even if Unity's internal handler class name ever changes.
            hookGenericProxyDispatch();
        }
    }

    /** Surgical, low-overhead hook: rewrite args inside Unity's InvocationHandler.invoke. */
    private boolean hookUnityHandler(LoadPackageParam lpparam) {
        Class<?> handler = XposedHelpers.findClassIfExists(UNITY_HANDLER, lpparam.classLoader);
        if (handler == null) {
            XposedBridge.log(TAG + ": " + UNITY_HANDLER + " not found; using generic fallback");
            return false;
        }
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(handler, "invoke", rewriteHook);
        XposedBridge.log(TAG + ": hooked " + unhooks.size() + " " + UNITY_HANDLER + ".invoke method(s)");
        return !unhooks.isEmpty();
    }

    /** Generic fallback: libcore dispatches every proxy call through Proxy.invoke(proxy, method, args). */
    private void hookGenericProxyDispatch() {
        try {
            Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(java.lang.reflect.Proxy.class, "invoke", rewriteHook);
            XposedBridge.log(TAG + ": installed generic Proxy.invoke hook (" + unhooks.size() + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": generic Proxy hook failed: " + t);
        }
    }

    /**
     * Shared rewrite: when the dispatched method is the new 3-arg
     * {@code onServiceConnected(ComponentName, IBinder, IBinderSession)}, swap it for the
     * 2-arg method and drop the session argument before the original dispatch runs.
     */
    private final XC_MethodHook rewriteHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            final Object[] a = param.args;

            // Locate the Method arg and the Object[] call-args arg regardless of position,
            // so this works for both InvocationHandler.invoke and Proxy.invoke shapes.
            Method method = null;
            int methodIdx = -1, argsIdx = -1;
            for (int i = 0; i < a.length; i++) {
                if (method == null && a[i] instanceof Method) {
                    method = (Method) a[i];
                    methodIdx = i;
                } else if (argsIdx == -1 && a[i] instanceof Object[]) {
                    argsIdx = i;
                }
            }
            if (method == null || argsIdx == -1) {
                return;
            }
            if (!"onServiceConnected".equals(method.getName())) {
                return;
            }

            final Class<?>[] pt = method.getParameterTypes();
            if (pt.length != 3 || pt[0] != ComponentName.class || pt[1] != IBinder.class) {
                return; // only the new 3-arg (ComponentName, IBinder, IBinderSession) overload
            }

            final Object[] callArgs = (Object[]) a[argsIdx];
            if (callArgs == null || callArgs.length < 2) {
                return;
            }

            a[methodIdx] = legacyTwoArg;                                 // dispatch the 2-arg method
            a[argsIdx] = new Object[] { callArgs[0], callArgs[1] };       // drop IBinderSession
            XposedBridge.log(TAG + ": rewrote 3-arg onServiceConnected -> 2-arg (crash averted)");
        }
    };
}
