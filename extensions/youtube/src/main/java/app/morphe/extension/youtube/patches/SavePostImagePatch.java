package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Adds image-saving actions to YouTube's native community-post menu. */
@SuppressWarnings("unused")
public final class SavePostImagePatch {
    private static final long MAX_IMAGE_BYTES = 50L * 1024L * 1024L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "post-image-download");
        thread.setDaemon(true);
        return thread;
    });

    private static WeakReference<ListView> activeList = new WeakReference<>(null);
    private static List<WeakReference<View>> activeFooters = new ArrayList<>();
    private static WeakReference<ViewGroup> activeAlternateRoot = new WeakReference<>(null);
    private static WeakReference<PopupWindow> activeAlternatePopup = new WeakReference<>(null);
    private static WeakReference<Object> activeAlternateController = new WeakReference<>(null);
    private static volatile long menuGeneration;
    private static final WeakHashMap<ImageView, String> BOUND_POST_IMAGES = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<View>> ANCHOR_ROWS = new WeakHashMap<>();

    private SavePostImagePatch() {
    }

    /** Records the URL actually bound to a backstage image view, including carousel rebinds. */
    public static void bindVisiblePostImage(ImageView imageView, Object imageModel) {
        if (imageView == null) return;
        String url = null;
        try {
            url = bestImageUrl(imageModel);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read a bound post image", ex);
        }
        final String boundUrl = url;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            recordBoundImage(imageView, boundUrl);
        } else {
            imageView.post(() -> recordBoundImage(imageView, boundUrl));
        }
    }

    private static void recordBoundImage(ImageView imageView, String url) {
        if (url == null) BOUND_POST_IMAGES.remove(imageView);
        else BOUND_POST_IMAGES.put(imageView, url);
    }

    /** Associates YouTube's menu anchor with the row it belongs to. */
    public static void bindPostMenuAnchor(View rowRoot, View anchor) {
        if (rowRoot != null && anchor != null) {
            ANCHOR_ROWS.put(anchor, new WeakReference<>(rowRoot));
        }
    }

    /**
     * Called at the entry of YouTube's post-menu opening method. The native menu
     * remains responsible for showing itself; actions are appended after either
     * native popup implementation has populated and shown.
     */
    public static void onPostMenuOpening(View anchor, Object menuController, Object menuTag) {
        if (anchor == null || menuController == null) {
            return;
        }

        // The current image must come from the visible bound view. The post
        // model's first image is not necessarily the selected carousel image.
        final String currentUrl = findVisibleBoundImage(anchor);
        final String postId = postIdFromTag(menuTag);
        final long generation;
        synchronized (SavePostImagePatch.class) {
            generation = ++menuGeneration;
        }
        MAIN.post(() -> {
            if (generation != menuGeneration) {
                return;
            }
            removeActiveFooters();
            if (currentUrl == null && postId == null) {
                return;
            }
            try {
                ListPopupWindow listPopup = getShowingListPopup(menuController);
                if (listPopup != null) {
                    addListPopupActions(anchor, listPopup, currentUrl, postId, generation);
                    return;
                }
                addAlternatePopupActions(anchor, menuController, currentUrl, postId, generation);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not append actions to the YouTube community menu popup", ex);
            }
        });
    }

    private static void addListPopupActions(View anchor, ListPopupWindow popup,
            String currentUrl, String postId, long generation) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Logger.printException(() -> "ListPopupWindow footer insertion requires Android 10+; alternate PopupWindow path was not active");
            return;
        }
        ListView list = popup.getListView();
        if (list == null || !popup.isShowing()) {
            Logger.printException(() -> "Post image actions unavailable: shown ListPopupWindow has no ListView");
            return;
        }
        Context context = anchor.getContext();
        View current = makeFooter(context, str("smethan_save_post_image_current"));
        View all = makeFooter(context, str("smethan_save_post_image_all"));
        current.setOnClickListener(view -> {
            if (generation != menuGeneration) return;
            removeActiveFooters();
            popup.dismiss();
            saveCurrent(context, anchor, currentUrl);
        });
        all.setOnClickListener(view -> {
            if (generation != menuGeneration) return;
            removeActiveFooters();
            popup.dismiss();
            saveAll(context, postId);
        });
        // Keep YouTube's adapter and rows intact; non-selectable footers do not
        // add synthetic positions to its native menu click handler.
        list.addFooterView(current, null, false);
        list.addFooterView(all, null, false);
        activeList = new WeakReference<>(list);
        activeFooters = new ArrayList<>(2);
        activeFooters.add(new WeakReference<>(current));
        activeFooters.add(new WeakReference<>(all));
        popup.show();
    }

    private static void addAlternatePopupActions(View anchor, Object controller,
            String currentUrl, String postId, long generation) throws ReflectiveOperationException {
        Object wrapper = getShowingAlternateController(controller);
        if (wrapper == null) {
            Logger.printException(() -> "Post image actions unavailable: neither auoo.u nor the active auoo.e PopupWindow path is showing");
            return;
        }
        String wrapperName = wrapper.getClass().getName();
        PopupWindow popup;
        ViewGroup footerParent;
        Object sizingContainer = null;
        boolean hasCustomAuthLayout = false;
        if ("defpackage.auth".equals(wrapperName)) {
            popup = readPopupField(wrapper, "d");
            Object customLayout = readField(wrapper, "e"); // autf recalculates its bounded dimensions on layout.
            hasCustomAuthLayout = customLayout != null;
            footerParent = customLayout == null ? null : readViewGroupField(customLayout, "d");
            if (footerParent == null) {
                footerParent = findMenuLinearLayout(popup == null ? null : popup.getContentView());
            }
        } else if ("defpackage.autk".equals(wrapperName)) {
            popup = readPopupField(wrapper, "c");
            sizingContainer = readField(wrapper, "d"); // autm owns menu measurement and placement.
            footerParent = sizingContainer == null ? null : readViewGroupField(sizingContainer, "a");
        } else {
            Logger.printException(() -> "Post image actions unavailable: unsupported autj implementation " + wrapperName);
            return;
        }
        if (popup == null || !popup.isShowing() || footerParent == null) {
            Logger.printException(() -> "Post image actions unavailable: active " + wrapperName + " popup or native footer container could not be resolved");
            return;
        }

        Context context = anchor.getContext();
        View current = makeFooter(context, str("smethan_save_post_image_current"));
        View all = makeFooter(context, str("smethan_save_post_image_all"));
        current.setOnClickListener(view -> runAlternateAction(generation, context, anchor, currentUrl, postId,
                true, wrapper, popup));
        all.setOnClickListener(view -> runAlternateAction(generation, context, anchor, currentUrl, postId,
                false, wrapper, popup));
        footerParent.addView(current, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        footerParent.addView(all, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activeAlternateRoot = new WeakReference<>(footerParent);
        activeAlternatePopup = new WeakReference<>(popup);
        activeAlternateController = new WeakReference<>(wrapper);
        activeFooters = new ArrayList<>(2);
        activeFooters.add(new WeakReference<>(current));
        activeFooters.add(new WeakReference<>(all));

        footerParent.requestLayout();
        View content = popup.getContentView();
        if (content != null) content.requestLayout();
        if (sizingContainer != null) {
            // autk's autm.c() recomputes the popup's bounded height and location
            // from all children. Apply that placement to prevent fixed-size
            // PopupWindow clipping when the two footer rows are appended.
            resizeAutkPopup(sizingContainer, popup);
        } else if (!hasCustomAuthLayout) {
            // The undecorated auth variant uses a wrap-content LinearLayout as
            // PopupWindow content, so refresh its measured height explicitly.
            popup.update(popup.getWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void runAlternateAction(long generation, Context context, View anchor,
            String currentUrl, String postId, boolean saveCurrent, Object wrapper, PopupWindow popup) {
        if (generation != menuGeneration) return;
        removeActiveFooters();
        dismissPopup(popup, wrapper);
        if (saveCurrent) saveCurrent(context, anchor, currentUrl);
        else saveAll(context, postId);
    }

    private static void resizeAutkPopup(Object sizingContainer, PopupWindow popup) {
        try {
            Object placement = sizingContainer.getClass().getMethod("c").invoke(sizingContainer);
            Object size = readField(placement, "b");
            Object point = readField(placement, "c");
            int width = ((Number) size.getClass().getMethod("getWidth").invoke(size)).intValue();
            int height = ((Number) size.getClass().getMethod("getHeight").invoke(size)).intValue();
            int x = sizePointCoordinate(point, "x");
            int y = sizePointCoordinate(point, "y");
            popup.update(x, y, width, height);
        } catch (Exception ex) {
            Logger.printException(() -> "autk PopupWindow footer added but custom size refresh failed; menu may clip the footer", ex);
        }
    }

    private static int sizePointCoordinate(Object point, String fieldName) throws ReflectiveOperationException {
        return point.getClass().getField(fieldName).getInt(point);
    }

    private static void dismissPopup(PopupWindow popup, Object wrapper) {
        try {
            if (wrapper != null) {
                wrapper.getClass().getMethod("c").invoke(wrapper);
            } else if (popup != null && popup.isShowing()) {
                popup.dismiss();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not dismiss community post menu after image action", ex);
        }
    }

    private static View makeFooter(Context context, String label) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(16);
        int horizontal = Math.round(16 * context.getResources().getDisplayMetrics().density);
        int vertical = Math.round(14 * context.getResources().getDisplayMetrics().density);
        view.setPadding(horizontal, vertical, horizontal, vertical);
        view.setMinHeight(Math.round(48 * context.getResources().getDisplayMetrics().density));
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        return view;
    }

    private static void removeActiveFooters() {
        ListView list = activeList.get();
        ViewGroup alternateRoot = activeAlternateRoot.get();
        if (list != null) {
            for (WeakReference<View> footerReference : activeFooters) {
                try {
                    View footer = footerReference.get();
                    if (footer != null) {
                        list.removeFooterView(footer);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not remove stale post image menu footer", ex);
                }
            }
        }
        if (alternateRoot != null) {
            for (WeakReference<View> footerReference : activeFooters) {
                try {
                    View footer = footerReference.get();
                    if (footer != null && footer.getParent() == alternateRoot) {
                        alternateRoot.removeView(footer);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not remove stale alternate-popup image action", ex);
                }
            }
        }
        activeFooters = new ArrayList<>();
        activeList = new WeakReference<>(null);
        activeAlternateRoot = new WeakReference<>(null);
        activeAlternatePopup = new WeakReference<>(null);
        activeAlternateController = new WeakReference<>(null);
    }

    private static ListPopupWindow getShowingListPopup(Object controller) {
        // In this YouTube build the menu controller is auoo and its field u is
        // the active ListPopupWindow. Read only that exact runtime class field.
        try {
            Object popup = readField(controller, "u");
            return popup instanceof ListPopupWindow && ((ListPopupWindow) popup).isShowing()
                    ? (ListPopupWindow) popup : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object getShowingAlternateController(Object controller) {
        try {
            Object wrapper = readField(controller, "e");
            if (wrapper == null || !(wrapper.getClass().getName().equals("defpackage.auth")
                    || wrapper.getClass().getName().equals("defpackage.autk"))) {
                return null;
            }
            Object showing = wrapper.getClass().getMethod("g").invoke(wrapper);
            return Boolean.TRUE.equals(showing) ? wrapper : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static PopupWindow readPopupField(Object owner, String name) throws ReflectiveOperationException {
        Object popup = readField(owner, name);
        return popup instanceof PopupWindow ? (PopupWindow) popup : null;
    }

    private static ViewGroup readViewGroupField(Object owner, String name) throws ReflectiveOperationException {
        Object value = readField(owner, name);
        return value instanceof ViewGroup ? (ViewGroup) value : null;
    }

    private static Object readField(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static ViewGroup findMenuLinearLayout(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        ViewGroup best = null;
        if (root instanceof LinearLayout && containsRecyclerView(root)) {
            best = group;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup candidate = findMenuLinearLayout(group.getChildAt(i));
            if (candidate != null) best = candidate;
        }
        return best;
    }

    private static boolean containsRecyclerView(View root) {
        if (root.getClass().getName().endsWith("RecyclerView")) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsRecyclerView(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static String findVisibleBoundImage(View anchor) {
        WeakReference<View> reference = ANCHOR_ROWS.get(anchor);
        View row = reference == null ? null : reference.get();
        if (row == null || !row.isAttachedToWindow()) return null;
        Rect rowBounds = new Rect();
        if (!row.getGlobalVisibleRect(rowBounds)) return null;
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(row);
        String bestUrl = null;
        long bestArea = 0;
        int examined = 0;
        while (!pending.isEmpty() && examined++ < 256) {
            View view = pending.removeFirst();
            if (view instanceof ImageView) {
                String url = BOUND_POST_IMAGES.get(view);
                if (url != null && view.isShown()) {
                    Rect imageBounds = new Rect();
                    if (view.getGlobalVisibleRect(imageBounds) && imageBounds.intersect(rowBounds)) {
                        long area = (long) imageBounds.width() * imageBounds.height();
                        if (area > bestArea) {
                            bestArea = area;
                            bestUrl = url;
                        }
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.addLast(group.getChildAt(i));
            }
        }
        return bestUrl;
    }

    private static String postIdFromTag(Object menuTag) {
        if (menuTag == null || !"defpackage.aean".equals(menuTag.getClass().getName())) return null;
        try {
            Object post = menuTag.getClass().getField("b").get(menuTag);
            Object id = post.getClass().getField("i").get(post);
            return id instanceof String ? (String) id : null;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the community post ID", ex);
            return null;
        }
    }

    private static void saveCurrent(Context context, View anchor, String openedUrl) {
        // A bind may finish while the menu opens, so resolve once more on click.
        String visibleUrl = findVisibleBoundImage(anchor);
        String url = visibleUrl != null ? visibleUrl : openedUrl;
        if (url == null) {
            Utils.showToastShort(str("smethan_save_post_image_current_unavailable"));
            return;
        }
        saveSnapshot(context, Collections.singletonList(url));
    }

    private static void saveSnapshot(Context context, List<String> snapshot) {
        // Snapshot the currently visible image URL before queuing asynchronous work.
        List<String> immutableUrls = new ArrayList<>(snapshot);
        Context appContext = context.getApplicationContext();
        Utils.showToastShort(str("smethan_save_post_image_saving"));
        for (int i = 0; i < immutableUrls.size(); i++) {
            final int index = i + 1;
            final int count = immutableUrls.size();
            final String url = immutableUrls.get(i);
            try {
                DOWNLOADS.execute(() -> download(appContext, url, index, count));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not queue post image download", ex);
                MAIN.post(() -> Utils.showToastShort(str("smethan_save_post_image_failed")));
            }
        }
    }

    private static void saveAll(Context context, String postId) {
        if (postId == null) {
            Utils.showToastShort(str("smethan_save_post_image_all_unavailable"));
            return;
        }
        Context appContext = context.getApplicationContext();
        Utils.showToastShort(str("smethan_save_post_image_saving_all"));
        try {
            DOWNLOADS.execute(() -> {
                try {
                    List<String> urls = PostImageWebResolver.resolve(postId);
                    for (int i = 0; i < urls.size(); i++) {
                        download(appContext, urls.get(i), i + 1, urls.size());
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not resolve all images for community post " + postId, ex);
                    MAIN.post(() -> Utils.showToastShort(str("smethan_save_post_image_all_unavailable")));
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not queue all post images", ex);
            Utils.showToastShort(str("smethan_save_post_image_failed"));
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
            Object urlValue = variantClass.getField("c").get(variant);
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

    private static void download(Context context, String sourceUrl, int index, int count) {
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
            final String message = count > 1
                    ? "Post image " + index + " of " + count + " saved"
                    : str("smethan_save_post_image_saved");
            MAIN.post(() -> Utils.showToastShort(message));
        } catch (Exception ex) {
            if (item != null) {
                try {
                    resolver.delete(item, null, null);
                } catch (Exception cleanupError) {
                    ex.addSuppressed(cleanupError);
                }
            }
            Logger.printException(() -> "Post image download failed", ex);
            final String message = count > 1
                    ? "Post image " + index + " of " + count + " failed"
                    : str("smethan_save_post_image_failed");
            MAIN.post(() -> Utils.showToastShort(message));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
