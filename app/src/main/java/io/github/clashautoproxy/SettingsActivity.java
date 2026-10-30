package io.github.clashautoproxy;

import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.util.*;

public final class SettingsActivity extends AppCompatActivity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private MaterialTextView statusTitle, statusDetail, packageLabel, connectionLabel;
    private MaterialButton appsButton;
    private MaterialSwitch enableSwitch;
    private Bundle lastStatus = new Bundle();
    private boolean alive, connecting, resumed;
    private long pingAt;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refreshStatus(); }
    };
    private final Runnable timeout = () -> { connecting = false; if (lastStatus.getLong("reportedAt", -1) < pingAt) alive = false; renderStatus(); };
    private final Runnable countdown = new Runnable() {
        @Override public void run() { renderStatus(); if (resumed && alive && lastStatus.getLong("pendingUntil") > SystemClock.uptimeMillis()) main.postDelayed(this, 1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = getSharedPreferences("settings", MODE_PRIVATE);
        root = Ui.column(this); Ui.toolbar(this, root, "Clash 自动代理");
        ScrollView scroll = new ScrollView(this); scroll.setId(R.id.settings_scroll); scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = Ui.column(this); Ui.pad(page, 16, 8, 16, 24); scroll.addView(page);
        LinearLayout status = Ui.card(this, page, null);
        connectionLabel = Ui.text(this, "正在检查系统连接", com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, true); status.addView(connectionLabel);
        statusTitle = Ui.text(this, "连接检查中", com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall, false); Ui.pad(statusTitle, 0, 8, 0, 8); status.addView(statusTitle);
        statusDetail = Ui.body(this, ""); statusDetail.setTextIsSelectable(true); status.addView(statusDetail);
        Ui.button(this, status, "重新检查连接", this::ping);
        LinearLayout auto = Ui.card(this, page, "自动控制");
        enableSwitch = Ui.toggle(auto, "启用自动代理", "打开所选应用时开启；默认等所有所选应用结束运行后，才按延迟关闭模块开启的代理。", prefs.getBoolean("enabled", false));
        enableSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && (selected().isEmpty() || !serviceAvailable())) {
                button.setChecked(false); message(selected().isEmpty() ? "请先选择至少一个触发应用" : "未找到代理服务，请检查包名与模式"); return;
            } save(prefs.edit().putBoolean("enabled", checked));
        });
        appsButton = Ui.button(this, auto, "", () -> startActivity(new Intent(this, AppPickerActivity.class))); updateApps();
        MaterialTextView delayLabel = Ui.body(this, delayText(prefs.getInt("delay", 3))); Ui.pad(delayLabel, 0, 20, 0, 0); auto.addView(delayLabel);
        Slider delay = new Slider(this); delay.setValueFrom(0); delay.setValueTo(15); delay.setStepSize(1); delay.setValue(Math.max(0, Math.min(15, prefs.getInt("delay", 3))));
        delay.setContentDescription("满足关闭条件后的延迟，单位秒"); delay.setLabelFormatter(value -> (int) value + " 秒");
        auto.addView(delay, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        delay.addOnChangeListener((slider, value, user) -> { delayLabel.setText(delayText((int) value)); if (user) save(prefs.edit().putInt("delay", (int) value)); });
        toggle(auto, "后台仍运行时保留代理", "默认开启：返回桌面不关闭；等所有所选应用都结束运行。有最近任务的存活进程或实际后台服务计入运行，无最近任务的纯缓存不计入。关闭此项可恢复离开前台即关闭。", "keepWhileRunning", true);
        auto.addView(Ui.body(this, "倒计时结束前会再次检查；任一所选应用仍在运行就取消关闭。后台进程自行启动只会保留已有代理，不会单独触发开启。"));
        LinearLayout behavior = Ui.card(this, page, "保护与行为");
        behavior.addView(Ui.body(this, "手动代理始终受保护：只关闭本模块开启且实例未被替换的代理。其他渠道开启或重新开启的代理不会被接管。"));
        toggle(behavior, "锁屏或熄屏时也关闭", "可选：即使所选应用仍在后台，也立即释放模块开启的代理。默认关闭，遵循后台运行规则。", "backgroundStopOnScreenOff", false);
        toggle(behavior, "保护其他 VPN", "检测到其他 VPN 时不启动，避免抢占正在使用的连接。", "protectOtherVpn", true);
        toggle(behavior, "忽略临时系统界面", "通知栏和系统权限弹窗不视为退出。实际效果取决于 ROM 的前台事件。", "ignoreSystemUi", true);
        LinearLayout proxy = Ui.card(this, page, "代理服务");
        packageLabel = Ui.body(this, proxyPackage()); packageLabel.setTextIsSelectable(true); proxy.addView(packageLabel); Ui.button(this, proxy, "修改代理包名", this::editPackage);
        RadioGroup modes = new RadioGroup(this); mode(modes, getString(R.string.vpn_mode), 1); mode(modes, getString(R.string.core_mode), 2);
        modes.check(prefs.getBoolean("vpn", true) ? 1 : 2); modes.setOnCheckedChangeListener((g, id) -> save(prefs.edit().putBoolean("vpn", id == 1))); proxy.addView(modes);
        toggle(proxy, "使用 Clash 官方开启入口", "VPN 模式优先使用：调用客户端自带的开启功能，可能短暂显示提示；没有匹配入口时自动回退系统服务启动。请在客户端中也开启 VPN 模式。关闭此项或选择仅核心时，直接启动服务。", "officialStart", true);
        proxy.addView(Ui.body(this, "要求客户端提供兼容的 TunService / ClashService。VPN 模式下缺少官方 START_CLASH 入口时自动回退系统服务启动；服务结构不同的客户端不支持。系统“始终开启 VPN”需先关闭。")); Ui.button(this, proxy, "打开 Clash Meta", this::openProxy);
        LinearLayout appearance = Ui.card(this, page, "外观"); SharedPreferences visual = getSharedPreferences("appearance", MODE_PRIVATE);
        Ui.button(this, appearance, "主题 · " + themeName(visual.getInt("mode", -1)), this::chooseTheme);
        Ui.toggle(appearance, "使用壁纸动态配色", "遵循 Material 3 系统配色，并自动适应深浅色。", visual.getBoolean("dynamic", true)).setOnCheckedChangeListener((b, value) -> { visual.edit().putBoolean("dynamic", value).apply(); recreate(); });
        LinearLayout tests = Ui.card(this, page, "测试与诊断");
        tests.addView(Ui.body(this, "先手动连接并关闭一次 Clash，再测试。没有所选应用运行时，测试约 10 秒后释放；正常后台保留规则优先。已有手动代理不会被测试接管。服务运行不等于网络连通。"));
        Ui.button(this, tests, "测试开启 · 保留约 10 秒", () -> command("testStart")); Ui.button(this, tests, "释放模块启动的代理", () -> command("release"));
        Ui.button(this, tests, "重试当前自动控制", () -> command("retry")); Ui.button(this, tests, "检查 VPN 授权与环境", () -> command("check")); Ui.button(this, tests, "查看诊断信息", this::diagnostics);
        LinearLayout help = Ui.card(this, page, "安装与使用");
        help.addView(Ui.body(this, "1. 在 LSPosed 启用模块，作用域仅选「系统框架 / android」，重启手机。\n\n2. 手动打开 Clash Meta，选好配置、授予 VPN 权限，成功连接后关闭。\n\n3. 选择触发应用，再启用自动控制。返回桌面保留代理；所有所选应用结束运行后，延迟释放模块开启的代理。\n\n当前只支持主用户 user 0。应用分身、工作资料及多用户尚不支持。缓存进程不等于正在使用的后台应用，详见后台保留说明。"));
        MaterialTextView foot = Ui.body(this, BuildConfig.VERSION_NAME + " · Android 15 起\n事件驱动，不轮询前台、不持有唤醒锁。尚未进行真机功耗测量。代理启动存在时间窗口，不保证应用首次请求已走代理。\n\nVPN 模式优先使用客户端官方入口；未提供时使用受权限与 VPN 状态检查保护的系统服务启动路径。客户端服务结构仍需设备验证。"); Ui.pad(foot, 4, 4, 4, 8); page.addView(foot); Ui.install(this, root);
    }
    private void mode(RadioGroup p, String label, int id) { MaterialRadioButton r = new MaterialRadioButton(this); r.setId(id); r.setText(label); r.setMinHeight(Ui.dp(this, 48)); p.addView(r); }
    private void toggle(LinearLayout p, String title, String detail, String key, boolean def) { Ui.toggle(p, title, detail, prefs.getBoolean(key, def)).setOnCheckedChangeListener((b, v) -> save(prefs.edit().putBoolean(key, v))); }
    private String delayText(int n) { return n == 0 ? "满足关闭条件后立即关闭" : "满足关闭条件后 " + n + " 秒关闭"; }
    private String proxyPackage() { return prefs.getString("proxyPackage", Contract.DEFAULT_PROXY); }
    private Set<String> selected() { return new HashSet<>(prefs.getStringSet("packages", Set.of())); }
    private void updateApps() { appsButton.setText(getString(R.string.selected_count, selected().size())); }
    private void save(SharedPreferences.Editor e) { e.apply(); getContentResolver().notifyChange(Contract.CONFIG_URI, null); }
    private void message(String s) { Snackbar.make(root, s, Snackbar.LENGTH_LONG).show(); }
    private boolean serviceAvailable() {
        try { getPackageManager().getServiceInfo(new ComponentName(proxyPackage(), prefs.getBoolean("vpn", true) ? Contract.TUN_SERVICE : Contract.CORE_SERVICE), PackageManager.ComponentInfoFlags.of(0)); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; updateApps(); enableSwitch.setChecked(prefs.getBoolean("enabled", false));
        getContentResolver().registerContentObserver(Contract.STATUS_URI, false, observer); ping();
    }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(timeout); main.removeCallbacks(countdown); getContentResolver().unregisterContentObserver(observer); super.onPause(); }
    private void ping() { pingAt = SystemClock.elapsedRealtime(); connecting = true; alive = false; main.removeCallbacks(timeout); main.postDelayed(timeout, 3000); getContentResolver().notifyChange(Contract.PING_URI, null); refreshStatus(); }
    private void refreshStatus() {
        try { Bundle b = getContentResolver().call(Contract.CONFIG_URI, "status", null, null); lastStatus = b == null ? new Bundle() : b;
            if (lastStatus.getBoolean("connected") && lastStatus.getLong("reportedAt", -1) >= pingAt) { alive = true; connecting = false; main.removeCallbacks(timeout); }
        } catch (RuntimeException e) { alive = false; connecting = false; lastStatus = new Bundle(); lastStatus.putString("error", e.toString()); }
        renderStatus(); main.removeCallbacks(countdown); if (resumed && alive && lastStatus.getLong("pendingUntil") > SystemClock.uptimeMillis()) main.postDelayed(countdown, 1000);
    }
    private String stateName(String c) { return switch(c) {
        case "DISABLED" -> "自动控制已关闭"; case "WAITING" -> "等待触发应用"; case "STARTING" -> "正在启动代理"; case "OWNED" -> "代理由模块管理";
        case "MANUAL" -> "已有手动代理"; case "WAIT_STOP" -> "等待释放代理"; case "STOPPING" -> "正在关闭代理"; case "TESTING" -> "测试代理运行中"; case "ERROR" -> "需要处理问题"; default -> "系统监听已连接"; }; }
    private void renderStatus() {
        if (statusTitle == null) return; String error = lastStatus.getString("error", "");
        connectionLabel.setText(alive ? "系统监听已连接 · Android " + android.os.Build.VERSION.RELEASE : connecting ? "正在检查系统连接" : "系统监听未连接");
        statusTitle.setText(alive ? stateName(lastStatus.getString("stateCode", "")) : connecting ? "连接检查中" : "请启用模块并重启");
        statusTitle.setTextColor(Ui.color(this, !error.isEmpty() && alive ? androidx.appcompat.R.attr.colorError : com.google.android.material.R.attr.colorOnSurface));
        String detail = !alive ? connecting ? "正在请求系统监听确认。" : "在 LSPosed 勾选系统框架后重启。设置页面能够打开，不代表系统 Hook 已生效。" :
            "最近前台：" + lastStatus.getString("foreground", "—") + "\n" + (lastStatus.getBoolean("running") ? "检测到代理服务运行" : "未检测到代理服务运行");
        if (alive) {
            if (lastStatus.getInt("userId") != 0) detail += "\n非主用户，暂停触发";
            long n = lastStatus.getLong("pendingUntil") - SystemClock.uptimeMillis(); if (n > 0) detail += "\n约 " + (n + 999) / 1000 + " 秒后关闭";
            if (!lastStatus.getBoolean("serviceHook")) detail += "\n服务事件监听不可用：手动启停反馈可能延迟到下次前台事件。";
            if (prefs.getBoolean("keepWhileRunning", true)) detail += "\n后台判定：" + (lastStatus.getBoolean("presenceKnown") ? "仍运行 " + lastStatus.getStringArrayList("runningSelected") : "尚未确认，保守保留自有代理");
            detail += lastStatus.getBoolean("owned") ? "\n代理归属：模块自动开启" : lastStatus.getBoolean("running") ? "\n代理归属：手动 / 其他渠道，受保护" : "\n代理归属：无";
            if (!BuildConfig.VERSION_NAME.equals(lastStatus.getString("moduleVersion"))) detail += "\n系统仍加载旧版模块，请重启手机。";
        }
        if (!error.isEmpty()) detail += "\n\n" + error; statusDetail.setText(detail);
    }
    private void command(String action) {
        if (!alive) { message("系统监听未连接，请先启用模块并重启"); ping(); return; }
        if ("testStart".equals(action) && !serviceAvailable()) { message("未找到代理服务，请检查包名和模式"); return; }
        save(prefs.edit().putString("command", action).putLong("commandId", Math.max(System.currentTimeMillis(), prefs.getLong("commandId", 0) + 1))); message("请求已发送，结果将在状态卡片和诊断中显示");
    }
    private void editPackage() {
        LinearLayout box = Ui.column(this); Ui.pad(box, 24, 8, 24, 0); TextInputLayout input = (TextInputLayout) getLayoutInflater().inflate(R.layout.ui_input, box, false);
        input.setHint("Clash Meta 包名"); input.getEditText().setText(proxyPackage()); box.addView(input);
        var dialog = new MaterialAlertDialogBuilder(this).setTitle("修改代理包名").setMessage("仅适用于 CMFA 兼容分支，服务类名需保持一致。").setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String value = input.getEditText().getText().toString().trim();
            if (!value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) { input.setError("请输入有效包名"); return; }
            Set<String> chosen = selected(); chosen.remove(value); var e = prefs.edit().putString("proxyPackage", value).putStringSet("packages", chosen); if (chosen.isEmpty()) e.putBoolean("enabled", false);
            save(e); packageLabel.setText(value); updateApps(); enableSwitch.setChecked(prefs.getBoolean("enabled", false)); dialog.dismiss();
        })); dialog.show();
    }
    private void openProxy() { Intent i = getPackageManager().getLaunchIntentForPackage(proxyPackage()); if (i == null) message("未找到 Clash Meta，请检查包名"); else try { startActivity(i); } catch (RuntimeException e) { message("无法打开：" + e.getMessage()); } }
    private String themeName(int m) { return m == 1 ? "浅色" : m == 2 ? "深色" : "跟随系统"; }
    private void chooseTheme() {
        SharedPreferences p = getSharedPreferences("appearance", MODE_PRIVATE); int m = p.getInt("mode", -1);
        new MaterialAlertDialogBuilder(this).setTitle("主题").setSingleChoiceItems(new String[]{"跟随系统", "浅色", "深色"}, m == 1 ? 1 : m == 2 ? 2 : 0, (d, which) -> {
            int value = which == 0 ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : which == 1 ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
            p.edit().putInt("mode", value).apply(); d.dismiss();
            if (AppCompatDelegate.getDefaultNightMode() == value) recreate(); else AppCompatDelegate.setDefaultNightMode(value);
        }).setNegativeButton("取消", null).show();
    }
    private void diagnostics() {
        refreshStatus(); Bundle h = lastStatus.getBundle("health");
        StringBuilder r = new StringBuilder("Clash 自动代理 " + BuildConfig.VERSION_NAME + "\nAndroid ").append(android.os.Build.VERSION.RELEASE).append(" / API ").append(android.os.Build.VERSION.SDK_INT)
            .append("\n设备：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append("\n系统：").append(android.os.Build.DISPLAY)
            .append("\n当前连接：").append(alive).append("\n系统模块版本：").append(lastStatus.getString("moduleVersion", "—"))
            .append("\n会话：").append(lastStatus.getString("session", "—")).append("\n状态：").append(lastStatus.getString("stateCode", "未连接"))
            .append("\n最近前台：").append(lastStatus.getString("foreground", "—")).append("\n服务事件监听：").append(lastStatus.getBoolean("serviceHook"))
            .append("\n自动控制：").append(prefs.getBoolean("enabled", false)).append("\n代理包名：").append(proxyPackage()).append("\n模式：").append(prefs.getBoolean("vpn", true) ? "VPN" : "仅核心")
            .append("\n目标应用：").append(selected()).append("\n关闭延迟：").append(prefs.getInt("delay", 3)).append(" 秒").append("\n保护手动代理：始终开启")
            .append("\n保护其他 VPN：").append(prefs.getBoolean("protectOtherVpn", true)).append("\n错误：").append(lastStatus.getString("error", ""));
        r.append("\n使用官方开启入口：").append(prefs.getBoolean("officialStart", true));
        r.append("\n后台仍运行时保留：").append(prefs.getBoolean("keepWhileRunning", true))
            .append("\n熄屏时也关闭：").append(prefs.getBoolean("backgroundStopOnScreenOff", false))
            .append("\n模块拥有当前代理：").append(lastStatus.getBoolean("owned"))
            .append("\n确认代理运行：").append(lastStatus.getBoolean("running"))
            .append("\n后台判定有效：").append(lastStatus.getBoolean("presenceKnown"))
            .append("\n仍运行的所选应用：").append(lastStatus.getStringArrayList("runningSelected"))
            .append("\n后台监听接口：").append(lastStatus.getString("applicationHooks", "—"));
        if (lastStatus.getLong("applicationCheckedAt") > 0) r.append("\n后台快照距今：")
            .append(Math.max(0, (SystemClock.elapsedRealtime() - lastStatus.getLong("applicationCheckedAt")) / 1000)).append(" 秒");
        if (lastStatus.getLong("vpnCheckedAt") > 0) r.append("\n最近 VPN 查询距今：")
            .append(Math.max(0, (SystemClock.elapsedRealtime() - lastStatus.getLong("vpnCheckedAt")) / 1000)).append(" 秒")
            .append("\n最近 VPN 所属包：").append(lastStatus.getString("currentVpnOwner", ""))
            .append("\n最近 VPN 接口：").append(lastStatus.getString("currentVpnInterface", ""))
            .append("\nVPN 读取路径：").append(lastStatus.getString("vpnReadSource", "未读取"))
            .append("\nVPN 兼容诊断：").append(lastStatus.getString("vpnReadDetail", ""));
        if (h != null && h.containsKey("checkedAt")) r.append("\n\n环境检查（距今 ").append(Math.max(0, (SystemClock.elapsedRealtime() - h.getLong("checkedAt")) / 1000)).append(" 秒）")
            .append("\nClash 版本：").append(h.getString("proxyVersion", "—")).append("\nVPN 已授权：").append(h.getBoolean("vpnApproved"))
            .append("\n始终开启 VPN：").append(h.getString("alwaysOn", "—")).append("\n检查错误：").append(h.getString("checkError", ""));
        if (h != null) r.append("\nVPN 所属包：").append(h.getString("vpnOwnerPackage", "未读取"))
            .append("\nVPN 接口：").append(h.getString("vpnInterface", "未读取"));
        Bundle launch = lastStatus.getBundle("launch");
        if (launch != null && launch.containsKey("requestedAt")) r.append("\n\n最近启动请求：\n组件：").append(launch.getString("component"))
            .append("\n距今：").append(Math.max(0, (SystemClock.elapsedRealtime() - launch.getLong("requestedAt")) / 1000)).append(" 秒")
            .append("\n启动路径：").append(launch.getString("route", "—"))
            .append("\n目标 UID：").append(launch.getInt("uid")).append("\n请求结果：").append(launch.getString("outcome", "—"))
            .append("\n详细结果：").append(launch.getString("detail", "—"));
        if (h != null && h.containsKey("proxyUid")) r.append("\n\n代理 UID：").append(h.getInt("proxyUid"))
            .append("\nClash targetSdk：").append(h.getInt("targetSdk")).append("\n已被强行停止：").append(h.getBoolean("stopped"))
            .append("\n应用被挂起：").append(h.getBoolean("suspended")).append("\n后台 AppOp：").append(op(h, "backgroundOp"))
            .append("\n普通后台 AppOp：").append(op(h, "runBackgroundOp")).append("\n前台服务 AppOp：").append(op(h, "foregroundOp"))
            .append("\n官方入口：").append(h.getString("officialEntry", "未找到，见检查错误"));
        if (launch != null && launch.getBundle("before") != null) {
            Bundle before = launch.getBundle("before");
            r.append("\n启动前后台 AppOp：").append(op(before, "backgroundOp")).append("\n启动前前台服务 AppOp：").append(op(before, "foregroundOp"))
                .append("\n启动前已强行停止：").append(before.getBoolean("stopped"));
        }
        r.append("\n\n系统启动追踪（只读，单次最多 40 条）：\n监听接口：").append(lastStatus.getString("traceCoverage", "—")).append('\n');
        ArrayList<String> trace = lastStatus.getStringArrayList("startTrace");
        if (trace != null) for (String line : trace) r.append(line).append('\n');
        r.append("\n\n最近事件（仅内存，最多 100 条）：\n"); ArrayList<String> logs = lastStatus.getStringArrayList("logs"); if (logs != null) for (String s : logs) r.append(s).append('\n');
        LinearLayout box = Ui.column(this); Ui.pad(box, 24, 4, 24, 16); box.addView(Ui.body(this, "诊断包含应用名单和设备信息，复制后请按需分享。"));
        MaterialTextView t = Ui.body(this, r.toString()); Ui.pad(t, 0, 16, 0, 0); t.setTextIsSelectable(true); box.addView(t); ScrollView scroll = new ScrollView(this); scroll.addView(box);
        new MaterialAlertDialogBuilder(this).setTitle("诊断信息").setView(scroll).setNegativeButton("关闭", null).setPositiveButton("复制", (d, w) -> { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Clash 自动代理诊断", r)); message("已复制诊断信息"); }).show();
    }
    private static String op(Bundle b, String key) {
        if (!b.containsKey(key)) return "未读取，见检查错误";
        int value = b.getInt(key);
        return value + switch (value) { case 0 -> "（允许）"; case 1 -> "（忽略）"; case 2 -> "（拒绝）"; case 3 -> "（默认）"; case 4 -> "（仅前台）"; default -> "（未知）"; };
    }
}
