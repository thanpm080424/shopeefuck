# Flutter wrapper
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

# Bỏ qua cảnh báo thiếu thư viện Google Play Core (Gây ra lỗi build)
-dontwarn com.google.android.play.core.**
-dontwarn io.flutter.embedding.**

# Keep our service class name so Android can instantiate it
-keep class com.example.hkpickclone.AutoPickService
-keep class com.example.hkpickclone.MainActivity

# Keep ML Kit internals working properly
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# Cho phép obfuscate toàn bộ các file còn lại!
