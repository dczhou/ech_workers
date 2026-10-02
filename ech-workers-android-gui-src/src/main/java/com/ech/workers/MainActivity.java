/*
 ============================================================================
 Name        : MainActivity.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : Main Activity
 ============================================================================
 */

package com.ech.workers;

import android.os.Bundle;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import com.ech.workers.tunnel.Tunnel;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity implements View.OnClickListener {
	private Preferences prefs;
    private Spinner spinner_profiles;
    private Button btn_add_profile;
    private Button btn_save_profile;
    private Button btn_rename_profile;
    private Button btn_delete_profile;
    private Button btn_test;
    private EditText edittext_socks_port;
    private EditText edittext_wss_addr;
    private EditText edittext_ech_dns;
    private EditText edittext_ech_domain;
    private EditText edittext_pref_ip;
    private EditText edittext_proxy_ip;
    private EditText edittext_token;
    private CheckBox checkbox_global;
    // IPv4/IPv6 默认启用，不在 UI 展示
    private Button button_apps;
    private Button button_control;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		prefs = new Preferences(this);
		setContentView(R.layout.main);

        spinner_profiles = (Spinner) findViewById(R.id.profile_spinner);
        btn_add_profile = (Button) findViewById(R.id.btn_add_profile);
        btn_save_profile = (Button) findViewById(R.id.btn_save_profile);
        btn_rename_profile = (Button) findViewById(R.id.btn_rename_profile);
        btn_delete_profile = (Button) findViewById(R.id.btn_delete_profile);
        btn_test = (Button) findViewById(R.id.btn_test);

        edittext_socks_port = (EditText) findViewById(R.id.socks_port);
        edittext_wss_addr = (EditText) findViewById(R.id.wss_addr);
        edittext_ech_dns = (EditText) findViewById(R.id.ech_dns);
        edittext_ech_domain = (EditText) findViewById(R.id.ech_domain);
        edittext_pref_ip = (EditText) findViewById(R.id.pref_ip);
        edittext_proxy_ip = (EditText) findViewById(R.id.proxy_ip);
        edittext_token = (EditText) findViewById(R.id.token);
        checkbox_global = (CheckBox) findViewById(R.id.global);
        button_apps = (Button) findViewById(R.id.apps);
        button_control = (Button) findViewById(R.id.control);

        btn_add_profile.setOnClickListener(this);
        btn_save_profile.setOnClickListener(this);
        btn_rename_profile.setOnClickListener(this);
        btn_delete_profile.setOnClickListener(this);
        btn_test.setOnClickListener(this);
        checkbox_global.setOnClickListener(this);
        button_apps.setOnClickListener(this);
        button_control.setOnClickListener(this);
        
        initProfileSpinner();
		updateUI();

		/* Request VPN permission */

        Intent intent = VpnService.prepare(MainActivity.this);
		if (intent != null)
		  startActivityForResult(intent, 0);
		else
		  onActivityResult(0, RESULT_OK, null);
	}

    private class ProfileItem {
        String id;
        String name;
        
        ProfileItem(String id, String name) {
            this.id = id;
            this.name = name;
        }
        
        @Override
        public String toString() {
            return name;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ProfileItem that = (ProfileItem) o;
            return id.equals(that.id);
        }
    }

    private void initProfileSpinner() {
        refreshProfileSpinner();
        
        spinner_profiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ProfileItem item = (ProfileItem) parent.getItemAtPosition(position);
                if (!item.id.equals(prefs.getCurrentProfileId())) {
                    savePrefs(); // Save current profile before switching
                    prefs.setCurrentProfileId(item.id);
                    updateUI();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void refreshProfileSpinner() {
        Set<String> ids = prefs.getProfileIds();
        List<ProfileItem> items = new ArrayList<>();
        String currentId = prefs.getCurrentProfileId();

        for (String id : ids) {
            String name = prefs.getProfileName(id);
            items.add(new ProfileItem(id, name));
        }

        java.util.Collections.sort(items, new java.util.Comparator<ProfileItem>() {
            @Override
            public int compare(ProfileItem a, ProfileItem b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        int selectedIndex = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(currentId)) {
                selectedIndex = i;
                break;
            }
        }
        
        ArrayAdapter<ProfileItem> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner_profiles.setAdapter(adapter);
        spinner_profiles.setSelection(selectedIndex);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if ((result == RESULT_OK) && prefs.getEnable()) {
            // 仅当用户手动启用时才启动服务
            Intent intent = new Intent(this, TProxyService.class);
            startService(intent.setAction(TProxyService.ACTION_CONNECT));
        }
    }

    private void showAddProfileDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.dialog_hint_name);
        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_add)
            .setView(input)
            .setPositiveButton(R.string.ok, null) // Set null first, override later
            .setNegativeButton(R.string.cancel, null)
            .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            Toast.makeText(MainActivity.this, R.string.toast_name_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // Check dup name
                        for (String id : prefs.getProfileIds()) {
                            if (prefs.getProfileName(id).equals(name)) {
                                Toast.makeText(MainActivity.this, R.string.toast_profile_exists, Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        String newId = UUID.randomUUID().toString();
                        savePrefs(); // Save current before switching
                        prefs.addProfile(newId, name);
                        prefs.setCurrentProfileId(newId);
                        refreshProfileSpinner();
                        updateUI();
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
    }

    private void showRenameProfileDialog() {
        final String currentId = prefs.getCurrentProfileId();
        final EditText input = new EditText(this);
        input.setText(prefs.getProfileName(currentId));
        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_rename)
            .setView(input)
            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                         // Check dup name
                        for (String id : prefs.getProfileIds()) {
                            if (!id.equals(currentId) && prefs.getProfileName(id).equals(name)) {
                                Toast.makeText(MainActivity.this, R.string.toast_profile_exists, Toast.LENGTH_SHORT).show();
                                return;
                            }
                        }
                        prefs.setProfileName(currentId, name);
                        refreshProfileSpinner();
                    }
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void deleteCurrentProfile() {
        final String currentId = prefs.getCurrentProfileId();
        Set<String> ids = prefs.getProfileIds();
        if (ids.size() <= 1) {
            Toast.makeText(this, R.string.toast_cannot_delete_last, Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_delete)
            .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    prefs.removeProfile(currentId);
                    // Switch to another one
                    String nextId = prefs.getProfileIds().iterator().next();
                    prefs.setCurrentProfileId(nextId);
                    refreshProfileSpinner();
                    updateUI();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

	@Override
	public void onClick(View view) {
        if (view == checkbox_global) {
            savePrefs();
            updateUI();
        } else if (view == button_apps) {
            startActivity(new Intent(this, AppListActivity.class));
        } else if (view == btn_add_profile) {
            showAddProfileDialog();
        } else if (view == btn_save_profile) {
            String wssAddr = edittext_wss_addr.getText().toString().trim();
            if (wssAddr.isEmpty()) {
                Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            savePrefs();
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        } else if (view == btn_rename_profile) {
            showRenameProfileDialog();
        } else if (view == btn_delete_profile) {
            deleteCurrentProfile();
        } else if (view == btn_test) {
            startNodeSpeedTest();
        } else if (view == button_control) {
            boolean isEnable = prefs.getEnable();
            if (isEnable) {
                prefs.setEnable(false);
                updateUI();
                // 停用：先发 DISCONNECT，再延迟 200ms 发送一个 stopSelf
                startService(new Intent(this, TProxyService.class).setAction(TProxyService.ACTION_DISCONNECT));
            } else {
                String wssAddr = edittext_wss_addr.getText().toString().trim();
                if (wssAddr.isEmpty()) {
                    Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                savePrefs();
                prefs.setEnable(true);
                updateUI();
                startService(new Intent(this, TProxyService.class).setAction(TProxyService.ACTION_CONNECT));
            }
        }
	}

	private void updateUI() {
        edittext_socks_port.setText(Integer.toString(prefs.getSocksPort()));
        edittext_wss_addr.setText(prefs.getWssAddr());
        edittext_ech_dns.setText(prefs.getEchDns());
        edittext_ech_domain.setText(prefs.getEchDomain());
        edittext_pref_ip.setText(prefs.getPrefIp());
        edittext_proxy_ip.setText(prefs.getProxyIp());
        edittext_token.setText(prefs.getToken());
        checkbox_global.setChecked(prefs.getGlobal());

        boolean editable = !prefs.getEnable();
        edittext_socks_port.setEnabled(editable);
        edittext_wss_addr.setEnabled(editable);
        edittext_ech_dns.setEnabled(editable);
        edittext_ech_domain.setEnabled(editable);
        edittext_pref_ip.setEnabled(editable);
        edittext_proxy_ip.setEnabled(editable);
        edittext_token.setEnabled(editable);
        checkbox_global.setEnabled(editable);
        
        boolean globalChecked = checkbox_global.isChecked();
        button_apps.setEnabled(editable && !globalChecked);
        if (button_apps.isEnabled()) {
             button_apps.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF9C27B0)); // Purple
        } else {
             button_apps.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFBDBDBD)); // Grey
        }
        
        spinner_profiles.setEnabled(editable);
        btn_add_profile.setEnabled(editable);
        btn_save_profile.setEnabled(editable);
        btn_rename_profile.setEnabled(editable);
        btn_delete_profile.setEnabled(editable);

        int grey = 0xFFBDBDBD;
        spinner_profiles.setAlpha(editable ? 1.0f : 0.5f);
        btn_add_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFF4CAF50 : grey));
        btn_save_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFF2196F3 : grey));
        btn_rename_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFFFF9800 : grey));
        btn_delete_profile.setBackgroundTintList(android.content.res.ColorStateList.valueOf(editable ? 0xFFF44336 : grey));

        // 测速按钮：测速进行中不可点。
        // 注意：测速不读写配置、不建立 VPN 隧道，因此服务运行期间同样允许测试
        // （与其它按钮不同，不跟随 editable 置灰），便于连接状态下比较其它节点。
        boolean canTest = !testing;
        btn_test.setEnabled(canTest);
        btn_test.setBackgroundTintList(android.content.res.ColorStateList.valueOf(canTest ? 0xFF009688 : grey));

        if (editable) {
          button_control.setText(R.string.control_enable);
          button_control.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50)); // Green
        } else {
          button_control.setText(R.string.control_disable);
          button_control.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFF44336)); // Red
        }
	}

    // ==================== 节点测速 ====================
    // 说明：测速不建立 VPN 隧道（不碰 VpnService/tun），只开普通 TCP(WebSocket) 连接，
    //       因此可在代理运行期间安全执行，不占用系统唯一的 VPN 隧道名额；
    //       测试串行执行，任意时刻只有一条测试连接。

    /** 测速目标：分别代表「非 CF CDN」与「CF CDN」两种情况 */
    private static final String[] TEST_URLS = {
        "https://myip.ms/",
        "https://whatismyipaddress.com/",
    };
    private static final String[] TEST_LABELS = {
        "myip.ms",
        "whatismyipaddress.com",
    };
    private static final int TEST_TIMEOUT_MS = 15000;

    private volatile boolean testing = false;
    private volatile boolean testCancel = false;
    private Thread testThread;
    private AlertDialog testProgressDialog;

    /** 单个站点的测速结果 */
    private static class SiteResult {
        final String url;
        boolean done = false;
        boolean ok = false;
        int status = 0;
        long ms = -1;
        long wsMs = -1;
        long totalMs = -1;
        long echMs = 0;
        String err = "";

        SiteResult(String url) {
            this.url = url;
        }
    }

    /** 单个节点的测速结果 */
    private static class NodeTestResult {
        final String name;
        final SiteResult[] sites;

        NodeTestResult(String name) {
            this.name = name;
            this.sites = new SiteResult[TEST_URLS.length];
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private List<Preferences.NodeConfig> getSortedNodeConfigs() {
        List<Preferences.NodeConfig> nodes = new ArrayList<>();
        for (String id : prefs.getProfileIds()) {
            nodes.add(prefs.getNodeConfig(id));
        }
        java.util.Collections.sort(nodes, new java.util.Comparator<Preferences.NodeConfig>() {
            @Override
            public int compare(Preferences.NodeConfig a, Preferences.NodeConfig b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return nodes;
    }

    private void startNodeSpeedTest() {
        if (testing) {
            return;
        }
        // 分应用代理模式下若把本应用也加入代理，测速流量会进入自己的 tun（自环），结果无意义
        if (!prefs.getGlobal() && prefs.getApps().contains(getPackageName())) {
            Toast.makeText(this, R.string.test_self_in_vpn, Toast.LENGTH_LONG).show();
            return;
        }

        savePrefs(); // 当前节点的最新编辑也纳入测试

        final List<Preferences.NodeConfig> nodes = getSortedNodeConfigs();
        if (nodes.isEmpty()) {
            return;
        }

        testing = true;
        testCancel = false;
        updateUI();

        final TextView progressText = new TextView(this);
        progressText.setPadding(dp(20), dp(16), dp(20), dp(8));
        progressText.setText(getString(R.string.test_progress, 1, nodes.size(), nodes.get(0).name));
        testProgressDialog = new AlertDialog.Builder(this)
            .setTitle(R.string.test_title)
            .setView(progressText)
            .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // 只停止发起后续测试；正在进行的这一次最多再等 TEST_TIMEOUT_MS
                    testCancel = true;
                }
            })
            .setCancelable(false)
            .create();
        testProgressDialog.show();

        final long startedAt = System.currentTimeMillis();
        testThread = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<NodeTestResult> results = new ArrayList<>();

                for (int i = 0; i < nodes.size() && !testCancel; i++) {
                    final Preferences.NodeConfig node = nodes.get(i);
                    final NodeTestResult nodeResult = new NodeTestResult(node.name);

                    for (int s = 0; s < TEST_URLS.length && !testCancel; s++) {
                        final int nodeIndex = i;
                        final int siteIndex = s;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (testProgressDialog != null && testProgressDialog.isShowing()) {
                                    progressText.setText(getString(R.string.test_progress,
                                            nodeIndex + 1, nodes.size(),
                                            node.name + " · " + TEST_LABELS[siteIndex]));
                                }
                            }
                        });

                        nodeResult.sites[s] = runOneSiteTest(node, TEST_URLS[s]);
                    }

                    results.add(nodeResult);
                }

                final long elapsedSec = (System.currentTimeMillis() - startedAt) / 1000;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finishNodeSpeedTest(results, elapsedSec);
                    }
                });
            }
        }, "node-speed-test");
        testThread.start();
    }

    /** 调用 Go 侧测速（阻塞，必须在后台线程执行） */
    private SiteResult runOneSiteTest(Preferences.NodeConfig node, String url) {
        SiteResult sr = new SiteResult(url);
        String wsAddr = node.normalizedWsAddr();
        if (wsAddr.isEmpty()) {
            sr.done = true;
            sr.err = getString(R.string.test_no_server);
            return sr;
        }
        try {
            String json = Tunnel.testSite(
                    wsAddr,
                    node.echDns,
                    node.echDomain,
                    node.prefIp,
                    node.token,
                    node.proxyIp,
                    url,
                    TEST_TIMEOUT_MS);
            JSONObject obj = new JSONObject(json);
            sr.ok = obj.optBoolean("ok", false);
            sr.ms = obj.optLong("ms", -1);
            sr.wsMs = obj.optLong("ws_ms", -1);
            sr.totalMs = obj.optLong("total_ms", -1);
            sr.echMs = obj.optLong("ech_ms", 0);
            sr.status = obj.optInt("status", 0);
            sr.err = obj.optString("err", "");
        } catch (Throwable t) {
            sr.ok = false;
            sr.err = String.valueOf(t.getMessage());
        }
        sr.done = true;
        return sr;
    }

    private void finishNodeSpeedTest(final List<NodeTestResult> results, final long elapsedSec) {
        testing = false;
        updateUI();
        if (testProgressDialog != null) {
            try {
                testProgressDialog.dismiss();
            } catch (Throwable ignored) {
            }
            testProgressDialog = null;
        }
        if (isFinishing()) {
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(12), pad, dp(12));

        TextView summary = new TextView(this);
        String summaryText = getString(R.string.test_summary, results.size(), elapsedSec);
        if (testCancel) {
            summaryText = summaryText + "\n" + getString(R.string.test_cancelled);
        }
        summary.setText(summaryText);
        summary.setPadding(0, 0, 0, dp(4));
        box.addView(summary);

        for (NodeTestResult node : results) {
            TextView title = new TextView(this);
            title.setText(node.name);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setPadding(0, dp(10), 0, 0);
            box.addView(title);

            for (SiteResult sr : node.sites) {
                if (sr == null || !sr.done) {
                    continue;
                }
                TextView line = new TextView(this);
                line.setTextSize(13);
                String prefix = "  " + labelForUrl(sr.url) + "  ";
                SpannableString span = new SpannableString(prefix + siteResultText(sr));
                span.setSpan(new ForegroundColorSpan(siteResultColor(sr)),
                        prefix.length(), span.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                line.setText(span);
                box.addView(line);
            }
        }

        TextView note = new TextView(this);
        note.setTextSize(11);
        note.setPadding(0, dp(14), 0, 0);
        note.setText(getString(R.string.test_no_tun_note) + "\n" + getString(R.string.test_vpn_note));
        box.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
            .setTitle(R.string.test_result_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .show();
    }

    private String labelForUrl(String url) {
        for (int i = 0; i < TEST_URLS.length; i++) {
            if (TEST_URLS[i].equals(url)) {
                return TEST_LABELS[i];
            }
        }
        return url;
    }

    private String siteResultText(SiteResult sr) {
        if (!sr.ok) {
            return sr.err == null || sr.err.isEmpty() ? getString(R.string.test_failed) : sr.err;
        }
        String text = getString(R.string.test_line, sr.totalMs, sr.wsMs, sr.ms);
        if (sr.status != 200) {
            text = text + getString(R.string.test_status_suffix, sr.status);
        }
        return text;
    }

    private int siteResultColor(SiteResult sr) {
        if (!sr.ok || sr.totalMs < 0) {
            return 0xFFF44336; // 红：失败
        }
        if (sr.totalMs < 1000) {
            return 0xFF4CAF50; // 绿
        }
        if (sr.totalMs <= 3000) {
            return 0xFFFF9800; // 橙
        }
        return 0xFFF44336;     // 红：偏慢
    }

    @Override
    protected void onDestroy() {
        testCancel = true; // 避免 Activity 销毁后回调弹框
        super.onDestroy();
    }

	private void savePrefs() {
        int port = 1080;
        try {
            port = Integer.parseInt(edittext_socks_port.getText().toString());
        } catch (Exception e) {
        }
        if (port < 1024) {
            port = 1024;
            edittext_socks_port.setText(Integer.toString(port));
            Toast.makeText(getApplicationContext(), "端口已设置为≥1024", Toast.LENGTH_SHORT).show();
        }
        prefs.setSocksPort(port);
        prefs.setWssAddr(edittext_wss_addr.getText().toString());
        prefs.setEchDns(edittext_ech_dns.getText().toString());
        prefs.setEchDomain(edittext_ech_domain.getText().toString());
        prefs.setPrefIp(edittext_pref_ip.getText().toString());
        prefs.setProxyIp(edittext_proxy_ip.getText().toString().trim());
        prefs.setToken(edittext_token.getText().toString());
        
        // IPv4/IPv6 默认启用
        prefs.setIpv4(true);
        prefs.setIpv6(true);
        prefs.setGlobal(checkbox_global.isChecked());
        prefs.setUdpInTcp(false);
        prefs.setRemoteDns(true);
    }
}
