# WebView 不通过 JS 接口暴露任何原生能力，无需额外 keep 规则。
# 保留行号信息便于排查线上问题。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
