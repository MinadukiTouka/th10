# TH10 Android native build (ndk-build).
#
# Why ndk-build and not CMake: on this host every CMake configure that enables
# both C and CXX crashes inside CMakeFindBinUtils (exit 0xC0000409), with CMake
# 3.22.1 and 3.31.6 alike, ASCII paths, a minimal environment and both the NDK
# toolchain file and CMake's built-in Android support. C-only projects configure
# fine. ndk-build (GNU make) sidesteps the problem entirely and is what SDL2's own
# android-project uses, so SDL2 ships a ready Android.mk.
#
# Modules:
#   SDL2      shared  <- third_party/SDL2/Android.mk (upstream, unmodified)
#   freetype  static  <- FreeType amalgamations, zlib from the NDK
#   main      shared  <- game/ + platform/ + src/ (Switch host layer) +
#                        portable/sdl/Renderer.cpp + android-host/AndroidMain.cpp
LOCAL_PATH := $(call my-dir)
JNI_PATH := $(LOCAL_PATH)

# workspace root = th10-port/android/app/src/main/jni -> six levels up
TH10_ROOT := $(abspath $(JNI_PATH)/../../../../../..)
TH10_PORT := $(TH10_ROOT)/th10-port
TH10_TP   := $(TH10_ROOT)/third_party

ifeq ($(wildcard $(TH10_PORT)/game/Types.hpp),)
$(error TH10_ROOT resolved to '$(TH10_ROOT)' but th10-port/game/Types.hpp is not there)
endif
ifeq ($(wildcard $(TH10_TP)/SDL2/Android.mk),)
$(error SDL2 sources missing under '$(TH10_TP)'; run tools/fetch-deps.ps1)
endif
ifeq ($(wildcard $(TH10_TP)/freetype/include/freetype/freetype.h),)
$(error FreeType sources missing under '$(TH10_TP)'; run tools/fetch-deps.ps1)
endif

# ---------------------------------------------------------------------- SDL2
SDL2_PATH := $(TH10_TP)/SDL2
include $(CLEAR_VARS)
LOCAL_PATH := $(SDL2_PATH)
include $(SDL2_PATH)/Android.mk
LOCAL_PATH := $(JNI_PATH)

# ------------------------------------------------------------------ FreeType
# The amalgamation sources from modules.cfg; the default
# include/freetype/config/ftmodule.h registers exactly these modules, so all of
# them have to be compiled in. zlib comes from the NDK sysroot (-lz).
FREETYPE_PATH := $(TH10_TP)/freetype
include $(CLEAR_VARS)
LOCAL_PATH := $(FREETYPE_PATH)
LOCAL_MODULE := freetype
LOCAL_SRC_FILES := \
  src/base/ftbase.c src/base/ftsystem.c src/base/ftinit.c src/base/ftdebug.c \
  src/base/ftbbox.c src/base/ftbdf.c src/base/ftbitmap.c src/base/ftcid.c \
  src/base/ftfstype.c src/base/ftgasp.c src/base/ftglyph.c src/base/ftgxval.c \
  src/base/ftmm.c src/base/ftotval.c src/base/ftpatent.c src/base/ftpfr.c \
  src/base/ftstroke.c src/base/ftsynth.c src/base/fttype1.c src/base/ftwinfnt.c \
  src/autofit/autofit.c \
  src/bdf/bdf.c \
  src/cff/cff.c \
  src/cid/type1cid.c \
  src/gzip/ftgzip.c \
  src/hvf/hvf.c \
  src/lzw/ftlzw.c \
  src/pcf/pcf.c \
  src/pfr/pfr.c \
  src/psaux/psaux.c \
  src/pshinter/pshinter.c \
  src/psnames/psnames.c \
  src/raster/raster.c \
  src/sdf/sdf.c \
  src/sfnt/sfnt.c \
  src/smooth/smooth.c \
  src/svg/svg.c \
  src/truetype/truetype.c \
  src/type1/type1.c \
  src/type42/type42.c \
  src/winfonts/winfnt.c
LOCAL_C_INCLUDES := $(FREETYPE_PATH)/include
LOCAL_EXPORT_C_INCLUDES := $(FREETYPE_PATH)/include
LOCAL_CFLAGS := -O2 -fno-strict-aliasing -DFT2_BUILD_LIBRARY \
  -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable
LOCAL_LDLIBS := -lz
include $(BUILD_STATIC_LIBRARY)

# ---------------------------------------------------------------------- game
include $(CLEAR_VARS)
LOCAL_PATH := $(TH10_PORT)

GAME_SRC := $(patsubst $(TH10_PORT)/%,%,$(wildcard $(TH10_PORT)/game/*.cpp) $(wildcard $(TH10_PORT)/platform/*.cpp))
# Wasm-module glue from the freestanding/browser builds; not part of a native game.
GAME_SRC := $(filter-out game/LegacyBridge.cpp game/LegacyCallbacks.cpp game/Exports.cpp game/Freestanding.cpp,$(GAME_SRC))

HOST_SRC := \
  src/AudioHost.cpp \
  src/FileHost.cpp \
  src/FontHost.cpp \
  src/GraphicsHost.cpp \
  src/Platform.cpp \
  src/TimeHost.cpp \
  android-host/AndroidMain.cpp \
  android-host/SoftFloat.c \
  portable/sdl/Renderer.cpp

LOCAL_MODULE := main
LOCAL_SRC_FILES := $(GAME_SRC) $(HOST_SRC)

LOCAL_C_INCLUDES := \
  $(TH10_PORT)/src \
  $(TH10_PORT)/portable/sdl \
  $(TH10_PORT)/game

# Same code-generation contract as upstream: no FMA contraction (bit-exact float
# order for replays/RNG), no strict aliasing.
LOCAL_CFLAGS   := -O2 -ffp-contract=off -fno-strict-aliasing \
  -DTH_NATIVE_PLATFORM=1 -DTH_SDL3=1
LOCAL_CPPFLAGS := -fno-exceptions -fno-rtti \
  -Wno-misleading-indentation -Wno-parentheses \
  -Wno-unused-variable -Wno-unused-but-set-variable -Wno-sign-compare

LOCAL_SHARED_LIBRARIES := SDL2
LOCAL_STATIC_LIBRARIES := freetype
# GLES 3.0 entry points (glBlitFramebuffer, glVertexAttribDivisor,
# glDrawArraysInstanced, glGenVertexArrays) live in libGLESv3.so, not the
# libGLESv2.so stub; both name the same system library from API 18 on.
LOCAL_LDLIBS := -lGLESv3 -lEGL -landroid -llog -lz -lm -ldl

include $(BUILD_SHARED_LIBRARY)
