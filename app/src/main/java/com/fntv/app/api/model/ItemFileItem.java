package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;

/** 媒体文件条目（GET api/v1/media/itemfile/{guid}） */
public class ItemFileItem {

    @SerializedName("media_guid")
    public String mediaGuid;

    @SerializedName("item_guid")
    public String itemGuid;

    public String filename;

    @SerializedName("file_path")
    public String filePath;

    public long duration;

    @SerializedName("create_time")
    public long createTime;

    @SerializedName("season_number")
    public int seasonNumber;

    @SerializedName("episode_number")
    public int episodeNumber;

    @SerializedName("media_stream")
    public PlayListItem.MediaStreamInfo mediaStream;
}
