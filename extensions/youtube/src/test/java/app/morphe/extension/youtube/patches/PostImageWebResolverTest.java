/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class PostImageWebResolverTest {
    private static final String POST_ID = "UgkxxYHjF_WQ8gf5hp5gjr1pFpDB_E6rd6gZ";
    private static final String FIRST_IMAGE =
            "{\"backstageImageRenderer\":{\"image\":{\"thumbnails\":["
                    + "{\"url\":\"https://yt3.ggpht.com/first-small\",\"width\":100,\"height\":100},"
                    + "{\"url\":\"https://yt3.ggpht.com/first-large\",\"width\":1000,\"height\":1000}]}}}";
    private static final String SECOND_IMAGE =
            "{\"backstageImageRenderer\":{\"image\":{\"thumbnails\":["
                    + "{\"url\":\"https://yt3.ggpht.com/second\",\"width\":800,\"height\":800}]}}}";
    private static final String INITIAL_DATA =
            "{\"contents\":{\"backstagePostRenderer\":{\"postId\":\"" + POST_ID
                    + "\",\"backstageAttachment\":{\"postMultiImageRenderer\":{\"images\":["
                    + FIRST_IMAGE + "," + SECOND_IMAGE + "]}}}}}";
    private static final List<String> EXPECTED = List.of(
            "https://yt3.ggpht.com/first-large", "https://yt3.ggpht.com/second");

    @Test public void extractsOrderedImagesFromObjectAssignment() throws Exception {
        String html = "<script>var ytInitialData = " + INITIAL_DATA + ";</script>";
        assertEquals(EXPECTED, PostImageWebResolver.extract(html, POST_ID));
    }

    @Test public void extractsImagesFromEscapedStringAssignment() throws Exception {
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < INITIAL_DATA.length(); i++) {
            encoded.append("\\x");
            int value = INITIAL_DATA.charAt(i);
            encoded.append(Character.forDigit(value >>> 4, 16));
            encoded.append(Character.forDigit(value & 15, 16));
        }
        String html = "<script>var ytInitialData = '" + encoded + "';</script>";
        assertEquals(EXPECTED, PostImageWebResolver.extract(html, POST_ID));
    }

    @Test public void extractsTenOrderedImagesFromMixedJavaScriptEscapes() throws Exception {
        StringBuilder images = new StringBuilder();
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            if (i > 0) images.append(',');
            String url = "https://yt3.ggpht.com/image-" + i;
            expected.add(url);
            images.append("{\"backstageImageRenderer\":{\"image\":{\"thumbnails\":[")
                    .append("{\"url\":\"").append(url)
                    .append("\",\"width\":800,\"height\":800}]}}}");
        }
        String data = "{\"contents\":\n{\"backstagePostRenderer\":{\"postId\":\"" + POST_ID
                + "\",\"note\":\"backslash \\\\ test\",\"backstageAttachment\":{"
                + "\"postMultiImageRenderer\":{\"images\":[" + images + "]}}}}}";
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '{' || c == '"') {
                escaped.append("\\x");
                escaped.append(Character.forDigit(c >>> 4, 16));
                escaped.append(Character.forDigit(c & 15, 16));
            } else if (c == '/') {
                escaped.append("\\/");
            } else if (c == '\\') {
                escaped.append("\\\\");
            } else if (c == '\n') {
                escaped.append("\\n");
            } else if (c == 'u') {
                escaped.append("\\u0075");
            } else {
                escaped.append(c);
            }
        }
        String html = "<script>var ytInitialData = '" + escaped + "';</script>";
        assertEquals(expected, PostImageWebResolver.extract(html, POST_ID));
    }
}
