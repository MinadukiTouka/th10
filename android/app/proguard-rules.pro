# No obfuscation-dependent entry points: the game is native and the Java side is
# only the launcher and the SDL activity. Keep SDL's classes reachable from JNI.
-keep class org.libsdl.app.** { *; }
-keep class com.zun.th10.** { *; }
