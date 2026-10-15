#include <jni.h>
#include <thread>
#include <system_error>

// A single, read-only platform lookup, restricted to the supplied local ViewRoot.
// Use an attached native thread for the hidden platform signature. Do not install
// VMRuntime exemptions or expose a general reflection/transaction bridge.
extern "C" JNIEXPORT jobject JNICALL
Java_com_lateral_beast_ProtectedHostSurface_read(JNIEnv* env, jobject, jobject root) {
    if (!root) return nullptr;
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return nullptr;
    jobject rootRef = env->NewGlobalRef(root);
    if (!rootRef) return nullptr;
    jobject surfaceRef = nullptr;
    try {
      std::thread worker([&] {
        JNIEnv* workerEnv = nullptr;
        if (vm->AttachCurrentThread(&workerEnv, nullptr) != JNI_OK) return;
        jclass rootClass = workerEnv->FindClass("android/view/ViewRootImpl");
        if (rootClass && workerEnv->IsInstanceOf(rootRef, rootClass)) {
            jmethodID method = workerEnv->GetMethodID(rootClass, "getSurfaceControl",
                                                     "()Landroid/view/SurfaceControl;");
            if (method) {
                jobject surface = workerEnv->CallObjectMethod(rootRef, method);
                if (!workerEnv->ExceptionCheck() && surface) {
                    surfaceRef = workerEnv->NewGlobalRef(surface);
                }
            }
        }
        // An unsupported ROM simply leaves protected presentation unavailable.
        if (workerEnv->ExceptionCheck()) workerEnv->ExceptionClear();
        vm->DetachCurrentThread();
      });
      worker.join();
    } catch (const std::system_error&) {
        env->DeleteGlobalRef(rootRef);
        return nullptr;
    }
    env->DeleteGlobalRef(rootRef);
    jobject result = surfaceRef ? env->NewLocalRef(surfaceRef) : nullptr;
    if (surfaceRef) env->DeleteGlobalRef(surfaceRef);
    return result;
}
