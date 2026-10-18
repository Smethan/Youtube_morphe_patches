/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the public image URLs attached to a YouTube community post.
 * Network access must be performed off the main thread. This class only reads the public
 * post page; it does not download or save the images.
 */
public final class PostImageWebResolver {
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 5000;
    private static final int READ_TIMEOUT_MILLISECONDS = 7000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_IMAGES = 10;
    private static final String POST_ID_PATTERN = "(?:Ugkx[A-Za-z0-9_-]{32}|Ug[A-Za-z0-9_-]{10,})";

    private PostImageWebResolver() {}

    /**
     * Reads and parses a public post page, returning the ordered original thumbnail URL for each
     * attached image. Throws IOException for network, HTTP, parsing, or missing-post failures.
     */
    public static List<String> resolve(String postId) throws IOException {
        if (postId == null || !postId.matches(POST_ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid YouTube community post ID");
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://www.youtube.com/post/" + postId);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/html");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("YouTube post page returned HTTP " + status);
            }
            String contentType = connection.getContentType();
            if (contentType != null && !contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html")) {
                throw new IOException("YouTube post page did not return HTML");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new IOException("YouTube post page exceeds the 2 MiB response limit");
            }

            byte[] body;
            try (InputStream stream = connection.getInputStream()) {
                body = readBounded(stream);
            }
            return extract(new String(body, StandardCharsets.UTF_8), postId);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static List<String> extract(String html, String postId) throws IOException {
        String initialData = findInitialData(html);
        if (initialData == null) {
            throw new IOException("YouTube post page did not contain ytInitialData");
        }

        final JSONObject root;
        try {
            root = new JSONObject(initialData);
        } catch (JSONException ex) {
            throw new IOException("Could not parse YouTube post data", ex);
        }

        JSONObject post = findPostRenderer(root, postId);
        if (post == null) {
            throw new IOException("Post was not found in YouTube page data");
        }

        JSONObject attachment = post.optJSONObject("backstageAttachment");
        if (attachment == null) {
            throw new IOException("Post has no image attachment");
        }

        List<String> urls = new ArrayList<>();
        JSONObject multiImage = attachment.optJSONObject("postMultiImageRenderer");
        if (multiImage != null) {
            JSONArray images = multiImage.optJSONArray("images");
            if (images != null) {
                for (int i = 0; i < images.length() && urls.size() < MAX_IMAGES; i++) {
                    JSONObject item = images.optJSONObject(i);
                    JSONObject imageRenderer = item == null ? null : item.optJSONObject("backstageImageRenderer");
                    addBestThumbnail(urls, imageRenderer);
                }
            }
        } else {
            addBestThumbnail(urls, attachment.optJSONObject("backstageImageRenderer"));
        }

        if (urls.isEmpty()) {
            throw new IOException("No approved image URLs were found in the post");
        }
        return Collections.unmodifiableList(urls);
    }

    @Nullable
    private static JSONObject findPostRenderer(Object node, String postId) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject renderer = object.optJSONObject("backstagePostRenderer");
            if (renderer != null && postId.equals(renderer.optString("postId", null))) {
                return renderer;
            }
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) {
                JSONObject found = findPostRenderer(object.opt(keys.next()), postId);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findPostRenderer(array.opt(i), postId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void addBestThumbnail(List<String> urls, @Nullable JSONObject imageRenderer) {
        if (imageRenderer == null || urls.size() >= MAX_IMAGES) return;
        JSONObject image = imageRenderer.optJSONObject("image");
        JSONArray thumbnails = image == null ? null : image.optJSONArray("thumbnails");
        if (thumbnails == null) return;

        String bestUrl = null;
        long bestArea = -1;
        for (int i = 0; i < thumbnails.length(); i++) {
            JSONObject thumbnail = thumbnails.optJSONObject(i);
            if (thumbnail == null) continue;
            String candidate = thumbnail.optString("url", null);
            if (!isApprovedImageUrl(candidate)) continue;

            long width = Math.max(0, thumbnail.optLong("width", 0));
            long height = Math.max(0, thumbnail.optLong("height", 0));
            long area = width * height;
            if (bestUrl == null || area > bestArea) {
                bestUrl = candidate;
                bestArea = area;
            }
        }
        if (bestUrl != null) urls.add(bestUrl);
    }

    private static boolean isApprovedImageUrl(@Nullable String candidate) {
        if (candidate == null || candidate.length() > 4096) return false;
        try {
            URL url = new URL(candidate);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)) return false;
            String host = url.getHost().toLowerCase(java.util.Locale.ROOT);
            return host.equals("ytimg.com") || host.endsWith(".ytimg.com")
                    || host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com")
                    || host.equals("ggpht.com") || host.endsWith(".ggpht.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    @Nullable
    private static String findInitialData(String html) {
        String[] markers = {"var ytInitialData = ", "ytInitialData = ", "window[\"ytInitialData\"] = "};
        for (String marker : markers) {
            int markerAt = html.indexOf(marker);
            if (markerAt < 0) continue;
            int start = markerAt + marker.length();
            while (start < html.length() && Character.isWhitespace(html.charAt(start))) start++;
            if (start >= html.length()) continue;
            char first = html.charAt(start);
            if (first == '{') {
                int end = findJsonObjectEnd(html, start);
                if (end > start) return html.substring(start, end + 1);
            } else if (first == '\'' || first == '"') {
                // YouTube also serves ytInitialData as a JavaScript string with
                // every JSON character escaped (for example, \\x7b for '{').
                String decoded = decodeJavaScriptString(html, start);
                if (decoded != null && decoded.startsWith("{")) return decoded;
            }
        }
        return null;
    }

    @Nullable
    private static String decodeJavaScriptString(String html, int quoteAt) {
        char quote = html.charAt(quoteAt);
        StringBuilder decoded = new StringBuilder();
        for (int i = quoteAt + 1; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == quote) return decoded.toString();
            if (c != '\\') {
                decoded.append(c);
                continue;
            }
            if (++i >= html.length()) return null;
            char escape = html.charAt(i);
            if (escape == 'x' || escape == 'u') {
                int digits = escape == 'x' ? 2 : 4;
                if (i + digits >= html.length()) return null;
                int value = 0;
                for (int j = 0; j < digits; j++) {
                    int digit = Character.digit(html.charAt(++i), 16);
                    if (digit < 0) return null;
                    value = (value << 4) | digit;
                }
                decoded.append((char) value);
            } else if (escape == '\\' || escape == '\'' || escape == '"' || escape == '/') {
                decoded.append(escape);
            } else if (escape == 'n') {
                decoded.append('\n');
            } else if (escape == 'r') {
                decoded.append('\r');
            } else if (escape == 't') {
                decoded.append('\t');
            } else if (escape == 'b') {
                decoded.append('\b');
            } else if (escape == 'f') {
                decoded.append('\f');
            } else if (escape == '\n') {
                // JavaScript line continuation.
            } else {
                return null;
            }
        }
        return null;
    }

    private static int findJsonObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("YouTube post page exceeds the 2 MiB response limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
