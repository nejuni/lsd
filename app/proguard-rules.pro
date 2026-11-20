# No reflection, no native code.
# commons-compress references optional compression libraries that are not bundled (this app only uses TAR).
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
