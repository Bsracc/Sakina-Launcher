# Screen Guard (experimental Android prototype)

An on-device Android screen-censor prototype. The app requests Android's official MediaProjection consent, reads frames locally, uses ML Kit face detection plus a small TensorFlow Lite face-presentation classifier, then draws opaque overlay rectangles for detected faces classified by the model as female.

## Important limitations

- The classifier estimates visual presentation from a face crop. It does not determine a person's actual gender.
- The body cover is a deliberately broad rectangle estimated from face position and size. It is **not** a human-body segmentation mask and will not precisely follow a person's silhouette.
- Small, turned-away, occluded or undetected faces can be missed. Classification can also be wrong.
- The first prototype is intended for controlled testing, not as a guaranteed filter.
- Android prevents capture of protected/secure surfaces; this app does not attempt to bypass that protection.

## Build

From the repository root:

```bash
./gradlew -p screen-guard :app:assembleDebug
```

The CI workflow downloads the quantized gender model into `app/src/main/assets` before compiling. The model is from the MIT-licensed [Age-Gender Estimation TensorFlow Lite Android project](https://github.com/shubham0204/Age-Gender_Estimation_TF-Android).

## Install

Install the debug APK on an Android device running Android 8.0 or newer. Open Screen Guard, grant the overlay permission, then tap **Mulai sensor layar** and approve Android's screen-capture dialog. Use the ongoing notification's **Hentikan** action to stop it.

No runtime Internet permission is requested. Screen frames are processed in memory and are not saved.
