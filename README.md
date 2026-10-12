# Hangly for Android

A charm on a chain that hangs from the top of your screen, over every app. It swings when you
drag it, tilt the phone, or shake it, then settles. Same idea as the Windows Hangly app.

## Get the APK (no Android Studio needed)
1. Create a free GitHub repository and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, then **Build APK**, then **Run workflow** (it also runs on every push).
3. When it finishes (about 3 minutes), open the run and download the **Hangly-apk** artifact. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone and open it. Allow "install unknown apps" for your file manager or browser when asked.

## Or build in Android Studio
Open this folder, let Gradle sync, then Run (or Build > Build APK).

## First run
1. Open Hangly, turn on **Show pendant on screen**.
2. Android sends you to **Display over other apps**. Allow it for Hangly and come back; it switches on by itself.
3. Allow notifications (Android 13+). The small notification keeps it running and has a **Stop** button.

## Using your own picture
**Choose image** accepts any photo. A PNG with a transparent background looks best. For a JPG or white
background, keep "Remove a plain background" ticked.

## Notes
- The pendant is drawn at 80% opacity: Android 12+ blocks taps through overlays that are more opaque.
- Only the pendant itself catches touches; everything else stays tappable.
- Some phones (Xiaomi, Oppo, Vivo, Samsung) kill background apps. If it vanishes, set Hangly's battery to "Unrestricted".
