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
