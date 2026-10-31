package com.suanran.ruyi;

/** 网页分身槽位 1：独立进程 :webp1，独立 WebView 数据目录 */
public class BrowserP1 extends BrowserActivity {
    @Override protected int slot() { return 1; }
}
