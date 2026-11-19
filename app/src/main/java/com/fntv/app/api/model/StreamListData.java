package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** 媒体流详情（GET api/v1/stream/list/{itemGuid}）：文件 + 视频流 + 音频流 */
public class StreamListData {

    public List<StreamFile> files;

    @SerializedName("video_streams")
    public List<VideoStream> videoStreams;

    @SerializedName("audio_streams")
    public List<AudioStream> audioStreams;

    public static class StreamFile {
        public String path;

        @SerializedName("file_name")
        public String fileName;

        public long size;

        @SerializedName("create_time")
        public long createTime;

        public long timestamp;
    }

    public static class VideoStream {
        @SerializedName("resolution_type")
        public String resolutionType;

        @SerializedName("color_range_type")
        public String colorRangeType;

        @SerializedName("codec_name")
        public String codecName;

        public String profile;
        public int width;
        public int height;

        @SerializedName("bit_depth")
        public int bitDepth;

        public long bps;

        @SerializedName("r_frame_rate")
        public String frameRate;

        @SerializedName("pix_fmt")
        public String pixFmt;

        public long duration;

        @SerializedName("display_aspect_ratio")
        public String aspectRatio;
    }

    public static class AudioStream {
        @SerializedName("audio_type")
        public String audioType;

        @SerializedName("codec_name")
        public String codecName;

        public String language;
        public int channels;

        @SerializedName("channel_layout")
        public String channelLayout;

        @SerializedName("sample_rate")
        public String sampleRate;

        public String profile;
        public long bps;

        @SerializedName("is_default")
        public int isDefault;
    }
}
