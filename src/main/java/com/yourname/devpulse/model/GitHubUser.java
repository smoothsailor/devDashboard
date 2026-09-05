package com.yourname.devpulse.model;

import com.google.gson.annotations.SerializedName;

/**
 * 用户基本信息。REST 路径由 Gson 反序列化填充；GraphQL 路径由 setter 手动填充。
 */
public final class GitHubUser {
    private String login;

    @SerializedName("avatar_url")
    private String avatarUrl;

    private String name;
    private String bio;

    public String getLogin() {
        return login;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public String getName() {
        return name;
    }

    public String getBio() {
        return bio;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }
}
