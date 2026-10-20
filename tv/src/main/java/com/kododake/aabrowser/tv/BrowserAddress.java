package com.kododake.aabrowser.tv;

import java.net.URI;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

final class BrowserAddress {
    static String resolve(String input) {
        String value = input.trim();
        if (value.isEmpty()) return null;
        if (!value.contains(" ") && (value.startsWith("https://") || value.startsWith("http://"))) {
            try {
                URI uri = URI.create(value);
                if (uri.getHost() != null) return value;
            } catch (IllegalArgumentException ignored) { }
        }
        if (!value.contains(" ") && !value.contains(":") && value.contains(".")) {
            try {
                URI uri = URI.create("https://" + value);
                if (uri.getHost() != null) return uri.toString();
            } catch (IllegalArgumentException ignored) { }
        }
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
