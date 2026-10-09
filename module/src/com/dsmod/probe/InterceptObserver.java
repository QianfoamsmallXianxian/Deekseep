package com.dsmod.probe;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
final class InterceptObserver {
    public static final String KIND_RATE_LIMIT   = "rate_limit";
    public static final String KIND_AUTH         = "auth";
    public static final String KIND_RISK_CONTROL = "risk_control";
    public static final String KIND_SERVER_ERROR = "server_error";
    public static final String KIND_UNKNOWN      = "unknown";
    private static final String LOG_PATH = "/data/data/com.deepseek.chat/files/dsprobe_intercept.log";
    private static final int MAX_LOG_BYTES = 512 * 1024;
    private static final int MAX_BODY_SNIPPET = 240;
    private static final int MAX_RECORDS = 200;
    private static final Object LOCK = new Object();
    private static final ArrayList<Entry> RING = new ArrayList<Entry>();
    private InterceptObserver() {}
    public static final class Entry {
        public final long ts; public final String kind; public final int http;
        public final String bodySnippet; public final String context;
        Entry(long ts,String kind,int http,String bodySnippet,String context){
            this.ts=ts;this.kind=kind;this.http=http;
            this.bodySnippet=bodySnippet;this.context=context;
        }
        public String toJson(){
            try{return new JSONObject().put("ts",ts).put("kind",kind)
                    .put("http",http).put("body",bodySnippet)
                    .put("ctx",context).toString();}
            catch(Throwable t){return "{\"ts\":"+ts+",\"kind\":\""+kind+"\"}";}
        }
        public String display(){return "["+kind+"] HTTP "+http+" · "+context;}
    }
    public static void record(String kind,int http,String body,String context){
        try{
            String k = kind==null?KIND_UNKNOWN:kind;
            Entry e = new Entry(System.currentTimeMillis(),k,http,
                    snippet(body),context==null?"":context);
            synchronized(LOCK){
                RING.add(e);
                while(RING.size()>MAX_RECORDS) RING.remove(0);
                appendToFileLocked(e);
            }
        }catch(Throwable ignored){}
    }
    public static List<Entry> recent(int limit){
        synchronized(LOCK){
            int n=Math.min(limit<=0?MAX_RECORDS:limit,RING.size());
            ArrayList<Entry> out=new ArrayList<Entry>(n);
            for(int i=RING.size()-n;i<RING.size();i++) out.add(RING.get(i));
            return Collections.unmodifiableList(out);
        }
    }
    public static List<Entry> recentByKind(String kind,int limit){
        ArrayList<Entry> out=new ArrayList<Entry>();
        if(kind==null) return out;
        synchronized(LOCK){
            for(int i=RING.size()-1;i>=0&&out.size()<limit;i--){
                Entry e=RING.get(i);
                if(kind.equals(e.kind)) out.add(0,e);
            }
        }
        return Collections.unmodifiableList(out);
    }
    public static int countByKind(String kind){
        if(kind==null) return 0;
        synchronized(LOCK){
            int n=0;
            for(Entry e:RING) if(kind.equals(e.kind)) n++;
            return n;
        }
    }
    public static int total(){synchronized(LOCK){return RING.size();}}
    public static void clear(){
        synchronized(LOCK){
            RING.clear();
            try{File f=new File(LOG_PATH);if(f.exists()) f.delete();}
            catch(Throwable ignored){}
        }
    }
    public static String exportJson(){
        JSONArray arr=new JSONArray();
        for(Entry e:recent(MAX_RECORDS)){
            try{arr.put(new JSONObject(e.toJson()));}catch(Throwable ignored){}
        }
        return arr.toString();
    }
    public static String exportLines(){
        StringBuilder sb=new StringBuilder();
        for(Entry e:recent(MAX_RECORDS)) sb.append(e.toJson()).append('\n');
        return sb.toString();
    }
    private static String snippet(String body){
        if(body==null) return "";
        String s=body.trim();
        if(s.length()>MAX_BODY_SNIPPET) s=s.substring(0,MAX_BODY_SNIPPET)+"\u2026";
        return s;
    }
    private static void appendToFileLocked(Entry e){
        FileOutputStream out=null;
        try{
            File f=new File(LOG_PATH);
            File parent=f.getParentFile();
            if(parent!=null&&!parent.exists()) parent.mkdirs();
            if(f.exists()&&f.length()>MAX_LOG_BYTES) f.delete();
            out=new FileOutputStream(f,true);
            out.write((e.toJson()+"\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }catch(Throwable ignored){
        }finally{
            if(out!=null) try{out.close();}catch(Throwable ignored){}
        }
    }
}
