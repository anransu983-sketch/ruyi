package com.suanran.ruyi;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppDetailActivity extends Activity {

    private String pkg;
    private String srcLabel;
    private String srcVersion;
    private TextView logText, nextNumText, existingClonesText;
    private Button generateBtn;
    private Spinner countSpinner;
    private int nextNum;
    private boolean generating = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) { finish(); return; }

        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            srcLabel = pm.getApplicationLabel(ai).toString();
            srcVersion = pi.versionName == null ? "" : pi.versionName;
            ((ImageView) findViewById(R.id.detailIcon)).setImageDrawable(pm.getApplicationIcon(ai));
            ((TextView) findViewById(R.id.detailName)).setText(srcLabel);
            long size = new File(ai.sourceDir).length();
            int splitCount = 1;
            if (ai.splitSourceDirs != null) {
                splitCount += ai.splitSourceDirs.length;
                for (String s : ai.splitSourceDirs) size += new File(s).length();
            }
            ((TextView) findViewById(R.id.detailInfo)).setText(
                    pkg + "\n版本 " + srcVersion + " · 大小 " + humanSize(size) + " · 分包 " + splitCount + " 个");
        } catch (PackageManager.NameNotFoundException e) {
            Toast.makeText(this, "应用不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        logText = findViewById(R.id.logText);
        nextNumText = findViewById(R.id.nextNumText);
        existingClonesText = findViewById(R.id.existingClones);
        generateBtn = findViewById(R.id.generateBtn);
        countSpinner = findViewById(R.id.countSpinner);
        countSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1", "2", "3", "4", "5"}));
        countSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                generateBtn.setText("生成 " + (pos + 1) + " 个");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        generateBtn.setOnClickListener(v -> startGenerate());

        refreshState();
    }

    private void refreshState() {
        CloneRegistry registry = new CloneRegistry(this);
        nextNum = registry.nextCloneNumber(this, pkg);
        nextNumText.setText("下一个分身编号：c" + nextNum + "（包名 " + pkg + ".c" + nextNum + "）");
        List<CloneRegistry.Entry> mine = registry.clonesOf(pkg);
        if (mine.isEmpty()) {
            existingClonesText.setText("这个应用还没有分身");
        } else {
            StringBuilder sb = new StringBuilder("已生成的分身：");
            for (CloneRegistry.Entry e : mine) {
                sb.append("\n· ").append(e.cloneName).append("（").append(e.clonePkg)
                  .append("，基于版本 ").append(e.baseVersion).append("）");
            }
            existingClonesText.setText(sb.toString());
        }
    }

    private void startGenerate() {
        if (generating) return;
        generating = true;
        generateBtn.setEnabled(false);
        final int count = countSpinner.getSelectedItemPosition() + 1;
        final int startNum = nextNum;
        appendLog("开始生成 " + count + " 个分身，从 c" + startNum + " 起…");
        if (!getPackageManager().canRequestPackageInstalls()) {
            appendLog("提示：还没给「如意」安装未知应用的权限，装分身时系统会再问一次");
        }
        executor.execute(() -> {
            CloneEngine engine = new CloneEngine();
            CloneRegistry registry = new CloneRegistry(this);
            List<File> produced = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int n = startNum + i;
                appendLog("—— 生成分身 c" + n + " ——");
                CloneEngine.Result r = engine.makeClone(this, pkg, n,
                        s -> runOnUiThread(() -> appendLog(s)));
                if (r.ok) {
                    CloneRegistry.Entry e = new CloneRegistry.Entry();
                    e.srcPkg = pkg;
                    e.srcName = srcLabel;
                    e.clonePkg = r.clonePkg;
                    e.cloneName = r.cloneName;
                    e.cloneNum = n;
                    e.baseVersion = srcVersion;
                    e.createdAt = System.currentTimeMillis();
                    registry.add(e);
                    produced.add(r.apk);
                } else {
                    appendLog("分身 c" + n + " 生成失败：" + r.error);
                    break;
                }
            }
            runOnUiThread(() -> {
                generating = false;
                generateBtn.setEnabled(true);
                refreshState();
                appendLog("生成结束，共 " + produced.size() + " 个，开始逐个安装…");
                for (File apk : produced) installApk(apk);
                if (!produced.isEmpty() && !getPackageManager().canRequestPackageInstalls()) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                        appendLog("已打开权限页：给「如意」打开「允许来自此来源」后，回来再点生成即可直接安装");
                    } catch (Exception ignored) {}
                }
            });
        });
    }

    private void installApk(File apk) {
        try {
            Uri uri = CloneFileProvider.getUriForFile(this, apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            appendLog("拉起安装失败：" + e.getMessage());
        }
    }

    private void appendLog(String s) {
        if (logText.getText().length() > 0) logText.append("\n");
        logText.append(s);
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
