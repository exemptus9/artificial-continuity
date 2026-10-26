package com.intentintegrity.app;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.Locale;
/** Conservative URL rules; unknown pages require an explicit timed reveal. */
public final class Routes {
    private Routes() {}
    public static final String[] IDS={"youtube","reddit","threads","x","facebook","tiktok","instagram"};
    public static final String[] NAMES={"YouTube","Reddit","Threads","X","Facebook","TikTok","Instagram profile"};
    public static String site(String value){try{URI u=URI.create(value);if(!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443))return "";String h=u.getHost();if(h==null)return "";h=h.toLowerCase(Locale.ROOT);
        if(h.equals("youtube.com")||h.equals("www.youtube.com")||h.equals("m.youtube.com")||h.equals("youtu.be"))return "youtube";
        if(h.equals("reddit.com")||h.equals("www.reddit.com")||h.equals("old.reddit.com")||h.equals("m.reddit.com"))return "reddit";
        if(h.equals("threads.com")||h.equals("www.threads.com")||h.equals("threads.net")||h.equals("www.threads.net"))return "threads";
        if(h.equals("x.com")||h.equals("www.x.com")||h.equals("twitter.com")||h.equals("mobile.twitter.com"))return "x";
        if(h.equals("facebook.com")||h.equals("www.facebook.com")||h.equals("m.facebook.com"))return "facebook";
        if(h.equals("tiktok.com")||h.equals("www.tiktok.com")||h.equals("m.tiktok.com"))return "tiktok";
        if(h.equals("instagram.com")||h.equals("www.instagram.com"))return "instagram";
    }catch(Exception ignored){}return "";}
    public static String search(String id,String query){if(query==null||query.trim().isEmpty()||query.length()>500)throw new IllegalArgumentException("Enter a search phrase of 1–500 characters.");String q;try{q=URLEncoder.encode(query.trim(),"UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}return switch(id){
        case "youtube" -> "https://www.youtube.com/results?search_query="+q;
        case "reddit" -> "https://www.reddit.com/search/?q="+q;
        case "threads" -> "https://www.threads.com/search?q="+q;
        case "x" -> "https://x.com/search?q="+q+"&src=typed_query";
        case "facebook" -> "https://www.facebook.com/search/top/?q="+q;
        case "tiktok" -> "https://www.tiktok.com/search?q="+q;
        case "instagram" -> {String name=query.trim().replaceFirst("^@","");if(!name.matches("[A-Za-z0-9._]{1,30}")||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("For Instagram, enter a username, not a topic.");yield "https://www.instagram.com/"+name+"/";}
        default -> throw new IllegalArgumentException("Unsupported platform.");};}
    public static boolean allowed(String value){String s=site(value);if(s.isEmpty())return false;URI u=URI.create(value);String p=u.getPath();if(p==null)p="/";p=p.replaceAll("/+$","");String query=u.getRawQuery()==null?"":u.getRawQuery();if(p.matches("/(accounts|login|signin|consent|checkpoint|challenge|privacy|settings)(/.*)?"))return true;
        return switch(s){
        case "youtube" -> (p.equals("/results")&&hasQuery(query,"search_query"))||(p.equals("/watch")&&hasQuery(query,"v"))||p.matches("/(playlist|@[^/]+|channel/[^/]+|c/[^/]+|user/[^/]+)(/.*)?")||p.equals("/feed/subscriptions")||(u.getHost().equals("youtu.be")&&p.length()>1);
        case "reddit" -> !p.matches("/r/(popular|all)(/.*)?")&&((p.equals("/search")&&hasQuery(query,"q"))||p.matches("/(r|user|u)/[^/]+(/.*)?")||p.startsWith("/message"));
        case "threads" -> (p.equals("/search")&&hasQuery(query,"q"))||p.startsWith("/@");
        case "x" -> (p.equals("/search")&&hasQuery(query,"q"))||p.startsWith("/messages")||p.matches("/[^/]+/status/[^/]+.*");
        case "facebook" -> (p.startsWith("/search")&&hasQuery(query,"q"))||p.startsWith("/messages")||p.contains("/posts/")||p.startsWith("/permalink.php");
        case "tiktok" -> (p.equals("/search")&&hasQuery(query,"q"))||p.startsWith("/@");
        case "instagram" -> p.startsWith("/direct/")||p.matches("/(p|reel)/[^/]+.*")||(p.matches("/[A-Za-z0-9._]{1,30}")&&!p.matches("/(explore|reels|stories|about|directory)"));
        default -> false;};}
    private static boolean hasQuery(String q,String key){for(String item:q.split("&"))if(item.startsWith(key+"=")){try{return !URLDecoder.decode(item.substring(key.length()+1),"UTF-8").trim().isEmpty();}catch(Exception e){return false;}}return false;}
    /** Ephemeral exact-URI lease scope; never persist/log query or fragment values. */
    public static String pageKey(String value){
        if(site(value).isEmpty())return "";
        try{
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(URI.create(value).toASCIIString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            char[] hex="0123456789abcdef".toCharArray(),out=new char[hash.length*2];
            for(int i=0;i<hash.length;i++){out[i*2]=hex[(hash[i]&255)>>>4];out[i*2+1]=hex[hash[i]&15];}
            return new String(out);
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 is unavailable.",e);}
    }
}
