package com.yourname.devpulse.model;

import com.google.gson.annotations.SerializedName;

/**
 * 单个仓库的统计信息。
 * REST 路径由 Gson 反序列化填充；GraphQL 路径由 setter 手动填充。
 */
public final class GitHubRepo {
    private String name;

    @SerializedName("stargazers_count")
    private int stargazersCount;

    @SerializedName("open_issues_count")
    private int openIssuesCount;        // 仅 Issues（不含 PR）

    private int openPullRequestsCount;  // PR 数量；-1 表示未知（无 token 走 REST 时）

    private boolean fork;

    public String getName() {
        return name;
    }

    public int getStargazersCount() {
        return stargazersCount;
    }

    public int getOpenIssuesCount() {
        return openIssuesCount;
    }

    public int getOpenPullRequestsCount() {
        return openPullRequestsCount;
    }

    public boolean isFork() {
        return fork;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setStargazersCount(int stargazersCount) {
        this.stargazersCount = stargazersCount;
    }

    public void setOpenIssuesCount(int openIssuesCount) {
        this.openIssuesCount = openIssuesCount;
    }

    public void setOpenPullRequestsCount(int openPullRequestsCount) {
        this.openPullRequestsCount = openPullRequestsCount;
    }

    public void setFork(boolean fork) {
        this.fork = fork;
    }
}
