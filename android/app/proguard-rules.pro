# Tink uses protobuf-lite reflection for key formats.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn android.crypto.hpke.**
# Argon2id for passwords (BouncyCastle); everything else in it is removed by R8
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters$Builder { *; }
-dontwarn org.bouncycastle.**
# libsignal calls back into these classes from native code
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**
# kmp-tor loads the tor binary and uses reflection-ish resource loading; keep it all (can't test R8 on-device here)
-keep class io.matthewnelson.** { *; }
-dontwarn io.matthewnelson.**
