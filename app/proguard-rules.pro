# 本模块 release 也不开启混淆（见 build.gradle）。
# 万一以后开启 minify，这些 keep 规则保证反射与 LSPosed 入口可用。
-keep class com.edgeguard.gesture.xposed.** { *; }
-keep class com.edgeguard.gesture.common.** { *; }
-keep class com.edgeguard.gesture.app.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
