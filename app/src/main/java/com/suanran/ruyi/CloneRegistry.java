package com.suanran.ruyi;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 分身登记表：filesDir/clones.json */
public class CloneRegistry {

    public static class Entry {
        public String srcPkg;
        public String srcName;
        public String clonePkg;
        public String cloneName;
        public int cloneNum;
        public String baseVersion;
        public long createdAt;
    }

    private final File file;

    public CloneRegistry(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "clones.json");
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
                e.srcPkg = o.optString("srcPkg");
                e.srcName = o.optString("srcName");
                e.clonePkg = o.optString("clonePkg");
                e.cloneName = o.optString("cloneName");
                e.cloneNum = o.optInt("cloneNum");
                e.baseVersion = o.optString("baseVersion");
                e.createdAt = o.optLong("createdAt");
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized void add(Entry e) {
        List<Entry> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).clonePkg.equals(e.clonePkg)) {
                all.set(i, e);
                save(all);
                return;
            }
        }
        all.add(e);
        save(all);
    }

    private synchronized void save(List<Entry> all) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : all) {
                JSONObject o = new JSONObject();
                o.put("srcPkg", e.srcPkg);
                o.put("srcName", e.srcName);
                o.put("clonePkg", e.clonePkg);
                o.put("cloneName", e.cloneName);
                o.put("cloneNum", e.cloneNum);
                o.put("baseVersion", e.baseVersion);
                o.put("createdAt", e.createdAt);
                arr.put(o);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /** 从 c2 起找第一个未被占用的编号：登记表里没有、且系统里没装同名包 */
    public int nextCloneNumber(Context ctx, String srcPkg) {
        List<Entry> all = load();
        PackageManager pm = ctx.getPackageManager();
        for (int n = 2; n < 1000; n++) {
            String candidate = srcPkg + ".c" + n;
            boolean used = false;
            for (Entry e : all) {
                if (candidate.equals(e.clonePkg)) { used = true; break; }
            }
            if (used) continue;
            try {
                pm.getPackageInfo(candidate, 0);
                continue; // 已安装同名包，跳过
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            return n;
        }
        return 2;
    }

    public List<Entry> clonesOf(String srcPkg) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : load()) {
            if (srcPkg.equals(e.srcPkg)) out.add(e);
        }
        return out;
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
