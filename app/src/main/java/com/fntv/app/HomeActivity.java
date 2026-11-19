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
    private View panelLibrary, panelSettings;
    private LinearLayout homeContainer, libraryContainer, libBarRow, settingsContainer;
    private ImageView btnGear, btnSearch, btnLibBack, btnSetBack;
    private TextView tvLibTitle;
    private EditText etSearch;

    private int currentTab = 0;
    private final List<MediaDbItem> libraries = new ArrayList<>();
    private final Map<String, List<PlayListItem>> rails = new HashMap<>();
    private List<PlayListItem> continueList = new ArrayList<>();
    private String selectedLibGuid = null;
    private String selectedLibTitle = "媒体库";
    private boolean selectedLibLive = false;
    private int sortBy = 0; // 0=更新时间(create_time DESC) 1=名称(sort_title ASC)
    private boolean railsRequested = false;
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
        setContentView(R.layout.activity_home);

        prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        api = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (api.getApi() == null && !baseUrl.isEmpty()) api.updateBaseUrl(baseUrl);

        scrollHome = findViewById(R.id.scrollHome);
        panelLibrary = findViewById(R.id.panelLibrary);
        panelSettings = findViewById(R.id.panelSettings);
        homeContainer = findViewById(R.id.homeContainer);
        libraryContainer = findViewById(R.id.libraryContainer);
        libBarRow = findViewById(R.id.libBarRow);
        settingsContainer = findViewById(R.id.settingsContainer);
        btnGear = findViewById(R.id.btnGear);
        btnSearch = findViewById(R.id.btnSearch);
        btnLibBack = findViewById(R.id.btnLibBack);
        btnSetBack = findViewById(R.id.btnSetBack);
        tvLibTitle = findViewById(R.id.tvLibTitle);
        etSearch = findViewById(R.id.etSearch);

        btnGear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(2); }
        });
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (libraries.isEmpty()) { switchTab(1); return; }
                if (selectedLibGuid == null) openLibrary(libraries.get(0));
                else switchTab(1);
                etSearch.setFocusable(true);
                etSearch.setFocusableInTouchMode(true);
                etSearch.postDelayed(new Runnable() {
                    @Override public void run() {
                        etSearch.requestFocus();
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager)
                                        getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(etSearch, 0);
                    }
                }, 120);
            }
        });
        btnLibBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(0); }
        });
        btnSetBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchTab(0); }
        });
        Ui.focusEffect(btnGear);
        Ui.focusEffect(btnSearch);
        Ui.focusEffect(btnLibBack);
        Ui.focusEffect(btnSetBack);

        // 搜索框平时不参与遥控器焦点，只有点搜索图标时才临时获取焦点
        etSearch.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (!has) etSearch.setFocusable(false);
            }
        });

        etSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    doSearch(v.getText().toString().trim());
                    return true;
                }
                return false;
            }
        });

        switchTab(0);
        loadHome();
        buildSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab == 0 && !rails.isEmpty()) loadContinue();
    }

    @Override
    public void onBackPressed() {
        if (currentTab != 0) {
            switchTab(0);
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 导航 ----------------

    private void switchTab(int index) {
        currentTab = index;
        // 首页内容必须一起隐藏，否则它上面的卡片仍然可聚焦，
        // 遥控器会跑到被面板盖住的地方，看不见焦点框
        scrollHome.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        // 首页的搜索/设置是悬浮图标，进二级页时一起隐藏，避免抢焦点
        btnGear.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        btnSearch.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        panelLibrary.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        panelSettings.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (index == 1) {
            btnLibBack.requestFocus();
        } else if (index == 2) {
            btnSetBack.requestFocus();
        } else {
            btnGear.requestFocus();
        }
        // 面板切换后焦点有可能被清空，延迟兜底一次
        final View fallback = index == 1 ? btnLibBack : (index == 2 ? btnSetBack : btnGear);
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

    private void loadContinue() {
        api.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null) {
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
                if (selectedLibGuid == null && !libraries.isEmpty()) {
                    MediaDbItem first = libraries.get(0);
                    selectedLibGuid = first.guid;
                    selectedLibTitle = first.title == null ? "媒体库" : first.title;
                    selectedLibLive = isLive(first);
                }
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
            api.getApi().getItemList(listRequest(lib.guid, 0))
                    .enqueue(new Callback<ApiResponse<ItemListResponse>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                               Response<ApiResponse<ItemListResponse>> response) {
                            if (response.isSuccessful() && response.body() != null
                                    && response.body().code == 0 && response.body().data != null
                                    && response.body().data.list != null) {
                                rails.put(lib.guid, response.body().data.list);
                                renderHome();
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

        for (MediaDbItem lib : libraries) {
            List<PlayListItem> items = rails.get(lib.guid);
            if (items == null || items.isEmpty()) continue;
            addRail(lib.title == null ? "媒体库" : lib.title, items, lib);
        }

        scrollHome.post(new Runnable() {
            @Override public void run() { scrollHome.scrollTo(0, 0); }
        });
        if (homeContainer.getChildCount() == 0) {
            homeContainer.addView(Ui.text(this, "还没有内容，先去媒体库看看", 15, R.color.ui_text3, false));
        }
    }

    private void addContinueRail() {
        homeContainer.addView(Ui.sectionHeader(this, "继续观看", null, null));
        LinearLayout row = Ui.row(this);
        int max = Math.min(10, continueList.size());
        for (int i = 0; i < max; i++) {
            final PlayListItem it = continueList.get(i);
            if (it == null) continue;
            int pct = it.duration > 0 ? (int) (it.ts * 100 / it.duration) : 0;
            long remain = it.duration > 0 ? Math.max(0, it.duration - it.ts) : 0;
            String badge = remain > 0 ? Ui.remainingText(remain) : "未开始";
            String sub = it.duration > 0 ? Ui.formatDuration(it.duration) : subOf(it);
            View card = Ui.wideCard(this, it.getDisplayTitle(), sub,
                    Ui.posterUrl(baseUrl, it.poster), badge, pct, 300, 169,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { openDetail(it); }
                    });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.rightMargin = Ui.dp(this, 14);
            row.addView(card);
        }
        homeContainer.addView(Ui.rail(this, row));
    }

    private void addRail(String title, List<PlayListItem> items, final MediaDbItem lib) {
        homeContainer.addView(Ui.sectionHeader(this, title, "更多", new View.OnClickListener() {
            @Override public void onClick(View v) { openLibrary(lib); }
        }));
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
        homeContainer.addView(Ui.rail(this, row));
    }

    // ---------------- 媒体库 ----------------

    private void openLibrary(MediaDbItem lib) {
        if (lib == null) return;
        selectedLibGuid = lib.guid;
        selectedLibTitle = lib.title == null ? "媒体库" : lib.title;
        selectedLibLive = isLive(lib);
        tvLibTitle.setText(selectedLibTitle);
        switchTab(1);
        loadLibraryItems(selectedLibGuid, selectedLibTitle);
    }

    /** 列表请求：sortBy 0=更新时间(create_time DESC) 1=名称(sort_title ASC) */
    private ItemListRequest listRequest(String guid, int sort) {
        return new ItemListRequest(guid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true,
                sort == 1 ? "sort_title" : "create_time",
                sort == 1 ? "ASC" : "DESC",
                60);
    }

    private void loadLibraryItems(String guid, String title) {
        if (guid == null) return;
        libraryContainer.removeAllViews();
        libraryContainer.addView(Ui.text(this, "加载中…", 15, R.color.ui_text3, false));
        Call<ApiResponse<ItemListResponse>> call = selectedLibLive
                ? api.getApi().getItemList(ItemListRequest.browseLiveChannels())
                : api.getApi().getItemList(listRequest(guid, sortBy));
        call.enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                libraryContainer.removeAllViews();
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null
                        || response.body().data.list == null
                        || response.body().data.list.isEmpty()) {
                    updateLibBar(0);
                    libraryContainer.addView(Ui.text(HomeActivity.this,
                            selectedLibLive ? "这个直播库是空的" : "这个媒体库是空的",
                            15, R.color.ui_text3, false));
                    return;
                }
                List<PlayListItem> list = response.body().data.list;
                updateLibBar(list.size());
                if (selectedLibLive) renderLiveGrid(list);
                else renderPosterGrid(list);
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {
                libraryContainer.removeAllViews();
                libraryContainer.addView(Ui.text(HomeActivity.this, "加载失败: " + t.getMessage(),
                        15, R.color.ui_text3, false));
            }
        });
    }

    private void renderPosterGrid(List<PlayListItem> list) {
        List<View> cards = new ArrayList<>();
        for (final PlayListItem it : list) {
            if (it == null) continue;
            cards.add(Ui.posterCard(this, it.getDisplayTitle(), subOf(it),
                    Ui.posterUrl(baseUrl, it.poster), badgeOf(it), null, 150, 214,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { openDetail(it); }
                    }));
        }
        Ui.grid(this, libraryContainer, cards, 170, 14);
        focusFirst(cards);
    }

    /** 网格渲染完把焦点交给第一张卡片，遥控器直接能上下左右走 */
    private void focusFirst(List<View> cards) {
        if (pendingFocus != null) {
            final View pf = pendingFocus;
            pendingFocus = null;
            pf.postDelayed(new Runnable() {
                @Override public void run() { pf.requestFocus(); }
            }, 60);
            return;
        }
        if (cards.isEmpty()) return;
        final View first = cards.get(0);
        first.postDelayed(new Runnable() {
            @Override public void run() {
                if (currentTab != 1) return;
                if (!first.hasFocus()) first.requestFocus();
                // 双保险：万一卡片还没布局好，至少让返回键拿到焦点，按键不会失灵
                if (!first.hasFocus()) {
                    btnLibBack.requestFocus();
                }
            }
        }, 650);
    }

    /** 直播频道：16:9 台标卡，点开直接播 */
    private void renderLiveGrid(List<PlayListItem> list) {
        List<View> cards = new ArrayList<>();
        for (final PlayListItem it : list) {
            if (it == null) continue;
            cards.add(Ui.wideCard(this, it.title == null ? it.getDisplayTitle() : it.title,
                    "直播", Ui.posterUrl(baseUrl, it.poster), null, 0, 240, 135,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { playItem(it); }
                    }));
        }
        Ui.grid(this, libraryContainer, cards, 260, 14);
        focusFirst(cards);
    }

    /** 媒体库工具条：共 N 项 + 排序（切换排序会重新请求服务端） */
    private void updateLibBar(int count) {
        libBarRow.removeAllViews();
        libBarRow.addView(Ui.text(this, "共 " + count + " 项", 15, R.color.ui_text3, false));

        View fill = new View(this);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        libBarRow.addView(fill);

        if (selectedLibLive) return;

        TextView t1 = Ui.pill(this, "更新时间", sortBy == 0);
        TextView t2 = Ui.pill(this, "名称", sortBy == 1);
        t1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sortBy == 0) return;
                sortBy = 0;
                pendingFocus = t1;
                loadLibraryItems(selectedLibGuid, selectedLibTitle);
            }
        });
        t2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sortBy == 1) return;
                sortBy = 1;
                pendingFocus = t2;
                loadLibraryItems(selectedLibGuid, selectedLibTitle);
            }
        });
        Ui.focusEffect(t1);
        Ui.focusEffect(t2);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.rightMargin = Ui.dp(this, 10);
        t1.setLayoutParams(lp1);
        libBarRow.addView(t1);
        libBarRow.addView(t2);
    }

    private void doSearch(String q) {
        if (q.isEmpty()) {
            loadLibraryItems(selectedLibGuid, selectedLibTitle);
            return;
        }
        hideKeyboard();
        libraryContainer.removeAllViews();
        libraryContainer.addView(Ui.text(this, "搜索中…", 15, R.color.ui_text3, false));
        api.getApi().search(q).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                libraryContainer.removeAllViews();
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null
                        || response.body().data.isEmpty()) {
                    libraryContainer.addView(Ui.text(HomeActivity.this, "没有找到相关内容",
                            15, R.color.ui_text3, false));
                    return;
                }
                List<View> cards = new ArrayList<>();
                for (final PlayListItem it : response.body().data) {
                    if (it == null) continue;
                    cards.add(Ui.posterCard(HomeActivity.this, it.getDisplayTitle(), subOf(it),
                            Ui.posterUrl(baseUrl, it.poster), badgeOf(it), null, 150, 214,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) { openDetail(it); }
                            }));
                }
                Ui.grid(HomeActivity.this, libraryContainer, cards, 170, 14);
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                libraryContainer.removeAllViews();
                libraryContainer.addView(Ui.text(HomeActivity.this, "搜索失败: " + t.getMessage(),
                        15, R.color.ui_text3, false));
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

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

        int step = prefs.getInt("seek_step", 10);
        int stepIdx = step == 15 ? 1 : (step == 30 ? 2 : 0);
        play.addView(settingLine("快进步长", "遥控器左右键单次跳转的秒数",
                pills(new String[]{"10 秒", "15 秒", "30 秒"}, stepIdx, new OnPick() {
                    @Override
                    public void onPick(int index) {
                        prefs.edit().putInt("seek_step",
                                index == 0 ? 10 : (index == 1 ? 15 : 30)).apply();
                    }
                })));

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
}
