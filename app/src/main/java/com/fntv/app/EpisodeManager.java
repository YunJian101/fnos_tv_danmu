package com.fntv.app;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.fntv.app.api.FnApiManager;
import com.fntv.app.api.model.ApiResponse;
import com.fntv.app.api.model.PlayListItem;

import java.util.List;

import retrofit2.Call;
import retrofit2.Response;

/** 剧集列表管理 — 加载、切换剧集 */
public class EpisodeManager {

    public interface Callback {
        String getBaseUrl();
        String getParentGuid();
        String getItemGuid();
        int getEpisodeNumber();
        FnApiManager getApiManager();
        Context getContext();
        void onSwitchEpisode(String guid, String title);
    }

    private List<PlayListItem> episodeList;
    private int currentEpIndex = -1;
    private boolean loadingEpisodes = false;
    private final Callback cb;
    private final Button btnEpisodeList;
    private final Button btnNextEp;
    private String currentGuid;
    private String currentTitle;

    private static final String TAG = "Player";

    public EpisodeManager(Callback cb, Button btnEpisodeList, Button btnNextEp) {
        this.cb = cb;
        this.btnEpisodeList = btnEpisodeList;
        this.btnNextEp = btnNextEp;
    }

    /** 是否有下一集 */
    public boolean hasNext() {
        return episodeList != null && currentEpIndex >= 0 && currentEpIndex < episodeList.size() - 1;
    }

    /** 是否正在加载 */
    public boolean isLoading() { return loadingEpisodes; }

    /** 是否已加载列表 */
    public boolean isLoaded() { return episodeList != null && !episodeList.isEmpty(); }

    /** 加载剧集列表 */
    public void loadList(String parentGuid) {
        if (parentGuid == null || parentGuid.isEmpty()) return;
        loadingEpisodes = true;
        Log.d(TAG, "getEpisodeList 请求: " + cb.getBaseUrl() + "/v/api/v1/episode/list/" + parentGuid);
        cb.getApiManager().getApi().getEpisodeList(parentGuid).enqueue(
                new retrofit2.Callback<ApiResponse<List<PlayListItem>>>() {
                    @Override
                    public void onResponse(Call<ApiResponse<List<PlayListItem>>> call,
                                           Response<ApiResponse<List<PlayListItem>>> resp) {
                        loadingEpisodes = false;
                        Log.d(TAG, "getEpisodeList 响应 code=" + resp.code()
                                + " body=" + (resp.body() != null ? "code=" + resp.body().code + " size="
                                + (resp.body().data != null ? resp.body().data.size() : "null") : "null"));
                        if (resp.isSuccessful() && resp.body() != null && resp.body().code == 0
                                && resp.body().data != null && !resp.body().data.isEmpty()) {
                            episodeList = resp.body().data;
                            currentEpIndex = -1;
                            int epNum = cb.getEpisodeNumber();
                            String itemGuid = cb.getItemGuid();
                            for (int i = 0; i < episodeList.size(); i++) {
                                PlayListItem ep = episodeList.get(i);
                                if (ep.guid.equals(itemGuid)) {
                                    currentEpIndex = i;
                                    break;
                                }
                                if (currentEpIndex < 0 && epNum > 0 && ep.episodeNumber == epNum) {
                                    currentEpIndex = i;
                                }
                            }
                            Log.d(TAG, "getEpisodeList 成功: " + episodeList.size() + " 集, currentIdx=" + currentEpIndex
                                    + " epNum=" + epNum + " itemGuid=" + itemGuid);
                            // 选集按钮已从底栏移除（与上/下一集重复），这里不再显示
        btnEpisodeList.setVisibility(View.GONE);
                            updateNextBtn();
                        }
                    }

                    @Override
                    public void onFailure(Call<ApiResponse<List<PlayListItem>>> call, Throwable t) {
                        loadingEpisodes = false;
                        Log.e(TAG, "getEpisodeList 失败: " + t.getMessage());
                    }
                });
    }

    public boolean hasPrev() { return episodeList != null && currentEpIndex > 0; }

    /** 当前正在播的集号（切集后标题要跟着变） */
    public int getCurrentEpisodeNumber() {
        if (episodeList != null && currentEpIndex >= 0 && currentEpIndex < episodeList.size()) {
            PlayListItem e = episodeList.get(currentEpIndex);
            return e.episodeNumber > 0 ? e.episodeNumber : currentEpIndex + 1;
        }
        return 0;
    }

    /** 当前正在播的集标题 */
    public String getCurrentEpisodeTitle() {
        if (episodeList != null && currentEpIndex >= 0 && currentEpIndex < episodeList.size()) {
            return episodeList.get(currentEpIndex).title;
        }
        return null;
    }

    /** 播放上一集 */
    public void playPrev() {
        if (!hasPrev()) return;
        PlayListItem prev = episodeList.get(currentEpIndex - 1);
        currentEpIndex--;
        currentGuid = prev.guid;
        currentTitle = prev.title;
        updateNextBtn();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    /** 播放下一个剧集 */
    public void playNext() {
        if (!hasNext()) return;
        PlayListItem next = episodeList.get(currentEpIndex + 1);
        currentEpIndex++;
        currentGuid = next.guid;
        currentTitle = next.title;
        updateNextBtn();
        cb.onSwitchEpisode(currentGuid, currentTitle);
    }

    /** 显示剧集选择器（自绘卡片，不用系统 AlertDialog） */
    public void showPicker() {
        if (episodeList == null || episodeList.isEmpty()) return;
        android.content.Context ctx = cb.getContext();
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);

        for (int i = 0; i < episodeList.size(); i++) {
            PlayListItem ep = episodeList.get(i);
            boolean current = i == currentEpIndex;
            String label = "EP" + (ep.episodeNumber > 0 ? ep.episodeNumber : (i + 1))
                    + "  " + (ep.title != null ? ep.title : "");
            android.widget.TextView row = new android.widget.TextView(ctx);
            row.setText(label + (current ? "  ✓" : ""));
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
            row.setTextColor(com.fntv.app.ui.Ui.isNight(ctx) ? 0xFFF3F6FA : 0xFF10151C);
            if (current) row.setTypeface(null, android.graphics.Typeface.BOLD);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(com.fntv.app.R.drawable.ui_card_plain);
            int ph = com.fntv.app.ui.Ui.dp(ctx, 18);
            int pv = com.fntv.app.ui.Ui.dp(ctx, 13);
            row.setPadding(ph, pv, ph, pv);
            row.setFocusable(true);
            row.setFocusableInTouchMode(false);
            android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = com.fntv.app.ui.Ui.dp(ctx, 8);
            row.setLayoutParams(rlp);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            com.fntv.app.ui.Ui.focusEffect(row);
            list.addView(row);
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        int h = com.fntv.app.ui.Ui.dp(ctx, 460);
        sv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, h));

        android.app.Activity act = (android.app.Activity) ctx;
        final android.app.Dialog d = com.fntv.app.ui.Ui.isNight(act)
                ? com.fntv.app.ui.Ui.dialogDark(act, "选择剧集", sv, "取消")
                : com.fntv.app.ui.Ui.dialog(act, "选择剧集", sv, null, null, "取消");

        for (int i = 0; i < episodeList.size() && i < list.getChildCount(); i++) {
            final int idx = i;
            list.getChildAt(i).setOnClickListener(new android.view.View.OnClickListener() {
                @Override public void onClick(android.view.View v) {
                    d.dismiss();
                    if (idx >= 0 && idx < episodeList.size()) {
                        PlayListItem s = episodeList.get(idx);
                        currentEpIndex = idx;
                        currentGuid = s.guid;
                        currentTitle = s.title;
                        updateNextBtn();
                        cb.onSwitchEpisode(currentGuid, currentTitle);
                    }
                }
            });
            if (i == currentEpIndex) list.getChildAt(i).requestFocus();
        }
        if (currentEpIndex < 0 && list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }
    /** 重置状态（切换到新剧时调用） */
    public void reset() {
        episodeList = null;
        currentEpIndex = -1;
        loadingEpisodes = false;
    }

    private void updateNextBtn() {
        btnNextEp.setVisibility(hasNext() ? View.VISIBLE : View.GONE);
    }
}
