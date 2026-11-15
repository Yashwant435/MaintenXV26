# MaintenX Android (WebView app)

Wraps `app/src/main/assets/index.html` in a native Android app and builds a
debug APK on GitHub Actions.

## Build on GitHub
1. Push this project to a GitHub repository (root of the repo = this folder).
2. Open the **Actions** tab -> **Build Android APK** -> **Run workflow**
   (it also runs on every push to `main`).
3. When the run is green, open it and download **MaintenX-debug-apk** from
   the *Artifacts* section (a zip containing `MaintenX-v26-debug.apk`).
4. Unzip, copy the APK to the phone, tap it and allow "Install unknown apps".

## Updating the app
Replace `app/src/main/assets/index.html` with the new version, commit, and the
workflow rebuilds the APK. Bump `versionCode` in `app/build.gradle` for each
release so Android treats it as an update.

## Notes
- Minimum Android 10 (API 29).
- Photos: the picker offers gallery and camera.
- CSV / JSON exports are saved to the phone's **Downloads** folder.
- Report / dashboard exports open in a preview; tap **Print / PDF** and choose
  *Save as PDF*.
- Data is stored inside the app (separate from data saved in a browser), so use
  the app's backup/restore to move existing data across.
- This is a debug-signed build for testing. A Play Store release needs a
  release keystore.
