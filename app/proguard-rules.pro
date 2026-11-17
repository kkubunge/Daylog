# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# kotlinx.serialization 난독화 예외 설정
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.aiutil.daylog.** {
    *** Companion;
}
-keepclasseswithmembers class com.aiutil.daylog.** {
    kotlinx.serialization.KSerializer serializer(...);
}
