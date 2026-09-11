#include "common.h"

extern "C" {
    void skiko_initCallbacks(
        KOpaquePointer callBoolean,
        KOpaquePointer callInt,
        KOpaquePointer callNativePointer,
        KOpaquePointer callVoid,
        KOpaquePointer dispose
    );
}

// Legacy entry point kept for compatibility; registration is done from Kotlin on Linux.
SKIKO_EXPORT void skiko_register_callbacks_via_dlsym() {
}
