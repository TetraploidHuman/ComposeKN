#ifndef COMPOSEKN_WIN32_ICU_H
#define COMPOSEKN_WIN32_ICU_H

/*
 * ICU 数据文件（icudtl.dat）的内嵌声明。
 *
 * 这两个符号的**定义**在构建期生成的文件里（win32_icu_data.generated.cpp，
 * 由 vendor/skiko/skia-mingw/build-windows-native-demo.sh 写出来，不入库）：
 * 它用 `.incbin` 把 icudtl.dat 原样放进 .rdata 段。
 *
 * 之所以用「生成一个 .cpp」而不是 objcopy/资源文件：K/N 的构建只会编译
 * src/windowsMain/cpp/win32 下的 .cc/.cpp（见 skiko 的 CompileSkikoCppTask），
 * 而 .incbin 是 clang 自带汇编器支持的指令 —— 于是**不需要额外工具链**
 * （预编译模式下目标机器上只有 JDK + konan），也不需要把 10MB 的数组
 * 展开成几十 MB 的源码。
 */

#ifdef __cplusplus
extern "C" {
#endif

/** icudtl.dat 的第一个字节。 */
extern const unsigned char composekn_icudtl_data[];
/** icudtl.dat 之后的一个字节（数据长度 = end - data）。 */
extern const unsigned char composekn_icudtl_end[];

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* COMPOSEKN_WIN32_ICU_H */
