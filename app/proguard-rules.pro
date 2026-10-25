# Al-Minhaj TV Launcher — R8/ProGuard rules

# The WebView calls every bridge method reflectively by its exact name from
# JavaScript (window.AndroidTV.getX). Renaming or removing any of them breaks
# the launcher, so keep the whole bridge class intact.
-keep class com.ossamah.tvlauncher.AndroidTVBridge { *; }

# The activity is constructed by the Android system (launcher entry points
# declared in AndroidManifest.xml) and referenced by name.
-keep class com.ossamah.tvlauncher.MainActivity { *; }

-keepattributes JavascriptInterface, InnerClasses, Signature, Annotation
