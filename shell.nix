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
    export COMPOSEKN_LIBCRYPT_LIB=${libxcryptCompat}/lib
    # Fallback when hardware DRI/gallium crashes (common on headless/VM/old GPU setups).
    export LIBGL_ALWAYS_SOFTWARE=1
    export GALLIUM_DRIVER=llvmpipe
  '';
}
