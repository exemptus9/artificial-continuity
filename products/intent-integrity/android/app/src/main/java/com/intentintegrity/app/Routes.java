package com.intentintegrity.app;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Deliberately conservative URL rules. Unknown pages require an explicit timed reveal. */
public final class Routes {
    private Routes() {}
    public static final String[] IDS = {"youtube", "reddit", "threads", "x", "facebook", "tiktok", "instagram"};
    public static final String[] NAMES = {"YouTube", "Reddit", "Threads", "X", "Facebook", "TikTok", "Instagram profile"};
    public static String site(String value) {
        try {
            URI u = URI.create(value);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null || (u.getPort() != -1 && u.getPort() != 443)) return "";
            String h = u.getHost();
            if (h == null) return "";
            h = h.toLowerCase(Locale.ROOT);
            if (h.equals("youtube.com") || h.equals("www.youtube.com") || h.equals("m.youtube.com") || h.equals("youtu.be")) return "youtube";
            if (h.equals("reddit.com") || h.equals("www.reddit.com") || h.equals("old.reddit.com") || h.equals("m.reddit.com")) return "reddit";
            if (h.equals("threads.com") || h.equals("www.threads.com") || h.equals("threads.net") || h.equals("www.threads.net")) return "threads";
            if (h.equals("x.com") || h.equals("www.x.com") || h.equals("twitter.com") || h.equals("mobile.twitter.com")) return "x";
            if (h.equals("facebook.com") || h.equals("www.facebook.com") || h.equals("m.facebook.com")) return "facebook";
            if (h.equals("tiktok.com") || h.equals("www.tiktok.com") || h.equals("m.tiktok.com")) return "tiktok";
            if (h.equals("instagram.com") || h.equals("www.instagram.com")) return "instagram";
        } catch (IllegalArgumentException ignored) {}
        return "";
    }
    public static String search(String id, String query) {
        if (query == null || query.trim().isEmpty() || query.length() > 500) throw new IllegalArgumentException("Enter a search phrase of 1–500 characters.");
        String q = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        return switch (id) {
            case "youtube" -> "https://www.youtube.com/results?search_query=" + q;
            case "reddit" -> "https://www.reddit.com/search/?q=" + q;
            case "threads" -> "https://www.threads.com/search?q=" + q;
            case "x" -> "https://x.com/search?q=" + q + "&src=typed_query";
            case "facebook" -> "https://www.facebook.com/search/top/?q=" + q;
            case "tiktok" -> "https://www.tiktok.com/search?q=" + q;
            case "instagram" -> {
                String name = query.trim().replaceFirst("^@", "");
                if (!name.matches("[A-Za-z0-9._]{1,30}") || name.equals(".") || name.equals("..")) throw new IllegalArgumentException("For Instagram, enter a username, not a topic. General Instagram web search is not promised.");
                yield "https://www.instagram.com/" + name + "/";
            }
            default -> throw new IllegalArgumentException("Unsupported platform.");
        };
    }
    public static boolean allowed(String value) {
        String s = site(value);
        if (s.isEmpty()) return false;
        URI u = URI.create(value);
        String p = u.getPath();
        if (p == null) p = "/";
        p = p.replaceAll("/+$", "");
        String query = u.getRawQuery() == null ? "" : u.getRawQuery();
        if (p.matches("/(accounts|login|signin|consent|checkpoint|challenge|privacy|settings)(/.*)?")) return true;
        return switch (s) {
            case "youtube" -> (p.equals("/results") && hasQuery(query,"search_query")) || (p.equals("/watch") && hasQuery(query,"v")) || p.matches("/(playlist|@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)(/.*)?") || p.equals("/feed/subscriptions") || (u.getHost().equals("youtu.be") && p.length()>1);
            case "reddit" -> (p.equals("/search") && hasQuery(query,"q")) || p.matches("/(r|user|u)/[^/]+(/.*)?") || p.startsWith("/message");
            case "threads" -> (p.equals("/search") && hasQuery(query,"q")) || p.startsWith("/@");
            case "x" -> (p.equals("/search") && hasQuery(query,"q")) || p.startsWith("/messages") || p.matches("/[^/]+/status/[^/]+.*");
            case "facebook" -> (p.startsWith("/search") && hasQuery(query,"q")) || p.startsWith("/messages") || p.contains("/posts/") || p.startsWith("/permalink.php");
            case "tiktok" -> (p.equals("/search") && hasQuery(query,"q")) || p.startsWith("/@");
            case "instagram" -> p.startsWith("/direct/") || p.matches("/(p|reel)/[^/]+.*") || (p.matches("/[A-Za-z0-9._]{1,30}") && !p.matches("/(explore|reels|stories|about|directory)"));
            default -> false;
        };
    }
    private static boolean hasQuery(String q, String key) {
        for (String item : q.split("&")) if (item.startsWith(key + "=") && item.length() > key.length()+1) return true;
        return false;
    }
    public static String pageKey(String value) {
        try { URI u=URI.create(value); return u.getScheme()+"://"+u.getHost()+u.getPath(); }
        catch (Exception e) { return ""; }
    }
}
