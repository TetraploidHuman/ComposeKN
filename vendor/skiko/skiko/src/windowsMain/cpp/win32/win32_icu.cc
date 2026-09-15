/**
 * ICU 数据内嵌（Windows / mingwX64）：让 exe 单文件可跑，不再要求 exe 同目录
 * 放一个 10MB 的 icudtl.dat。
 *
 * ---------------------------------------------------------------------------
 * 为什么需要这个文件
 *
 * 上游 Windows 版 Skia 的做法（third_party/icu/BUILD.gn）：ICU 用 stubdata 编译，
 * 真正的数据由 `third_party/icu/SkLoadICU.cpp` 从 **exe/模块同目录** mmap
 * `icudtl.dat`，再 `udata_setCommonData(addr)` 交给 ICU。Skia 的
 * `SkUnicodes::ICU::Make()`（modules/skunicode/src/SkUnicode_icu.cpp）在
 * `SkLoadICU()` 返回 false 时直接返回 nullptr —— 而 skiko 的 Shaper/ParagraphBuilder
 * 拿到 nullptr 会崩（实测：把 icudtl.dat 删掉后 exe 在第一条文本排版断言上退出码 5）。
 *
 * 也就是说：数据文件必须在、且必须在 exe 同目录。发布包于是只能「exe + icudtl.dat」
 * 两个文件，用户把 exe 单独拷走 / 直接在 zip 里双击运行就会崩。
 *
 * ---------------------------------------------------------------------------
 * 做法
 *
 * 1. 构建期（build-windows-native-demo.sh）用 `.incbin` 把 icudtl.dat 编进 exe 的
 *    .rdata 段，导出 `composekn_icudtl_data` / `composekn_icudtl_end` 两个符号；
 * 2. 这里**提供同名符号 `SkLoadICU()`**：直接对内存里的数据调用
 *    `udata_setCommonData()`（与上游 init_icu 除 mmap 外的两步完全一致）。
 *
 * 链接器会不会用我们这份？libicu.a 里上游的 `libicu.SkLoadICU.o` 是**独立的归档成员**，
 * 只有当链接器还缺 `SkLoadICU` 时才会被拉进来；而 win32_window.cc 显式引用了
 * `SkLoadICU()`，我们这份会先被拉进链（同一个归档内的成员会互相解析），
 * 于是上游那个成员不再被拉入 —— 既不用重编 Skia、也不用改上游源码。
 *
 * 验收看 composekn-startup.log 里的这一行：
 *   icu: 使用内嵌数据初始化成功（10468208 字节，无需 exe 同目录的 icudtl.dat）
 */
#include "win32_icu.h"

#include <cstdarg>
#include <cstddef>
#include <cstdio>

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>

#include "unicode/udata.h"

#include "win32_bridge.h"

namespace {

/** 真实的 icudtl.dat 约 10MB；离谱的小值说明构建时没把数据文件编进来。 */
constexpr size_t kMinimumIcuDataSize = 1024 * 1024;

size_t embeddedIcuDataSize() {
    return static_cast<size_t>(composekn_icudtl_end - composekn_icudtl_data);
}

void logf(const char* fmt, ...) {
    char message[192];
    va_list ap;
    va_start(ap, fmt);
    std::vsnprintf(message, sizeof(message), fmt, ap);
    va_end(ap);
    message[sizeof(message) - 1] = '\0';
    composekn_win32_log(message);
}

}  // namespace

/**
 * 覆盖上游 `SkLoadICU()`（libicu.a 的 libicu.SkLoadICU.o）：
 * 数据已经编在 exe 里了，直接交给 ICU，不需要碰文件系统。
 */
bool SkLoadICU() {
    static int state = 0;  // 0 = 还没试过，1 = 成功，-1 = 失败
    if (state != 0) return state > 0;

    const size_t size = embeddedIcuDataSize();
    if (size < kMinimumIcuDataSize) {
        state = -1;
        logf("icu: 内嵌数据只有 %llu 字节（应该 >= 1MB）—— 构建时没把 icudtl.dat 编进来，"
             "文本排版会失败",
             static_cast<unsigned long long>(size));
        return false;
    }

    UErrorCode err = U_ZERO_ERROR;
    udata_setCommonData(composekn_icudtl_data, &err);
    if (err != U_ZERO_ERROR) {
        state = -1;
        logf("icu: udata_setCommonData() 失败 err=%d", static_cast<int>(err));
        return false;
    }
    udata_setFileAccess(UDATA_ONLY_PACKAGES, &err);
    if (err != U_ZERO_ERROR) {
        state = -1;
        logf("icu: udata_setFileAccess() 失败 err=%d", static_cast<int>(err));
        return false;
    }

    state = 1;
    logf("icu: 使用内嵌数据初始化成功（%llu 字节，无需 exe 同目录的 icudtl.dat）",
         static_cast<unsigned long long>(size));
    return true;
}
