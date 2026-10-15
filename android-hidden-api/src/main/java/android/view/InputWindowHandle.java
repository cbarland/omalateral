package android.view;

import android.graphics.Matrix;
import android.graphics.Region;
import android.os.IBinder;

/** Compile-only fields read by the shell helper; no replacement platform implementation. */
public final class InputWindowHandle {
    public IBinder token;
    public String name;
    public int displayId;
    public int ownerUid;
    public int inputConfig;
    public int layoutParamsFlags;
    public Matrix transform;
    public Region touchableRegion;
}
