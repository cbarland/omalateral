package android.window;

import android.graphics.Matrix;
import android.util.Pair;
import android.util.Size;
import android.view.InputWindowHandle;

/** Compile-only Android 15 platform signatures. Never include in an APK. */
public abstract class WindowInfosListener {
    public WindowInfosListener() { throw new UnsupportedOperationException("Signature stub"); }
    public abstract void onWindowInfosChanged(InputWindowHandle[] windows, DisplayInfo[] displays);
    public Pair<InputWindowHandle[], DisplayInfo[]> register() { throw new UnsupportedOperationException(); }
    public void unregister() { throw new UnsupportedOperationException(); }
    public static final class DisplayInfo {
        public int mDisplayId;
        public Size mLogicalSize;
        public Matrix mTransform;
    }
}
