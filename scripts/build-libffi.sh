#!/usr/bin/env bash
#
# Cross-compile libffi 3.3 for Android (arm64-v8a and x86_64).
#
# WHY libffi 3.3 (not 3.4+)?
#   Chaquopy's cffi package (_cffi_backend.so) expects symbol version
#   LIBFFI_BASE_7.0. libffi 3.4+ exports LIBFFI_BASE_8.0 instead,
#   causing "cannot locate symbol ffi_type_float" at runtime.
#
# PREREQUISITES:
#   - Android NDK (r25+ recommended, tested with r29)
#     Install via Android Studio: SDK Manager > SDK Tools > NDK
#   - Standard build tools: autoconf, automake, libtool, make
#     macOS: brew install autoconf automake libtool
#     Linux: apt install autoconf automake libtool make
#
# USAGE:
#   ./scripts/build-libffi.sh [NDK_PATH]
#
#   NDK_PATH defaults to $ANDROID_NDK_HOME, or auto-detects from
#   ~/Library/Android/sdk/ndk/ (macOS) or ~/Android/Sdk/ndk/ (Linux).
#
# OUTPUT:
#   app/src/main/jniLibs/arm64-v8a/libffi.so
#   app/src/main/jniLibs/x86_64/libffi.so

set -euo pipefail

LIBFFI_VERSION="3.3"
LIBFFI_URL="https://github.com/libffi/libffi/releases/download/v${LIBFFI_VERSION}/libffi-${LIBFFI_VERSION}.tar.gz"
MIN_SDK=29

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_DIR/build/libffi-build"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"

# --- Find NDK ---
find_ndk() {
    if [ -n "${1:-}" ] && [ -d "$1" ]; then
        echo "$1"
        return
    fi
    if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
        echo "$ANDROID_NDK_HOME"
        return
    fi
    # Auto-detect from SDK
    local sdk_locations=(
        "$HOME/Library/Android/sdk/ndk"  # macOS
        "$HOME/Android/Sdk/ndk"           # Linux
    )
    for sdk_ndk in "${sdk_locations[@]}"; do
        if [ -d "$sdk_ndk" ]; then
            # Pick the latest version
            local latest
            latest=$(ls -1 "$sdk_ndk" 2>/dev/null | sort -V | tail -1)
            if [ -n "$latest" ]; then
                echo "$sdk_ndk/$latest"
                return
            fi
        fi
    done
    echo ""
}

NDK_PATH=$(find_ndk "${1:-}")
if [ -z "$NDK_PATH" ]; then
    echo "ERROR: Android NDK not found."
    echo "Install via Android Studio SDK Manager, or pass the path:"
    echo "  $0 /path/to/android-ndk"
    exit 1
fi
echo "Using NDK: $NDK_PATH"

# Determine host OS for toolchain
case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    Linux)  HOST_TAG="linux-x86_64" ;;
    *)      echo "ERROR: Unsupported OS"; exit 1 ;;
esac

TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG"
if [ ! -d "$TOOLCHAIN" ]; then
    echo "ERROR: Toolchain not found at $TOOLCHAIN"
    exit 1
fi

# --- Download libffi source ---
mkdir -p "$BUILD_DIR"
TARBALL="$BUILD_DIR/libffi-${LIBFFI_VERSION}.tar.gz"
if [ ! -f "$TARBALL" ]; then
    echo "Downloading libffi ${LIBFFI_VERSION}..."
    curl -L -o "$TARBALL" "$LIBFFI_URL"
fi

# --- Build for each ABI ---
declare -A ABI_TARGETS=(
    ["arm64-v8a"]="aarch64-linux-android"
    ["x86_64"]="x86_64-linux-android"
)

for ABI in "${!ABI_TARGETS[@]}"; do
    TARGET="${ABI_TARGETS[$ABI]}"
    echo ""
    echo "========================================="
    echo "Building libffi ${LIBFFI_VERSION} for ${ABI} (${TARGET})"
    echo "========================================="

    SRC_DIR="$BUILD_DIR/libffi-${LIBFFI_VERSION}-${ABI}"
    rm -rf "$SRC_DIR"
    mkdir -p "$SRC_DIR"
    tar xzf "$TARBALL" -C "$SRC_DIR" --strip-components=1

    cd "$SRC_DIR"

    CC="$TOOLCHAIN/bin/${TARGET}${MIN_SDK}-clang"
    CXX="$TOOLCHAIN/bin/${TARGET}${MIN_SDK}-clang++"
    AR="$TOOLCHAIN/bin/llvm-ar"
    RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    STRIP="$TOOLCHAIN/bin/llvm-strip"

    # Verify compiler exists
    if [ ! -x "$CC" ]; then
        echo "ERROR: Compiler not found: $CC"
        exit 1
    fi

    PREFIX="$BUILD_DIR/install-${ABI}"
    rm -rf "$PREFIX"

    ./configure \
        --host="$TARGET" \
        --prefix="$PREFIX" \
        --enable-shared \
        --disable-static \
        --disable-docs \
        CC="$CC" \
        CXX="$CXX" \
        AR="$AR" \
        RANLIB="$RANLIB" \
        STRIP="$STRIP"

    make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)" clean || true
    make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
    make install

    # Copy .so to jniLibs
    mkdir -p "$JNILIBS_DIR/$ABI"
    SO_FILE=$(find "$PREFIX/lib" -name "libffi.so*" -not -type l | head -1)
    if [ -z "$SO_FILE" ]; then
        echo "ERROR: libffi.so not found in $PREFIX/lib"
        exit 1
    fi
    cp "$SO_FILE" "$JNILIBS_DIR/$ABI/libffi.so"
    "$STRIP" "$JNILIBS_DIR/$ABI/libffi.so"

    echo "Installed: $JNILIBS_DIR/$ABI/libffi.so"

    cd "$PROJECT_DIR"
done

# --- Verify ---
echo ""
echo "========================================="
echo "Build complete. Verifying..."
echo "========================================="
for ABI in "${!ABI_TARGETS[@]}"; do
    SO="$JNILIBS_DIR/$ABI/libffi.so"
    SIZE=$(wc -c < "$SO" | tr -d ' ')
    echo "  $ABI: $SO ($SIZE bytes)"
done
echo ""
echo "Done. The .so files are ready for use in the Android app."
