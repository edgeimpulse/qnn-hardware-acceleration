# QNN hardware acceleration on Android with Edge Impulse

This is an Android sample app that runs an Edge Impulse object detection model on live camera frames. It's set up to offload inference to the Hexagon NPU on Qualcomm® SoCs through the Qualcomm AI Engine Direct (QNN) TensorFlow Lite delegate.

The app reads frames from the camera with Camera2, runs the Edge Impulse C++ inferencing SDK through JNI, and draws the bounding boxes it gets back over the preview.

> [!WARNING]
> This sample is a work in progress. The default build doesn't compile in the QNN delegate, so the app runs on the CPU until you complete [Enable the QNN delegate](#enable-the-qnn-delegate).

![The sample app on an Android device, showing a camera view of a model car park with red bounding boxes labeled "vehicle" and a confidence score, and a status line reading "Detections: 7".](https://github.com/user-attachments/assets/aff006dc-1129-4e8a-a56a-5fbc05fc0472)

## What's in the repository

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/example/test_camera/` | Kotlin app: Camera2 preview on a `TextureView`, YUV to RGB888 conversion, the bounding box overlay, and the JNI calls |
| `app/src/main/cpp/native-lib.cpp` | JNI bridge: takes a 640x480 RGB888 frame, calls `run_classifier()`, and returns boxes and timing to Kotlin |
| `app/src/main/cpp/CMakeLists.txt` | Native build for the Edge Impulse SDK, the model, and the prebuilt TensorFlow Lite static libraries |
| `app/src/main/cpp/tflite/` | The Edge Impulse C++ library export (`edge-impulse-sdk/`, `model-parameters/`, `tflite-model/`) and the TensorFlow Lite download scripts |
| `app/src/main/cpp/tensorflow-lite/` | TensorFlow Lite headers used by the full TensorFlow Lite engine |
| `app/src/main/fetchqnnlibs.sh` | Work-in-progress script that downloads the QAIRT SDK and copies its Android libraries (see [the note on this script](#about-fetchqnnlibssh)) |

The bundled model is a Faster Objects, More Objects (FOMO) detector with one class, `vehicle`. It takes a 320x320 int8-quantized input and runs on the full TensorFlow Lite engine (`EI_CLASSIFIER_USE_FULL_TFLITE=1`). To use your own model, see [Use your own model](#use-your-own-model).

## Requirements

- Android Studio with the Android SDK, NDK 27.1.12297006, and CMake 3.22.1. The versions come from `app/build.gradle.kts`, and you can install them from the SDK Manager.
- An arm64-v8a Android device running Android 9 (API level 28) or later, with a camera.
- To use the QNN delegate, a device with a Qualcomm SoC that has a Hexagon NPU, and the [Qualcomm AI Runtime (QAIRT) SDK](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk).
- `wget` and `unzip`, which `download_tflite_libs.sh` uses. On Windows, use `download_tflite_libs.bat`.

## Build and run on the CPU

1. Clone the repository:

   ```sh
   git clone https://github.com/edgeimpulse/qnn-hardware-acceleration.git
   cd qnn-hardware-acceleration
   ```

2. Download the prebuilt TensorFlow Lite libraries for Android arm64. The script saves them to `app/src/main/cpp/tflite/android64/`, which git ignores:

   ```sh
   bash app/src/main/cpp/tflite/download_tflite_libs.sh
   ```

   On Windows, run `app\src\main\cpp\tflite\download_tflite_libs.bat` instead.

3. Open the repository root in Android Studio and wait for the Gradle sync to finish.

4. Connect your device with USB debugging turned on, then select **Run**.

5. Grant camera access when the app asks for it. The preview should show boxes around any vehicles the model detects.

The app logs timing for every frame under the `MainActivity` tag:

```sh
adb logcat -s MainActivity
```

- `EI_timing_us` gives the DSP (image processing) and classification times reported by the Edge Impulse SDK, in microseconds.
- `E2E_inference_ms` gives the time for the whole JNI call, in milliseconds.

## Enable the QNN delegate

The Edge Impulse SDK creates the QNN delegate (HTP backend) when it loads the model, but only if you compile with `EI_CLASSIFIER_USE_QNN_DELEGATES`. See `edge-impulse-sdk/classifier/inferencing_engines/tflite_full.h`.

`CMakeLists.txt` defines `EI_CLASSIFIER_ENABLE_QNN_DELEGATE=1`, which the SDK doesn't read. As a result, the delegate code isn't compiled and inference runs on the CPU.

To build with the delegate, do the following.

### 1. Get the QAIRT SDK

Download the SDK from the [Qualcomm AI Engine Direct SDK page](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk) and unzip it. These steps call the unzipped folder `$QNN_SDK_ROOT`.

### 2. Copy the runtime libraries into the app

Create `app/src/main/jniLibs/arm64-v8a/`. Gradle packages any `.so` file in that folder into the APK. Then copy the delegate and the HTP backend libraries into it.

Replace `V##` with the Hexagon architecture version of your SoC. For example, `V73` uses the `hexagon-v73` folder.

```sh
DEST=app/src/main/jniLibs/arm64-v8a
mkdir -p "$DEST"
cp "$QNN_SDK_ROOT"/lib/aarch64-android/libQnnTFLiteDelegate.so "$DEST"/
cp "$QNN_SDK_ROOT"/lib/aarch64-android/libQnnHtp.so "$DEST"/
cp "$QNN_SDK_ROOT"/lib/aarch64-android/libQnnHtpPrepare.so "$DEST"/
cp "$QNN_SDK_ROOT"/lib/aarch64-android/libQnnHtpV##Stub.so "$DEST"/
cp "$QNN_SDK_ROOT"/lib/aarch64-android/libQnnSystem.so "$DEST"/
cp "$QNN_SDK_ROOT"/lib/hexagon-v##/unsigned/libQnnHtpV##Skel.so "$DEST"/
```

If you're not sure which version your SoC uses, copy the `Stub` and `Skel` pair for each version in the SDK. For the full list of backend libraries, see [Backend](https://docs.qualcomm.com/doc/80-63442-10/topic/backend.html) in the QAIRT documentation.

git ignores the `.so` files, so you have to copy them again in every clone.

### 3. Compile and link the delegate

In `app/src/main/cpp/CMakeLists.txt`:

1. Replace `-DEI_CLASSIFIER_ENABLE_QNN_DELEGATE=1` with `-DEI_CLASSIFIER_USE_QNN_DELEGATES=1`.
2. Add the QAIRT `include` folder to `target_include_directories`. That folder contains `QNN/TFLiteDelegate/QnnTFLiteDelegate.h`.
3. Add `libQnnTFLiteDelegate.so` from `jniLibs/arm64-v8a/` to `target_link_libraries`.

The QAIRT release notes list the TensorFlow Lite version that the delegate supports. Check that it's compatible with the prebuilt TensorFlow Lite libraries from step 2 of [Build and run on the CPU](#build-and-run-on-the-cpu).

### 4. Update the manifest and the packaging options

Android apps that use the QNN delegate also need these two changes:

1. Allow the app to load the FastRPC library from the vendor partition. Add this inside `<application>` in `app/src/main/AndroidManifest.xml`:

   ```xml
   <uses-native-library
       android:name="libcdsprpc.so"
       android:required="false" />
   ```

2. Switch to legacy packaging, so that the native libraries are extracted when the app is installed. Add this to the `android { }` block of `app/build.gradle.kts`:

   ```kotlin
   packaging {
       jniLibs {
           useLegacyPackaging = true
       }
   }
   ```

For details, see [Tutorial - Using TFLite Delegate With a Java Application](https://docs.qualcomm.com/doc/80-63442-10/topic/tutorial_java_application.html) and the Android [`<uses-native-library>`](https://developer.android.com/guide/topics/manifest/uses-native-library-element) reference.

### 5. Environment variables

`MainActivity.onCreate()` sets these variables before the model loads, so you don't need to change anything:

| Variable | Value |
| --- | --- |
| `ADSP_LIBRARY_PATH` | The app's `nativeLibraryDir`, followed by the system DSP paths |
| `LD_LIBRARY_PATH` | The app's `nativeLibraryDir`, followed by the existing value |
| `QNN_LOG_LEVEL` | `info` |
| `QNN_TFLITE_DELEGATE_OPTIONS` | `{"backend":"htp","profiling":true,"profiling_file_path":"/sdcard/qnn_profile.json"}` |

### 6. Check that the delegate loaded

If the delegate fails to load, `run_classifier()` returns an error, and the native code logs it as `run_classifier rc=<code>` under the `MAIN` tag:

```sh
adb logcat -s MAIN
```

When the delegate loads, its libraries appear in the app's memory map:

```sh
adb shell 'cat /proc/$(pidof -s com.example.test_camera)/maps | grep -i qnn'
```

You can also look for QNN log lines in `adb logcat`. Seeing the delegate in the memory map shows that it loaded. It doesn't show how many operations it took over from the CPU.

## Measure performance

Speed depends on the model, the SoC, the QAIRT version, and the device's thermal state. To compare the CPU and the NPU on your device:

1. Build and run the CPU version, wait for the timings to settle, and record `EI_timing_us cls=` and `E2E_inference_ms` over at least a few hundred frames.
2. Enable the delegate, confirm that it loaded, and repeat the measurement in the same conditions.
3. Compare the medians, not single frames. The first frames include model loading and graph preparation.

When you share results, give the device model, SoC, Android version, QAIRT version, model, and input size.

## Use your own model

1. In Edge Impulse Studio, deploy your project as a **C++ library**. See [Deploy your model as a C++ library](https://docs.edgeimpulse.com/docs/run-inference/cpp-library/deploy-your-model-as-a-c-library).
2. Replace `edge-impulse-sdk/`, `model-parameters/`, and `tflite-model/` in `app/src/main/cpp/tflite/` with the folders from the export. Keep this repository's `CMakeLists.txt`.
3. If your model isn't an object detector, change how `MainActivity.kt` and `native-lib.cpp` handle results.

For more on running Edge Impulse models in Android apps, see [Running your impulse on Android](https://docs.edgeimpulse.com/docs/run-inference/cpp-library/running-your-impulse-android) and the [example-android-inferencing](https://github.com/edgeimpulse/example-android-inferencing) repository.

## About fetchqnnlibs.sh

`app/src/main/fetchqnnlibs.sh` is a work in progress. Don't rely on it yet, and copy the libraries by hand as described in [step 2](#2-copy-the-runtime-libraries-into-the-app).

The script has these known issues:

- It downloads QAIRT 2.39.0.250926, a large download, unless you pass `--zip-file <path>` to point it at a zip you already have.
- It needs bash 4 or later, because it uses `mapfile`. Run it with `bash`, not `sh`. The default bash on macOS is version 3.2.
- It copies to `app/src/main/arm64-v8a/`, but Gradle packages native libraries from `app/src/main/jniLibs/arm64-v8a/`.
- Its filter matches the `.so` files for every target in the SDK, not just Android. Libraries with the same name overwrite each other.

## Troubleshooting

- **The app shows "Camera permission required."** Grant camera access in the device settings, then restart the app.
- **The app shows "No Camera2 IDs from HAL."** The device didn't report a camera to Camera2. Connect a camera or use a different device.
- **Logcat shows `Bad features size`.** The frame passed to `passToCpp()` isn't 640x480 RGB888. Check `streamSize` in `MainActivity.kt`.
- **Build fails with `libtensorflow-lite.a` not found.** Run `download_tflite_libs.sh` (see [Build and run on the CPU](#build-and-run-on-the-cpu)).
- **Build fails with `QnnTFLiteDelegate.h` not found.** Add the QAIRT `include` folder to the CMake include path (see [step 3](#3-compile-and-link-the-delegate)).
- **AAPT reports an error for `<uses-native-library>`.** The element must be inside `<application>`.
- **The delegate fails to load at run time.** Check that the `Stub` and `Skel` libraries match your SoC's Hexagon version, that `libcdsprpc.so` is declared in the manifest, and that `useLegacyPackaging` is set.

## Get help

Ask questions on the [Edge Impulse forum](https://forum.edgeimpulse.com). For problems with this sample, open an issue in this repository.

## License

This project is licensed under the BSD 3-Clause Clear License. See [LICENSE.3-clause-bsd-clear](LICENSE.3-clause-bsd-clear). The QAIRT SDK libraries aren't part of this repository and come with their own license from Qualcomm Technologies, Inc.

Qualcomm is a trademark or registered trademark of Qualcomm Incorporated.
