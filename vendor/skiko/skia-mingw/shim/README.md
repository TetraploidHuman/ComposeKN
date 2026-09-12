# libstdc++ 符号补齐 shim

Kotlin/Native 的 mingwX64 链接使用 konan 自带的 **静态 libstdc++（GCC 9.2）**。
而 Skia 是用 **nixpkgs MinGW GCC 15** 的头编译的（因为需要 C++20：`<compare>` 等），
GCC 15 的 libstdc++ 内联代码会引用 3 个 GCC 11+ 才有的符号：

| 符号 | 说明 |
|------|------|
| `std::__cxx11::basic_string<char>::_M_replace_cold` | GCC 11+ 的 string 慢路径 |
| `std::__cxx11::basic_string<wchar_t>::_M_replace_cold` | 同上（宽字符） |
| `std::__throw_bad_array_new_length()` | GCC 11+ 的越界长度检查 |

本 shim 用 GCC 15 头编译，把这 3 个符号显式实例化/定义出来，从而可以继续使用
konan 的静态 libstdc++（单一 C++ 运行时副本，无需附带 `libstdc++-6.dll`）。

`_M_replace_cold` 的实现直接来自 `<bits/basic_string.tcc>`（安装的头里就有定义），
shim 只是用显式实例化把它导出。
