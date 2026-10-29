#!/usr/bin/env bash
# Builds the Marathon Recompiled Android APK (arm64-v8a) from this repository.
#
# The game itself is not part of this repository. You need your own legally obtained copy of
# Sonic the Hedgehog (2006) for Xbox 360: the build reads default.xex, shader.arc and
# shader_lt.arc from it and generates the recompiled code locally. See android/README.md.
#
# Usage:
#   android/build.sh --game <extracted game folder>   full build (needs the game files)
#   android/build.sh                                  full build, game files already in
#                                                     MarathonRecompLib/private/
#   android/build.sh --skip-game                      everything that doesn't need the game:
#                                                     host tools, dependencies and the launcher
#                                                     (the APK has no libmain.so and can't play)
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT   Android SDK with platform 35 and build-tools
#   ANDROID_NDK_HOME                  NDK 27.2.12479018 (default: $ANDROID_HOME/ndk/27.2.12479018)
#   MARATHON_ANDROID_WORK             build directory (default: android/build)
#   JOBS                              parallel jobs (default: nproc)
#   MARATHON_VERSION_CODE / _NAME     APK version (default: 13 / 0.6.0)
#   MARATHON_APP                      marathon (default) or soleanna: Soleanna Hub, Sonic's town as a
#                                     launcher for other Android games (android/soleanna)
#   HUB_VERSION_CODE / _NAME          Soleanna Hub's version (default: 1 / 0.1)
#   MARATHON_KEYSTORE, MARATHON_STORE_PASSWORD, MARATHON_KEY_ALIAS, MARATHON_KEY_PASSWORD
#                                     optional release signing; otherwise the debug key is used

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$ROOT/android"
WORK="${MARATHON_ANDROID_WORK:-$HERE/build}"
JOBS="${JOBS:-$(nproc)}"
NDK_VERSION=27.2.12479018

# Pinned third-party sources fetched at build time.
FFMPEG_TAG=n7.1.1
FREETYPE_TAG=VER-2-13-3
XZ_TAG=v5.6.4
LIBARCHIVE_TAG=v3.8.1
ADRENOTOOLS_URL=https://github.com/bylaws/libadrenotools.git
ADRENOTOOLS_COMMIT=8fae8ce254dfc1344527e05301e43f37dea2df80
# Sonic '06 Mod Manager patches by Knuxfan24 and contributors (bundled as the offline patch list;
# the launcher's "Check for updates" fetches newer ones from the same repository).
PATCHES_URL=https://github.com/Knuxfan24/Sonic-06-Mod-Manager-Patches.git
PATCHES_COMMIT=52ac81009e2580d6e93a52d87c7d1e4296977760
# unlub (ShadowLAG's build of unluac 1.2.2, MIT), the Lua decompiler the Mod Manager uses for
# patches that edit game scripts. Taken from the Sonic '06 Mod Manager repository.
SMM_URL=https://github.com/hyperbx/SonicNextModManager.git
SMM_COMMIT=2ee12036f96e0884a5577577410396d6d829155a
UNLUB_SHA256=217da273617adf4f65a919c2d9948c748146075153888d5844d9aa0385678265

GAME_DIR=""
SKIP_GAME=0
while [ $# -gt 0 ]; do
    case "$1" in
        --game) GAME_DIR="$2"; shift 2 ;;
        --game=*) GAME_DIR="${1#--game=}"; shift ;;
        --skip-game) SKIP_GAME=1; shift ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- Toolchain ---------------------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK="${ANDROID_NDK_HOME:-${SDK:+$SDK/ndk/$NDK_VERSION}}"
[ -n "$SDK" ] && [ -d "$SDK" ] || die "set ANDROID_HOME to an Android SDK (platform 35, build-tools)"
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || die "NDK $NDK_VERSION not found; set ANDROID_NDK_HOME"
for tool in git cmake ninja make clang clang++ python3 java; do
    command -v "$tool" > /dev/null || die "$tool is required"
done
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
STRIP="$TC/bin/llvm-strip"
ANDROID_CMAKE=(-G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON)

mkdir -p "$WORK/src"

# Fetches one git ref (tag or commit) into $WORK/src/<name>, shallowly, once.
fetch() {
    local url="$2" ref="$3" dir="$WORK/src/$1"
    [ "$(cat "$WORK/src/.$1.ref" 2> /dev/null)" = "$url $ref" ] && return 0
    rm -rf "$dir"
    git init -q "$dir"
    git -C "$dir" remote add origin "$url"
    git -C "$dir" fetch -q --depth 1 origin "$ref"
    git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
    echo "$url $ref" > "$WORK/src/.$1.ref"
}

# --- Submodules and the plume patch ----------------------------------------------------------------
log "Submodules"
git -C "$ROOT" submodule update --init --recursive

PLUME="$ROOT/thirdparty/plume"
if git -C "$PLUME" apply --reverse --check "$HERE/patches/plume-android.patch" 2> /dev/null; then
    echo "plume-android.patch already applied"
else
    git -C "$PLUME" apply "$HERE/patches/plume-android.patch"
    echo "applied plume-android.patch"
fi

# --- Host code-generation tools ------------------------------------------------------------------
log "Host tools (XenonRecomp, XenosRecomp, u8extract, file_to_c)"
# LTO stays off: clang's LTO archives break the system linker here.
cmake -S "$HERE/host" -B "$WORK/host" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ -DMARATHON_RECOMP_OPTIMIZE_TOOLS=OFF
ninja -C "$WORK/host" -j "$JOBS" XenonRecomp XenosRecomp u8extract file_to_c
host_tool() { find "$WORK/host/tools" -name "$1" -type f -perm -u+x | head -1; }
FILE_TO_C="$(host_tool file_to_c)"

# --- Code generation from your game files ----------------------------------------------------------
LIB="$ROOT/MarathonRecompLib"
if [ "$SKIP_GAME" = 0 ]; then
    log "Game files"
    mkdir -p "$LIB/private"
    if [ -n "$GAME_DIR" ]; then
        [ -f "$GAME_DIR/default.xex" ] || die "$GAME_DIR/default.xex not found (point --game at the extracted game folder)"
        cp "$GAME_DIR/default.xex" "$LIB/private/"
        for arc in shader.arc shader_lt.arc; do
            src="$GAME_DIR/xenon/archives/$arc"
            [ -f "$src" ] || src="$GAME_DIR/$arc"
            [ -f "$src" ] || die "$arc not found in $GAME_DIR/xenon/archives"
            cp "$src" "$LIB/private/"
        done
    fi
    for f in default.xex shader.arc shader_lt.arc; do
        [ -f "$LIB/private/$f" ] || die "MarathonRecompLib/private/$f is missing: pass --game <folder> or copy it there (see android/README.md)"
    done

    log "Recompiling the game code (XenonRecomp)"
    (cd "$LIB" && "$(host_tool XenonRecomp)" config/Marathon.toml ../tools/XenonRecomp/XenonUtils/ppc_context.h)

    log "Recompiling the shaders (XenosRecomp)"
    mkdir -p "$LIB/private/shader"
    "$(host_tool u8extract)" "$LIB/private/shader.arc" "$LIB/private/shader" > /dev/null
    "$(host_tool u8extract)" "$LIB/private/shader_lt.arc" "$LIB/private/shader" > /dev/null
    (cd "$LIB" && LD_LIBRARY_PATH="$ROOT/tools/XenosRecomp/thirdparty/dxc-bin/lib/x64" "$(host_tool XenosRecomp)")
fi

# --- FFmpeg (XMA audio only) -----------------------------------------------------------------------
FFMPEG_OUT="$WORK/ffmpeg-android"
if [ ! -f "$FFMPEG_OUT/lib/libavcodec.a" ]; then
    log "FFmpeg $FFMPEG_TAG with the xmaframes decoder"
    fetch ffmpeg https://github.com/FFmpeg/FFmpeg.git "refs/tags/$FFMPEG_TAG"
    FF="$WORK/src/ffmpeg"
    git -C "$FF" apply --reverse --check "$HERE/patches/ffmpeg-xmaframes.patch" 2> /dev/null \
        || git -C "$FF" apply "$HERE/patches/ffmpeg-xmaframes.patch"
    (cd "$FF" && ./configure --prefix="$FFMPEG_OUT" --target-os=android --arch=aarch64 --cpu=armv8-a \
        --enable-cross-compile --cc="$TC/bin/aarch64-linux-android26-clang" \
        --cxx="$TC/bin/aarch64-linux-android26-clang++" --ar="$TC/bin/llvm-ar" \
        --ranlib="$TC/bin/llvm-ranlib" --nm="$TC/bin/llvm-nm" --strip="$TC/bin/llvm-strip" \
        --enable-pic --disable-doc --disable-programs --enable-static --disable-shared \
        --disable-autodetect --disable-everything --enable-decoder=xmaframes --disable-debug \
        --extra-cflags="-O2" \
        && make -j "$JOBS" && make install)
fi

# --- FreeType (for msdf-atlas-gen) -----------------------------------------------------------------
FREETYPE_OUT="$WORK/freetype-android"
if [ ! -f "$FREETYPE_OUT/lib/libfreetype.a" ]; then
    log "FreeType $FREETYPE_TAG"
    fetch freetype https://github.com/freetype/freetype.git "refs/tags/$FREETYPE_TAG"
    cmake -S "$WORK/src/freetype" -B "$WORK/freetype-build" "${ANDROID_CMAKE[@]}" -DANDROID_PLATFORM=android-26 \
        -DCMAKE_INSTALL_PREFIX="$FREETYPE_OUT" -DBUILD_SHARED_LIBS=OFF -DFT_DISABLE_ZLIB=ON \
        -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON
    ninja -C "$WORK/freetype-build" -j "$JOBS" install
fi

# --- libmodarchive (xz + libarchive) for the mod installer ---------------------------------------
ARCHIVE_PREFIX="$WORK/archive-prefix"
if [ ! -f "$WORK/modarchive-build/libmodarchive.so" ]; then
    log "xz $XZ_TAG, libarchive $LIBARCHIVE_TAG and libmodarchive"
    fetch xz https://github.com/tukaani-project/xz.git "refs/tags/$XZ_TAG"
    fetch libarchive https://github.com/libarchive/libarchive.git "refs/tags/$LIBARCHIVE_TAG"
    cmake -S "$WORK/src/xz" -B "$WORK/xz-build" "${ANDROID_CMAKE[@]}" -DANDROID_PLATFORM=android-29 \
        -DBUILD_SHARED_LIBS=OFF -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF \
        -DXZ_TOOL_LZMAINFO=OFF -DXZ_TOOL_SCRIPTS=OFF -DXZ_TOOL_SYMLINKS=OFF -DXZ_TOOL_SYMLINKS_LZMA=OFF \
        -DXZ_DOC=OFF -DXZ_NLS=OFF -DBUILD_TESTING=OFF -DCMAKE_INSTALL_PREFIX="$ARCHIVE_PREFIX"
    ninja -C "$WORK/xz-build" -j "$JOBS" install
    cmake -S "$WORK/src/libarchive" -B "$WORK/libarchive-build" "${ANDROID_CMAKE[@]}" -DANDROID_PLATFORM=android-29 \
        -DBUILD_SHARED_LIBS=OFF -DCMAKE_INSTALL_PREFIX="$ARCHIVE_PREFIX" -DCMAKE_PREFIX_PATH="$ARCHIVE_PREFIX" \
        -DLIBLZMA_INCLUDE_DIR="$ARCHIVE_PREFIX/include" -DLIBLZMA_LIBRARY="$ARCHIVE_PREFIX/lib/liblzma.a" \
        -DENABLE_LZMA=ON -DENABLE_ZLIB=ON -DENABLE_BZip2=OFF -DENABLE_LZ4=OFF -DENABLE_ZSTD=OFF \
        -DENABLE_LIBB2=OFF -DENABLE_OPENSSL=OFF -DENABLE_MBEDTLS=OFF -DENABLE_NETTLE=OFF \
        -DENABLE_LIBXML2=OFF -DENABLE_EXPAT=OFF -DENABLE_PCREPOSIX=OFF -DENABLE_PCRE2POSIX=OFF \
        -DENABLE_LIBGCC=OFF -DENABLE_CNG=OFF -DENABLE_TAR=OFF -DENABLE_CPIO=OFF -DENABLE_CAT=OFF \
        -DENABLE_UNZIP=OFF -DENABLE_XATTR=OFF -DENABLE_ACL=OFF -DENABLE_ICONV=OFF -DENABLE_TEST=OFF \
        -DENABLE_INSTALL=ON -DENABLE_WERROR=OFF
    ninja -C "$WORK/libarchive-build" -j "$JOBS" archive_static
    cmake -S "$HERE/modarchive" -B "$WORK/modarchive-build" "${ANDROID_CMAKE[@]}" -DANDROID_PLATFORM=android-29 \
        -DLIBARCHIVE_SOURCE_DIR="$WORK/src/libarchive" \
        -DLIBARCHIVE_STATIC_LIB="$WORK/libarchive-build/libarchive/libarchive.a" \
        -DLIBLZMA_STATIC_LIB="$ARCHIVE_PREFIX/lib/liblzma.a"
    ninja -C "$WORK/modarchive-build" -j "$JOBS"
fi

# --- libadrenotools (custom Turnip drivers) --------------------------------------------------------
if [ -z "$(find "$WORK/adrenotools-build" -name libadrenotools.so 2> /dev/null | head -1)" ]; then
    log "libadrenotools ${ADRENOTOOLS_COMMIT:0:7}"
    fetch libadrenotools "$ADRENOTOOLS_URL" "$ADRENOTOOLS_COMMIT"
    git -C "$WORK/src/libadrenotools" submodule update --init --recursive --depth 1
    cmake -S "$WORK/src/libadrenotools" -B "$WORK/adrenotools-build" "${ANDROID_CMAKE[@]}" \
        -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared -DBUILD_SHARED_LIBS=ON \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
    ninja -C "$WORK/adrenotools-build" -j "$JOBS"
fi

# --- The game library (libmain.so) -----------------------------------------------------------------
if [ "$SKIP_GAME" = 0 ]; then
    log "Marathon Recompiled for Android (libmain.so)"
    export VCPKG_ROOT="$ROOT/thirdparty/vcpkg"
    # API 29 gives native ELF TLS for the game's thread_local context.
    cmake -S "$ROOT" -B "$WORK/native" "${ANDROID_CMAKE[@]}" -DANDROID_PLATFORM=android-29 \
        -DANDROID_STL=c++_shared \
        -DMARATHON_ANDROID_FFMPEG="$FFMPEG_OUT" -DMARATHON_HOST_FILE_TO_C="$FILE_TO_C" \
        -DFREETYPE_LIBRARY="$FREETYPE_OUT/lib/libfreetype.a" \
        -DFREETYPE_INCLUDE_DIR_ft2build="$FREETYPE_OUT/include/freetype2" \
        -DFREETYPE_INCLUDE_DIR_freetype2="$FREETYPE_OUT/include/freetype2" \
        -DMARATHON_RECOMP_OPTIMIZE_TOOLS=OFF -DSDL_TEST=OFF
    ninja -C "$WORK/native" -j "$JOBS" MarathonRecomp
fi

# --- Stage native libraries ------------------------------------------------------------------------
log "Staging native libraries"
APP="$HERE/android-app"
JNI="$APP/app/src/main/jniLibs/arm64-v8a"
rm -rf "$APP/app/src/main/jniLibs"
mkdir -p "$JNI" "$WORK/symbols"
if [ "$SKIP_GAME" = 0 ]; then
    cp "$WORK/native/MarathonRecomp/libmain.so" "$WORK/symbols/libmain.so.unstripped"
    "$STRIP" --strip-unneeded -o "$JNI/libmain.so" "$WORK/native/MarathonRecomp/libmain.so"
fi
cp "$TC/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"
"$STRIP" --strip-unneeded -o "$JNI/libmodarchive.so" "$WORK/modarchive-build/libmodarchive.so"
for lib in libadrenotools libhook_impl libmain_hook libfile_redirect_hook libgsl_alloc_hook; do
    so="$(find "$WORK/adrenotools-build" -name "$lib.so" -type f | head -1)"
    [ -n "$so" ] || die "$lib.so was not built"
    "$STRIP" --strip-unneeded -o "$JNI/$lib.so" "$so"
done

# --- Launcher assets: the Mod Manager patch list and unlub -----------------------------------------
log "Mod Manager patches and unlub"
fetch patches "$PATCHES_URL" "$PATCHES_COMMIT"
rm -rf "$APP/app/src/main/assets/patches"
mkdir -p "$APP/app/src/main/assets/patches" "$APP/app/libs"
cp "$WORK/src/patches/"*.mlua "$APP/app/src/main/assets/patches/"
fetch smm "$SMM_URL" "$SMM_COMMIT"
cp "$WORK/src/smm/Sonic-06-Mod-Manager/res/Applications/unlub.jar" "$APP/app/libs/unlub.jar"
echo "$UNLUB_SHA256  $APP/app/libs/unlub.jar" | sha256sum -c --quiet - || die "unlub.jar checksum mismatch"

# --- Soleanna Hub's town mission (our own Lua, no game files) ------------------------------------
log "Soleanna Hub mod"
rm -rf "$APP/app/src/soleanna/assets/hubmod"
python3 "$HERE/soleanna/make_hub_mod.py" "$APP/app/src/soleanna/assets/hubmod"
rm -f "$APP/app/src/soleanna/assets/hubmod/hub_mission.lua"

# --- APK -------------------------------------------------------------------------------------------
log "APK"
FLAVOR="${MARATHON_APP:-marathon}"
case "$FLAVOR" in marathon|soleanna) ;; *) die "MARATHON_APP must be marathon or soleanna" ;; esac
FLAVOR_TASK="$(printf '%s' "${FLAVOR:0:1}" | tr a-z A-Z)${FLAVOR:1}"
printf 'sdk.dir=%s\n' "$SDK" > "$APP/local.properties"
export MARATHON_VERSION_CODE="${MARATHON_VERSION_CODE:-13}"
export MARATHON_VERSION_NAME="${MARATHON_VERSION_NAME:-0.6.0}"
(cd "$APP" && ./gradlew --no-daemon ":app:assemble${FLAVOR_TASK}Release")
mkdir -p "$WORK/out"
NAME=MarathonRecomp-android-arm64
[ "$FLAVOR" = soleanna ] && NAME=SoleannaHub-android-arm64
if [ "$SKIP_GAME" = 0 ]; then
    OUT="$WORK/out/$NAME.apk"
else
    OUT="$WORK/out/$NAME-launcher-only.apk"
fi
cp "$APP/app/build/outputs/apk/$FLAVOR/release/app-$FLAVOR-release.apk" "$OUT"
log "Done: $OUT"
[ "$SKIP_GAME" = 0 ] || echo "This APK has no game library (built with --skip-game) and can't start the game."
