# Networking stays exactly as written. OkHttp finds its TLS platform through reflection.
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Bio is stored by name in the details cache, so its names are data.
-keep enum in.devh.aadhaarlock.** { *; }

# Shrink and optimize only. Names stay readable in the debug log and in crash traces.
-dontobfuscate
