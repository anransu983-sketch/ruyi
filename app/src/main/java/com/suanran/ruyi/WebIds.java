package com.suanran.ruyi;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 网页分身（网页身份）登记表：filesDir/webids.json */
public class WebIds {

    public static final int MAX_SLOTS = 8;

    public static class Entry {
        public long id;
        public int slot;          // 1..8，对应独立进程与 WebView 数据目录
        public String name;
        public String home;       // 主页 URL
        public String ua;         // UA 预设 key：pixel/samsung/iphone/desktop/default
        public boolean fresh;     // 首次打开前先清空该槽位残留数据
    }

    /** UA 预设：key -> {显示名, UA 字符串}；default 表示用系统默认 UA */
    public static final String[][] UA_PRESETS = {
            {"pixel", "安卓 · Pixel", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"},
            {"samsung", "安卓 · 三星", "Mozilla/5.0 (Linux; Android 16; SM-S9480) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"},
            {"iphone", "iPhone · Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Mobile/15E148 Safari/604.1"},
            {"desktop", "桌面 · Windows Chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"},
            {"default", "系统默认", ""},
    };

    public static String uaString(String key) {
        for (String[] p : UA_PRESETS) if (p[0].equals(key)) return p[2];
        return "";
    }

    public static String uaLabel(String key) {
        for (String[] p : UA_PRESETS) if (p[0].equals(key)) return p[1];
        return key;
    }

    private final File file;

    public WebIds(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "webids.json");
    }

    public synchronized List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        if (!file.exists()) return out;
        try {
            byte[] data = readAll(file);
            JSONArray arr = new JSONArray(new String(data, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.id = o.optLong("id");
                e.slot = o.optInt("slot");
                e.name = o.optString("name");
                e.home = o.optString("home");
                e.ua = o.optString("ua", "pixel");
                e.fresh = o.optBoolean("fresh", true);
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized Entry byId(long id) {
        for (Entry e : load()) if (e.id == id) return e;
        return null;
    }

    public synchronized void add(Entry e) {
        List<Entry> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == e.id) { all.set(i, e); save(all); return; }
        }
        all.add(e);
        save(all);
    }

    public synchronized void remove(long id) {
        List<Entry> all = load();
        List<Entry> kept = new ArrayList<>();
        for (Entry e : all) if (e.id != id) kept.add(e);
        save(kept);
    }

    /** 找第一个空闲槽位（1..8），满了返回 -1 */
    public synchronized int freeSlot() {
        boolean[] used = new boolean[MAX_SLOTS + 1];
        for (Entry e : load()) if (e.slot >= 1 && e.slot <= MAX_SLOTS) used[e.slot] = true;
        for (int s = 1; s <= MAX_SLOTS; s++) if (!used[s]) return s;
        return -1;
    }

    private synchronized void save(List<Entry> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : all) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("slot", e.slot);
                o.put("name", e.name);
                o.put("home", e.home);
                o.put("ua", e.ua);
                o.put("fresh", e.fresh);
                arr.put(o);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length && (r = fis.read(buf, off, buf.length - off)) > 0) off += r;
            return buf;
        }
    }
}
