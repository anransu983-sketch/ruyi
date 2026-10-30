package com.suanran.ruyi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {

    private final List<AppItem> allApps = new ArrayList<>();
    private final List<AppItem> shownApps = new ArrayList<>();
    private AppAdapter appAdapter;
    private CloneAdapter cloneAdapter;
    private ListView appList, cloneList;
    private EditText searchBox;
    private TextView emptyText, tabApps, tabClones;
    private boolean showingClones = false;

    static class AppItem {
        String label, pkg, version;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appList = findViewById(R.id.appList);
        cloneList = findViewById(R.id.cloneList);
        searchBox = findViewById(R.id.searchBox);
        emptyText = findViewById(R.id.emptyText);
        tabApps = findViewById(R.id.tabApps);
        tabClones = findViewById(R.id.tabClones);

        appAdapter = new AppAdapter();
        appList.setAdapter(appAdapter);
        cloneAdapter = new CloneAdapter();
        cloneList.setAdapter(cloneAdapter);

        tabApps.setOnClickListener(v -> switchTab(false));
        tabClones.setOnClickListener(v -> switchTab(true));
        switchTab(false);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        appList.setOnItemClickListener((parent, view, pos, id) -> {
            AppItem item = shownApps.get(pos);
            Intent intent = new Intent(this, AppDetailActivity.class);
            intent.putExtra("pkg", item.pkg);
            startActivity(intent);
        });

        cloneList.setOnItemClickListener((parent, view, pos, id) -> {
            CloneRegistry.Entry e = cloneAdapter.items.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(e.cloneName)
                    .setItems(new String[]{"打开", "卸载"}, (d, which) -> {
                        if (which == 0) openClone(e);
                        else uninstallClone(e);
                    })
                    .show();
        });

        new Thread(this::loadApps).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cloneAdapter.reload();
        updateEmpty();
    }

    private void switchTab(boolean clones) {
        showingClones = clones;
        appList.setVisibility(clones ? View.GONE : View.VISIBLE);
        cloneList.setVisibility(clones ? View.VISIBLE : View.GONE);
        searchBox.setVisibility(clones ? View.GONE : View.VISIBLE);
        styleTab(tabApps, !clones);
        styleTab(tabClones, clones);
        if (clones) cloneAdapter.reload();
        updateEmpty();
    }

    private void styleTab(TextView tv, boolean selected) {
        tv.setBackgroundResource(selected ? R.drawable.tab_selected : R.drawable.tab_normal);
        tv.setTextColor(selected ? Color.WHITE : getColor(R.color.text_main));
    }

    private void updateEmpty() {
        boolean empty;
        String msg;
        if (showingClones) {
            empty = cloneAdapter.getCount() == 0;
            msg = "还没有分身，去「应用」页选一个生成吧";
        } else {
            empty = shownApps.isEmpty();
            msg = "没有匹配的应用";
        }
        emptyText.setText(msg);
        emptyText.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        List<AppItem> items = new ArrayList<>();
        for (ApplicationInfo ai : installed) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            AppItem item = new AppItem();
            item.label = pm.getApplicationLabel(ai).toString();
            item.pkg = ai.packageName;
            try {
                PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                item.version = pi.versionName == null ? "" : pi.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                item.version = "";
            }
            try { item.icon = pm.getApplicationIcon(ai); } catch (Exception ignored) {}
            items.add(item);
        }
        Collections.sort(items, (a, b) -> a.label.compareToIgnoreCase(b.label));
        runOnUiThread(() -> {
            allApps.clear();
            allApps.addAll(items);
            applyFilter();
        });
    }

    private void applyFilter() {
        String q = searchBox.getText().toString().trim().toLowerCase();
        shownApps.clear();
        for (AppItem item : allApps) {
            if (q.isEmpty() || item.label.toLowerCase().contains(q) || item.pkg.toLowerCase().contains(q)) {
                shownApps.add(item);
            }
        }
        appAdapter.notifyDataSetChanged();
        updateEmpty();
    }

    private void openClone(CloneRegistry.Entry e) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(e.clonePkg);
        if (launch == null) {
            Toast.makeText(this, "这个分身还没安装或没有启动入口", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(launch);
    }

    private void uninstallClone(CloneRegistry.Entry e) {
        Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.clonePkg));
        startActivity(intent);
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int p) { return shownApps.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_app, parent, false);
            }
            AppItem item = shownApps.get(p);
            ((ImageView) convertView.findViewById(R.id.itemIcon)).setImageDrawable(item.icon);
            ((TextView) convertView.findViewById(R.id.itemName)).setText(item.label);
            ((TextView) convertView.findViewById(R.id.itemPkg)).setText(item.pkg);
            ((TextView) convertView.findViewById(R.id.itemVer)).setText("版本 " + item.version);
            return convertView;
        }
    }

    private class CloneAdapter extends BaseAdapter {
        final List<CloneRegistry.Entry> items = new ArrayList<>();

        void reload() {
            items.clear();
            items.addAll(new CloneRegistry(MainActivity.this).load());
            notifyDataSetChanged();
            updateEmpty();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int p, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_clone, parent, false);
            }
            CloneRegistry.Entry e = items.get(p);
            ((TextView) convertView.findViewById(R.id.cloneName)).setText(e.cloneName);
            ((TextView) convertView.findViewById(R.id.clonePkg)).setText(e.clonePkg);
            ((TextView) convertView.findViewById(R.id.cloneMeta)).setText("原应用：" + e.srcName + " · 基于版本 " + e.baseVersion);
            return convertView;
        }
    }
}
