package com.fntv.app.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.LruCache;
import android.util.Log;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 简易图片加载器 - 使用 OkHttp（自动经过 AuthInterceptor 签名）
 * 兼容 Android 4.4+
 */
public class SimpleImageLoader {

    private static final String TAG = "ImageLoader";
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    /** 加载图片（使用 OkHttpClient，自动携带认证头） */
    public static void load(String url, ImageView view, OkHttpClient client) {
        if (url == null || url.isEmpty()) {
            view.setImageBitmap(null);
            view.setBackgroundColor(0xFF333333);
            return;
        }

        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        // 磁盘缓存命中：直接读文件解码，省掉一次网络请求
        Bitmap fromDisk = readDisk(view.getContext(), url);
        if (fromDisk != null) {
            cache.put(url, fromDisk);
            view.setImageBitmap(fromDisk);
            return;
        }

        // Android 4.4 AsyncTask 默认串行，用线程池实现并行下载
        new ImageLoadTask(view, client).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
    }

    /** 带完成回调的加载：背景图用它做双层交叉淡入 */
    public static void loadWithCallback(String url, final ImageView view, OkHttpClient client,
                                        final Runnable onDone) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (onDone != null) view.post(onDone);
            return;
        }
        new ImageLoadTask(view, client, false, onDone).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
    }

    /** 带淡入的加载：背景大图用这个，避免"啪一下"换图 */
    public static void loadFade(String url, final ImageView view, OkHttpClient client) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            if (view.getDrawable() == null) {
                view.setAlpha(0f);
                view.setImageBitmap(cached);
                view.animate().alpha(1f).setDuration(240).start();
            } else {
                view.setImageBitmap(cached);
            }
            return;
        }
        new ImageLoadTask(view, client, true).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, url);
    }

    public static void clearCache() {
        cache.evictAll();
    }

    private static class ImageLoadTask extends AsyncTask<String, Void, Bitmap> {
        private final ImageView imageView;
        private final OkHttpClient client;

        private final boolean fade;

        private final Runnable onDone;

        ImageLoadTask(ImageView imageView, OkHttpClient client) {
            this(imageView, client, false, null);
        }

        ImageLoadTask(ImageView imageView, OkHttpClient client, boolean fade) {
            this(imageView, client, fade, null);
        }

        ImageLoadTask(ImageView imageView, OkHttpClient client, boolean fade, Runnable onDone) {
            this.imageView = imageView;
            this.client = client;
            this.fade = fade;
            this.onDone = onDone;
        }

        @Override
        protected Bitmap doInBackground(String... params) {
            String urlStr = params[0];
            try {
                Request request = new Request.Builder()
                        .url(urlStr)
                        .build();

                Response response = client.newCall(request).execute();
                int code = response.code();
                String contentType = response.header("Content-Type");
                long len = response.body() != null ? response.body().contentLength() : -1;
                // Log.d(TAG, "HTTP " + code + " type=" + contentType + " len=" + len + " " + urlStr);

                if (code != 200) return null;

                // 不是图片就打印并跳过
                if (contentType != null && !contentType.contains("image")) {
                    String body = response.body() != null ? response.body().string() : "";
                    String snippet = body.length() > 200 ? body.substring(0, 200) : body;
                    Log.w(TAG, "NOT_IMAGE: " + snippet);
                    return null;
                }

                // 读取图片字节
                InputStream input = response.body().byteStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = input.read(buf)) != -1) baos.write(buf, 0, n);
                input.close();
                byte[] imageData = baos.toByteArray();

                saveDisk(imageView.getContext(), urlStr, imageData);

                // 三重解码尝试
                Bitmap bitmap = tryDecode(imageData);

                if (bitmap != null) {
                    cache.put(urlStr, bitmap);
                    Log.d(TAG, "OK (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                } else {
                    Log.e(TAG, "DECODE_FAILED");
                }
                return bitmap;
            } catch (Exception e) {
                Log.e(TAG, "FAIL: " + e.getMessage());
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                if (fade) {
                    // 背景大图：从透明淡入，避免硬切换造成的"闪一下"
                    imageView.setAlpha(0f);
                    imageView.setImageBitmap(bitmap);
                    imageView.animate().alpha(1f).setDuration(240).start();
                } else {
                    imageView.setImageBitmap(bitmap);
                }
            } else if (!fade) {
                imageView.setBackgroundColor(0xFF444444);
            }
            if (onDone != null) imageView.post(onDone);
        }
    }

    // ---------- 图片磁盘缓存（cacheDir/img，LRU 200MB） ----------
    private static final long DISK_LIMIT = 200L * 1024 * 1024;

    private static java.io.File imgDir(android.content.Context ctx) {
        java.io.File d = new java.io.File(ctx.getCacheDir(), "img");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String keyOf(String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    private static Bitmap readDisk(android.content.Context ctx, String url) {
        try {
            java.io.File f = new java.io.File(imgDir(ctx), keyOf(url));
            if (!f.exists()) return null;
            byte[] data = readAll(f);
            if (data == null || data.length == 0) return null;
            f.setLastModified(System.currentTimeMillis());
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveDisk(android.content.Context ctx, String url, byte[] data) {
        try {
            java.io.File f = new java.io.File(imgDir(ctx), keyOf(url));
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(data);
            out.close();
            trimDisk(ctx);
        } catch (Exception ignored) { }
    }

    private static void trimDisk(android.content.Context ctx) {
        try {
            java.io.File[] files = imgDir(ctx).listFiles();
            if (files == null || files.length < 200) return;
            long total = 0;
            for (java.io.File f : files) total += f.length();
            if (total <= DISK_LIMIT) return;
            java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
                @Override public int compare(java.io.File a, java.io.File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (java.io.File f : files) {
                if (total <= DISK_LIMIT * 8 / 10) break;
                total -= f.length();
                f.delete();
            }
        } catch (Exception ignored) { }
    }

    private static byte[] readAll(java.io.File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap tryDecode(byte[] data) {
        Bitmap bmp = null;
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inPurgeable = true;
            o.inInputShareable = true;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o); } catch (Exception ignored) {}
        }
        if (bmp == null) {
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length); } catch (Exception ignored) {}
        }
        return bmp;
    }
}
