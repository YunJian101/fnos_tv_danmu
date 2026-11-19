package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.ui.Theme;
import com.fntv.app.ui.Ui;

import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 季详情页：单独一页。剧集按网页端排版——
 * 左缩略图（已看打勾）+ 右侧「集号. 标题 / 时长 / 简介」。
 */
public class SeasonActivity extends AppCompatActivity {

    private FnApiManager api;
    private String baseUrl;
    private LinearLayout episodeContainer;
    private TextView titleView, subView, seriesView, overviewView;
    private android.widget.ImageView backdrop;

    private String seasonGuid, parentGuid, category, seasonTitle;
    private int seasonNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.applySaved(this);
        super.onCreate(savedInstanceState);
        com.fntv.app.ui.Ui.applyScale(this);
        setContentView(R.layout.activity_season);

        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        api = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (api.getApi() == null && !baseUrl.isEmpty()) api.updateBaseUrl(baseUrl);

        episodeContainer = findViewById(R.id.episodeContainer);
        titleView = findViewById(R.id.tvSeasonTitle);
        subView = findViewById(R.id.tvSeasonSub);
        seriesView = findViewById(R.id.tvSeriesTitle);
        overviewView = findViewById(R.id.tvSeasonOverview);
        backdrop = findViewById(R.id.seasonBackdrop);

        Intent i = getIntent();
        seasonGuid = i.getStringExtra("season_guid");
        parentGuid = i.getStringExtra("parent_guid");
        category = i.getStringExtra("category");
        seasonTitle = i.getStringExtra("season_title");
        seasonNumber = i.getIntExtra("season_number", 1);

        if (seasonTitle == null) seasonTitle = "第 " + seasonNumber + " 季";
        titleView.setText(seasonTitle);

        // 顶部沿用影片信息与背景图
        String series = i.getStringExtra("series_title");
        if (series != null && !series.isEmpty()) seriesView.setText(series);
        String overview = i.getStringExtra("overview");
        if (overview != null && !overview.isEmpty()) {
            overviewView.setText(overview);
        } else {
            overviewView.setVisibility(View.GONE);
        }
        String bd = i.getStringExtra("backdrop");
        if (bd != null && !bd.isEmpty()) {
            String url = Ui.backdropUrl(baseUrl, bd);
            if (url != null) Ui.loadImage(backdrop, url);
        }

        FrameLayout root = findViewById(R.id.seasonRoot);
        View back = Ui.backButton(this, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                Ui.dp(this, 48), Ui.dp(this, 48));
        blp.gravity = Gravity.TOP | Gravity.LEFT;
        blp.leftMargin = Ui.dp(this, 28);
        blp.topMargin = Ui.dp(this, 20);
        root.addView(back, blp);
        back.requestFocus();

        loadEpisodes();
    }

    private void loadEpisodes() {
        if (seasonGuid == null) return;
        episodeContainer.removeAllViews();
        for (int i = 0; i < 6; i++) episodeContainer.addView(skeletonRow());
        api.getApi().getEpisodeList(seasonGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                episodeContainer.removeAllViews();
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null
                        || response.body().data.isEmpty()) {
                    episodeContainer.addView(Ui.text(SeasonActivity.this, "这一季还没有剧集",
                            15, R.color.ui_text3, false));
                    return;
                }
                List<PlayListItem> eps = response.body().data;
                subView.setText("共 " + eps.size() + " 集");
                // 窗口化渲染：全部位置先用等高占位撑住，只有可见范围 ±6 行才真正构建
                pendingEps = eps;
                if (episodeScroll == null) episodeScroll = findScroll(episodeContainer);
                episodeContainer.removeAllViews();
                slots.clear();
                realRows.clear();
                int rowH = Ui.dp(SeasonActivity.this, 170);
                for (int i = 0; i < eps.size(); i++) {
                    FrameLayout slot = new FrameLayout(SeasonActivity.this);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, rowH);
                    lp.bottomMargin = Ui.dp(SeasonActivity.this, 14);
                    slot.setLayoutParams(lp);
                    episodeContainer.addView(slot);
                    slots.add(slot);
                }
                materializeAround(0);
                episodeScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                    @Override public void onScrollChange(View v, int sx, int sy, int ox, int oy) {
                        int first = sy / Ui.dp(SeasonActivity.this, 184);
                        materializeAround(first);
                    }
                });
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                episodeContainer.removeAllViews();
                episodeContainer.addView(Ui.text(SeasonActivity.this, "加载失败: " + t.getMessage(),
                        15, R.color.ui_text3, false));
            }
        });
    }

    private List<PlayListItem> pendingEps;
    private final java.util.List<FrameLayout> slots = new java.util.ArrayList<>();
    private final java.util.Map<Integer, View> realRows = new java.util.HashMap<>();
    private android.widget.ScrollView episodeScroll;

    private android.widget.ScrollView findScroll(android.view.View v) {
        while (v != null) {
            if (v instanceof android.widget.ScrollView) return (android.widget.ScrollView) v;
            v = (v.getParent() instanceof android.view.View) ? (android.view.View) v.getParent() : null;
        }
        return null;
    }

    /** 只构建可见范围 ±6 行，其余保持占位 */
    private void materializeAround(int firstVisible) {
        if (pendingEps == null) return;
        int from = Math.max(0, firstVisible - 12);
        int to = Math.min(pendingEps.size() - 1, firstVisible + 12);
        for (int i = from; i <= to; i++) {
            if (realRows.containsKey(i)) continue;
            FrameLayout slot = slots.get(i);
            View row = episodeRow(pendingEps.get(i));
            slot.removeAllViews();
            slot.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            realRows.put(i, row);
        }
    }
    private int renderedIdx = 0;

    private void renderChunk(int count) {
        if (pendingEps == null) return;
        int end = Math.min(renderedIdx + count, pendingEps.size());
        for (int i = renderedIdx; i < end; i++) {
            episodeContainer.addView(episodeRow(pendingEps.get(i)));
        }
        renderedIdx = end;
    }

    private void scheduleNextChunk() {
        if (pendingEps == null || renderedIdx >= pendingEps.size()) return;
        episodeContainer.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                renderChunk(16);
                scheduleNextChunk();
            }
        }, 16);
    }

    /** 加载占位行：灰块 + 两行灰条，接口回来前先撑住版面 */
    private View skeletonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 14);
        row.setLayoutParams(rlp);

        View thumb = new View(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(this, 240), Ui.dp(this, 135));
        thumb.setLayoutParams(tlp);
        thumb.setBackground(com.fntv.app.ui.Ui.round(0xFF2A3140, Ui.dp(this, 12)));
        thumb.setAlpha(0.35f);
        row.addView(thumb);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Ui.dp(this, 18);
        col.setLayoutParams(clp);
        for (int k = 0; k < 2; k++) {
            View line = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    k == 0 ? Ui.dp(this, 320) : Ui.dp(this, 500), Ui.dp(this, k == 0 ? 22 : 16));
            lp.topMargin = k == 0 ? 0 : Ui.dp(this, 12);
            line.setLayoutParams(lp);
            line.setBackground(com.fntv.app.ui.Ui.round(0xFF2A3140, Ui.dp(this, 6)));
            line.setAlpha(0.35f);
            col.addView(line);
        }
        row.addView(col);
        return row;
    }

    /** 一行一集：左缩略图，右标题/时长/简介（对齐网页端排版） */
    private View episodeRow(final PlayListItem ep) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);
        row.setBackgroundResource(R.drawable.ui_card_plain);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 14);
        row.setLayoutParams(rlp);

        FrameLayout thumb = new FrameLayout(this);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 340), Ui.dp(this, 192));
        thumb.setLayoutParams(tlp);
        thumb.addView(Ui.posterBox(this, Ui.posterUrl(baseUrl, ep.poster),
                        ep.episodeNumber > 0 ? String.valueOf(ep.episodeNumber) : "EP", 14),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        if (ep.watched == 1) {
            TextView tick = Ui.text(this, "✓", 14, R.color.ui_on_accent, true);
            tick.setGravity(Gravity.CENTER);
            tick.setBackgroundResource(R.drawable.ui_badge_ep);
            FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(
                    Ui.dp(this, 30), Ui.dp(this, 30));
            klp.gravity = Gravity.TOP | Gravity.RIGHT;
            klp.topMargin = Ui.dp(this, 8);
            klp.rightMargin = Ui.dp(this, 8);
            thumb.addView(tick, klp);
        }
        row.addView(thumb);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = Ui.dp(this, 24);
        info.setLayoutParams(ilp);

        String raw = ep.title == null ? "" : ep.title.trim();
        // 接口有时把标题写成「第 N 集」，避免出现「1.第 1 集」
        if (raw.startsWith("第") && raw.endsWith("集")) raw = "";
        String name;
        if (raw.isEmpty()) {
            name = ep.episodeNumber > 0 ? "第 " + ep.episodeNumber + " 集" : "剧集";
        } else {
            name = (ep.episodeNumber > 0 ? ep.episodeNumber + ". " : "") + raw;
        }
        TextView title = Ui.text(this, name, 23, R.color.ui_text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);

        if (ep.duration > 0) {
            TextView dur = Ui.text(this, cnDuration(ep.duration), 14, R.color.ui_text3, false);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(this, 8);
            dur.setLayoutParams(dlp);
            info.addView(dur);
        }

        if (ep.overview != null && !ep.overview.isEmpty()) {
            TextView ov = Ui.text(this, ep.overview, 15, R.color.ui_text2, false);
            ov.setMaxLines(4);
            ov.setEllipsize(TextUtils.TruncateAt.END);
            ov.setLineSpacing(Ui.dp(this, 5), 1f);
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.topMargin = Ui.dp(this, 8);
            ov.setLayoutParams(olp);
            info.addView(ov);
        }
        row.addView(info);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(SeasonActivity.this, PlayerActivity.class);
                intent.putExtra("guid", ep.guid);
                intent.putExtra("title", ep.title);
                intent.putExtra("tv_title", ep.tvTitle);
                intent.putExtra("episode_number", ep.episodeNumber);
                intent.putExtra("poster", ep.poster);
                intent.putExtra("category", category);
                intent.putExtra("ts", ep.ts);
                intent.putExtra("duration", ep.duration);
                if (parentGuid != null) intent.putExtra("parent_guid", parentGuid);
                startActivity(intent);
            }
        });
        Ui.focusEffect(row);
        return row;
    }

    /** 「20分8秒」这种时长文案，和网页端一致 */
    private static String cnDuration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分" + s + "秒";
        return s + "秒";
    }
}
