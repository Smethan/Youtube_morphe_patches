package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.PopupMenu;

import com.facebook.litho.ComponentHost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Saves the exact community-post image bound to a long-pressed view. */
@SuppressWarnings("unused")
public final class SavePostImagePatch {
    private static final long MAX_IMAGE_BYTES = 50L * 1024L * 1024L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "post-image-download");
        thread.setDaemon(true);
        return thread;
    });

    // Values contain no view reference, so stale entries disappear when YouTube
    // releases recycled image views.
    private static final WeakHashMap<ImageView, Binding> BINDINGS = new WeakHashMap<>();
    private static WeakReference<View> activeLongClickView = new WeakReference<>(null);
    private static WeakReference<ImageView> activeImage = new WeakReference<>(null);

    private static final class Binding {
        String url;
        boolean fallbackInstalled;
        boolean wasClickable;
        boolean wasLongClickable;
        boolean clickDelegated;

        Binding(String url) {
            this.url = url;
        }
    }

    private SavePostImagePatch() {
    }

    /**
     * Injection point in the community image binder. Called for every bind,
     * including an empty model, so a recycled view never keeps its old URL.
     */
    public static void bindImage(ImageView imageView, Object imageModel) {
        if (imageView == null) {
            return;
        }

        String url;
        try {
            url = bestImageUrl(imageModel);
        } catch (Exception ex) {
            Logger.printException(() -> "Post image URL extraction failed", ex);
            url = null;
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            // Image binding normally runs on the UI thread. Do not mutate a View
            // from a background callback if YouTube changes that behavior.
            final String resolvedUrl = url;
            imageView.post(() -> applyBinding(imageView, resolvedUrl));
        } else {
            applyBinding(imageView, url);
        }
    }

    private static void applyBinding(ImageView imageView, String url) {
        Binding previous = BINDINGS.get(imageView);
        if (url == null) {
            if (previous != null) {
                BINDINGS.remove(imageView);
                removeFallback(imageView, previous);
                if (activeImage.get() == imageView) {
                    clearActiveLongClick();
                }
            }
            return;
        }
        if (previous != null) {
            if (!previous.url.equals(url) && activeImage.get() == imageView) {
                clearActiveLongClick();
            }
            previous.url = url;
            return;
        }

        Binding binding = new Binding(url);
        BINDINGS.put(imageView, binding);
        // In Litho feeds, use the existing clickable parent as the gesture
        // target. For non-Litho detail views, install a view-local fallback
        // once this ImageView is attached and its full hierarchy is known.
        imageView.post(() -> installFallbackIfNeeded(imageView, binding));
    }

    /** Observe a normal Litho touch without consuming it or changing its tap route. */
    public static void onComponentHostTouch(ComponentHost host, MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN || BINDINGS.isEmpty()) {
            return;
        }
        try {
            clearActiveLongClick();
            ImageView image = findBoundImageAt(host, event.getRawX(), event.getRawY());
            if (image == null) {
                return;
            }
            View clickTarget = findClickableAncestor(image);
            if (clickTarget == null || clickTarget.isLongClickable()) {
                return;
            }
            Binding binding = BINDINGS.get(image);
            if (binding == null) {
                return;
            }
            removeFallback(image, binding);
            final String pressedUrl = binding.url;
            activeImage = new WeakReference<>(image);
            activeLongClickView = new WeakReference<>(clickTarget);
            clickTarget.setOnLongClickListener(pressed -> {
                ImageView current = activeImage.get();
                Binding currentBinding = BINDINGS.get(image);
                return current == image && currentBinding == binding
                        && pressedUrl.equals(currentBinding.url)
                        && showSaveMenu(image, pressedUrl);
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Post image touch hook failed", ex);
            clearActiveLongClick();
        }
    }

    private static ImageView findBoundImageAt(ComponentHost host, float rawX, float rawY) {
        Rect visible = new Rect();
        for (ImageView image : BINDINGS.keySet()) {
            if (image != null && image.isShown() && isDescendantOf(image, host)
                    && image.getGlobalVisibleRect(visible)
                    && visible.contains((int) rawX, (int) rawY)) {
                return image;
            }
        }
        return null;
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        ViewParent parent = child.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean hasComponentHostAncestor(View imageView) {
        ViewParent parent = imageView.getParent();
        while (parent instanceof View) {
            if (parent instanceof ComponentHost) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static void installFallbackIfNeeded(ImageView imageView, Binding binding) {
        if (BINDINGS.get(imageView) != binding || !imageView.isAttachedToWindow()
                || hasComponentHostAncestor(imageView) || imageView.isLongClickable()) {
            return;
        }
        binding.wasClickable = imageView.isClickable();
        binding.wasLongClickable = imageView.isLongClickable();
        binding.clickDelegated = !binding.wasClickable;
        if (binding.clickDelegated) {
            imageView.setOnClickListener(ignored -> {
                View target = findClickableAncestor(imageView);
                if (target != null) {
                    target.performClick();
                }
            });
        }
        imageView.setOnLongClickListener(pressed -> {
            Binding current = BINDINGS.get(imageView);
            return current == binding && showSaveMenu(imageView, binding.url);
        });
        binding.fallbackInstalled = true;
    }

    private static void removeFallback(ImageView imageView, Binding binding) {
        if (!binding.fallbackInstalled) {
            return;
        }
        imageView.setOnLongClickListener(null);
        imageView.setLongClickable(binding.wasLongClickable);
        if (binding.clickDelegated) {
            imageView.setOnClickListener(null);
            imageView.setClickable(binding.wasClickable);
        }
        binding.fallbackInstalled = false;
    }

    private static void clearActiveLongClick() {
        View view = activeLongClickView.get();
        if (view != null) {
            view.setOnLongClickListener(null);
            view.setLongClickable(false);
        }
        activeLongClickView = new WeakReference<>(null);
        activeImage = new WeakReference<>(null);
    }

    private static View findClickableAncestor(View imageView) {
        ViewParent parent = imageView.getParent();
        while (parent instanceof View) {
            View view = (View) parent;
            if (view.isClickable()) {
                return view;
            }
            parent = view.getParent();
        }
        return null;
    }

    private static boolean showSaveMenu(ImageView imageView, String pressedUrl) {
        Binding binding = BINDINGS.get(imageView);
        if (binding == null || pressedUrl == null || !imageView.isAttachedToWindow()) {
            return false;
        }
        // The URL was captured with the gesture. A feed rebind while the popup
        // is open cannot switch the download to another post's image.
        try {
            PopupMenu popup = new PopupMenu(imageView.getContext(), imageView);
            popup.getMenu().add(str("smethan_save_post_image"));
            popup.setOnMenuItemClickListener(item -> {
                Context context = imageView.getContext().getApplicationContext();
                Utils.showToastShort(str("smethan_save_post_image_saving"));
                DOWNLOADS.execute(() -> download(context, pressedUrl));
                return true;
            });
            popup.show();
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Post image save menu failed", ex);
            return false;
        }
    }

    private static String bestImageUrl(Object imageModel) throws ReflectiveOperationException {
        if (imageModel == null) {
            return null;
        }
        Object listValue = imageModel.getClass().getField("c").get(imageModel);
        if (!(listValue instanceof Iterable<?>)) {
            return null;
        }

        String best = null;
        long bestArea = -1;
        for (Object variant : (Iterable<?>) listValue) {
            if (variant == null) {
                continue;
            }
            Class<?> variantClass = variant.getClass();
            Field urlField = variantClass.getField("c");
            Object urlValue = urlField.get(variant);
            if (!(urlValue instanceof String)) {
                continue;
            }
            String candidate = normalizeImageUrl((String) urlValue);
            if (candidate == null) {
                continue;
            }
            long width = Math.max(0, variantClass.getField("d").getInt(variant));
            long height = Math.max(0, variantClass.getField("e").getInt(variant));
            long area = width * height;
            if (best == null || area >= bestArea) {
                best = candidate;
                bestArea = area;
            }
        }
        return best;
    }

    private static String normalizeImageUrl(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("//")) {
            candidate = "https:" + candidate;
        } else if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        try {
            URL url = new URL(candidate);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || !isImageHost(url.getHost())) {
                return null;
            }
            return url.toExternalForm();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isImageHost(String hostname) {
        String host = hostname.toLowerCase(Locale.ROOT);
        return host.equals("ggpht.com") || host.endsWith(".ggpht.com")
                || host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com")
                || host.equals("ytimg.com") || host.endsWith(".ytimg.com");
    }

    private static HttpURLConnection openImage(String sourceUrl) throws IOException {
        URL url = new URL(sourceUrl);
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (normalizeImageUrl(url.toExternalForm()) == null) {
                throw new IOException("Unexpected image host");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                return connection;
            }
            if (status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 3) {
                    throw new IOException("Image redirect failed");
                }
                url = new URL(url, location);
            } else {
                connection.disconnect();
                throw new IOException("Image response " + status);
            }
        }
        throw new IOException("Too many image redirects");
    }

    private static void download(Context context, String sourceUrl) {
        Uri item = null;
        HttpURLConnection connection = null;
        ContentResolver resolver = context.getContentResolver();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw new IOException("Downloads storage requires Android 10");
            }
            connection = openImage(sourceUrl);
            String mimeType = connection.getContentType();
            if (mimeType == null) {
                throw new IOException("Image content type missing");
            }
            mimeType = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            String extension = switch (mimeType) {
                case "image/jpeg" -> "jpg";
                case "image/png" -> "png";
                case "image/webp" -> "webp";
                case "image/gif" -> "gif";
                case "image/avif" -> "avif";
                case "image/heif" -> "heif";
                case "image/heic" -> "heic";
                default -> throw new IOException("Unsupported image content type");
            };
            if (connection.getContentLengthLong() > MAX_IMAGE_BYTES) {
                throw new IOException("Image exceeds size limit");
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "YouTube-post-" + System.currentTimeMillis() + "-"
                            + UUID.randomUUID().toString().substring(0, 8) + "." + extension);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/YouTube");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            item = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (item == null) {
                throw new IOException("Cannot create Pictures entry");
            }

            try (InputStream input = connection.getInputStream();
                 OutputStream output = resolver.openOutputStream(item, "w")) {
                if (output == null) {
                    throw new IOException("Cannot open Pictures entry");
                }
                byte[] buffer = new byte[64 * 1024];
                long copied = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > MAX_IMAGE_BYTES) {
                        throw new IOException("Image exceeds size limit");
                    }
                    output.write(buffer, 0, read);
                }
                if (copied == 0) {
                    throw new IOException("Empty image response");
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(item, ready, null, null) != 1) {
                throw new IOException("Could not publish image in Pictures");
            }
            MAIN.post(() -> Utils.showToastShort(str("smethan_save_post_image_saved")));
        } catch (Exception ex) {
            if (item != null) {
                try {
                    resolver.delete(item, null, null);
                } catch (Exception cleanupError) {
                    ex.addSuppressed(cleanupError);
                }
            }
            Logger.printException(() -> "Post image download failed", ex);
            MAIN.post(() -> Utils.showToastShort(str("smethan_save_post_image_failed")));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
