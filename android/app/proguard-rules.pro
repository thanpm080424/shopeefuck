# Tối ưu hóa bytecode và gom phẳng toàn bộ class nội bộ
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'o'
-overloadaggressively

# XÓA SẠCH 100% LOG TRONG BẢN RELEASE (Không để lộ bất kỳ chuỗi HK_BENCHMARK, BEST_ORDER, INTEGRITY_CHECK nào trong classes.dex)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Xóa thông tin tên file nguồn và số dòng để JADX không thể tái tạo cấu trúc
-renamesourcefileattribute SourceFile

# Chỉ giữ lại đúng tên classEntryPoint bắt buộc của AndroidManifest (nhưng làm rối toàn bộ hàm và biến private bên trong nó!)
-keep public class com.example.hkpickclone.MainActivity {
    public <init>();
}
-keep public class com.example.hkpickclone.AutoPickService {
    public <init>();
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
    protected void onServiceConnected();
}

# Keep ML Kit internals working properly
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.play.core.**
-dontwarn io.flutter.embedding.**

# Giữ quy tắc chuẩn cho Flutter & Firebase
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }
-keep class com.google.firebase.** { *; }
