package com.suanran.ruyi;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.android.apksig.ApkSigner;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * 改包克隆引擎：提取已安装应用（含分包）→ ARSCLib 合并 → 定向改包名 → 重签。
 * 改包规则与已验证的 patch-manifest.py 一致：
 * 只改 package、provider authorities、自定义全大写权限名、taskAffinity；类名与 action 一律不碰。
 */
public class CloneEngine {

    public interface Logger {
        void log(String s);
    }

    public static class Result {
        public boolean ok;
        public File apk;
        public String clonePkg;
        public String cloneName;
        public String error;
    }

    private static final String[] PERM_TAGS = {
            "permission", "uses-permission", "permission-group", "uses-permission-sdk-23", "uses-permission-sdk-m"
    };

    public Result makeClone(Context ctx, String srcPkg, int num, Logger log) {
        Result result = new Result();
        String clonePkg = srcPkg + ".c" + num;
        result.clonePkg = clonePkg;
        PackageManager pm = ctx.getPackageManager();
        File workDir = new File(ctx.getCacheDir(), "fenshen-work/" + clonePkg);
        File outDir = new File(ctx.getCacheDir(), "fenshen-out");
        try {
            ApplicationInfo ai = pm.getApplicationInfo(srcPkg, 0);
            String srcLabel = pm.getApplicationLabel(ai).toString();
            result.cloneName = srcLabel + " " + num;

            // a) 复制 base + splits 到工作目录
            deleteRecursively(workDir);
            if (!workDir.mkdirs() && !workDir.isDirectory()) throw new Exception("无法创建工作目录");
            List<File> apkFiles = new ArrayList<>();
            File baseCopy = new File(workDir, "base.apk");
            copyFile(new File(ai.sourceDir), baseCopy);
            apkFiles.add(baseCopy);
            log.log("已提取 base.apk（" + humanSize(baseCopy.length()) + "）");
            if (ai.splitSourceDirs != null) {
                for (int i = 0; i < ai.splitSourceDirs.length; i++) {
                    File dst = new File(workDir, "split_" + i + ".apk");
                    copyFile(new File(ai.splitSourceDirs[i]), dst);
                    apkFiles.add(dst);
                }
                log.log("已提取分包 " + ai.splitSourceDirs.length + " 个");
            }

            // b) 合并 / 加载
            ApkModule module;
            ApkBundle bundle = null;
            if (apkFiles.size() > 1) {
                log.log("合并分包中…（大应用可能需要一些时间）");
                bundle = new ApkBundle();
                bundle.loadApkDirectory(workDir);
                module = bundle.mergeModules();
            } else {
                module = ApkModule.loadApkFile(baseCopy);
            }
            log.log("合并完成");

            // c) 改包
            AndroidManifestBlock manifest = module.getAndroidManifestBlock();
            String oldPkg = manifest.getPackageName();
            if (oldPkg == null || oldPkg.isEmpty()) oldPkg = srcPkg;
            manifest.setPackageName(clonePkg);
            int changed = patchManifestTree(manifest, oldPkg, clonePkg);
            manifest.setApplicationLabel(result.cloneName);
            log.log("改包完成：包名 " + clonePkg + "，清单定向修改 " + changed + " 处");

            // d) 写未签名 APK
            if (!outDir.mkdirs() && !outDir.isDirectory()) { /* already exists */ }
            File unsigned = new File(outDir, clonePkg + ".unsigned.apk");
            module.writeApk(unsigned);
            try { module.close(); } catch (Exception ignored) {}
            if (bundle != null) { try { bundle.close(); } catch (Exception ignored) {} }
            log.log("已写出未签名包（" + humanSize(unsigned.length()) + "）");

            // e) 签名（apksig 自带对齐）
            File signed = new File(outDir, clonePkg + ".apk");
            signApk(ctx, unsigned, signed, Math.max(ai.minSdkVersion, 29));
            unsigned.delete();
            result.apk = signed;
            result.ok = true;
            log.log("签名完成 → " + signed.getName() + "（" + humanSize(signed.length()) + "）");
        } catch (Throwable t) {
            result.ok = false;
            result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.log("失败：" + result.error);
        } finally {
            deleteRecursively(workDir);
        }
        return result;
    }

    /** 遍历 manifest 全部元素做定向修改，返回修改处数 */
    private int patchManifestTree(AndroidManifestBlock manifest, String oldPkg, String newPkg) {
        int changed = 0;
        ResXmlElement root = manifest.getManifestElement();
        if (root == null) return 0;
        Iterator<ResXmlElement> it = root.recursiveElements();
        while (it.hasNext()) {
            ResXmlElement el = it.next();
            String tag = el.getName();
            if (tag == null) continue;

            if ("provider".equals(tag)) {
                ResXmlAttribute attr = el.searchAttributeByName("authorities");
                if (attr != null) {
                    String v = attr.getValueAsString();
                    if (v != null && v.contains(oldPkg)) {
                        String[] parts = v.split(";");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length; i++) {
                            if (i > 0) sb.append(';');
                            String p = parts[i];
                            sb.append(p.startsWith(oldPkg) ? newPkg + p.substring(oldPkg.length()) : p);
                        }
                        attr.setValueAsString(sb.toString());
                        changed++;
                    }
                }
            }

            if (isPermTag(tag)) {
                ResXmlAttribute attr = el.searchAttributeByName("name");
                if (attr != null) {
                    String v = attr.getValueAsString();
                    if (isCustomPermission(v, oldPkg)) {
                        attr.setValueAsString(newPkg + v.substring(oldPkg.length()));
                        changed++;
                    }
                }
            }

            ResXmlAttribute affinity = el.searchAttributeByName("taskAffinity");
            if (affinity != null) {
                String v = affinity.getValueAsString();
                if (v != null && v.startsWith(oldPkg)) {
                    affinity.setValueAsString(newPkg + v.substring(oldPkg.length()));
                    changed++;
                }
            }
        }
        return changed;
    }

    private boolean isPermTag(String tag) {
        for (String t : PERM_TAGS) if (t.equals(tag)) return true;
        return false;
    }

    /** 旧包名 + 全大写下划线后缀（如 .DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION）才是自定义权限 */
    private boolean isCustomPermission(String name, String oldPkg) {
        if (name == null || !name.startsWith(oldPkg + ".")) return false;
        String suffix = name.substring(oldPkg.length() + 1);
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (!(c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.')) return false;
        }
        return true;
    }

    private void signApk(Context ctx, File unsigned, File out, int minSdk) throws Exception {
        File ksFile = ensureKeystore(ctx);
        char[] pass = BuildConfig.CLONE_KS_PASS.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, pass);
        }
        PrivateKey key = (PrivateKey) ks.getKey("ruyi", pass);
        X509Certificate cert = (X509Certificate) ks.getCertificate("ruyi");
        if (key == null || cert == null) throw new Exception("克隆签名钥读取失败");
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "ruyi", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(unsigned)
                .setOutputApk(out)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }

    /** 克隆专用钥：首次运行时从 assets 复制到私有目录 */
    private File ensureKeystore(Context ctx) throws Exception {
        File dst = new File(ctx.getFilesDir(), "clone-sign.keystore");
        if (dst.exists() && dst.length() > 0) return dst;
        try (InputStream in = ctx.getAssets().open("ruyi-release.keystore");
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
        }
        return dst;
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = fis.read(buf)) > 0) fos.write(buf, 0, r);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
