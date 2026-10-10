package android.companion.virtual.computercontrol;

import android.view.SurfaceControl;

public final class InteractiveMirror implements AutoCloseable {
    private InteractiveMirror() {}
    public SurfaceControl getMirrorSurfaceControl() { throw new RuntimeException("Stub"); }
    public void setHostDisplayId(int displayId) { throw new RuntimeException("Stub"); }
    public void setInteractive(boolean interactive) { throw new RuntimeException("Stub"); }
    @Override public void close() { throw new RuntimeException("Stub"); }
}
