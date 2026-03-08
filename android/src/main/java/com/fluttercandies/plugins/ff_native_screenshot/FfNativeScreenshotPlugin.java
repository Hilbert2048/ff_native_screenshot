package com.fluttercandies.plugins.ff_native_screenshot;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import io.flutter.embedding.engine.plugins.FlutterPlugin;

/**
 * FfNativeScreenshotPlugin
 */
public class FfNativeScreenshotPlugin implements FlutterPlugin, ScreenshotApi.ScreenshotHostApi {

    private static final String TAG = "FfNativeScreenshot";
    private ScreenshotApi.ScreenshotFlutterApi screenshotFlutterApi;
    private Context context;
    private ActivityLifecycleCallbacks callbacks = new ActivityLifecycleCallbacks();
    private Handler handler;
    //private FileObserver fileObserver;
    private ScreenshotDetector detector;
    //private String lastScreenshotFileName;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        ScreenshotApi.ScreenshotHostApi.setup(flutterPluginBinding.getBinaryMessenger(), this);
        screenshotFlutterApi = new ScreenshotApi.ScreenshotFlutterApi(flutterPluginBinding.getBinaryMessenger());
        context = flutterPluginBinding.getApplicationContext();
        if (context instanceof Application) {
            Application application = (Application) context;
            application.registerActivityLifecycleCallbacks(callbacks);
        }
    }


    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        ScreenshotApi.ScreenshotHostApi.setup(binding.getBinaryMessenger(), null);
        screenshotFlutterApi = null;
    }


    @Override
    public void takeScreenshot(ScreenshotApi.Result<byte[]> result) {
        if (callbacks.currentActivity == null) {
            result.success(null);
            return;
        }

        Activity activity = callbacks.currentActivity;
        Window window = activity.getWindow();
        View decorView = window.getDecorView();

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                SurfaceView surfaceView = findSurfaceView(decorView);
                if (surfaceView != null
                        && surfaceView.getHolder().getSurface() != null
                        && surfaceView.getHolder().getSurface().isValid()) {
                    takeSurfaceScreenshot(surfaceView, result, () -> {
                        takeWindowScreenshot(window, decorView, result);
                    });
                } else {
                    takeWindowScreenshot(window, decorView, result);
                }
            } else {
                takeSoftwareScreenshot(decorView, result);
            }
        } catch (Exception e) {
            Log.e(TAG, "takeScreenshot: " + e.getMessage());
            result.error(e);
        }
    }

    private void takeSurfaceScreenshot(SurfaceView surfaceView, ScreenshotApi.Result<byte[]> result, Runnable fallback) {
        int width = surfaceView.getWidth() > 0 ? surfaceView.getWidth() : 1;
        int height = surfaceView.getHeight() > 0 ? surfaceView.getHeight() : 1;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        try {
            PixelCopy.request(surfaceView, bitmap, copyResult -> {
                if (copyResult == PixelCopy.SUCCESS) {
                    takeScreenshotResult(bitmap, result);
                } else {
                    Log.w(TAG, "SurfaceView PixelCopy failed (" + copyResult + "), falling back to Window");
                    bitmap.recycle();
                    fallback.run();
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "SurfaceView PixelCopy threw IllegalArgumentException", e);
            bitmap.recycle();
            fallback.run();
        }
    }

    private void takeWindowScreenshot(Window window, View decorView, ScreenshotApi.Result<byte[]> result) {
        int width = decorView.getWidth();
        int height = decorView.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] loc = new int[2];
        decorView.getLocationInWindow(loc);

        try {
            PixelCopy.request(window, new Rect(loc[0], loc[1], loc[0] + width, loc[1] + height), bitmap, copyResult -> {
                if (copyResult == PixelCopy.SUCCESS) {
                    takeScreenshotResult(bitmap, result);
                } else {
                    Log.w(TAG, "Window PixelCopy failed (" + copyResult + "), falling back to View.draw");
                    bitmap.recycle();
                    takeSoftwareScreenshot(decorView, result);
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Window PixelCopy threw IllegalArgumentException", e);
            bitmap.recycle();
            takeSoftwareScreenshot(decorView, result);
        }
    }

    private void takeSoftwareScreenshot(View view, ScreenshotApi.Result<byte[]> result) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            result.error(new Exception("Invalid view size: " + width + "x" + height));
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        canvas.setBitmap(null);
        takeScreenshotResult(bitmap, result);
    }

    private SurfaceView findSurfaceView(View view) {
        if (view instanceof SurfaceView) {
            return (SurfaceView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                SurfaceView sv = findSurfaceView(group.getChildAt(i));
                if (sv != null) return sv;
            }
        }
        return null;
    }

    @Override
    public void startListeningScreenshot() {
        handler = new Handler(Looper.getMainLooper());

        detector = new ScreenshotDetector(context, () -> {
            handler.post(() -> {
                onTakeScreenshot();
            });
        });
        detector.start();
//
//       if (Build.VERSION.SDK_INT >= 29) {
//           final List<File> files = new ArrayList<>();
//           final List<String> paths = new ArrayList<>();
//           for (Path path : Path.values()) {
//               files.add(new File(path.getPath()));
//               paths.add(path.getPath());
//           }
//           fileObserver = new FileObserver(files) {
//               @Override
//               public void onEvent(int event, final String filename) {
//                   if (event == FileObserver.CREATE) {
//                       handler.post(() -> {
//                           for (String fullPath : paths) {
//                               File file = new File(fullPath + filename);
//                               handleScreenshot(file);
//                           }
//                       });
//                   }
//               }
//           };
//           fileObserver.startWatching();
//       } else {
//           for (final Path path : Path.values()) {
//               fileObserver = new FileObserver(path.getPath()) {
//                   @Override
//                   public void onEvent(int event, final String filename) {
//
//                       File file = new File(path.getPath() + filename);
//                       if (event == FileObserver.CREATE) {
//                           handler.post(() -> {
//                               handleScreenshot(file);
//                           });
//                       }
//                   }
//               };
//               fileObserver.startWatching();
//           }
//       }
    }

//    private void handleScreenshot(File file) {
//        if (file.exists()) {
//            String path = file.getPath();
//            if (lastScreenshotFileName!=path && getMimeType(file.getPath()).contains("image")) {
//                lastScreenshotFileName = path;
//                onTakeScreenshot();
//            }
//        }
//    }

    @Override
    public void stopListeningScreenshot() {

//        if (fileObserver != null) fileObserver.stopWatching();
//        lastScreenshotFileName = null;
        if (detector != null) {
            detector.stop();
            detector = null;
        }
    }

    private void takeScreenshotResult(Bitmap bitmap, ScreenshotApi.Result<byte[]> result) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        byte[] imageInByte = stream.toByteArray();
        result.success(imageInByte);
    }

    private void onTakeScreenshot(String path) {
        try {
            File imageFile = new File(path);
            FileInputStream inputStream = new FileInputStream(imageFile);
            byte[] buffer = new byte[1024];
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            int len = 0;
            while ((len = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            byte[] imageInByte = outputStream.toByteArray();
            if (screenshotFlutterApi != null) {
                screenshotFlutterApi.onTakeScreenshot(imageInByte, (v) -> {
                });
            }
        } catch (Exception e) {
            Log.e("onTakeScreenshot", e.getMessage());
        } finally {

        }
    }


    private void onTakeScreenshot() {
        takeScreenshot(new TakeScreenshotResult());
    }

    class TakeScreenshotResult implements ScreenshotApi.Result<byte[]> {
        @Override
        public void success(byte[] result) {
            if (screenshotFlutterApi != null) {
                screenshotFlutterApi.onTakeScreenshot(result, (v) -> {
                });
            }
        }

        @Override
        public void error(Throwable error) {
            Log.e("takeScreenshot", error.getMessage());
        }
    }

    class ActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {

        private Activity currentActivity;

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {

        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {

        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            currentActivity = activity;
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {

        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            if (currentActivity == activity) {
                currentActivity = null;
            }
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {

        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {

        }
    }

    public static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    public enum Path {
        DCIM(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + File.separator + "Screenshots" + File.separator),
        PICTURES(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + File.separator + "Screenshots" + File.separator);

        final private String path;

        public String getPath() {
            return path;
        }

        Path(String path) {
            this.path = path;
        }
    }
}

