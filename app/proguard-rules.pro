# Keep the Activities referenced from XML / manifest.
-keep class com.nahla.docscanner.MainActivity { *; }
-keep class com.nahla.docscanner.EditActivity { *; }

# View inflated from XML.
-keep class com.nahla.docscanner.CropOverlayView { *; }

-keepattributes SourceFile,LineNumberTable