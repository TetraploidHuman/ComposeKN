// ComposeKN: 补齐 MinGW/GCC-15 libstdc++ 中、konan 自带的 libstdc++（GCC 9.2）
// 里不存在的 3 个符号。Kotlin/Native 的 mingwX64 链接使用 msys2 的静态
// libstdc++（单一副本，避免混用两个 C++ 运行时的风险），因此这些
// 由 GCC 15 头文件内联代码引用的新符号需要在这里显式导出。
//
//   1) std::basic_string<char>::_M_replace_cold    （GCC 11+）
//   2) std::basic_string<wchar_t>::_M_replace_cold （GCC 11+）
//   3) std::__throw_bad_array_new_length           （GCC 11+）
#include <string>
#include <cstdlib>
#include <bits/functexcept.h>

// 1) + 2) 强制实例化 _M_replace_cold（定义已在 <string> 引入的
//    basic_string.tcc 中，这里只是把它实例化出来）。
template void
std::basic_string<char>::_M_replace_cold(char*, std::size_t, const char*,
                                         std::size_t, std::size_t);

template void
std::basic_string<wchar_t>::_M_replace_cold(wchar_t*, std::size_t, const wchar_t*,
                                            std::size_t, std::size_t);

// 3) GCC 11+ 的 std::vector/:string 越界长度检查辅助函数。
//    本工程以 -fno-exceptions 编译，无法抛出 std::bad_array_new_length，
//    改为直接终止进程（该路径只应在内存破坏时触发）。
namespace std {
void __throw_bad_array_new_length(void) {
    std::abort();
}
}  // namespace std

// ---------------------------------------------------------------------------
// 4) _onexit —— 打破 atexit <-> _onexit 的相互递归（Windows 原生 exe 无法启动的根因）
//
// konan 的 crt2.o（MSVCRT 时代）提供的 atexit 是：
//     int atexit(void (*f)(void)) { return _onexit((_onexit_t)f) ? 0 : -1; }
// 而 nixpkgs 的 UCRT 导入库 libucrtbase.a / libmsvcrt.a 里各自带有一个
// “兼容包装实现”的 _onexit：
//     int _onexit(_onexit_t f) { return atexit(f) ? f : NULL; }   // -> 又回调 atexit
// 两者互为递归。第一个触发点是 crtbegin.o 注册的静态构造器
// __gcc_register_frame（函数体只有一句 atexit(__gcc_deregister_frame)），
// 于是进程在 __do_global_ctors 阶段就无限递归 -> 栈溢出 -> 在进入 main 之前
// 静默退出（退出码 1，stdout 无输出）。Windows 实机与 Wine 上表现一致。
//
// 修法：本 shim 排在所有 UCRT 导入库之前，由它提供 _onexit，递归即被打断。
//
// 语义说明：这里返回函数指针（= 注册成功），但不真正登记到退出表 ——
// 本工程的 exit 走的是 CRT 自己的 onexit 表，而 atexit 又只被静态构造器
// （__gcc_register_frame，注册的是一个空函数）使用，因此不登记不影响运行；
// 代价是进程退出时不会执行 atexit 注册的回调（含 C++ 全局析构函数）。
#include <stdlib.h>   // _onexit_t

extern "C" _onexit_t _onexit(_onexit_t f) {
    (void)f;
    return f;         // 非 NULL = 注册成功，避免调用方把 atexit 判为失败
}
