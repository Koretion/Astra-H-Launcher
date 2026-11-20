# Reglas de R8 para el build release.

# Código propio: se conserva completo. Ocupa poco y así no se rompen los nombres que usan
# Binder/AIDL, intents con clase explícita, enums guardados por nombre ni la reflexión de QF.
-keep class com.ts10.inspector.** { *; }

# LiteRT/TensorFlow Lite: la parte nativa accede a las clases Java por nombre.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# Clases ocultas del firmware QF a las que se accede por reflexión.
-dontwarn android.qf.**

# Conserva números de línea en las trazas de error de los informes de diagnóstico.
-keepattributes SourceFile,LineNumberTable
