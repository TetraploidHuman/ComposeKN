# 预编译 mingw-Skia 包（`skia-mingw-<tag>.tar.zst`）

## 这是什么

一份**自包含**的 GNU-ABI (MinGW-w64 / UCRT) Skia 静态库集合，用途是让
Kotlin/Native `mingwX64` 目标能够链接 Skia（JetBrains 发布的 Windows Skia 是
MSVC ABI，符号名形如 `?drawRect@SkCanvas@@QEAAX...`，跟 K/N 需要的 Itanium
ABI `_ZN8SkCanvas8drawRect...` 根本对不上）。

包里所有东西都是**静态归档**，链接期只需要 konan 自带的 lld：

```
skia-mingw-m150-b8e40a7c49/
├── manifest.json      # skia tag/commit、构建用的 gcc/nixpkgs 版本、每个文件的 sha256
├── icudtl.dat         # Skia 的 ICU 数据（必须放在 exe 同目录）
├── libs/              # 22 个 Skia 静态库（libskia.a / libicu.a / ...）
├── runtime/           # 链接期需要的运行期/导入库
│   ├── libmcfgthread.a    # nixpkgs mingw 工具链的线程库（libstdc++ 内联代码引用它）
│   ├── libmsvcrt.a        # 已删除 _onexit 兼容包装（见下）
│   ├── libucrtbase.a      # 同上
│   ├── libntdll.a
│   └── libmingwex.a
└── shim/              # 符号补齐 shim（含 _onexit 与 3 个 GCC 11+ libstdc++ 符号）
```

## 为什么需要它

从源码构建 mingw-Skia 需要「nixpkgs 的 mingw 交叉工具链 + 约 15GB 磁盘 + 半小时
以上」，CI 上每次重来不现实。把这个包当作构建产物缓存下来之后，CI 只需要：

1. 下载并校验这个包（`scripts/fetch-skia-mingw.sh`，40MB，约 10 秒）
2. 用 apt 里的 `g++-mingw-w64-x86-64` 编译 skiko 的 C++ 桥接
3. `./gradlew :samples:windows-demo:linkReleaseExecutableMingwX64`

包里的库**必须**和宿主工具链的 ABI 家族一致（GNU/Itanium + UCRT + mcfgthread）。
这也是为什么 CI 上要显式 pin nixpkgs 版本（见 `.github/workflows/windows-native-selftest.yml`）。

## 用法

```bash
# 下载到 /tmp/composekn-skia-mingw/skia-mingw-<tag>
./scripts/fetch-skia-mingw.sh /tmp/composekn-skia-mingw

# 用它链接 Windows exe（不需要 SKIA_MINGW_WORK，也不需要 Skia 源码）
SKIA_MINGW_PREBUILT=/tmp/composekn-skia-mingw/skia-mingw-m150-b8e40a7c49 \
    ./vendor/skiko/skia-mingw/build-windows-native-demo.sh

# 跑自检（logic / render / window 三段）
SKIA_MINGW_PREBUILT=/tmp/.../skia-mingw-m150-b8e40a7c49 \
    ./scripts/test-windows-native.sh --skip-build
```

默认下载地址是本仓库的 GitHub Release 资产
（tag `skia-mingw-<SKIA_TAG>`）；可用 `COMPOSEKN_SKIA_PREBUILT_URL=` 覆盖，
也支持 `file://` 与本地路径。校验哈希在 `prebuilt.sha256`，不匹配直接拒绝解包。

## 重新生成

```bash
# 1) 先构建 Skia（见 build-skia-mingw.sh 的用法）
SKIA_MINGW_WORK=/mnt/hdd2/KtLLM/skia-mingw \
nix-shell ./shell.nix --run ./vendor/skiko/skia-mingw/package-prebuilt.sh

# 2) 上传为新资产，并把新哈希写进 prebuilt.sha256
gh release create skia-mingw-<tag> \
    /mnt/hdd2/KtLLM/skia-mingw/prebuilt/skia-mingw-<tag>.tar.zst
sha256sum …/skia-mingw-<tag>.tar.zst | cut -d' ' -f1 > vendor/skiko/skia-mingw/prebuilt.sha256
```

打包脚本用 `--sort=name --mtime=@0` 固定了 tar 元数据，所以同样的输入会产出同样的包
（`zstd` 版本不同时压缩流仍可能不同，哈希以实际产物为准）。

## 包里的坑（都是踩过的）

| 东西 | 为什么必须在包里 |
|---|---|
| `libstdcxx-symbols-shim.a` | nixpkgs GCC 15 的 libstdc++ 头文件会内联出 `basic_string::_M_replace_cold` 等 3 个符号，而 konan 自带的 libstdc++ 是 GCC 9.2，没有它们 → 必须在 shim 里显式实例化 |
| shim 里的 `_onexit` | konan 的 `crt2.o` 里 `atexit` 调 `_onexit`，而 UCRT 导入库里的 `_onexit` 又回调 `atexit` → 无限递归，进程在 `__do_global_ctors` 阶段静默退出（无输出、退出码 1）。shim 必须排在所有 UCRT 导入库之前 |
| `libmsvcrt.a` / `libucrtbase.a` 里删掉 `_onexit` 成员 | 同上，双保险 |
| `libmcfgthread.a` | nixpkgs mingw 工具链用 mcfgthread 做线程模型，Skia 的内联代码会引用 `__mcfgthread_*` |
| `icudtl.dat` | Skia 的 ICU 数据文件，运行时从 exe 同目录加载；缺了它文本排版直接失败 |
| **不要**给链接加 `-L<mingw lib dir>` | 否则 `-lmingw32` 会解析到精简版 `libmingw32.a`，缺 `mingw_app_type` / `__security_init_cookie` 等 7 个符号（konan 自带 sysroot 那份才有）。包里的库都用绝对路径给出 |
