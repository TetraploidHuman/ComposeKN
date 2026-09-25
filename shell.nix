{ pkgs ? import <nixpkgs> {} }:

let
  # nixpkgs libgbm (26.0.3) can lag mesa (26.1.x); gallium + mismatched libgbm → SIGSEGV.
  libgbmMatched = pkgs.libgbm.overrideAttrs (old: {
    version = pkgs.mesa.version;
    src = pkgs.mesa.src;
  });

  # Kotlin/Native links libcrypt.so.1; nixpkgs libxcrypt only ships libcrypt.so.2.
  # Lightweight symlink — do NOT overrideAttrs libxcrypt (rebuilds from source every time).
  libxcryptCompat = pkgs.runCommand "composekn-libcrypt-so1" {} ''
    mkdir -p $out/lib
    ln -sfn ${pkgs.libxcrypt}/lib/libcrypt.so.2.0.0 $out/lib/libcrypt.so.1
  '';

  # Zulu/Temurin avoid konanc host-JVM ffiFreeClosure SIGSEGV seen with NixOS OpenJDK 21.
  jdk =
    if pkgs ? temurin then pkgs.temurin.packages.jdk-21
    else if pkgs ? zulu21 then pkgs.zulu21
    else pkgs.jdk21;

  runtimeLibs = with pkgs; [
    libxcryptCompat
    libgbmMatched
    mesa
    wayland
    libxkbcommon
    libglvnd
    fontconfig
    freetype
    libxau
    libxdmcp
    dbus
    libnotify
    vulkan-loader
    stdenv.cc.cc.lib
  ];
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    jdk
    pkg-config
    wayland
    wayland-protocols
    libxkbcommon
    fontconfig
    freetype
    libglvnd
    mesa
    dbus
    libnotify
    vulkan-loader
    vulkan-headers
    # mingw-w64 cross toolchain for skiko mingwX64 (Windows) native target.
    pkgs.pkgsCross.mingwW64.stdenv.cc
  ];

  shellHook = ''
    export JAVA_HOME=${jdk}
    export COMPOSEKN_JAVA_HOME=$JAVA_HOME
    export PATH=$JAVA_HOME/bin:$PATH
    export XDG_RUNTIME_DIR=''${XDG_RUNTIME_DIR:-/run/user/$UID}
    export WAYLAND_DISPLAY=''${WAYLAND_DISPLAY:-wayland-0}
    # Matched libgbm/mesa + no inherited LD_LIBRARY_PATH (avoids stale libgbm 26.0.x).
    export LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath runtimeLibs}
    # konanc/ld.lld 链接 -ldbus-1 等需要 LIBRARY_PATH（不只是运行时 LD_LIBRARY_PATH）。
    export LIBRARY_PATH=${pkgs.lib.makeLibraryPath runtimeLibs}''${LIBRARY_PATH:+:$LIBRARY_PATH}
    export COMPOSEKN_LIBCRYPT_LIB=${libxcryptCompat}/lib
    # Fallback when hardware DRI/gallium crashes (common on headless/VM/old GPU setups).
    # GLES 回退仍用 llvmpipe；Vulkan 默认 lavapipe ICD（与软件 GL 配套）。
    # 无条件写死展开后的 store 路径：父环境若残留含 * 的 VK_ICD_FILENAMES，
    # loader 会报 Found no drivers / CreateInstance=-9。
    # 真机 GPU：LIBGL_ALWAYS_SOFTWARE=0 VK_ICD_FILENAMES= VK_DRIVER_FILES= nix-shell …
    export LIBGL_ALWAYS_SOFTWARE=''${LIBGL_ALWAYS_SOFTWARE:-1}
    export GALLIUM_DRIVER=''${GALLIUM_DRIVER:-llvmpipe}
    if [ -z "''${COMPOSEKN_VK_HARDWARE:-}" ]; then
      export VK_ICD_FILENAMES=${pkgs.mesa}/share/vulkan/icd.d/lvp_icd.x86_64.json
      export VK_DRIVER_FILES=$VK_ICD_FILENAMES
    else
      unset VK_ICD_FILENAMES VK_DRIVER_FILES
    fi
  '';
}
