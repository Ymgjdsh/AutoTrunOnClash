package io.github.clashautoproxy;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.text.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.*;

public final class AppPickerActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> chosen = new HashSet<>();
    private final List<Entry> all = new ArrayList<>(), filtered = new ArrayList<>();
    private SharedPreferences prefs;
    private MaterialTextView summary, empty;
    private MaterialButton save;
    private CircularProgressIndicator progress;
    private Adapter adapter;
    private String query = "", filter = "all";
    private boolean loaded;
    private Set<String> displayedSelection = Set.of();
    private final Map<String, Integer> chipIds = new HashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); prefs = getSharedPreferences("settings", MODE_PRIVATE);
        chosen.addAll(state == null ? persisted() : Objects.requireNonNullElse(state.getStringArrayList("chosen"), new ArrayList<>()));
        if (state != null) { query = state.getString("query", ""); filter = state.getString("filter", "all"); }
        LinearLayout root = Ui.column(this);
        var toolbar = Ui.toolbar(this, root, "选择触发应用"); toolbar.setNavigationIcon(R.drawable.ic_back); toolbar.setNavigationContentDescription("返回"); toolbar.setNavigationOnClickListener(v -> leave());
        toolbar.getMenu().add("清空选择").setOnMenuItemClickListener(item -> {
            if (!chosen.isEmpty()) new MaterialAlertDialogBuilder(this).setTitle("清空所有选择？").setMessage("清空后仍需点击保存。保存空名单将关闭自动控制。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (d, w) -> { chosen.clear(); applyFilter(); }).show(); return true;
        });
        LinearLayout top = Ui.column(this); Ui.pad(top, 16, 8, 16, 0); root.addView(top);
        TextInputLayout search = (TextInputLayout) getLayoutInflater().inflate(R.layout.ui_input, top, false);
        search.setHint("搜索应用名称或包名"); search.setStartIconDrawable(R.drawable.ic_search); search.setStartIconCheckable(false); search.setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT); search.getEditText().setId(R.id.app_search); top.addView(search);
        ChipGroup chips = new ChipGroup(this); chips.setSingleSelection(true); chips.setSelectionRequired(true); top.addView(chips);
        addChip(chips, "全部", "all"); addChip(chips, "已选择", "selected"); addChip(chips, "已失效", "missing");
        chips.check(chipIds.getOrDefault(filter, chipIds.get("all")));
        chips.setOnCheckedStateChangeListener((g, ids) -> { for (var e : chipIds.entrySet()) if (ids.contains(e.getValue())) filter = e.getKey(); applyFilter(); });
        summary = Ui.body(this, "正在读取应用…"); Ui.pad(summary, 0, 4, 0, 8); top.addView(summary);
        progress = new CircularProgressIndicator(this); progress.setIndeterminate(true); progress.setContentDescription("正在读取应用");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)); pp.gravity = Gravity.CENTER_HORIZONTAL; root.addView(progress, pp);
        empty = Ui.body(this, ""); Ui.pad(empty, 24, 24, 24, 24); empty.setVisibility(View.GONE); root.addView(empty);
        RecyclerView list = new RecyclerView(this); list.setId(R.id.app_list); list.setLayoutManager(new LinearLayoutManager(this)); adapter = new Adapter();
        adapter.setStateRestorationPolicy(RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY); list.setAdapter(adapter); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottom = Ui.column(this); Ui.pad(bottom, 16, 8, 16, 12); root.addView(bottom);
        save = (MaterialButton) getLayoutInflater().inflate(R.layout.ui_save_button, bottom, false); save.setText(getString(R.string.save_count, chosen.size())); save.setEnabled(false); bottom.addView(save);
        save.setOnClickListener(v -> {
            SharedPreferences.Editor e = prefs.edit().putStringSet("packages", new HashSet<>(chosen)); if (chosen.isEmpty()) e.putBoolean("enabled", false);
            e.apply(); getContentResolver().notifyChange(Contract.CONFIG_URI, null); finish();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { leave(); } });
        search.getEditText().setText(query); search.getEditText().addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); applyFilter(); }
            @Override public void afterTextChanged(Editable e) {}
        });
        Ui.install(this, root); loadApps();
    }
    private void addChip(ChipGroup group, String title, String key) {
        Chip c = (Chip) getLayoutInflater().inflate(R.layout.ui_filter_chip, group, false); c.setId(View.generateViewId()); c.setText(title); chipIds.put(key, c.getId()); group.addView(c);
    }
    private Set<String> persisted() { return new HashSet<>(prefs.getStringSet("packages", Set.of())); }
    private void leave() {
        if (chosen.equals(persisted())) { finish(); return; }
        new MaterialAlertDialogBuilder(this).setTitle("放弃未保存的更改？").setMessage("当前的应用选择尚未保存。")
            .setNegativeButton("继续编辑", null).setPositiveButton("放弃更改", (d, w) -> finish()).show();
    }
    private void loadApps() {
        String proxy = prefs.getString("proxyPackage", Contract.DEFAULT_PROXY);
        Set<String> snapshot = new HashSet<>(chosen); worker.execute(() -> {
            try {
                PackageManager pm = getPackageManager(); Map<String, Entry> unique = new HashMap<>();
                for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.ResolveInfoFlags.of(0))) {
                    ApplicationInfo a = info.activityInfo.applicationInfo; String pkg = a.packageName;
                    if (!pkg.equals(getPackageName()) && !pkg.equals(proxy)) unique.put(pkg, new Entry(pkg, a.loadLabel(pm).toString(), a.loadIcon(pm), true));
                }
                for (String pkg : snapshot) if (!unique.containsKey(pkg) && !pkg.equals(proxy) && !pkg.equals(getPackageName()))
                    unique.put(pkg, new Entry(pkg, "已卸载或不可启动的应用", null, false));
                ArrayList<Entry> entries = new ArrayList<>(unique.values()); Collator collator = Collator.getInstance(Locale.CHINA);
                entries.sort((a, b) -> { int n = collator.compare(a.label, b.label); return n == 0 ? a.pkg.compareTo(b.pkg) : n; });
                main.post(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    chosen.remove(proxy); chosen.remove(getPackageName()); all.addAll(entries); loaded = true;
                    progress.setVisibility(View.GONE); save.setEnabled(true); applyFilter();
                });
            } catch (RuntimeException e) {
                main.post(() -> { if (isDestroyed()) return; progress.setVisibility(View.GONE); summary.setText("应用列表读取失败"); empty.setText(getString(R.string.app_load_error, e.getClass().getSimpleName())); empty.setVisibility(View.VISIBLE); });
            }
        });
    }
    private void applyFilter() {
        if (adapter == null || summary == null || save == null) return;
        List<Entry> before = new ArrayList<>(filtered); Set<String> beforeSelected = displayedSelection;
        String q = query.trim().toLowerCase(Locale.ROOT); filtered.clear();
        for (Entry e : all) if (("all".equals(filter) || "selected".equals(filter) && chosen.contains(e.pkg) || "missing".equals(filter) && !e.available)
            && (e.label.toLowerCase(Locale.ROOT).contains(q) || e.pkg.toLowerCase(Locale.ROOT).contains(q))) filtered.add(e);
        displayedSelection = Set.copyOf(chosen);
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return before.size(); }
            @Override public int getNewListSize() { return filtered.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) { return before.get(oldPosition).pkg.equals(filtered.get(newPosition).pkg); }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                String pkg = before.get(oldPosition).pkg; return before.get(oldPosition).equals(filtered.get(newPosition)) && beforeSelected.contains(pkg) == chosen.contains(pkg);
            }
        }).dispatchUpdatesTo(adapter);
        summary.setText(loaded ? "已选 " + chosen.size() + " 个 · 当前显示 " + filtered.size() + " 个" : "正在读取应用…");
        save.setText(getString(R.string.save_count, chosen.size())); empty.setText("missing".equals(filter) ? "没有已失效的选择" : "没有符合条件的应用"); empty.setVisibility(loaded && filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) { out.putStringArrayList("chosen", new ArrayList<>(chosen)); out.putString("query", query); out.putString("filter", filter); super.onSaveInstanceState(out); }
    @Override protected void onDestroy() { worker.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private record Entry(String pkg, String label, Drawable icon, boolean available) {}
    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(AppPickerActivity.this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(Ui.dp(AppPickerActivity.this, 72)); Ui.pad(row, 16, 8, 12, 8);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TypedValue ripple = new TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true); row.setBackgroundResource(ripple.resourceId);
            ImageView icon = new ImageView(AppPickerActivity.this); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(AppPickerActivity.this, 40), Ui.dp(AppPickerActivity.this, 40)));
            LinearLayout names = Ui.column(AppPickerActivity.this); Ui.pad(names, 16, 0, 8, 0);
            MaterialTextView name = Ui.text(AppPickerActivity.this, "", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, false), pkg = Ui.body(AppPickerActivity.this, ""); names.addView(name); names.addView(pkg); row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialCheckBox check = new MaterialCheckBox(AppPickerActivity.this); check.setMinWidth(Ui.dp(AppPickerActivity.this, 48)); check.setMinHeight(Ui.dp(AppPickerActivity.this, 48)); row.addView(check);
            return new Holder(row, icon, name, pkg, check);
        }
        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            Entry e = filtered.get(position); h.name.setText(e.label); h.pkg.setText(e.pkg); if (e.icon == null) h.icon.setImageResource(R.drawable.ic_apps); else h.icon.setImageDrawable(e.icon);
            h.check.setOnCheckedChangeListener(null); h.check.setChecked(chosen.contains(e.pkg)); h.check.setContentDescription("选择 " + e.label);
            h.check.setOnCheckedChangeListener((b, value) -> { if (value) chosen.add(e.pkg); else chosen.remove(e.pkg); applyFilter(); });
            h.itemView.setOnClickListener(v -> h.check.setChecked(!h.check.isChecked()));
        }
        @Override public int getItemCount() { return filtered.size(); }
    }
    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon; final MaterialTextView name, pkg; final MaterialCheckBox check;
        Holder(View v, ImageView i, MaterialTextView n, MaterialTextView p, MaterialCheckBox c) { super(v); icon = i; name = n; pkg = p; check = c; }
    }
}
