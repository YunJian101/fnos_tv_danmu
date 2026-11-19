package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.ItemListRequest;
import com.fntv.app.api.model.ItemListResponse;
import com.fntv.app.api.model.MediaDbItem;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.ui.Theme;
import com.fntv.app.ui.Ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 主界面（按预览图）：
 * 首页 = 媒体库大卡 + 继续观看 + 各库轨道，右上角只有「搜索 / 设置」两个图标；
 * 媒体库 / 设置 是各自带返回箭头的二级页，没有顶部导航条，也没有底部悬浮导航。
 */
public class HomeActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private FnApiManager api;
    private String baseUrl;

    private ScrollView scrollHome;
    private View panelSettings;
    private LinearLayout homeContainer, settingsContainer;
    private ImageView btnGear, btnSearch, btnSetBack;

    private int currentTab = 0;
    private final List<MediaDbItem> libraries = new ArrayList<>();
    private final Map<String, List<PlayListItem>> rails = new HashMap<>();
    private List<PlayListItem> continueList = new ArrayList<>();
    private boolean railsRequested = false;
    private String homeSig = null;   // 首页数据指纹，未变化就不重绘
    private final java.util.List<String> continueGuids = new java.util.ArrayList<>();
    private final java.util.List<View> continueViews = new java.util.ArrayList<>();
    private int savedScrollY = 0;
    private String savedFocusGuid = null;
    private boolean userTouched = false;
    private View pendingFocus = null;   // 切排序后把焦点还给点下去的那颗胶囊

    private static final int[] MARK_COLORS = {
            0xFFFF7BA8, 0xFF6FB4FF, 0xFFFF9AB6, 0xFF5BE3A8, 0xFF8FB6FF,
            0xFFFFC46A, 0xFF9AD8FF, 0xFFB79BFF
    };

    private UpdateManager updateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.applySaved(this);
        super.onCreate(savedInstanceState);
        Ui.applyScale(this);
        com.fntv.app.api.FnApiManager.initDiskCache(getApplicationContext());
        setContentView(R.layout.activity_home);

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        api = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (api.getApi() == null && !baseUrl.isEmpty()) api.updateBaseUrl(baseUrl);

        scrollHome = findViewById(R.id.scrollHome);
        panelSettings = findViewById(R.id.panelSettings);
        homeContainer = findViewById(R.id.homeContainer);
        settingsContainer = findViewById(R.id.settingsContainer);
        btnGear = findViewById(R.id.btnGear);
        btnSearch = findViewById(R.id.btnSearch);
        btnSetBack = findViewById(R.id.btnSetBack);

        btnGear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(2); }
        });
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startSearch(); }
        });
        btnSetBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(0); }
        });
        Ui.focusEffect(btnGear);
        Ui.focusEffect(btnSearch);
        Ui.focusEffect(btnSetBack);

        switchTab(0);
        loadHome();
        buildSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到首页必须拿最新的播放记录，否则「继续观看」是旧的
        com.fntv.app.api.FnApiManager.invalidateApi("/play/list");
        com.fntv.app.api.FnApiManager.invalidateApi("/play/info");
        forceContinueRefresh = true;   // 只要是从播放器/详情页回来，继续观看一定变了
        loadContinue();
        // 播放器退出瞬间服务端可能还没落库，1 秒后补拉一次
        homeContainer.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing() && currentTab == 0) loadContinue();
            }
        }, 1000);
        userTouched = false;
        restorePosition();
    }

    @Override
    protected void onSaveInstanceState(android.os.Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("home_scroll_y", savedScrollY);
        out.putString("home_focus_guid", savedFocusGuid);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (currentTab != 0) return;
        savedScrollY = scrollHome.getScrollY();
        savedFocusGuid = focusedCardGuid();
    }

    private String focusedCardGuid() {
        View v = getCurrentFocus();
        while (v != null) {
            Object tag = v.getTag();
            if (tag instanceof String && ((String) tag).startsWith("fntv_guid:")) {
                return ((String) tag).substring(10);
            }
            v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
        }
        return null;
    }

    private void restorePosition() {
        if (currentTab != 0) return;
        final int y = savedScrollY;
        final String guid = savedFocusGuid;
        // 多次兜底：重建/加载完成、焦点重新分配都会把 ScrollView 拉回顶部，
        // 所以 220/700/1400ms 各纠正一次；用户一按键就停止，避免和用户抢滚动
        int[] delays = {220, 700, 1400};
        for (final int delay : delays) {
            scrollHome.postDelayed(new Runnable() {
                @Override public void run() {
                    if (currentTab != 0 || userTouched) return;
                    if (guid != null) {
                        View card = findCardByGuid(scrollHome, guid);
                        if (card != null && !card.hasFocus()) card.requestFocus();
                    }
                    scrollHome.scrollTo(0, y);
                }
            }, delay);
        }
    }

    private View findCardByGuid(View v, String guid) {
        Object tag = v.getTag();
        if (tag instanceof String && tag.equals("fntv_guid:" + guid)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findCardByGuid(g.getChildAt(i), guid);
                if (r != null) return r;
            }
        }
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) userTouched = true;
        return super.dispatchKeyEvent(e);
    }

    private long lastBackAt = 0L;

    @Override
    public void onBackPressed() {
        if (currentTab != 0) {
            switchTab(0);
            return;
        }
        // 首页再按返回：2 秒内连按两次才退出，防止遥控器误触
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackAt = now;
        android.widget.Toast.makeText(this, "再按一次返回键退出",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------------- 导航 ----------------

    /** 首页默认焦点：第一张卡片（媒体库/继续观看），没有内容才退回设置图标 */
    private void focusHomeFirst() {
        homeContainer.postDelayed(new Runnable() {
            @Override public void run() {
                View target = findFirstFocusable(homeContainer);
                if (target != null) target.requestFocus();
                else btnGear.requestFocus();
            }
        }, 260);
    }

    private View findFirstFocusable(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            if (c.isFocusable()) return c;
            if (c instanceof android.view.ViewGroup) {
                View inner = findFirstFocusable((android.view.ViewGroup) c);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private void switchTab(int index) {
        currentTab = index;
        // 首页内容必须一起隐藏，否则它上面的卡片仍然可聚焦，
        // 遥控器会跑到被面板盖住的地方，看不见焦点框
        scrollHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        // 首页的搜索/设置是悬浮图标，进二级页时一起隐藏，避免抢焦点
        btnGear.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (index == 2) {
            btnSetBack.requestFocus();
        } else {
            focusHomeFirst();   // 回到首页优先聚焦内容，而不是右上角设置
        }
        // 面板切换后焦点有可能被清空，延迟兜底一次
        final View fallback = index == 2 ? btnSetBack : btnGear;
        fallback.postDelayed(new Runnable() {
            @Override public void run() {
                View root = getWindow().getDecorView();
                if (root.findFocus() == null) fallback.requestFocus();
            }
        }, 700);
    }

    // ---------------- 首页 ----------------

    private void loadHome() {
        homeContainer.removeAllViews();
        homeContainer.addView(Ui.text(this, "正在加载…", 15, R.color.ui_text3, false));
        loadContinue();
        loadLibraries();
    }

    /** 只更新「继续观看」卡片上的剩余时间角标，不重建视图 */
    /** 只要「这一批剧」没变，就按 guid 匹配原地改角标，顺序变化也不重建 */
    private boolean updateContinueInPlace(List<PlayListItem> data) {
        java.util.List<PlayListItem> picked = new java.util.ArrayList<>();
        int max = Math.min(10, data.size());
        for (int i = 0; i < max; i++) {
            if (data.get(i) != null) picked.add(data.get(i));
        }
        if (picked.size() != continueViews.size()) return false;
        java.util.Map<String, View> byGuid = new java.util.HashMap<>();
        for (int i = 0; i < continueGuids.size() && i < continueViews.size(); i++) {
            byGuid.put(continueGuids.get(i), continueViews.get(i));
        }
        // 集合必须完全一致（忽略顺序）
        for (PlayListItem it : picked) {
            if (it.guid == null || !byGuid.containsKey(it.guid)) return false;
        }
        for (PlayListItem it : picked) {
            long remain = it.duration > 0 ? Math.max(0, it.duration - it.ts) : 0;
            String badge = remain > 0 ? Ui.remainingText(remain) : "未开始";
            View b = findBadge(byGuid.get(it.guid));
            if (b instanceof android.widget.TextView) ((android.widget.TextView) b).setText(badge);
        }
        continueList = data;
        return true;
    }

    private View findBadge(View v) {
        if ("fntv_badge".equals(v.getTag())) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findBadge(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private boolean forceContinueRefresh = false;

    private void loadContinue() {
        api.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null) {
                    // 内容没变就不重绘首页，避免从播放器返回时整页闪一下
                    StringBuilder sigB = new StringBuilder();
                    for (PlayListItem it : response.body().data) {
                        if (it == null) continue;
                        sigB.append(it.guid).append(':').append(it.ts).append('|');
                    }
                    if (!forceContinueRefresh && sigB.toString().equals(homeSig) && scrollHome.getChildCount() > 0) {
                        return;
                    }
                    // 走增量 diff：新增插入、消失移除、顺序变化只移动视图，不整页重建
                    if (continueRowView != null && continueRowView.getChildCount() > 0) {
                        applyContinueDiff(response.body().data);
                        homeSig = sigB.toString();
                        forceContinueRefresh = false;
                        return;
                    }
                    homeSig = sigB.toString();
                    continueList = response.body().data;
                    renderHome();
                }
            }
            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    private void loadLibraries() {
        api.getApi().getMediaDbList().enqueue(new Callback<ApiResponse<List<MediaDbItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<MediaDbItem>>> call,
                                   Response<ApiResponse<List<MediaDbItem>>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null) {
                    renderHome();
                    return;
                }
                libraries.clear();
                libraries.addAll(response.body().data);
                    com.fntv.app.data.MediaStore.libraries.clear();
                    com.fntv.app.data.MediaStore.libraries.addAll(libraries);
                loadRails();
                renderHome();
            }
            @Override
            public void onFailure(Call<ApiResponse<List<MediaDbItem>>> call, Throwable t) {
                renderHome();
            }
        });
    }

    private static boolean isLive(MediaDbItem lib) {
        return lib != null && ("IPTV".equalsIgnoreCase(lib.category)
                || "直播".equals(lib.title) || (lib.title != null && lib.title.contains("直播")));
    }

    private void loadRails() {
        if (railsRequested) return;
        railsRequested = true;
        int max = Math.min(6, libraries.size());
        for (int i = 0; i < max; i++) {
            final MediaDbItem lib = libraries.get(i);
            if (lib.guid == null || isLive(lib)) continue;
            api.getApi().getItemList(listRequest(lib.guid, 0, 20))
                    .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                               Response<ApiResponse<ItemListResponse>> response) {
                            if (response.isSuccessful() && response.body() != null
                                    && response.body().code == 0 && response.body().data != null
                                    && response.body().data.list != null) {
                                rails.put(lib.guid, response.body().data.list);
                                com.fntv.app.data.MediaStore.rails.put(lib.guid, response.body().data.list);
                                com.fntv.app.data.MediaStore.putList(lib.guid, 0, response.body().data.list);
                                insertRail(lib, response.body().data.list);   // 只更新这一行
                            }
                        }
                        @Override
                        public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
                    });
        }
    }

    /** 首页：媒体库大卡 + 继续观看 + 各库轨道 */
    private void renderHome() {
        if (currentTab != 0 && homeContainer.getChildCount() > 0) return;
        homeContainer.removeAllViews();

        if (!libraries.isEmpty()) {
            homeContainer.addView(Ui.sectionHeader(this, "媒体库", null, null));
            LinearLayout libRow = Ui.row(this);
            for (int i = 0; i < libraries.size(); i++) {
                final MediaDbItem lib = libraries.get(i);
                View card = Ui.libCard(this,
                        lib.title == null ? "媒体库" : lib.title,
                        isLive(lib) ? "Live" : null,
                        MARK_COLORS[i % MARK_COLORS.length],
                        240, 120,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { openLibrary(lib); }
                        });
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
                lp.rightMargin = Ui.dp(this, 14);
                libRow.addView(card);
            }
            homeContainer.addView(Ui.rail(this, libRow));
        }

        if (!continueList.isEmpty()) addContinueRail();

        railPlaceholders.clear();
        for (MediaDbItem lib : libraries) {
            if (lib.guid == null || isLive(lib)) continue;
            List<PlayListItem> items = rails.get(lib.guid);
            if (items != null && !items.isEmpty()) {
                addRail(lib.title == null ? "媒体库" : lib.title, items, lib);
            } else {
                View holder = buildRailSkeleton(lib);      // 先铺骨架
                railPlaceholders.put(lib.guid, holder);
                homeContainer.addView(holder);
            }
        }

        scrollHome.post(new Runnable() {
            @Override public void run() { scrollHome.scrollTo(0, 0); }
        });
        if (homeContainer.getChildCount() == 0) {
            homeContainer.addView(Ui.text(this, "还没有内容，先去媒体库看看", 15, R.color.ui_text3, false));
        }
    }

    /** 分类行分批追加：每次一行，避免一次性造上百个卡片卡住主线程 */
    private void appendRailsChunked(final java.util.List<MediaDbItem> libs, final int index) {
        if (index >= libs.size()) return;
        homeContainer.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || currentTab != 0) return;
                MediaDbItem lib = libs.get(index);
                List<PlayListItem> items = rails.get(lib.guid);
                if (items != null && !items.isEmpty()) {
                    addRail(lib.title == null ? "媒体库" : lib.title, items, lib);
                }
                appendRailsChunked(libs, index + 1);
            }
        }, index == 0 ? 0 : 16);
    }

    private LinearLayout continueRowView;

    private View makeContinueCard(final PlayListItem it) {
        int pct = it.duration > 0 ? (int) (it.ts * 100 / it.duration) : 0;
        long remain = it.duration > 0 ? Math.max(0, it.duration - it.ts) : 0;
        String badge = remain > 0 ? Ui.remainingText(remain) : "未开始";
        String sub = continueSubtitle(it);
        View card = Ui.wideCard(this, continueSeries(it), sub,
                Ui.posterUrl(baseUrl, it.poster), badge, pct, 320, 180,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openDetail(it); }
                });
        if (it.guid != null) card.setTag("fntv_guid:" + it.guid);
        // 卡片固定宽度：宽度若由内容反推，缩略图按 320dp 算高度就会失真
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 320), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 14);
        card.setLayoutParams(lp);
        return card;
    }

    /** 方案二：按 guid 做增量 diff —— 新增插入、消失移除、顺序变化只移动，不重建视图 */
    private void applyContinueDiff(List<PlayListItem> data) {
        if (continueRowView == null) return;
        java.util.List<PlayListItem> desired = new java.util.ArrayList<>();
        int max = Math.min(10, data.size());
        for (int i = 0; i < max; i++) {
            if (data.get(i) != null && data.get(i).guid != null) desired.add(data.get(i));
        }
        java.util.Map<String, View> existing = new java.util.HashMap<>();
        for (int i = 0; i < continueRowView.getChildCount(); i++) {
            View c = continueRowView.getChildAt(i);
            Object tag = c.getTag();
            if (tag instanceof String && ((String) tag).startsWith("fntv_guid:")) {
                existing.put(((String) tag).substring(10), c);
            }
        }
        // 1) 去掉不再需要的
        java.util.Set<String> want = new java.util.HashSet<>();
        for (PlayListItem it : desired) want.add(it.guid);
        for (String g : new java.util.ArrayList<>(existing.keySet())) {
            if (!want.contains(g)) {
                continueRowView.removeView(existing.remove(g));
            }
        }
        // 2) 按目标顺序重排 / 补新卡
        for (int i = 0; i < desired.size(); i++) {
            PlayListItem it = desired.get(i);
            View card = existing.get(it.guid);
            if (card == null) {
                card = makeContinueCard(it);
                existing.put(it.guid, card);
            } else {
                View b = findBadge(card);
                long remain = it.duration > 0 ? Math.max(0, it.duration - it.ts) : 0;
                String badge = remain > 0 ? Ui.remainingText(remain) : "未开始";
                if (b instanceof android.widget.TextView) ((android.widget.TextView) b).setText(badge);
            }
            if (continueRowView.indexOfChild(card) != i) {
                continueRowView.removeView(card);
                continueRowView.addView(card, Math.min(i, continueRowView.getChildCount()));
            }
        }
        // 3) 同步缓存
        continueListsync(desired);
    }

    private void continueListsync(java.util.List<PlayListItem> desired) {
        continueViews.clear();
        continueGuids.clear();
        for (PlayListItem it : desired) {
            View card = null;
            if (continueRowView != null) {
                for (int i = 0; i < continueRowView.getChildCount(); i++) {
                    Object tag = continueRowView.getChildAt(i).getTag();
                    if (tag instanceof String && tag.equals("fntv_guid:" + it.guid)) {
                        card = continueRowView.getChildAt(i);
                        break;
                    }
                }
            }
            if (card != null) {
                continueViews.add(card);
                continueGuids.add(it.guid);
            }
        }
    }

    private void addContinueRail() {
        homeContainer.addView(Ui.sectionHeader(this, "继续观看", null, null));
        LinearLayout row = Ui.row(this);
        continueRowView = row;
        continueViews.clear();
        continueGuids.clear();
        int max = Math.min(10, continueList.size());
        for (int i = 0; i < max; i++) {
            PlayListItem it = continueList.get(i);
            if (it == null) continue;
            if (it.guid != null) continueGuids.add(it.guid);
            View card = makeContinueCard(it);
            continueViews.add(card);
            row.addView(card);
        }
        homeContainer.addView(Ui.rail(this, row));
    }

    /** 某一行数据到了：原地替换骨架，没有骨架就追加 */
    private final java.util.Map<String, View> railPlaceholders = new java.util.HashMap<>();

    private void insertRail(MediaDbItem lib, List<PlayListItem> items) {
        if (items == null || items.isEmpty()) return;
        View ph = railPlaceholders.remove(lib.guid);
        int at = ph != null ? homeContainer.indexOfChild(ph) : homeContainer.getChildCount();
        if (ph != null) homeContainer.removeView(ph);
        addRailAt(lib.title == null ? "媒体库" : lib.title, items, lib, at);
    }

    /** 分类行骨架：标题 + 一排灰块 */
    private View buildRailSkeleton(MediaDbItem lib) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.sectionHeader(this, lib.title == null ? "媒体库" : lib.title, null, null));
        LinearLayout row = Ui.row(this);
        for (int i = 0; i < 6; i++) {
            View block = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 150), Ui.dp(this, 214));
            lp.rightMargin = Ui.dp(this, 14);
            block.setLayoutParams(lp);
            block.setBackground(com.fntv.app.ui.Ui.round(0xFF2A3140, Ui.dp(this, 12)));
            block.setAlpha(0.30f);
            row.addView(block);
        }
        box.addView(Ui.rail(this, row));
        return box;
    }

    private void addRail(String title, List<PlayListItem> items, final MediaDbItem lib) {
        addRailAt(title, items, lib, homeContainer.getChildCount());
    }

    private void addRailAt(String title, List<PlayListItem> items, final MediaDbItem lib, int at) {
        View header = Ui.sectionHeader(this, title, "更多", new View.OnClickListener() {
            @Override public void onClick(View v) { openLibrary(lib); }
        });
        LinearLayout row = Ui.row(this);
        int max = Math.min(20, items.size());
        for (int i = 0; i < max; i++) {
            final PlayListItem it = items.get(i);
            if (it == null) continue;
            View card = Ui.posterCard(this, it.getDisplayTitle(), subOf(it),
                    Ui.posterUrl(baseUrl, it.poster), badgeOf(it), null, 150, 214,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { openDetail(it); }
                    });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.rightMargin = Ui.dp(this, 14);
            row.addView(card);
        }
        homeContainer.addView(header, Math.min(at, homeContainer.getChildCount()));
        homeContainer.addView(Ui.rail(this, row), Math.min(at + 1, homeContainer.getChildCount()));
    }

    // ---------------- 媒体库 ----------------

    private void openLibrary(MediaDbItem lib) {
        if (lib == null) return;
        // 独立 Activity：真正的页面转场，返回首页时 onResume 会刷新播放记录
        Intent i = new Intent(this, LibraryActivity.class);
        i.putExtra("lib_guid", lib.guid);
        i.putExtra("lib_title", lib.title == null ? "媒体库" : lib.title);
        i.putExtra("lib_live", isLive(lib));
        startActivity(i);
    }

    /** 列表请求：sortBy 0=更新时间(create_time DESC) 1=名称(sort_title ASC) */
    private ItemListRequest listRequest(String guid, int sort) {
        return listRequest(guid, sort, 60);
    }

    /** 首页每行只要 20 条，减少响应体积；分类页仍用 60 */
    private ItemListRequest listRequest(String guid, int sort, int count) {
        return new ItemListRequest(guid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true,
                sort == 1 ? "sort_title" : "create_time",
                sort == 1 ? "ASC" : "DESC",
                count);
    }

    /** 第一行：只要剧名 */
    private String continueSeries(PlayListItem it) {
        if (it.tvTitle != null && !it.tvTitle.isEmpty()) return it.tvTitle;
        return it.title != null ? it.title : "";
    }

    /** 第二行：S01E04：集名（时长不显示） */
    private String continueSubtitle(PlayListItem it) {
        String epName = it.title != null ? it.title : "";
        if (epName.equals(continueSeries(it))) epName = "";
        if (it.episodeNumber <= 0 && epName.isEmpty()) return it.getCategoryLabel();
        StringBuilder sb = new StringBuilder();
        if (it.episodeNumber > 0) {
            sb.append(String.format(java.util.Locale.US, "S%02dE%02d",
                    it.seasonNumber > 0 ? it.seasonNumber : 1, it.episodeNumber));
            if (!epName.isEmpty()) sb.append("：").append(epName);
        } else {
            sb.append(epName);
        }
        return sb.toString();
    }


    /** 分批构建媒体库网格：每次追加 rowsPerChunk 行 */
    /** 网格渲染完把焦点交给第一张卡片，遥控器直接能上下左右走 */
    /** 直播频道：16:9 台标卡，点开直接播 */
    /** 媒体库工具条：共 N 项 + 排序（切换排序会重新请求服务端） */
    // ---------------- 文案工具 ----------------

    private static String badgeOf(PlayListItem it) {
        if (it.watched == 1) return "✓";
        return it.episodeNumber > 0 ? String.valueOf(it.episodeNumber) : null;
    }

    private static String score(String v) {
        return (v == null || v.isEmpty() || "0".equals(v) || "0.0".equals(v)) ? null : v;
    }

    private static String fmtScore(String raw) {
        if (raw == null) return null;
        try {
            return String.format(java.util.Locale.US, "%.1f", Double.parseDouble(raw));
        } catch (Exception e) {
            return raw;
        }
    }

    private String subOf(PlayListItem it) {
        String cat = it.getCategoryLabel();
        String sc = fmtScore(score(it.voteAverage));
        if (sc == null) return cat;
        return (cat == null || cat.isEmpty()) ? "★ " + sc : cat + " · ★ " + sc;
    }

    // ---------------- 设置（两列卡片） ----------------

    private void buildSettings() {
        settingsContainer.removeAllViews();

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = Ui.dp(this, 20);
        right.setLayoutParams(rlp);

        // ---------- 外观 ----------
        LinearLayout look = settingsCard(left, "外观");
        final int mode = Theme.get(this);
        look.addView(settingLine("主题", "关灯投影用黑夜，开灯环境用白天",
                pills(new String[]{"跟随系统", "黑夜", "白天"}, mode, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        Theme.set(HomeActivity.this, index);
                        recreate();
                    }
                })));
        look.addView(settingLine("界面缩放", "字小看不清时放大一档",
                pills(new String[]{"标准", "大", "特大"}, prefs.getInt("ui_scale", 0), new OnPick() {
                    @Override
                    public void onPick(int index) {
                        prefs.edit().putInt("ui_scale", index).apply();
                        com.fntv.app.ui.Ui.applyScale(HomeActivity.this);
                        recreate();
                    }
                })));

        // ---------- 播放 ----------
        LinearLayout play = settingsCard(left, "播放");
        String decoder = prefs.getString("decoder_mode", "hardware");
        play.addView(settingLine("解码模式", "硬解省电，软解兼容性更好",
                pills(new String[]{"硬解", "软解"}, "software".equals(decoder) ? 1 : 0, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        prefs.edit().putString("decoder_mode",
                                index == 1 ? "software" : "hardware").apply();
                    }
                })));

        // 快进 / 快退 分开设置
        final int[] STEP_VALS = {5, 10, 15, 30, 60};
        int fwd = prefs.getInt("seek_step_fwd", prefs.getInt("seek_step", 10));
        int back = prefs.getInt("seek_step_back", prefs.getInt("seek_step", 10));
        int fwdIdx = 1, backIdx = 1;
        for (int i = 0; i < STEP_VALS.length; i++) {
            if (STEP_VALS[i] == fwd) fwdIdx = i;
            if (STEP_VALS[i] == back) backIdx = i;
        }
        play.addView(settingLine("快进秒数", "遥控器右键单次前进多少秒",
                pills(new String[]{"5 秒", "10 秒", "15 秒", "30 秒", "60 秒"}, fwdIdx, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        prefs.edit().putInt("seek_step_fwd", STEP_VALS[index]).apply();
                    }
                })));
        play.addView(settingLine("快退秒数", "遥控器左键单次后退多少秒",
                pills(new String[]{"5 秒", "10 秒", "15 秒", "30 秒", "60 秒"}, backIdx, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        prefs.edit().putInt("seek_step_back", STEP_VALS[index]).apply();
                    }
                })));

        // ---------- 硬件解码器清单（方便排查/截图） ----------
        LinearLayout decCard = settingsCard(left, "硬件解码器");
        decCard.addView(Ui.text(this, decoderReport(), 13, R.color.ui_text2, false));

        int buffer = prefs.getInt("buffer_time", 30);
        int bufIdx = buffer == 15 ? 1 : (buffer == 60 ? 2 : 0);
        play.addView(settingLine("缓冲时间", "开始播放前预加载的秒数",
                pills(new String[]{"30 秒", "15 秒", "60 秒"}, bufIdx, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        prefs.edit().putInt("buffer_time",
                                index == 0 ? 30 : (index == 1 ? 15 : 60)).apply();
                    }
                })));

        play.addView(settingLine("字幕大小", "外挂字幕的显示字号",
                pills(new String[]{"小", "中", "大"}, prefs.getInt("sub_size", 1), new OnPick() {
                    @Override
                    public void onPick(int index) {
                        prefs.edit().putInt("sub_size", index).apply();
                    }
                })));

        // ---------- 弹幕 ----------
        LinearLayout danmuCard = settingsCard(right, "弹幕");
        String danmu = prefs.getString("danmu_url", "");
        if (danmu.isEmpty()) {
            String host = prefs.getString("host", "").replaceAll("^https?://", "")
                    .replaceAll("/.*$", "").replaceAll(":\\d+$", "");
            danmu = "http://" + host + ":9321";
        }
        TextView danmuBtn = Ui.text(this, danmu, 13, R.color.ui_text, false);
        danmuBtn.setSingleLine(true);
        danmuBtn.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        danmuBtn.setBackgroundResource(R.drawable.ui_btn_ghost);
        danmuBtn.setFocusable(true);
        danmuBtn.setPadding(Ui.dp(this, 16), Ui.dp(this, 9), Ui.dp(this, 16), Ui.dp(this, 9));
        final String danmuInit = danmu;
        danmuBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editDanmuServer(danmuInit); }
        });
        Ui.focusEffect(danmuBtn);
        danmuCard.addView(settingLine("弹幕服务器", "弹幕数据服务的地址", danmuBtn));

        danmuCard.addView(settingLine("显示弹幕", "播放时叠加弹幕",
                Ui.switchControl(this, "danmu_on", prefs.getBoolean("danmu_on", true), prefs)));
        danmuCard.addView(settingLine("不透明度", "弹幕文字的透明度",
                Ui.sliderControlLinked(this, "danmu_alpha", prefs.getInt("danmu_alpha", 72), prefs, "danmu_opacity", 0.15f, 1.0f)));
        danmuCard.addView(settingLine("字号", "弹幕文字的大小",
                Ui.sliderControlLinked(this, "danmu_size", prefs.getInt("danmu_size", 50), prefs, "danmu_fontsize", 14f, 34f)));
        danmuCard.addView(settingLine("滚动速度", "越小越慢，越大越快",
                Ui.sliderControlLinked(this, "danmu_speed_ui", prefs.getInt("danmu_speed_ui", 40), prefs, "danmu_speed", 0.3f, 2.0f)));
        danmuCard.addView(settingLine("显示区域", "弹幕最多占用屏幕的高度",
                pills(new String[]{"1/4", "1/2", "3/4", "全屏"}, areaIndex(prefs.getInt("danmu_area", 50)), new OnPick() {
                    @Override
                    public void onPick(int index) {
                        int[] vals = {25, 50, 75, 100};
                        prefs.edit().putInt("danmu_area", vals[Math.max(0, Math.min(3, index))]).apply();
                    }
                })));

        // ---------- 关于 ----------
        LinearLayout about = settingsCard(right, "关于");
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        about.addView(settingLine("版本", "FN TV v" + version + " · Android 13 已适配", null));

        // 缓存：显示当前占用 + 一键清除
        final TextView cacheInfo = Ui.text(this, formatCacheSize(cacheTotalSize()), 15,
                R.color.ui_text2, false);
        final TextView clearCache = Ui.ghostButton(this, "清除");
        clearCache.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                clearAllCaches();
                cacheInfo.setText(formatCacheSize(cacheTotalSize()));
                android.widget.Toast.makeText(HomeActivity.this, "缓存已清除",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        // 缓存大小直接显示在这一行的副标题处
        View cacheRow = settingLine("缓存", formatCacheSize(cacheTotalSize()), clearCache);
        about.addView(cacheRow);
        final TextView cacheInfoRow = findDescText(cacheRow);


        TextView update = Ui.primaryButton(this, "检查更新", 0);
        updateManager = new UpdateManager(this, update, BuildConfig.VERSION_CODE);
        updateManager.setup();
        about.addView(settingLine("更新", "检查是否有新版本", update));

        TextView logout = Ui.ghostButton(this, "退出登录");
        logout.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmLogout(); }
        });
        about.addView(settingLine("退出登录", "清除本机保存的账号与密码", logout));

        cols.addView(left);
        cols.addView(right);
        settingsContainer.addView(cols);
    }

    private static int areaIndex(int area) {
        if (area <= 25) return 0;
        if (area <= 50) return 1;
        if (area <= 75) return 2;
        return 3;
    }

    private interface OnPick { void onPick(int index); }

    private View pills(String[] labels, int selected, final OnPick cb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView p = Ui.pill(this, labels[i], i == selected);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i == 0 ? 0 : Ui.dp(this, 8);
            p.setLayoutParams(lp);
            p.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cb.onPick(idx);
                    // 选完立即重建设置页，让选中态跟着变，避免"点了没反应"
                    buildSettings();
                }
            });
            Ui.focusEffect(p);
            row.addView(p);
        }
        return row;
    }

    private LinearLayout settingsCard(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ui_card);
        card.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 18);
        card.setLayoutParams(lp);
        card.addView(Ui.text(this, title, 20, R.color.ui_text, true));
        parent.addView(card);
        return card;
    }

    /** 取 settingLine 里那个副标题 TextView */
    private TextView findDescText(View row) {
        if (!(row instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup r = (android.view.ViewGroup) row;
        if (r.getChildCount() == 0) return null;
        View col = r.getChildAt(0);
        if (col instanceof android.view.ViewGroup) {
            android.view.ViewGroup c = (android.view.ViewGroup) col;
            for (int i = 0; i < c.getChildCount(); i++) {
                if (c.getChildAt(i) instanceof TextView) {
                    TextView tv = (TextView) c.getChildAt(i);
                    Object tag = tv.getTag();
                    if ("desc".equals(tag)) return tv;
                    if (c.getChildCount() > 1 && i == 1) return tv;
                }
            }
        }
        return null;
    }

    /** 列出设备上所有视频解码器（含厂商硬解/系统软解），方便排查 */
    private String decoderReport() {
        StringBuilder sb = new StringBuilder();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                android.media.MediaCodecList list =
                        new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
                int hw = 0, sw = 0;
                for (android.media.MediaCodecInfo ci : list.getCodecInfos()) {
                    if (ci == null || ci.isEncoder()) continue;
                    StringBuilder types = new StringBuilder();
                    boolean video = false;
                    for (String t : ci.getSupportedTypes()) {
                        if (t == null) continue;
                        String m = t.toLowerCase();
                        if (m.startsWith("video/")) {
                            video = true;
                            if (types.length() > 0) types.append("/");
                            types.append(m.replace("video/", "").toUpperCase());
                        }
                    }
                    if (!video) continue;
                    String name = ci.getName();
                    boolean isSoft = name == null || name.startsWith("omx.google.")
                            || name.startsWith("c2.android.") || name.contains("software");
                    if (isSoft) sw++; else hw++;
                    sb.append(isSoft ? "软 " : "硬 ").append(name)
                      .append("  [").append(types).append("]\n");
                }
                sb.insert(0, "硬解 " + hw + " 个 · 软解 " + sw + " 个\n\n");
            } else {
                sb.append("系统版本过低，无法枚举");
            }
        } catch (Exception e) {
            sb.append("枚举失败: ").append(e.getMessage());
        }
        return sb.toString().trim();
    }

    private View settingLine(String title, String desc, View control) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(Ui.text(this, title, 16, R.color.ui_text, true));
        if (desc != null && !desc.isEmpty()) {
            TextView d = Ui.text(this, desc, 12, R.color.ui_text3, false);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(this, 5);
            d.setLayoutParams(dlp);
            col.addView(d);
        }
        row.addView(col);
        if (control != null) row.addView(control);
        return row;
    }

    private void editDanmuServer(final String current) {
        final EditText input = new EditText(this);
        input.setText(current);
        input.setSingleLine(true);
        input.setTextColor(Ui.color(this, R.color.ui_text));
        input.setBackgroundResource(R.drawable.ui_input);
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        Ui.dialog(this, "弹幕服务器地址", input, "保存", new Runnable() {
            @Override
            public void run() {
                prefs.edit().putString("danmu_url", input.getText().toString().trim()).apply();
                buildSettings();
            }
        }, "取消");
    }

    private void confirmLogout() {
        Ui.dialog(this, "退出登录", Ui.text(this, "退出后需要重新登录，确定吗？", 14, R.color.ui_text2, false),
                "退出", new Runnable() {
                    @Override
                    public void run() {
                        FnApiManager.clearApiCache();
                        prefs.edit().remove("token").remove("remember").remove("pass")
                                .remove("fnid_remember").remove("fnid_pass")
                                .remove("remember_ui").apply();
                        Intent i = new Intent(HomeActivity.this, MainActivity.class);
                        i.putExtra("skip_auto_login", true);
                        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                        finish();
                    }
                }, "取消");
    }

    // ---------------- 跳转 ----------------

    private void openDetail(PlayListItem it) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("guid", it.guid);
        i.putExtra("title", it.title);
        i.putExtra("tv_title", it.tvTitle);
        i.putExtra("poster", it.poster);
        i.putExtra("episode_number", it.episodeNumber);
        i.putExtra("season_number", it.seasonNumber);
        i.putExtra("category", it.getCategoryLabel());
        i.putExtra("overview", it.overview);
        i.putExtra("vote", it.voteAverage);
        i.putExtra("ts", it.ts);
        i.putExtra("duration", it.duration);
        i.putExtra("type", it.type);
        if (it.parentGuid != null) i.putExtra("parent_guid", it.parentGuid);
        startActivity(i);
    }

    private void playItem(PlayListItem it) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", it.guid);
        intent.putExtra("title", it.title);
        intent.putExtra("tv_title", it.tvTitle);
        intent.putExtra("episode_number", it.episodeNumber);
        intent.putExtra("poster", it.poster);
        intent.putExtra("category", it.getCategoryLabel());
        intent.putExtra("ts", it.ts);
        intent.putExtra("duration", it.duration);
        if (it.parentGuid != null) intent.putExtra("parent_guid", it.parentGuid);
        startActivity(intent);
    }
    // ---------- 缓存大小与清理 ----------
    private long dirSize(java.io.File dir) {
        if (dir == null || !dir.exists()) return 0;
        long total = 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files) {
            total += f.isDirectory() ? dirSize(f) : f.length();
        }
        return total;
    }

    private long cacheTotalSize() {
        long total = dirSize(new java.io.File(getCacheDir(), "img"));
        total += dirSize(new java.io.File(getFilesDir(), "cache/api"));
        return total;
    }

    private String formatCacheSize(long bytes) {
        if (bytes <= 0) return "暂无缓存";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024f);
        return String.format("%.1f MB", bytes / 1024f / 1024f);
    }

    private void clearAllCaches() {
        com.fntv.app.util.SimpleImageLoader.clearCache();   // 内存图
        deleteDir(new java.io.File(getCacheDir(), "img"));   // 图片磁盘
        com.fntv.app.api.FnApiManager.clearApiCache();       // 接口内存
        deleteDir(new java.io.File(getFilesDir(), "cache/api")); // 接口磁盘
    }

    private void deleteDir(java.io.File dir) {
        if (dir == null || !dir.exists()) return;
        java.io.File[] files = dir.listFiles();
        if (files != null) {
            for (java.io.File f : files) {
                if (f.isDirectory()) deleteDir(f); else f.delete();
            }
        }
    }


    /** 搜索：直接用分类页的搜索模式 */
    private void startSearch() {
        Intent i = new Intent(this, LibraryActivity.class);
        i.putExtra("search_mode", true);
        startActivity(i);
    }
}
