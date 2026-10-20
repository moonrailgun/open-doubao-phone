package com.moonrailgun.opendoubao.assistant.phone;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.AttributionSource;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.List;

/** The same narrowly scoped shell process is started by either ADB or Shizuku. */
public final class ShellDisplayService extends IShellDisplay.Stub {
    private static final String APP = "com.moonrailgun.opendoubao.assistant";
    private final Context context;
    private final int appUid;
    private final HandlerThread frames = new HandlerThread("virtual-display-frames");
    private IBinder owner;
    private VirtualDisplay display;
    private ImageReader reader;

    public ShellDisplayService() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        appUid = context.getPackageManager().getApplicationInfo(APP, 0).uid;
        frames.start();
    }

    // Shizuku 13 prefers this constructor; its supplied Context is not an ordinary application Context.
    public ShellDisplayService(Context ignored) throws Exception { this(); }

    private void checkCaller() {
        if (Binder.getCallingUid() != appUid) throw new SecurityException("Only the assistant may use this bridge");
    }

    @Override public synchronized void attach(IBinder client) throws android.os.RemoteException {
        checkCaller();
        if (owner != null) throw new IllegalStateException("Bridge already attached");
        owner = java.util.Objects.requireNonNull(client, "Missing assistant binder");
        owner.linkToDeath(() -> { releaseInternal(); System.exit(0); }, 0);
    }

    @android.annotation.SuppressLint("WrongConstant") // Shell-only hidden display flags are absent from the public SDK.
    @Override public synchronized int createDisplay() {
        checkCaller();
        if (owner == null || !owner.isBinderAlive()) throw new IllegalStateException("Assistant disconnected");
        if (display != null) throw new IllegalStateException("A virtual display is already running");
        if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("普通系统虚拟屏需要 Android 14 或更新版本");
        long identity = Binder.clearCallingIdentity();
        try {
            // Trusted and own-content-only: arbitrary activities, without mirroring the user's main screen.
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | (1 << 6) | (1 << 7) | (1 << 8) | (1 << 10) | (1 << 11);
            flags |= (1 << 14) | (1 << 16); // Own focus without taking the main screen's keyboard focus.
            reader = ImageReader.newInstance(1080, 1920, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(source -> {
                try (Image image = source.acquireLatestImage()) { /* Drain the display even without a preview. */ }
            }, new Handler(frames.getLooper()));
            display = context.getSystemService(DisplayManager.class).createVirtualDisplay(
                "Open Doubao", 1080, 1920, 320, reader.getSurface(), flags);
            if (display == null) throw new IllegalStateException("系统拒绝创建虚拟屏");
            return display.getDisplay().getDisplayId();
        } catch (RuntimeException e) {
            releaseInternal();
            throw e;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private int displayId() {
        if (display == null || !display.getDisplay().isValid()) throw new IllegalStateException("虚拟屏已断开");
        return display.getDisplay().getDisplayId();
    }

    @Override public synchronized void launch(String packageName, String url) {
        checkCaller();
        if (packageName == null || !packageName.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+"))
            throw new IllegalArgumentException("Invalid package");
        long identity = Binder.clearCallingIdentity();
        try {
            Intent intent;
            if (url == null) {
                intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            } else {
                Uri uri = Uri.parse(url);
                if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                    throw new IllegalArgumentException("Only web URLs are supported");
                intent = new Intent(Intent.ACTION_VIEW, uri).setPackage(packageName);
            }
            if (intent == null) throw new IllegalArgumentException("应用没有可启动入口");
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId());
            Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            int result = (int) Class.forName("android.app.IActivityManager").getMethod("startActivityAsUser",
                Class.forName("android.app.IApplicationThread"), String.class, Intent.class, String.class,
                IBinder.class, String.class, int.class, int.class, Class.forName("android.app.ProfilerInfo"), Bundle.class, int.class)
                .invoke(manager, null, "com.android.shell", intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    null, null, null, 0, 0, null, options.toBundle(), 0);
            if (result < 0) throw new IllegalStateException("系统拒绝启动应用：" + result);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法在虚拟屏启动应用", e);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized void key(int code) {
        checkCaller();
        if (code != KeyEvent.KEYCODE_BACK && code != KeyEvent.KEYCODE_HOME) throw new IllegalArgumentException("Unsupported key");
        long identity = Binder.clearCallingIdentity();
        try {
            Class<?> type = Class.forName("android.hardware.input.InputManager");
            Object manager = type.getMethod("getInstance").invoke(null);
            Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
            Method setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
            long now = SystemClock.uptimeMillis();
            for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                KeyEvent event = new KeyEvent(now, now, action, code, 0);
                setDisplay.invoke(event, displayId());
                if (!Boolean.TRUE.equals(inject.invoke(manager, event, 2)))
                    throw new IllegalStateException("系统拒绝虚拟屏按键");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("系统不支持虚拟屏按键", e);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    @Override public synchronized void releaseDisplay(boolean handOver) {
        checkCaller();
        long identity = Binder.clearCallingIdentity();
        try {
            if (handOver && display != null) {
                Object tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                Class<?> api = Class.forName("android.app.IActivityTaskManager");
                List<?> roots = (List<?>) api.getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(tasks, displayId());
                for (Object root : roots) {
                    int taskId = root.getClass().getField("taskId").getInt(root);
                    api.getMethod("moveRootTaskToDisplay", int.class, int.class).invoke(tasks, taskId, 0);
                }
            }
            releaseInternal();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法将应用转到主屏，请先停止任务", e);
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private synchronized void releaseInternal() {
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
    }

    @Override public void destroy() {
        // Shizuku itself also destroys its user service when unbinding or replacing a version.
        int caller = Binder.getCallingUid();
        if (caller != appUid && caller != 2000 && caller != 0) throw new SecurityException("Only the assistant or shell may stop this bridge");
        releaseInternal();
        frames.quitSafely();
        System.exit(0);
    }

    /** CLASSPATH is the installed APK; no separate privileged APK or root permission is needed. */
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 && Process.myUid() != 0) throw new SecurityException("Start through adb shell");
        if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("普通系统虚拟屏需要 Android 14 或更新版本");
        Looper.prepareMainLooper();
        ShellDisplayService service = new ShellDisplayService();
        Bundle extras = new Bundle();
        extras.putBinder("bridge", service);
        // app_process is not registered as an application process, so acquire the provider as shell.
        String authority = APP + ".virtualdisplay";
        IBinder token = new Binder();
        Class<?> api = Class.forName("android.app.IActivityManager");
        Object manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        Object holder = api.getMethod("getContentProviderExternal", String.class, int.class, IBinder.class, String.class)
            .invoke(manager, authority, 0, token, null);
        if (holder == null) throw new IllegalStateException("请先安装并打开豆闲");
        Bundle reply;
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            reply = (Bundle) Class.forName("android.content.IContentProvider").getMethod("call",
                AttributionSource.class, String.class, String.class, String.class, Bundle.class)
                .invoke(provider, new AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build(),
                    authority, "attach", null, extras);
        } finally {
            api.getMethod("removeContentProviderExternal", String.class, IBinder.class).invoke(manager, authority, token);
        }
        if (reply == null || !reply.getBoolean("accepted")) throw new IllegalStateException("Open the assistant's ADB setup first");
        System.out.println("Open Doubao virtual display bridge connected");
        Looper.loop();
    }
}
