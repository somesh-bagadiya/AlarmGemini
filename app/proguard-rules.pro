# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Fix for AutoValue MethodHandle issues with API < 26
# Based on: https://github.com/typetools/checker-framework/issues/4232
-dontwarn java.lang.invoke.MethodHandle
-dontwarn java.lang.invoke.MethodHandles*
-keep class autovalue.shaded.** { *; }
-dontwarn autovalue.shaded.com.google.common.hash.**