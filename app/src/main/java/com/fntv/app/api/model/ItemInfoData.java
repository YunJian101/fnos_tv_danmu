package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** 条目详情（GET api/v1/item/{guid}），用于视频信息 */
public class ItemInfoData {

    public String guid;
    public String title;

    @SerializedName("tv_title")
    public String tvTitle;

    @SerializedName("parent_guid")
    public String parentGuid;

    @SerializedName("original_title")
    public String originalTitle;

    public String posters;
    public String backdrops;
    public String logos;
    public String overview;
    public String type;

    @SerializedName("vote_average")
    public String voteAverage;

    public long duration;
    public String status;

    @SerializedName("air_date")
    public String airDate;

    @SerializedName("release_date")
    public String releaseDate;

    @SerializedName("number_of_seasons")
    public int numberOfSeasons;

    @SerializedName("number_of_episodes")
    public int numberOfEpisodes;

    public List<Integer> genres;

    @SerializedName("production_countries")
    public List<String> productionCountries;

    @SerializedName("media_stream")
    public PlayListItem.MediaStreamInfo mediaStream;
}
