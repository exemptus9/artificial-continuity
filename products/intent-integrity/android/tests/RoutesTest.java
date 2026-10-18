package com.intentintegrity.app;
public final class RoutesTest {
    private static int count=0;
    static void test(boolean value,String name){count++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        for(String s:Routes.IDS){String u=Routes.search(s,s.equals("instagram")?"@example":"hello & world");test(Routes.site(u).equals(s),s+" host");test(Routes.allowed(u),s+" search route");}
        String[] bad={"http://www.youtube.com/results?search_query=x","https://youtube.com.evil.test/","https://youtube.com@evil.test/","https://evil@youtube.com/","javascript:alert(1)","file:///etc/passwd","https://www.youtube.com:444/watch?v=x","data:text/html,hi","https://notyoutube.com/"};
        for(String u:bad)test(Routes.site(u).isEmpty(),"Reject "+u);
        String[] feeds={"https://www.youtube.com/","https://m.youtube.com/shorts/abc","https://www.youtube.com/results","https://www.youtube.com/results?search_query=+++","https://www.youtube.com/results?search_query=%20","https://www.reddit.com/","https://www.reddit.com/r/popular/","https://www.reddit.com/r/all/","https://www.threads.com/","https://x.com/home","https://www.facebook.com/","https://www.tiktok.com/foryou","https://www.instagram.com/explore/","https://www.instagram.com/reels/"};
        for(String u:feeds)test(!Routes.allowed(u),"Gate "+u);
        test(Routes.allowed("https://www.youtube.com/watch?v=123"),"chosen video");test(Routes.allowed("https://www.instagram.com/direct/inbox/"),"direct messages");test(Routes.allowed("https://www.reddit.com/r/java/comments/123/title"),"chosen discussion");
        Lease l=new Lease();test(!l.valid("a",100),"no grant");l.grant("a",100,1000);test(l.valid("a",100),"start inclusive");test(l.valid("a",1099),"before expiry");test(!l.valid("a",1100),"expiry exclusive");test(!l.valid("b",200),"scope separated");test(!l.valid("a",99),"clock reset safe");l.clear();test(!l.valid("a",200),"clear");
        try{Routes.search("instagram","../settings");throw new AssertionError("bad username");}catch(IllegalArgumentException expected){count++;}
        try{Routes.search("youtube"," ");throw new AssertionError("empty query");}catch(IllegalArgumentException expected){count++;}
        System.out.println("PASS: "+count+" URL, input and monotonic-lease assertions. Live websites and physical devices are not covered by these unit tests.");
    }
}
