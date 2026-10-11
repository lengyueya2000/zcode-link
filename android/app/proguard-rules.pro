# 保留行号信息便于排查线上问题。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ZcDownload 这个 JS 接口靠方法名被网页调用，名字一旦被混淆，blob/data 下载会静默失效。
# AGP 自带的 proguard-android-optimize.txt 已有同样的规则，这里显式写一遍，避免以后换配置时被悄悄破坏。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
