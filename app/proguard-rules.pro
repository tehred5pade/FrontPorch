# NewPipeExtractor resolves media URLs via reflection and runs YouTube's signature
# deciphering JavaScript through Rhino, so these classes must not be renamed/stripped.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }

-dontwarn org.mozilla.javascript.**
-dontwarn org.schabi.newpipe.extractor.**

-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses
