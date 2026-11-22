Bangla Subtitle Translator - Android icon pack

1. Copy everything inside res/ into app/src/main/res/ (merge folders).
2. AndroidManifest.xml <application>:
     android:icon="@mipmap/ic_launcher"
     android:roundIcon="@mipmap/ic_launcher_round"
3. Play Store listing icon: play-store/ic_launcher_512.png

Adaptive icon: white background (res/values/ic_launcher_background.xml) + transparent clover foreground.
Monochrome layer is used for Android 13+ themed icons.
