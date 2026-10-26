# Tink uses protobuf-lite reflection for key formats.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn android.crypto.hpke.**
# libsignal calls back into these classes from native code
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**
