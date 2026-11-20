package com.fntv.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.ItemListRequest;
import com.fntv.app.api.model.ItemListResponse;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.ui.Ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 媒体库（分类）页：独立 Activity，有真正的页面转场，
 * 返回首页时首页 onResume 会刷新「继续观看」。
 */
public class LibraryActivity extends AppCompatActivity {

    private FnApiManager api;
    private String baseUrl;
    private String libGuid;
    private String libTitle;
    private boolean isLive;
    private boolean searchMode = false;
    private android.widget.EditText searchBox;

    private ScrollView scrollRoot;
    private LinearLayout listContainer;
    private TextView countView;
    private TextView sortTime;
    private TextView sortName;
    private int sortBy = 0;
    private int cardWPx = 0;          // 0=更新时间 1=名称（沿用上次）
    private final List<View> cards = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.fntv.app.ui.Theme.applySaved(this);
        super.onCreate(savedInstanceState);
        com.fntv.app.ui.Ui.applyScale(this);
        FnApiManager.initDiskCache(getApplicationContext());
        api = FnApiManager.getInstance();

        baseUrl = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getString("host", "")
                .replaceAll("/+$", "");
        if (api.getApi() == null && !baseUrl.isEmpty()) api.updateBaseUrl(baseUrl);

        Intent in = getIntent();
        libGuid = in.getStringExtra("lib_guid");
        libTitle = in.getStringExtra("lib_title");
        isLive = in.getBooleanExtra("lib_live", false);
        searchMode = in.getBooleanExtra("search_mode", false);
        // 排序方式永久记住（重启也保留）
        sortBy = getSharedPreferences("fntv_prefs", MODE_PRIVATE).getInt("lib_sort", 0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.color(this, R.color.ui_bg));

        ScrollView scroll = new ScrollView(this);
        scrollRoot = scroll;
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(0, Ui.dp(this, 28), 0, Ui.dp(this, 40));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 56), 0, Ui.dp(this, 56), 0);
        scroll.addView(col);

        // 顶部：返回 + 标题 + 计数 + 排序
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View back = Ui.backButton(this, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);

        TextView title = Ui.text(this, searchMode ? "搜索" : (libTitle == null ? "媒体库" : libTitle), 30,
                R.color.ui_text, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 20);
        title.setLayoutParams(tlp);
        head.addView(title);

        View spacer = new View(this);
        head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        if (searchMode) {
            searchBox = new android.widget.EditText(this);
            searchBox.setSingleLine(true);
            searchBox.setHint("搜索影片、剧集…");
            searchBox.setHintTextColor(Ui.color(this, R.color.ui_text3));
            searchBox.setTextColor(Ui.color(this, R.color.ui_text));
            searchBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            searchBox.setBackground(com.fntv.app.ui.Ui.round(0xFFEDF0F5, Ui.dp(this, 12)));
            searchBox.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 420),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.leftMargin = Ui.dp(this, 24);
            searchBox.setLayoutParams(slp);
            searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                    doSearch(v.getText().toString().trim());
                    return true;
                }
            });
            head.addView(searchBox);
        }

        if (!isLive && !searchMode) {
            sortTime = Ui.pill(this, "更新时间", true);
            sortTime.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (sortBy == 0) return;
                    sortBy = 0;
                    getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putInt("lib_sort", sortBy).apply();
                    load();
                }
            });
            head.addView(sortTime);

            sortName = Ui.pill(this, "名称", false);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.leftMargin = Ui.dp(this, 10);
            sortName.setLayoutParams(nlp);
            sortName.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (sortBy == 1) return;
                    sortBy = 1;
                    getSharedPreferences("fntv_prefs", MODE_PRIVATE).edit().putInt("lib_sort", sortBy).apply();
                    load();
                }
            });
            head.addView(sortName);
        }
        col.addView(head);

        countView = Ui.text(this, searchMode ? "输入关键词后按确定搜索" : "加载中…", 14, R.color.ui_text3, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 12);
        countView.setLayoutParams(clp);
        col.addView(countView);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, 18);
        listContainer.setLayoutParams(llp);
        col.addView(listContainer);

        // 同一媒体库再进来时恢复上次滚动位置
        if (libGuid != null && libGuid.equals(com.fntv.app.data.MediaStore.lastListGuid)) {
            final int y = com.fntv.app.data.MediaStore.lastScrollY;
            scroll.postDelayed(new Runnable() {
                @Override public void run() { scroll.scrollTo(0, y); }
            }, 260);
        }

        setContentView(root);

        back.post(new Runnable() {
            @Override public void run() { back.requestFocus(); }
        });

        // 自绘入场动画：系统动画缩放被关掉时也能看到"从右边滑进来"
        col.setTranslationX(Ui.dp(this, 120));
        col.setAlpha(0.35f);
        col.animate().translationX(0f).alpha(1f).setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();

        // 等首帧画出来再请求，转场动画期间窗口有内容，不会先白屏再闪
        scroll.post(new Runnable() {
            @Override public void run() {
                if (searchMode) {
                    if (searchBox != null) {
                        searchBox.requestFocus();
                        android.view.inputmethod.InputMethodManager imm =
                                (android.view.inputmethod.InputMethodManager)
                                        getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(searchBox, 0);
                    }
                } else {
                    load();
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        com.fntv.app.data.MediaStore.lastSort = sortBy;
        com.fntv.app.data.MediaStore.lastListGuid = libGuid;
        if (scrollRoot != null) com.fntv.app.data.MediaStore.lastScrollY = scrollRoot.getScrollY();
    }

    /** 搜索：结果复用同一个网格 */
    private void doSearch(final String q) {
        if (q.isEmpty()) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        countView.setText("搜索中…");
        listContainer.removeAllViews();
        cards.clear();
        api.getApi().search(q).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> c,
                                   Response<ApiResponse<List<PlayListItem>>> r) {
                if (!r.isSuccessful() || r.body() == null || r.body().code != 0
                        || r.body().data == null || r.body().data.isEmpty()) {
                    countView.setText("没有找到相关内容");
                    return;
                }
                countView.setText("共 " + r.body().data.size() + " 个结果");
                buildCards(r.body().data);
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> c, Throwable t) {
                countView.setText("搜索失败: " + t.getMessage());
            }
        });
    }

    private ItemListRequest request() {
        return new ItemListRequest(libGuid,
                Arrays.asList("Movie", "TV", "Directory", "Video"),
                true,
                sortBy == 1 ? "sort_title" : "create_time",
                sortBy == 1 ? "ASC" : "DESC",
                60);
    }

    private void load() {
        if (isLive) {
            if (sortTime != null) {
                sortTime.setVisibility(View.GONE);
                if (sortName != null) sortName.setVisibility(View.GONE);
            }
        } else {
            if (sortTime != null) sortTime.setSelected(sortBy == 0);
            if (sortName != null) sortName.setSelected(sortBy == 1);
        }
        // 先吃共享数据：首页已经拉过就直接出，零等待
        java.util.List<PlayListItem> cached = isLive ? null
                : com.fntv.app.data.MediaStore.getList(libGuid, sortBy);
        listContainer.removeAllViews();
        cards.clear();
        if (cached != null && !cached.isEmpty()) {
            countView.setText("共 " + cached.size() + " 项");
            buildCards(cached);
        } else {
            countView.setText("加载中…");
        }

        Call<ApiResponse<ItemListResponse>> call = isLive
                ? api.getApi().getItemList(ItemListRequest.browseLiveChannels())
                : api.getApi().getItemList(request());
        call.enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> c,
                                   Response<ApiResponse<ItemListResponse>> r) {
                if (!r.isSuccessful() || r.body() == null || r.body().code != 0
                        || r.body().data == null) {
                    countView.setText("加载失败");
                    return;
                }
                List<PlayListItem> list = r.body().data.list;
                if (list == null) list = new ArrayList<>();
                countView.setText("共 " + list.size() + " 项");
                if (!isLive) com.fntv.app.data.MediaStore.putList(libGuid, sortBy, list);
                // 服务端返回与缓存一致时不再重绘，避免闪烁
                if (cards.size() != list.size()) buildCards(list);
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> c, Throwable t) {
                countView.setText("加载失败: " + t.getMessage());
            }
        });
    }

    private List<PlayListItem> items = new ArrayList<>();

    /** 只记录数据，真正创建卡片交给分批渲染，避免一次性造上百个视图 */
    private void buildCards(List<PlayListItem> list) {
        items = list == null ? new ArrayList<>() : list;
        cards.clear();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) == null) continue;
            cards.add(null);   // 占位，渲染到那一屏时再创建
        }
        gridChunked(0, 2);
    }

    private View makeCard(final PlayListItem it) {
        int cardWdp = (int) (cardWPx / getResources().getDisplayMetrics().density + 0.5f);
        int cardHdp = Math.max(80, cardWdp * 3 / 2);   // 2:3 海报
        return makeCard(it, cardWdp, cardHdp);
    }

    private View makeCard(final PlayListItem it, int cardWdp, int cardHdp) {
        if (isLive) {
            return Ui.wideCard(this, it.title == null ? it.getDisplayTitle() : it.title,
                    subOf(it), Ui.posterUrl(baseUrl, it.poster), null, 0, 260, 146,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { play(it); }
                    });
        }
        return Ui.posterCard(this, it.getDisplayTitle(), subOf(it),
                Ui.posterUrl(baseUrl, it.poster), null, null, cardWdp, cardHdp,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openDetail(it); }
                });
    }

    private void gridChunked(int startRow, int rowsPerChunk) {
        // 全部用像素计算，避免 dp/px 混用把列数算偏
        float density = getResources().getDisplayMetrics().density;
        int screenWpx = getResources().getDisplayMetrics().widthPixels;
        int padPx = Math.round(112 * density);          // 左右留白 56dp * 2
        int gapPx = Math.round(18 * density);           // 卡片间距 18dp
        int availPx = Math.max(1, screenWpx - padPx);
        // 最小可读卡宽：只决定"一行最多塞几个"，实际宽度仍由可用空间均分
        int minCardPx = Math.round((isLive ? 300 : 135) * density);
        int cols = Math.max(1, (availPx + gapPx) / (minCardPx + gapPx));
        int cardWpx = Math.max(1, (availPx - gapPx * (cols - 1)) / cols);
        cardWPx = cardWpx;
        int start = startRow * cols;
        if (start >= items.size()) {
            if (start == 0) countView.setText("这个媒体库是空的");
            return;
        }
        int end = Math.min(start + cols * rowsPerChunk, items.size());
        for (int i = start; i < end; i += cols) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setClipChildren(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(this, 26);
            line.setLayoutParams(lp);
            for (int j = 0; j < cols; j++) {
                int idx = i + j;
                if (idx >= items.size()) break;
                PlayListItem it = items.get(idx);
                if (it == null) continue;
                View card = cards.size() > idx ? cards.get(idx) : null;
                if (card == null) {
                    card = makeCard(it);           // 只在这一刻创建这一批
                    while (cards.size() <= idx) cards.add(null);
                    cards.set(idx, card);
                }
                if (card.getParent() instanceof ViewGroup) {
                    ((ViewGroup) card.getParent()).removeView(card);
                }
                // 宽度＝均分后的像素值；高度由卡片内部按比例（海报 2:3 / 直播 16:9）自适应
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        cardWpx, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = (j == cols - 1) ? 0 : gapPx;
                card.setLayoutParams(clp);
                line.addView(card);
            }
            listContainer.addView(line);
        }
        if (end < items.size()) {
            final int next = startRow + rowsPerChunk;
            listContainer.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing()) gridChunked(next, rowsPerChunk);
                }
            }, 16);
        } else if (start == 0 && !cards.isEmpty() && cards.get(0) != null) {
            cards.get(0).postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing() && !cards.isEmpty() && cards.get(0) != null) {
                        cards.get(0).requestFocus();
                    }
                }
            }, 120);
        }
    }

    private String subOf(PlayListItem it) {
        String cat = it.getCategoryLabel();
        String sc = scoreOf(it);
        if (sc == null) return cat;
        return (cat == null || cat.isEmpty()) ? "★ " + sc : cat + " · ★ " + sc;
    }

    /** 评分统一保留 1 位小数（服务端会给很长的小数） */
    private String scoreOf(PlayListItem it) {
        String v = it.voteAverage;
        if (v == null || v.isEmpty()) return null;
        try {
            float f = Float.parseFloat(v);
            if (f <= 0f) return null;
            String s = String.format(java.util.Locale.US, "%.1f", f);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

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

    private void play(PlayListItem it) {
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
