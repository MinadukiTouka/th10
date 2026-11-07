# ndk-build application settings. AGP overrides APP_ABI / APP_PLATFORM from
# build.gradle.kts; the STL choice and optimisation level live here.
APP_STL := c++_static
APP_OPTIM := release
APP_PLATFORM := android-26
APP_ABI := arm64-v8a
# 16 KB page alignment for Android 15+ devices.
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
