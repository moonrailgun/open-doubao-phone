package android.companion.virtual.computercontrol;

import android.content.Intent;
import android.content.IntentSender;
import android.media.Image;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.util.Size;
import android.view.accessibility.AccessibilityWindowInfo;
import com.android.internal.os.IResultReceiver;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

/** Signatures and constants copied from the CP3A.260905.010 framework.jar. */
public final class ComputerControlSession implements AutoCloseable {
    public static final int ACTION_GO_BACK = 1;
    public static final int BLOCK_REASON_UNKNOWN = 0;
    public static final int BLOCK_REASON_SECURE_CONTENT = 1;
    public static final int BLOCK_REASON_DISALLOWED_ACTIVITY_LAUNCH = 2;
    public static final int BLOCK_REASON_CALLER_INITIATED = 3;
    public static final int BLOCK_REASON_AUTHENTICATION_PROMPT_REQUESTED = 4;
    public static final int BLOCK_REASON_SUSPENDED = 5;
    public static final int CLOSE_REASON_UNKNOWN = 0;
    public static final int CLOSE_REASON_CALLER_INITIATED = 1;
    public static final int CLOSE_REASON_USER_INITIATED = 2;
    public static final int CLOSE_REASON_SESSION_TIMED_OUT = 3;
    public static final int CLOSE_REASON_SESSION_EMPTY = 4;
    public static final int ERROR_UNKNOWN = 0;
    public static final int ERROR_SESSION_LIMIT_REACHED = 1;
    public static final int ERROR_DEVICE_LOCKED = 2;
    public static final int ERROR_PERMISSION_DENIED = 3;

    private ComputerControlSession() {}

    public List<AccessibilityWindowInfo> getAccessibilityWindows() { throw new RuntimeException("Stub"); }
    public Size getDisplaySize() { throw new RuntimeException("Stub"); }
    public void requestScreenshot(Executor executor, OutcomeReceiver<Image, ScreenshotException> receiver, CancellationSignal cancel) { throw new RuntimeException("Stub"); }
    public void tap(int x, int y, CancellationSignal cancel, Executor executor, OutcomeReceiver<Void, InteractionException> receiver) { throw new RuntimeException("Stub"); }
    public void swipe(int fromX, int fromY, int toX, int toY, CancellationSignal cancel, Executor executor, OutcomeReceiver<Void, InteractionException> receiver) { throw new RuntimeException("Stub"); }
    public void performAction(int action, CancellationSignal cancel, Executor executor, OutcomeReceiver<Void, InteractionException> receiver) { throw new RuntimeException("Stub"); }
    public void launchApplication(String packageName) { throw new RuntimeException("Stub"); }
    public void startActivity(Intent intent, Bundle options) { throw new RuntimeException("Stub"); }
    public void addPackagesForAutomation(List<String> packageNames, Executor executor, TargetPackagesAdditionCallback callback) { throw new RuntimeException("Stub"); }
    public void setLifecycleCallback(Executor executor, LifecycleCallback callback) { throw new RuntimeException("Stub"); }
    public void setStabilityListener(Duration timeout, Executor executor, StabilityListener listener) { throw new RuntimeException("Stub"); }
    public InteractiveMirror createInteractiveMirror(IResultReceiver receiver) { throw new RuntimeException("Stub"); }
    public boolean requestResume() { throw new RuntimeException("Stub"); }
    public void handOverApplications() { throw new RuntimeException("Stub"); }
    @Override public void close() { throw new RuntimeException("Stub"); }

    public interface Callback {
        void onSessionPending(IntentSender intentSender);
        void onSessionCreated(ComputerControlSession session);
        void onSessionCreationFailed(int errorCode);
        void onSessionClosed();
    }

    public interface LifecycleCallback {
        void onActive();
        void onBlocked(int reason, String message);
        void onClosed(int reason);
    }

    public interface StabilityListener {
        void onSessionStable();
        default void onSessionUnstable(int reason) {}
    }

    public interface TargetPackagesAdditionCallback {
        void onTargetPackagesAdditionPending(IntentSender intentSender);
        void onTargetPackagesAdditionSuccess();
        void onTargetPackagesAdditionFailure(int errorCode);
    }

    public static class InteractionException extends Exception {
        public InteractionException(int errorCode) {}
        public int getErrorCode() { throw new RuntimeException("Stub"); }
    }

    public static class ScreenshotException extends Exception {
        public static final int ERROR_SCREEN_UNCHANGED = 7;
        public ScreenshotException(int errorCode) {}
        public int getErrorCode() { throw new RuntimeException("Stub"); }
    }
}
