package com.fntv.app.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** 演职人员（POST api/v1/person/list/{guid}） */
public class PersonItem {

    @SerializedName("person_guid")
    public String personGuid;

    public String name;
    public String role;
    public String job;
    public int order;

    @SerializedName("profile_path")
    public String profilePath;

    public String biography;

    @SerializedName("known_for_department")
    public String knownForDepartment;

    public static class Data {
        public int total;
        public List<PersonItem> list;
    }

    /** 展示用副标题：演员显示角色，职员显示职位 */
    public String subtitle() {
        if (role != null && !role.isEmpty()) return role;
        if (job != null && !job.isEmpty()) return jobLabel(job);
        if (knownForDepartment != null && !knownForDepartment.isEmpty()) return knownForDepartment;
        return "";
    }

    private static String jobLabel(String job) {
        switch (job) {
            case "Director": return "导演";
            case "Writer": return "编剧";
            case "Producer": return "制片";
            case "Screenplay": return "编剧";
            case "Original Music Composer": return "音乐";
            case "Director of Photography": return "摄影";
            default: return job;
        }
    }
}
