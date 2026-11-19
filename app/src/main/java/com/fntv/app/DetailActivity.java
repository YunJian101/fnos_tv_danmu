package com.fntv.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.ItemFileItem;
import com.fntv.app.api.model.ItemInfoData;
import com.fntv.app.api.model.ItemListRequest;
import com.fntv.app.api.model.ItemListResponse;
import com.fntv.app.api.model.PersonItem;
import com.fntv.app.api.model.PlayInfoResponse;
import com.fntv.app.api.model.PlayListItem;
import com.fntv.app.api.model.StreamListData;
import com.fntv.app.ui.Theme;
import com.fntv.app.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * 详情页：
 * 剧照打底 + 标题/评分/分类/播放 → 继续观看 → 季（封面卡，点进季详情页）
 * → 演职人员 → 视频信息 / 文件信息（仅电影、单集）。
 */
public class DetailActivity extends AppCompatActivity {

    private FnApiManager api;
    private String baseUrl;
    private LinearLayout container;
    private ImageView backdrop;
    private ImageView backdropNext;
    private String backdropUrl = null;   // 已应用的背景，避免重复加载

    private String guid, title, tvTitle, poster, category, overview, vote, type, parentGuid;
    private int episodeNumber, seasonNumber, numberOfSeasons, numberOfEpisodes;
    private long ts, duration;
    private String pendingGuid, pendingTitle, pendingTvTitle;
    private int pendingEpisode;

    private LinearLayout metaRow, seasonSection, castRow, videoSection, fileSection, continueRow;
    private android.widget.HorizontalScrollView continueHsv;
    private View castHeader, videoHeader, fileHeader, seasonTitle, continueHeader, continueScroll;
    private TextView overviewView, subtitleView, durationView, dateView;
    private LinearLayout specRow;
    private List<PlayListItem> seasons = new ArrayList<>();
    private boolean isSeries = false;
    private boolean isEpisode = false;   // 从「继续观看」进来的是单集
    private String airDate;
    private boolean firstResume = true;
    private String continueSig = null;   // 继续观看数据指纹，未变化就不重绘
    private String seasonPoster;   // 当前季封面（/item/{季guid}.posters）
    private final java.util.Map<String, String> seasonPosters = new java.util.HashMap<>();   // 每季各自的封面
    private final java.util.Map<String, String> seasonBackdrops = new java.util.HashMap<>(); // 每季各自的背景
    private String seriesArtwork;  // 剧集大图（/item/{剧集guid}.backdrops 优先）
    private PlayListItem resumeItem;
    private TextView playBtn;
    private final java.util.Set<String> seasonGuids = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.applySaved(this);
        super.onCreate(savedInstanceState);
        com.fntv.app.ui.Ui.applyScale(this);
        setContentView(R.layout.activity_detail);

        SharedPreferences prefs = getSharedPreferences("fntv_prefs", MODE_PRIVATE);
        api = FnApiManager.getInstance();
        baseUrl = prefs.getString("host", "").replaceAll("/+$", "");
        if (api.getApi() == null && !baseUrl.isEmpty()) api.updateBaseUrl(baseUrl);

        container = findViewById(R.id.detailContainer);
        backdrop = findViewById(R.id.backdrop);
        backdropNext = findViewById(R.id.backdropNext);

        Intent i = getIntent();
        guid = i.getStringExtra("guid");
        title = i.getStringExtra("title");
        tvTitle = i.getStringExtra("tv_title");
        poster = i.getStringExtra("poster");
        category = i.getStringExtra("category");
        overview = i.getStringExtra("overview");
        vote = i.getStringExtra("vote");
        type = i.getStringExtra("type");
        parentGuid = i.getStringExtra("parent_guid");
        episodeNumber = i.getIntExtra("episode_number", 0);
        seasonNumber = i.getIntExtra("season_number", 0);
        ts = i.getLongExtra("ts", 0);
        isEpisode = episodeNumber > 0;
        duration = i.getLongExtra("duration", 0);

        // 先用列表卡片同一张图填满背景（同一 URL 命中内存缓存），再换成大图
        // 不再先拿竖版封面当背景：那样会闪一下"海报→剧照"。
        // 卡片里带了 backdrop 就用它，没有就保持纯色底，等 loadArtwork 拿到真正的剧照再淡入。
        String quick = Ui.posterUrl(baseUrl, getIntent().getStringExtra("backdrop"));
        if (quick != null) applyBackdrop(quick);

        buildShell();
        loadDetail();
        loadSeasons();
        loadContinue();
        loadStreamInfo(guid);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放器退回来时刷新进度，避免还显示旧的那一集
        if (firstResume) {
            firstResume = false;
            return;
        }
        FnApiManager.invalidateApi("/play/list");
        FnApiManager.invalidateApi("/play/info");
        // 刚从 rail 播过某集：立即按那一集刷新头部，不必等服务端
        if (pendingGuid != null) {
            PlayListItem p = new PlayListItem();
            p.guid = pendingGuid;
            p.title = pendingTitle;
            p.tvTitle = pendingTvTitle;
            p.episodeNumber = pendingEpisode;
            p.seasonNumber = seasonNumber;
            applyResumeHeader(p);
            if (pendingGuid.equals(guid) == false) guid = guid;   // 保持详情页本身不变
        }
        // 只更新「继续（剩余…）」按钮和续播集，不动下面的卡片列表，避免整块重绘闪烁
        container.postDelayed(new Runnable() {
            @Override public void run() { refreshPlayState(); }
        }, 300);
    }

    /** 局部刷新：只更新续播记录与播放按钮文字，卡片列表保持原样 */
    private void refreshPlayState() {
        // 退出播放器后 1 秒再补拉一次，避开服务端落库延迟
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing()) refreshPlayStateOnce();
            }
        }, 1000);
    }

    /** 续播项变化时，把头部的季集/集名/简介一起刷新，避免"只有按钮在动" */
    /** 规格胶囊跟着续播集变化：重新拉这一集的流信息再重画 */
    /** 把「继续观看」横条直接定位到当前续播集（不依赖滚动动画，多次重试防布局未完成） */
    private void positionContinueRail() {
        final int[] delays = {80, 260, 600, 1100};
        for (int i = 0; i < delays.length; i++) {
            continueRow.postDelayed(new Runnable() {
                @Override public void run() {
                    if (isFinishing() || continueHsv == null || resumeItem == null
                            || resumeItem.guid == null) return;
                    for (int k = 0; k < continueRow.getChildCount(); k++) {
                        View c = continueRow.getChildAt(k);
                        Object tag = c.getTag();
                        if (tag instanceof String
                                && ((String) tag).equals("fntv_guid:" + resumeItem.guid)) {
                            int[] rowPos = new int[2];
                            int[] cardPos = new int[2];
                            continueRow.getLocationInWindow(rowPos);
                            c.getLocationInWindow(cardPos);
                            continueHsv.scrollTo(Math.max(0, cardPos[0] - rowPos[0]), 0);
                            break;
                        }
                    }
                }
            }, delays[i]);
        }
    }

    private void refreshSpecs(String episodeGuid) {
        if (episodeGuid == null || episodeGuid.isEmpty() || specRow == null) return;
        api.getApi().getStreamList(episodeGuid).enqueue(new Callback<ApiResponse<StreamListData>>() {
            @Override
            public void onResponse(Call<ApiResponse<StreamListData>> call,
                                   Response<ApiResponse<StreamListData>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null) return;
                StreamListData d = response.body().data;
                specRow.removeAllViews();
                if (d.videoStreams != null && !d.videoStreams.isEmpty()) {
                    StreamListData.VideoStream v = d.videoStreams.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (v.resolutionType != null) sb.append(v.resolutionType.toUpperCase());
                    if (v.colorRangeType != null && !v.colorRangeType.isEmpty()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(v.colorRangeType);
                    }
                    if (sb.length() > 0) specRow.addView(specChip(sb.toString(), true));
                }
                if (d.audioStreams != null && !d.audioStreams.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(2, d.audioStreams.size()); i++) {
                        if (d.audioStreams.get(i) == null) continue;
                        String codec = d.audioStreams.get(i).audioType != null
                                ? d.audioStreams.get(i).audioType
                                : d.audioStreams.get(i).codecName;
                        if (codec == null || codec.isEmpty()) continue;
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(codec);
                    }
                    if (sb.length() > 0) specRow.addView(specChip(sb.toString(), false));
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<StreamListData>> call, Throwable t) { }
        });
    }

    /** 续播项变化时刷新头部：集名用 title（tvTitle 是剧名，不能当集名） */
    private void applyResumeHeader(PlayListItem it) {
        try {
            String series = it.tvTitle != null && !it.tvTitle.isEmpty() ? it.tvTitle : it.title;
            String epName = it.title != null ? it.title : "";
            if (epName.equals(series)) epName = "";
            if (subtitleView != null && it.episodeNumber > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("S").append(it.seasonNumber > 0 ? it.seasonNumber : 1)
                  .append("E").append(it.episodeNumber);
                if (!epName.isEmpty()) sb.append("：").append(epName);
                subtitleView.setText(sb.toString());
            } else if (subtitleView != null && !epName.isEmpty()) {
                subtitleView.setText(epName);
            }
            if (overviewView != null && it.overview != null && !it.overview.isEmpty()) {
                overviewView.setText(it.overview);
            }
            if (durationView != null && it.duration > 0) {
                durationView.setText(cnDuration(it.duration));
            }
            if (dateView != null && it.airDate != null && !it.airDate.isEmpty()) {
                dateView.setText(it.airDate.replace("-", "/"));
            }
            // 规格标签也是按集算的（4K / HDR / 音频编码），换集就要重画
            if (it.guid != null && !it.guid.equals(guid)) {
                refreshSpecs(it.guid);
            }
        } catch (Exception ignored) { }
    }

    private void refreshPlayStateOnce() {
        // 从播放器回来必须拿最新进度，否则 30 秒 TTL 内会命中旧缓存
        com.fntv.app.api.FnApiManager.invalidateApi("/play/list");
        com.fntv.app.api.FnApiManager.invalidateApi("/play/info");
        api.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null) return;
                String name = displayTitle();
                for (PlayListItem it : response.body().data) {
                    if (it == null) continue;
                    // 从季页播放时，续播项是"某一集"，要按 parentGuid 归到本剧
                    String dTitle = displayTitle();
                    boolean sameSeries = (guid != null && (guid.equals(it.guid)
                            || guid.equals(it.parentGuid)))
                            || (parentGuid != null && (parentGuid.equals(it.parentGuid)
                            || parentGuid.equals(it.guid)))
                            || (it.tvTitle != null && dTitle != null
                            && (it.tvTitle.contains(dTitle) || dTitle.contains(it.tvTitle)))
                            || (pendingGuid != null && pendingGuid.equals(it.guid));
                    if (!sameSeries) continue;
                    String t = it.tvTitle != null && !it.tvTitle.isEmpty() ? it.tvTitle : it.title;
                    boolean sameTitle = t != null && t.equals(name);
                    boolean sameSeason = it.parentGuid != null && seasonGuids.contains(it.parentGuid);
                    if (sameTitle || sameSeason) {
                        resumeItem = it;
                        ts = it.ts;
                        applyResumeHeader(it);
                        updatePlayButton();
                        return;
                    }
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) { }
        });
    }

    private String displayTitle() {
        if (tvTitle != null && !tvTitle.isEmpty()) return tvTitle;
        return title == null ? "" : title;
    }

    // ---------------- 骨架 ----------------

    private void buildShell() {
        container.removeAllViews();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        View back = Ui.backButton(this, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);
        TextView ttl = Ui.text(this, displayTitle(), 26, R.color.ui_text, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 18);
        ttl.setLayoutParams(tlp);
        ttl.setSingleLine(true);
        ttl.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(ttl);
        container.addView(top);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 30);
        block.setLayoutParams(blp);

        TextView big = Ui.text(this, displayTitle(), 40, R.color.ui_text, true);
        big.setSingleLine(true);
        big.setEllipsize(TextUtils.TruncateAt.END);
        block.addView(big);

        // 单集详情：S1E8：集名
        subtitleView = Ui.text(this, "", 20, R.color.ui_text2, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sublp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sublp.topMargin = Ui.dp(this, 10);
        subtitleView.setLayoutParams(sublp);
        if (episodeNumber > 0) {
            String epName = title == null ? "" : title;
            subtitleView.setText("S" + Math.max(1, seasonNumber) + "E" + episodeNumber
                    + (epName.isEmpty() ? "" : "：" + epName));
        } else {
            subtitleView.setVisibility(View.GONE);
        }
        block.addView(subtitleView);

        // 视频 / 音频规格胶囊（单集才有数据）
        specRow = new LinearLayout(this);
        specRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splp.topMargin = Ui.dp(this, 12);
        specRow.setLayoutParams(splp);
        block.addView(specRow);

        metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 14);
        metaRow.setLayoutParams(mlp);
        block.addView(metaRow);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = Ui.dp(this, 14);
        chips.setLayoutParams(clp);
        if (category != null && !category.isEmpty()) {
            TextView chip = Ui.text(this, category, 14, R.color.ui_text2, false);
            chip.setBackgroundResource(R.drawable.ui_pill);
            chip.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
            chips.addView(chip);
        }
        block.addView(chips);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(this, 20);
        btns.setLayoutParams(bp);
        playBtn = Ui.primaryButton(this, ts > 0 ? "继续观看" : "播放", 0);
        playBtn.setPadding(Ui.dp(this, 46), Ui.dp(this, 16), Ui.dp(this, 46), Ui.dp(this, 16));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { onPlayClick(); }
        });
        btns.addView(playBtn);
        block.addView(btns);

        overviewView = Ui.text(this, overview == null ? "" : overview, 17, R.color.ui_text2, false);
        overviewView.setMaxLines(4);
        overviewView.setEllipsize(TextUtils.TruncateAt.END);
        overviewView.setLineSpacing(Ui.dp(this, 5), 1f);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = Ui.dp(this, 16);
        olp.rightMargin = Ui.dp(this, 260);
        overviewView.setLayoutParams(olp);
        block.addView(overviewView);
        container.addView(block);

        // 继续观看
        continueHeader = Ui.sectionHeader(this, "继续观看", null, null);
        container.addView(continueHeader);
        continueRow = Ui.row(this);
        continueScroll = Ui.rail(this, continueRow);
        if (continueScroll instanceof android.widget.HorizontalScrollView) {
            continueHsv = (android.widget.HorizontalScrollView) continueScroll;
        }
        container.addView(continueScroll);
        continueHeader.setVisibility(View.GONE);
        continueScroll.setVisibility(View.GONE);

        // 季
        seasonTitle = Ui.text(this, "季", 22, R.color.ui_text, true);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = Ui.dp(this, 14);
        stlp.bottomMargin = Ui.dp(this, 10);
        seasonTitle.setLayoutParams(stlp);
        seasonTitle.setVisibility(View.GONE);
        container.addView(seasonTitle);

        seasonSection = new LinearLayout(this);
        seasonSection.setOrientation(LinearLayout.VERTICAL);
        container.addView(seasonSection);

        // 演职人员
        castHeader = Ui.sectionHeader(this, "演职人员", null, null);
        container.addView(castHeader);
        castRow = Ui.row(this);
        container.addView(Ui.rail(this, castRow));
        castHeader.setVisibility(View.GONE);

        // 视频信息 / 文件信息
        videoHeader = Ui.sectionHeader(this, "视频信息", null, null);
        container.addView(videoHeader);
        videoSection = new LinearLayout(this);
        videoSection.setOrientation(LinearLayout.VERTICAL);
        container.addView(videoSection);
        videoHeader.setVisibility(View.GONE);

        fileHeader = Ui.sectionHeader(this, "文件信息", null, null);
        container.addView(fileHeader);
        fileSection = new LinearLayout(this);
        fileSection.setOrientation(LinearLayout.VERTICAL);
        container.addView(fileSection);
        fileHeader.setVisibility(View.GONE);

        rebuildMeta();
        back.requestFocus();
    }

    private void rebuildMeta() {
        if (metaRow == null) return;
        metaRow.removeAllViews();
        if (vote != null && !vote.isEmpty() && !"0".equals(vote) && !"0.0".equals(vote)) {
            String v = vote;
            try {
                v = String.format(java.util.Locale.US, "%.1f", Double.parseDouble(vote));
            } catch (Exception ignored) {}
            metaRow.addView(Ui.text(this, "★ " + v, 19, R.color.ui_text, true));
        }
        List<String> bits = new ArrayList<>();
        if (isEpisode) {
            // 单集：上面已经写了 S1E1，这里只留 播出日期 + 时长
            if (airDate != null && !airDate.isEmpty()) {
                dateView = Ui.text(this, airDate.replace("-", "/"), 19, R.color.ui_text3, false);
                bits.add("__DATE__");
            }
            if (duration > 0) {
                durationView = Ui.text(this, cnDuration(duration), 19, R.color.ui_text3, false);
                bits.add("__DUR__");   // 占位，下面用真正的控件替换
            }
        } else {
            if (seasonNumber > 0) bits.add("第 " + seasonNumber + " 季");
            if (numberOfEpisodes > 0) bits.add(numberOfEpisodes + " 集");
            if (duration > 0) bits.add(cnDuration(duration));
        }
        boolean first = metaRow.getChildCount() == 0;
        for (String b : bits) {
            if ("__DATE__".equals(b)) {
                if (!first) {
                    TextView sepD = Ui.text(this, "·", 19, R.color.ui_text3, false);
                    LinearLayout.LayoutParams spD = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    spD.leftMargin = Ui.dp(this, 12);
                    spD.rightMargin = Ui.dp(this, 12);
                    sepD.setLayoutParams(spD);
                    metaRow.addView(sepD);
                }
                metaRow.addView(dateView);
                first = false;
                continue;
            }
            if ("__DUR__".equals(b)) {
                if (!first) {
                    TextView sep0 = Ui.text(this, "·", 19, R.color.ui_text3, false);
                    LinearLayout.LayoutParams sp0 = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    sp0.leftMargin = Ui.dp(this, 12);
                    sp0.rightMargin = Ui.dp(this, 12);
                    sep0.setLayoutParams(sp0);
                    metaRow.addView(sep0);
                }
                metaRow.addView(durationView);
                first = false;
                continue;
            }
            if (!first) {
                TextView sep = Ui.text(this, "·", 19, R.color.ui_text3, false);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sp.leftMargin = Ui.dp(this, 12);
                sp.rightMargin = Ui.dp(this, 12);
                sep.setLayoutParams(sp);
                metaRow.addView(sep);
            }
            first = false;
            metaRow.addView(Ui.text(this, b, 19, R.color.ui_text2, false));
        }
    }

    // ---------------- 数据 ----------------

    private void loadDetail() {
        if (guid == null || guid.isEmpty()) return;
        Map<String, String> body = new HashMap<>();
        body.put("item_guid", guid);
        api.getApi().getPlayInfo(body).enqueue(new Callback<ApiResponse<PlayInfoResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<PlayInfoResponse>> call,
                                   Response<ApiResponse<PlayInfoResponse>> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null) {
                    PlayInfoResponse info = response.body().data;
                    if (info.item != null) {
                        if (info.item.overview != null && !info.item.overview.isEmpty()) {
                            overview = info.item.overview;
                        }
                        if (info.item.voteAverage != null && !info.item.voteAverage.isEmpty()) {
                            vote = info.item.voteAverage;
                        }
                        if (info.item.numberOfSeasons > 0) numberOfSeasons = info.item.numberOfSeasons;
                        if (info.item.numberOfEpisodes > 0) numberOfEpisodes = info.item.numberOfEpisodes;
                        if (info.item.seasonNumber > 0) seasonNumber = info.item.seasonNumber;
                        if (info.item.episodeNumber > 0) episodeNumber = info.item.episodeNumber;
                        if (info.item.duration > 0) duration = info.item.duration;
                        if (info.item.airDate != null) airDate = info.item.airDate;
                        if (info.parentGuid != null && !info.parentGuid.isEmpty()) {
                            parentGuid = info.parentGuid;
                        }
                        String bd = info.getBackdropPath();
                        if (bd != null) {
                            String url = Ui.backdropUrl(baseUrl, bd);
                            if (url != null) applyBackdrop(url);
                        }
                    }
                    if (overviewView != null && overview != null) overviewView.setText(overview);
                    rebuildMeta();
                    loadArtwork(parentGuid != null && !parentGuid.isEmpty() ? parentGuid : guid);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<PlayInfoResponse>> call, Throwable t) {}
        });
    }

    /** 继续观看：从播放记录里挑本剧的条目 */
    private void loadContinue() {
        api.getApi().getPlayList().enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null) {
                    return;
                }
                // 数据没变就不重建视图，避免每次从播放器返回都"闪一下"
                StringBuilder sigB = new StringBuilder();
                for (PlayListItem it : response.body().data) {
                    if (it == null) continue;
                    sigB.append(it.guid).append(':').append(it.ts).append('|');
                }
                String sig = sigB.toString();
                if (sig.equals(continueSig) && continueRow.getChildCount() > 0) {
                    return;
                }
                continueSig = sig;
                continueRow.removeAllViews();
                String name = displayTitle();
                List<PlayListItem> picked = new ArrayList<>();
                for (PlayListItem it : response.body().data) {
                    if (it == null) continue;
                    String t = it.tvTitle != null && !it.tvTitle.isEmpty() ? it.tvTitle : it.title;
                    boolean sameTitle = t != null && t.equals(name);
                    boolean sameSeason = it.parentGuid != null && seasonGuids.contains(it.parentGuid);
                    if (sameTitle || sameSeason) picked.add(it);
                    if (picked.size() >= 10) break;
                }
                resumeItem = picked.isEmpty() ? null : picked.get(0);
                if (resumeItem != null) {
                    // 无论从首页继续观看、分类还是别处进来，都以播放记录为准
                    isEpisode = true;
                    episodeNumber = resumeItem.episodeNumber;
                    seasonNumber = resumeItem.seasonNumber;
                    ts = resumeItem.ts;
                    if (resumeItem.duration > 0) duration = resumeItem.duration;
                    String epName = resumeItem.title == null ? "" : resumeItem.title;
                    if (subtitleView != null) {
                        subtitleView.setVisibility(View.VISIBLE);
                        subtitleView.setText("S" + Math.max(1, seasonNumber) + "E" + episodeNumber
                                + (epName.isEmpty() ? "" : "：" + epName));
                    }
                    loadStreamInfo(resumeItem.guid);
                    rebuildMeta();
                }
                updatePlayButton();
                if (resumeItem == null) {
                    continueHeader.setVisibility(View.GONE);
                    continueScroll.setVisibility(View.GONE);
                    return;
                }
                loadEpisodeRail(resumeItem);
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 季封面与剧集大图：按季 guid 取 posters，再按剧集 guid 取 backdrops */
    private void loadArtwork(final String seasonGuid) {
        if (seasonGuid == null || seasonGuid.isEmpty()) return;
        final String seasonGuidForArt = seasonGuid;
        api.getApi().getItemInfo(seasonGuid).enqueue(new Callback<ApiResponse<ItemInfoData>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemInfoData>> call,
                                   Response<ApiResponse<ItemInfoData>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null) return;
                ItemInfoData d = response.body().data;
                if (d.posters != null && !d.posters.isEmpty()) {
                    seasonPoster = d.posters;
                    if (seasonGuidForArt != null && d.posters != null) seasonPosters.put(seasonGuidForArt, d.posters);
                    if (!seasons.isEmpty()) renderSeasons();
                }
                String seriesGuid = d.parentGuid;
                if (seriesGuid != null && !seriesGuid.isEmpty()) {
                    api.getApi().getItemInfo(seriesGuid).enqueue(new Callback<ApiResponse<ItemInfoData>>() {
                        @Override
                        public void onResponse(Call<ApiResponse<ItemInfoData>> c2,
                                               Response<ApiResponse<ItemInfoData>> r2) {
                            if (!r2.isSuccessful() || r2.body() == null
                                    || r2.body().code != 0 || r2.body().data == null) return;
                            ItemInfoData s = r2.body().data;
                            String art = (s.backdrops != null && !s.backdrops.isEmpty())
                                    ? s.backdrops : s.posters;
                            if (art != null && !art.isEmpty()) {
                                seriesArtwork = art;
                                String url = Ui.backdropUrl(baseUrl, art);
                                if (url != null) applyBackdrop(url);
                            }
                        }

                        @Override
                        public void onFailure(Call<ApiResponse<ItemInfoData>> c2, Throwable t) {}
                    });
                } else if (seasonPoster != null) {
                    String url = Ui.backdropUrl(baseUrl, seasonPoster);
                    if (url != null) applyBackdrop(url);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemInfoData>> call, Throwable t) {}
        });
    }

    /** 继续观看：以正在看的那一集为起点，列出整季所有集，焦点落在该集上 */
    /** 背景统一入口：单数据源 + 双层交叉淡入，旧图始终可见直到新图淡入完成 */
    private void applyBackdrop(String url) {
        if (url == null || url.isEmpty() || backdropNext == null) return;
        if (url.equals(backdropUrl)) return;
        backdropUrl = url;
        com.fntv.app.util.SimpleImageLoader.loadWithCallback(url, backdropNext,
                FnApiManager.getInstance().getClient(), new Runnable() {
                    @Override public void run() {
                        backdropNext.setAlpha(0f);
                        backdropNext.animate().alpha(1f).setDuration(260).withEndAction(new Runnable() {
                            @Override public void run() {
                                if (backdropNext.getDrawable() != null) {
                                    backdrop.setImageDrawable(backdropNext.getDrawable());
                                }
                                backdropNext.setAlpha(0f);
                            }
                        }).start();
                    }
                });
    }

    private void loadEpisodeRail(final PlayListItem resume) {
        String seasonGuid = resume.parentGuid;
        if (seasonGuid == null && !seasons.isEmpty()) seasonGuid = seasons.get(0).guid;
        if (seasonGuid == null) return;
        api.getApi().getEpisodeList(seasonGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                continueRow.removeAllViews();
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null
                        || response.body().data.isEmpty()) {
                    continueHeader.setVisibility(View.GONE);
                    continueScroll.setVisibility(View.GONE);
                    return;
                }
                continueHeader.setVisibility(View.VISIBLE);
                continueScroll.setVisibility(View.VISIBLE);
                View focusTarget = null;
                List<PlayListItem> eps = response.body().data;
                int max = Math.min(40, eps.size());
                for (int i = 0; i < max; i++) {
                    final PlayListItem ep = eps.get(i);
                    boolean current = ep.guid != null && ep.guid.equals(resume.guid);
                    long remain = ep.duration > 0 ? Math.max(0, ep.duration - ep.ts) : 0;
                    String badge = current ? "正在观看"
                            : (ep.ts > 0 && remain > 0 ? Ui.remainingText(remain) : null);
                    int pct = ep.duration > 0 ? (int) (ep.ts * 100 / ep.duration) : 0;
                    String title = ep.episodeNumber > 0 ? "第 " + ep.episodeNumber + " 集"
                            : ep.getDisplayTitle();
                    String sub = ep.title != null && !ep.title.isEmpty() ? ep.title
                            : Ui.formatDuration(ep.duration);
                    View card = Ui.wideCard(DetailActivity.this, title, sub,
                            Ui.posterUrl(baseUrl, ep.poster), badge, pct, 300, 169,
                            new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    play(ep.guid, ep.title, ep.tvTitle, ep.episodeNumber,
                                            ep.poster, ep.ts, ep.duration);
                                }
                            });
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) card.getLayoutParams();
                    lp.rightMargin = Ui.dp(DetailActivity.this, 14);
                    if (ep.guid != null) card.setTag("fntv_guid:" + ep.guid);
                    continueRow.addView(card);
                    if (current) focusTarget = card;
                }
                positionContinueRail();
                // 进详情页优先聚焦「播放/继续」按钮，而不是某一集
                final View f = focusTarget;
                f.post(new Runnable() {
                    @Override public void run() {
                        // 焦点统一由 focusMainButton() 负责，这里不再抢
                        focusMainButton();
                    }
                });
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {}
        });
    }

    /** 播放按钮：有进度就续播那一集；单集直接播自己；剧集没进度就播第 1 季第 1 集 */
    private void onPlayClick() {
        if (resumeItem != null && resumeItem.guid != null) {
            play(resumeItem.guid, resumeItem.title, resumeItem.tvTitle,
                    resumeItem.episodeNumber, resumeItem.poster,
                    resumeItem.ts, resumeItem.duration);
            return;
        }
        if (isEpisode) {
            play(guid, title, tvTitle, episodeNumber, poster, ts, duration);
            return;
        }
        if (isSeries && !seasons.isEmpty() && seasons.get(0).guid != null) {
            playFirstEpisode(seasons.get(0).guid);
            return;
        }
        play(guid, title, tvTitle, episodeNumber, poster, ts, duration);
    }

    /** 没有播放记录时，从第 1 集开始播 */
    private void playFirstEpisode(String seasonGuid) {
        api.getApi().getEpisodeList(seasonGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null
                        || response.body().data.isEmpty()) {
                    Ui.toast(DetailActivity.this, "这一季没有可播放的剧集");
                    return;
                }
                PlayListItem ep = response.body().data.get(0);
                play(ep.guid, ep.title, ep.tvTitle, ep.episodeNumber, ep.poster, ep.ts, ep.duration);
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                Ui.toast(DetailActivity.this, "获取剧集失败");
            }
        });
    }
    private boolean focusGiven = false;

    /** 页面首屏完成后统一把焦点交给主按钮（播放/继续），与有没有播放记录无关 */
    private void focusMainButton() {
        if (focusGiven || playBtn == null) return;
        playBtn.postDelayed(new Runnable() {
            @Override public void run() {
                if (focusGiven || playBtn == null || !playBtn.isShown()) return;
                focusGiven = true;
                playBtn.requestFocus();
            }
        }, 120);
    }

    private void updatePlayButton() {
        if (playBtn == null) return;
        focusMainButton();
        if (isEpisode) {
            if (ts > 0 && duration > ts) {
                playBtn.setText("继续（剩余 " + cnDuration(duration - ts) + "）");
            } else {
                playBtn.setText("播放");
            }
            return;
        }
        if (isSeries) {
            if (resumeItem == null) {
                playBtn.setText("播放");
            } else {
                StringBuilder sb = new StringBuilder("继续观看");
                if (resumeItem.seasonNumber > 0 || resumeItem.episodeNumber > 0) {
                    sb.append(" 第").append(Math.max(1, resumeItem.seasonNumber)).append("季");
                    if (resumeItem.episodeNumber > 0) {
                        sb.append("第").append(resumeItem.episodeNumber).append("集");
                    }
                }
                playBtn.setText(sb.toString());
            }
        } else {
            if (ts > 0 && duration > ts) {
                playBtn.setText("继续（剩余 " + cnDuration(duration - ts) + "）");
            } else {
                playBtn.setText("播放");
            }
        }
    }

    /** 季：封面卡，点开进季详情页 */
    private void loadSeasons() {
        String target = parentGuid != null && !parentGuid.isEmpty() ? parentGuid : guid;
        if (target == null || target.isEmpty()) return;
        final String rootGuid = target;
        api.getApi().getSeasonList(rootGuid).enqueue(new Callback<ApiResponse<List<PlayListItem>>>() {
            @Override
            public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                   Response<ApiResponse<List<PlayListItem>>> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null
                        && !response.body().data.isEmpty()) {
                    seasons = response.body().data;
                    numberOfSeasons = seasons.size();
                    seasonGuids.clear();
                    for (PlayListItem s : seasons) {
                        if (s.guid != null) seasonGuids.add(s.guid);
                    }
                    rebuildMeta();
                    renderSeasons();
                    loadCast(seasons.get(0).guid);
                } else {
                    loadSeasonsViaList(rootGuid);
                    return;
                }
                if (false) {
                    boolean isTv = "TV".equals(type) || numberOfSeasons > 0
                            || episodeNumber > 0
                            || (tvTitle != null && !tvTitle.isEmpty());
                    if (isTv) {
                        PlayListItem ps = new PlayListItem();
                        ps.guid = rootGuid;
                        ps.seasonNumber = Math.max(1, seasonNumber);
                        ps.numberOfEpisodes = numberOfEpisodes;
                        ps.poster = poster;
                        seasons = new ArrayList<>();
                        seasons.add(ps);
                        seasonGuids.clear();
                        seasonGuids.add(rootGuid);
                        renderSeasons();
                        loadCast(rootGuid);
                    } else {
                        isSeries = false;
                        loadCast(guid);
                    }
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                loadCast(guid);
            }
        });
    }

    /** 兜底：按 item/list 查这个剧集下所有季（season/list 只认季 guid） */
    private void loadSeasonsViaList(String seriesGuid) {
        if (seriesGuid == null || seriesGuid.isEmpty()) return;
        ItemListRequest req = new ItemListRequest(seriesGuid,
                java.util.Arrays.asList("Season"), true, "season_number", "ASC", 50);
        api.getApi().getItemList(req).enqueue(new Callback<ApiResponse<ItemListResponse>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemListResponse>> call,
                                   Response<ApiResponse<ItemListResponse>> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null
                        && response.body().data.list != null
                        && !response.body().data.list.isEmpty()) {
                    seasons = response.body().data.list;
                    numberOfSeasons = seasons.size();
                    seasonGuids.clear();
                    for (PlayListItem s : seasons) {
                        if (s.guid != null) seasonGuids.add(s.guid);
                    }
                    rebuildMeta();
                    renderSeasons();
                    loadCast(seasons.get(0).guid);
                } else {
                    boolean isTv = "TV".equals(type) || numberOfSeasons > 0
                            || episodeNumber > 0
                            || (tvTitle != null && !tvTitle.isEmpty());
                    if (isTv) {
                        PlayListItem ps = new PlayListItem();
                        ps.guid = seriesGuid;
                        ps.seasonNumber = Math.max(1, seasonNumber);
                        ps.numberOfEpisodes = numberOfEpisodes;
                        ps.poster = poster;
                        seasons = new ArrayList<>();
                        seasons.add(ps);
                        seasonGuids.clear();
                        seasonGuids.add(seriesGuid);
                        renderSeasons();
                        loadCast(seriesGuid);
                    } else {
                        isSeries = false;
                        loadCast(guid);
                    }
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemListResponse>> call, Throwable t) {}
        });
    }

    private void renderSeasons() {
        isSeries = true;
        seasonTitle.setVisibility(View.VISIBLE);
        seasonSection.removeAllViews();

        if (!isEpisode) {
            videoHeader.setVisibility(View.GONE);
            fileHeader.setVisibility(View.GONE);
            videoSection.removeAllViews();
            fileSection.removeAllViews();
        }

        LinearLayout row = Ui.row(this);
        for (int i = 0; i < seasons.size(); i++) {
            final PlayListItem s = seasons.get(i);
            final int num = s.seasonNumber > 0 ? s.seasonNumber : (i + 1);
            String sub = s.numberOfEpisodes > 0 ? s.numberOfEpisodes + " 集" : "";
            if (s.airDate != null && s.airDate.length() >= 4) {
                sub = sub.isEmpty() ? s.airDate.substring(0, 4) : sub + " · " + s.airDate.substring(0, 4);
            }
            String own = s.guid != null ? seasonPosters.get(s.guid) : null;
            String cover = own != null ? own : (seasonPoster != null ? seasonPoster : poster);
            String badge = s.numberOfEpisodes > 0 ? String.valueOf(s.numberOfEpisodes) : null;
            View card = Ui.posterCard(this, "第 " + num + " 季", sub,
                    Ui.posterUrl(baseUrl, cover), badge, null, 150, 214,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { openSeason(s, num); }
                    });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) card.getLayoutParams();
            lp.rightMargin = Ui.dp(this, 14);
            row.addView(card);
        }
        seasonSection.addView(Ui.rail(this, row));
        if (seasons.size() > 1) prefetchSeasonArt(0);
        updatePlayButton();
    }

    /** 季详情单独一页 */
    private void openSeason(PlayListItem s, int num) {
        Intent i = new Intent(this, SeasonActivity.class);
        i.putExtra("season_guid", s.guid);
        i.putExtra("season_number", num);
        i.putExtra("season_title", "第 " + num + " 季");
        i.putExtra("series_title", displayTitle());
        i.putExtra("overview", overview);
        String ownBd = s.guid != null ? seasonBackdrops.get(s.guid) : null;
        i.putExtra("backdrop", ownBd != null ? ownBd
                : (seriesArtwork != null ? seriesArtwork : (seasonPoster != null ? seasonPoster : poster)));
        i.putExtra("category", category);
        if (parentGuid != null) i.putExtra("parent_guid", parentGuid);
        startActivity(i);
    }

    /** 为每一季预取各自的封面与背景（多季剧集的关键） */
    private void prefetchSeasonArt(int index) {
        if (index >= seasons.size()) return;
        PlayListItem s = seasons.get(index);
        if (s.guid == null) { prefetchSeasonArt(index + 1); return; }
        if (seasonPosters.containsKey(s.guid)) { prefetchSeasonArt(index + 1); return; }
        api.getApi().getItemInfo(s.guid).enqueue(new Callback<ApiResponse<ItemInfoData>>() {
            @Override
            public void onResponse(Call<ApiResponse<ItemInfoData>> call,
                                   Response<ApiResponse<ItemInfoData>> response) {
                if (response.isSuccessful() && response.body() != null
                        && response.body().code == 0 && response.body().data != null) {
                    ItemInfoData d = response.body().data;
                    if (d.posters != null) seasonPosters.put(s.guid, d.posters);
                    if (d.backdrops != null) seasonBackdrops.put(s.guid, d.backdrops);
                    renderSeasons();
                }
                prefetchSeasonArt(index + 1);
            }

            @Override
            public void onFailure(Call<ApiResponse<ItemInfoData>> call, Throwable t) {
                prefetchSeasonArt(index + 1);
            }
        });
    }

    /** 演职人员 */
    private void loadCast(String target) {
        if (target == null || target.isEmpty()) return;
        Map<String, Object> body = new HashMap<>();
        body.put("page", 1);
        body.put("page_size", 30);
        api.getApi().getPersonList(target, body).enqueue(new Callback<ApiResponse<PersonItem.Data>>() {
            @Override
            public void onResponse(Call<ApiResponse<PersonItem.Data>> call,
                                   Response<ApiResponse<PersonItem.Data>> response) {
                castRow.removeAllViews();
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null
                        || response.body().data.list == null
                        || response.body().data.list.isEmpty()) {
                    castHeader.setVisibility(View.GONE);
                    return;
                }
                castHeader.setVisibility(View.VISIBLE);
                for (PersonItem p : response.body().data.list) {
                    castRow.addView(castCard(p));
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<PersonItem.Data>> call, Throwable t) {
                castHeader.setVisibility(View.GONE);
            }
        });
    }

    private View castCard(PersonItem p) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, 132), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 18);
        col.setLayoutParams(lp);

        FrameLayout avatar = new FrameLayout(this);
        avatar.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104)));
        RoundedImageView img = new RoundedImageView(this);
        img.setCornerRadius(Ui.dp(this, 52));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackgroundColor(Ui.color(this, R.color.ui_surface2));
        avatar.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String url = Ui.posterUrl(baseUrl, p.profilePath);
        if (url != null) {
            Ui.loadImage(img, url);
        } else {
            TextView ph = Ui.text(this, p.name == null || p.name.isEmpty() ? "?" : p.name.substring(0, 1),
                    30, R.color.ui_text3, true);
            ph.setGravity(Gravity.CENTER);
            avatar.addView(ph, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        col.addView(avatar);

        TextView name = Ui.text(this, p.name == null ? "" : p.name, 14, R.color.ui_text, true);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = Ui.dp(this, 10);
        name.setLayoutParams(nlp);
        col.addView(name);

        String sub = p.subtitle();
        if (!sub.isEmpty()) {
            TextView role = Ui.text(this, sub, 12, R.color.ui_text3, false);
            role.setGravity(Gravity.CENTER);
            role.setSingleLine(true);
            role.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(this, 4);
            role.setLayoutParams(rlp);
            col.addView(role);
        }
        return col;
    }

    /** 视频信息 / 文件信息：一次 stream/list 拿全（编码、码率、位深、声道、采样率、文件路径大小日期） */
    private void loadStreamInfo(final String itemGuid) {
        if (itemGuid == null || itemGuid.isEmpty()) return;
        api.getApi().getStreamList(itemGuid).enqueue(new Callback<ApiResponse<StreamListData>>() {
            @Override
            public void onResponse(Call<ApiResponse<StreamListData>> call,
                                   Response<ApiResponse<StreamListData>> response) {
                videoSection.removeAllViews();
                fileSection.removeAllViews();
                if (!response.isSuccessful() || response.body() == null
                        || response.body().code != 0 || response.body().data == null) {
                    videoHeader.setVisibility(View.GONE);
                    fileHeader.setVisibility(View.GONE);
                    return;
                }
                StreamListData d = response.body().data;

                // ---- 视频信息 ----
                List<String[]> rows = new ArrayList<>();
                if (d.videoStreams != null && !d.videoStreams.isEmpty()) {
                    StreamListData.VideoStream v = d.videoStreams.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (v.resolutionType != null && !v.resolutionType.isEmpty()) {
                        sb.append(v.resolutionType.toUpperCase());
                    }
                    if (v.codecName != null && !v.codecName.isEmpty()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(v.codecName.toUpperCase());
                    }
                    if (v.bps > 0) {
                        sb.append(" ").append(String.format(java.util.Locale.US, "%.2f", v.bps / 1000000f)).append(" mbps");
                    }
                    if (v.bitDepth > 0) sb.append(" · ").append(v.bitDepth).append(" bit");
                    if (v.width > 0) sb.append(" · ").append(v.width).append("×").append(v.height);
                    if (v.frameRate != null && !v.frameRate.isEmpty()) sb.append(" · ").append(v.frameRate);
                    rows.add(new String[]{"视频", sb.toString().trim()});
                }
                if (d.audioStreams != null) {
                    int n = Math.min(2, d.audioStreams.size());
                    for (int i = 0; i < n; i++) {
                        StreamListData.AudioStream a = d.audioStreams.get(i);
                        StringBuilder sb = new StringBuilder();
                        sb.append(langLabel(a.language));
                        if (a.codecName != null && !a.codecName.isEmpty()) {
                            sb.append(" ").append(a.codecName.toUpperCase());
                        }
                        if (a.channelLayout != null && !a.channelLayout.isEmpty()) {
                            sb.append(" ").append(a.channelLayout);
                        }
                        if (a.sampleRate != null && !a.sampleRate.isEmpty()) {
                            sb.append(" · ").append(a.sampleRate).append(" Hz");
                        }
                        if (a.isDefault == 1) sb.append("（默认）");
                        rows.add(new String[]{i == 0 ? "音频" : "音频 " + (i + 1), sb.toString().trim()});
                    }
                }
                if (!rows.isEmpty()) {
                    videoHeader.setVisibility(View.VISIBLE);
                    videoSection.addView(infoCard(rows));
                } else {
                    videoHeader.setVisibility(View.GONE);
                }

                // ---- 标题下的规格胶囊 ----
                specRow.removeAllViews();
                if (d.videoStreams != null && !d.videoStreams.isEmpty()) {
                    StreamListData.VideoStream v = d.videoStreams.get(0);
                    StringBuilder sb = new StringBuilder();
                    if (v.resolutionType != null) sb.append(v.resolutionType.toUpperCase());
                    if (v.colorRangeType != null && !v.colorRangeType.isEmpty()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(v.colorRangeType);
                    }
                    if (sb.length() > 0) specRow.addView(specChip(sb.toString(), true));
                }
                if (d.audioStreams != null && !d.audioStreams.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(2, d.audioStreams.size()); i++) {
                        if (i > 0) sb.append(" / ");
                        String t = d.audioStreams.get(i).audioType;
                        sb.append(t == null || t.isEmpty()
                                ? d.audioStreams.get(i).codecName : t);
                    }
                    if (sb.length() > 0) specRow.addView(specChip(sb.toString(), false));
                }

                // ---- 文件信息 ----
                if (d.files != null && !d.files.isEmpty()) {
                    StreamListData.StreamFile f = d.files.get(0);
                    List<String[]> frows = new ArrayList<>();
                    if (f.fileName != null) frows.add(new String[]{"文件名", f.fileName});
                    // 只显示所在目录，不把整条带 token 的播放地址摊在屏幕上
        if (f.path != null && !f.path.isEmpty()) {
            String dir = f.path;
            int slash = Math.max(dir.lastIndexOf('/'), dir.lastIndexOf('\\'));
            if (slash > 0) dir = dir.substring(0, slash);
            if (dir.length() > 90) dir = "…" + dir.substring(dir.length() - 90);
            frows.add(new String[]{"所在目录", dir});
        }
                    if (f.size > 0) frows.add(new String[]{"文件大小", humanSize(f.size)});
                    String ct = dateOf(f.createTime);
                    if (!ct.isEmpty()) frows.add(new String[]{"文件创建日期", ct});
                    String at = dateOf(f.timestamp);
                    if (!at.isEmpty()) frows.add(new String[]{"添加日期", at});
                    if (!frows.isEmpty()) {
                        fileHeader.setVisibility(View.VISIBLE);
                        fileSection.addView(infoCard(frows));
                    }
                } else {
                    fileHeader.setVisibility(View.GONE);
                }
            }

            @Override
            public void onFailure(Call<ApiResponse<StreamListData>> call, Throwable t) {
                videoHeader.setVisibility(View.GONE);
                fileHeader.setVisibility(View.GONE);
            }
        });
    }

    private static String langLabel(String code) {
        if (code == null || code.isEmpty()) return "未知";
        switch (code.toLowerCase()) {
            case "chi": case "zho": case "zh": return "中文";
            case "eng": case "en": return "英语";
            case "jpn": case "ja": return "日语";
            case "kor": case "ko": return "韩语";
            default: return code;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gb >= 1) return String.format(java.util.Locale.US, "%.2f GB", gb);
        double mb = bytes / 1024.0 / 1024.0;
        return String.format(java.util.Locale.US, "%.1f MB", mb);
    }

    private static String dateOf(long ms) {
        long v = ms;
        if (v > 0 && v < 100000000000L) v = v * 1000;   // 秒 → 毫秒
        if (v <= 0) return "";
        String s = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date(v));
        // 明显不合理的年份直接不显示，别把 58009 这种写上去
        if (s.compareTo("1990") < 0 || s.compareTo("2100") > 0) return "";
        return s;
    }
    /** 信息卡片：窄卡 + 标签在上、值在下的两行式（对齐网页端） */
    private View infoCard(List<String[]> rows) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.ui_card);
        card.setPadding(Ui.dp(this, 26), Ui.dp(this, 20), Ui.dp(this, 26), Ui.dp(this, 8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Ui.dp(this, 820), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(this, 8);
        card.setLayoutParams(clp);

        for (String[] r : rows) {
            TextView label = Ui.text(this, r[0], 13, R.color.ui_text3, false);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(label);

            TextView value = Ui.text(this, r[1], 16, R.color.ui_text, false);
            value.setLineSpacing(Ui.dp(this, 3), 1f);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = Ui.dp(this, 7);
            vlp.bottomMargin = Ui.dp(this, 16);
            value.setLayoutParams(vlp);
            card.addView(value);
        }
        return card;
    }
    /** 规格胶囊：视频带 ▶ 图标位，音频带 ♪ */
    private View specChip(String text, boolean video) {
        TextView tv = Ui.text(this, (video ? "▣  " : "♪  ") + text, 14, R.color.ui_text2, false);
        tv.setBackgroundResource(R.drawable.ui_pill);
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String cnDuration(long seconds) {
        if (seconds <= 0) return "";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return h + "小时" + m + "分";
        if (m > 0) return m + "分" + s + "秒";
        return s + "秒";
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(s);
        }
        return sb.toString();
    }

    private static String objText(Object o) {
        if (o == null) return "";
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object x : (List<?>) o) {
                if (x == null) continue;
                if (sb.length() > 0) sb.append(" / ");
                sb.append(x.toString());
            }
            return sb.toString();
        }
        return o.toString();
    }

    private static String typeLabel(String t) {
        if ("Episode".equals(t)) return "剧集";
        if ("Movie".equals(t)) return "电影";
        if ("Video".equals(t)) return "视频";
        if ("TV".equals(t)) return "剧集";
        return t;
    }

    private void play(String g, String t, String tv, int ep, String po, long start, long dur) {
        // 记住这次是从哪一集播的，返回时优先用它刷新头部
        pendingGuid = g;
        pendingTitle = t;
        pendingTvTitle = tv;
        pendingEpisode = ep;
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("guid", g);
        intent.putExtra("title", t);
        intent.putExtra("tv_title", tv);
        intent.putExtra("episode_number", ep);
        intent.putExtra("poster", po);
        intent.putExtra("category", category);
        intent.putExtra("ts", start);
        intent.putExtra("duration", dur);
        if (parentGuid != null) intent.putExtra("parent_guid", parentGuid);
        startActivity(intent);
    }
}
