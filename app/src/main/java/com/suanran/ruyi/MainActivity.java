package com.suanran.ruyi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
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
    private WebAdapter webAdapter;
    private ListView appList, cloneList, webList;
    private EditText searchBox;
    private TextView emptyText, tabApps, tabClones, tabWeb;
    private Button addWebBtn;
    private static final int PAGE_APPS = 0, PAGE_CLONES = 1, PAGE_WEB = 2;
    private int page = PAGE_APPS;
    private boolean appsLoaded = false;

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
        webList = findViewById(R.id.webList);
        searchBox = findViewById(R.id.searchBox);
        emptyText = findViewById(R.id.emptyText);
        tabApps = findViewById(R.id.tabApps);
        tabClones = findViewById(R.id.tabClones);
        tabWeb = findViewById(R.id.tabWeb);
        addWebBtn = findViewById(R.id.addWebBtn);

        appAdapter = new AppAdapter();
        appList.setAdapter(appAdapter);
        cloneAdapter = new CloneAdapter();
        cloneList.setAdapter(cloneAdapter);
        webAdapter = new WebAdapter();
        webList.setAdapter(webAdapter);

        tabApps.setOnClickListener(v -> switchTab(PAGE_APPS));
        tabClones.setOnClickListener(v -> switchTab(PAGE_CLONES));
        tabWeb.setOnClickListener(v -> switchTab(PAGE_WEB));
        switchTab(PAGE_APPS);

        addWebBtn.setOnClickListener(v -> showCreateWebDialog());
        webList.setOnItemClickListener((parent, view, pos, id) -> {
            WebIds.Entry e = webAdapter.items.get(pos);
            startActivity(BrowserActivity.launchIntent(this, e));
        });
        webList.setOnItemLongClickListener((parent, view, pos, id) -> {
            WebIds.Entry e = webAdapter.items.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setItems(new String[]{"打开", "加到桌面", "删除身份"}, (d, which) -> {
                        if (which == 0) startActivity(BrowserActivity.launchIntent(this, e));
                        else if (which == 1) pinShortcut(e);
                        else deleteWeb(e);
                    })
                    .show();
            return true;
        });

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
        webAdapter.reload();
        // 0.1.0 的教训：权限是装机后才给的，首次读到空列表时回到前台要重读
        if (!appsLoaded) new Thread(this::loadApps).start();
        updateEmpty();
    }

    private void switchTab(int target) {
        page = target;
        appList.setVisibility(target == PAGE_APPS ? View.VISIBLE : View.GONE);
        cloneList.setVisibility(target == PAGE_CLONES ? View.VISIBLE : View.GONE);
        webList.setVisibility(target == PAGE_WEB ? View.VISIBLE : View.GONE);
        searchBox.setVisibility(target == PAGE_APPS ? View.VISIBLE : View.GONE);
        addWebBtn.setVisibility(target == PAGE_WEB ? View.VISIBLE : View.GONE);
        styleTab(tabApps, target == PAGE_APPS);
        styleTab(tabClones, target == PAGE_CLONES);
        styleTab(tabWeb, target == PAGE_WEB);
        if (target == PAGE_CLONES) cloneAdapter.reload();
        if (target == PAGE_WEB) webAdapter.reload();
        updateEmpty();
    }

    private void styleTab(TextView tv, boolean selected) {
        tv.setBackgroundResource(selected ? R.drawable.tab_selected : R.drawable.tab_normal);
        tv.setTextColor(selected ? Color.WHITE : getColor(R.color.text_main));
    }

    private void updateEmpty() {
        boolean empty;
        String msg;
        if (page == PAGE_CLONES) {
            empty = cloneAdapter.getCount() == 0;
            msg = "还没有分身，去「应用」页选一个生成吧";
        } else if (page == PAGE_WEB) {
            empty = webAdapter.getCount() == 0;
            msg = "还没有网页身份，点上面「＋ 新建网页身份」建一个";
        } else if (!appsLoaded) {
            empty = true;
            msg = "正在读取应用列表…";
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
            if (!items.isEmpty()) appsLoaded = true;
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

    // ---------------- 网页分身 ----------------

    private void showCreateWebDialog() {
        WebIds registry = new WebIds(this);
        int slot = registry.freeSlot();
        if (slot < 0) {
            Toast.makeText(this, "网页身份已满（最多 " + WebIds.MAX_SLOTS + " 个）", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(48, 24, 48, 8);
        EditText nameBox = new EditText(this);
        nameBox.setHint("身份名字，如：Claude 账号一");
        EditText homeBox = new EditText(this);
        homeBox.setHint("主页网址");
        homeBox.setText("https://claude.ai");
        Spinner uaSpinner = new Spinner(this);
        String[] labels = new String[WebIds.UA_PRESETS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = WebIds.UA_PRESETS[i][1];
        uaSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        form.addView(nameBox);
        form.addView(homeBox);
        form.addView(uaSpinner);

        new AlertDialog.Builder(this)
                .setTitle("新建网页身份")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String name = nameBox.getText().toString().trim();
                    if (name.isEmpty()) name = "身份 " + slot;
                    String home = homeBox.getText().toString().trim();
                    if (home.isEmpty()) home = "https://claude.ai";
                    if (!home.startsWith("http://") && !home.startsWith("https://")) home = "https://" + home;
                    WebIds.Entry e = new WebIds.Entry();
                    e.id = System.currentTimeMillis();
                    e.slot = slot;
                    e.name = name;
                    e.home = home;
                    e.ua = WebIds.UA_PRESETS[uaSpinner.getSelectedItemPosition()][0];
                    e.fresh = true;
                    registry.add(e);
                    webAdapter.reload();
                    Toast.makeText(this, "已创建「" + name + "」，点它打开", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void deleteWeb(WebIds.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("删除「" + e.name + "」？")
                .setMessage("这个身份里登录的账号和浏览数据会一并清空。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    new WebIds(this).remove(e.id);
                    webAdapter.reload();
                    Intent wipe = new Intent(this, BrowserActivity.classForSlot(e.slot));
                    wipe.putExtra(BrowserActivity.EXTRA_ID, e.id);
                    wipe.putExtra(BrowserActivity.EXTRA_WIPE, true);
                    wipe.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(wipe); } catch (Exception ignored) {}
                })
                .show();
    }

    private void pinShortcut(WebIds.Entry e) {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "当前桌面不支持添加快捷方式", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = BrowserActivity.launchIntent(this, e);
        intent.setAction(Intent.ACTION_VIEW);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "web_" + e.id)
                .setShortLabel(e.name)
                .setLongLabel(e.name)
                .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build();
        sm.requestPinShortcut(info, null);
    }

    private class WebAdapter extends BaseAdapter {
        final List<WebIds.Entry> items = new ArrayList<>();

        void reload() {
            items.clear();
            items.addAll(new WebIds(MainActivity.this).load());
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
            WebIds.Entry e = items.get(p);
            ((TextView) convertView.findViewById(R.id.cloneName)).setText("🌐 " + e.name);
            ((TextView) convertView.findViewById(R.id.clonePkg)).setText(e.home);
            ((TextView) convertView.findViewById(R.id.cloneMeta)).setText("标识：" + WebIds.uaLabel(e.ua) + " · 数据独立 · 长按可加到桌面");
            return convertView;
        }
    }
}
