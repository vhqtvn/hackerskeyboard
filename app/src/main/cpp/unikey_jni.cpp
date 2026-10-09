// JNI bridge to the bundled Unikey engine (ukengine/, GPL-2.0+, Pham Kim Long).
// Exposes the C API from ukengine/unikey.h to vn.vhn.pckeyboard.thor.Unikey.
//
// The engine is a global singleton (matches upstream design) and is driven from
// the app's UI thread only, so no locking is needed. Output is XUTF8 == UTF-8.
#include <jni.h>
#include <string.h>
#include "ukengine/unikey.h"
#include "ukengine/vnconv.h"

extern "C" {

JNIEXPORT void JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeSetup(JNIEnv*, jclass) {
    UnikeySetup();
    UnikeySetOutputCharset(CONV_CHARSET_XUTF8);
    UnikeySetInputMethod(UkTelex);
}

JNIEXPORT void JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeSetInputMethod(JNIEnv*, jclass, jint im) {
    UnikeySetInputMethod((UkInputMethod) im);
}

JNIEXPORT void JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeReset(JNIEnv*, jclass) {
    UnikeyResetBuf();
}

JNIEXPORT void JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeSetCaps(JNIEnv*, jclass, jint shift, jint caps) {
    UnikeySetCapsState(shift, caps);
}

JNIEXPORT jint JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeFilter(JNIEnv*, jclass, jint ch) {
    UnikeyFilter((unsigned int) ch);
    return UnikeyBackspaces;
}

JNIEXPORT jint JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeBackspace(JNIEnv*, jclass) {
    UnikeyBackspacePress();
    return UnikeyBackspaces;
}

JNIEXPORT jint JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeRestore(JNIEnv*, jclass) {
    UnikeyRestoreKeyStrokes();
    return UnikeyBackspaces;
}

JNIEXPORT void JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativePutChar(JNIEnv*, jclass, jint ch) {
    UnikeyPutChar((unsigned int) ch);
}

JNIEXPORT jboolean JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeAtWordBeginning(JNIEnv*, jclass) {
    return UnikeyAtWordBeginning() ? JNI_TRUE : JNI_FALSE;
}

// The UTF-8 output bytes produced by the last filter/backspace/restore call.
JNIEXPORT jstring JNICALL
Java_vn_vhn_pckeyboard_Unikey_nativeOutput(JNIEnv* env, jclass) {
    int n = UnikeyBufChars;
    if (n <= 0) return env->NewStringUTF("");
    char tmp[1200];
    if (n > (int) sizeof(tmp) - 1) n = sizeof(tmp) - 1;
    memcpy(tmp, UnikeyBuf, n);
    tmp[n] = 0;
    return env->NewStringUTF(tmp);
}

} // extern "C"
