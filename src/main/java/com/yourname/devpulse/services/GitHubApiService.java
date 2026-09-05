package com.yourname.devpulse.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.devpulse.model.GitHubRepo;
import com.yourname.devpulse.model.GitHubUser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 核心 API 调用逻辑。
 * - REST v3：拉取用户信息（无需 token），供 testConnection 与无 token 兜底使用。
 * - GraphQL v4：一次性取回用户 + 仓库，并把 issues 与 PR 精确拆开统计。
 * 所有网络请求都是同步阻塞的，必须由调用方放到后台线程执行。
 */
public final class GitHubApiService {
    private static final String API_BASE = "https://api.github.com";
    private static final String GRAPHQL_BASE = "https://api.github.com/graphql";
    private static final Gson GSON = new Gson();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private static final OkHttpClient CLIENT = buildClient();

    /**
     * GraphQL 查询：用户信息 + 每个仓库的 stars / open issues / open PRs。
     * 注意：GraphQL 的 issues(states:OPEN) 会把 PR 也算进去，所以“纯 issues = issues.totalCount - pullRequests.totalCount”。
     * 这里先取两个原始值，解析时再相减。
     */
    private static final String GRAPHQL_QUERY =
            "query($login: String!) {" +
            "  user(login: $login) {" +
            "    login" +
            "    name" +
            "    avatarUrl" +
            "    repositories(first: 100, isFork: false, orderBy: {field: UPDATED_AT, direction: DESC}) {" +
            "      nodes {" +
            "        name" +
            "        stargazerCount" +
            "        openIssues: issues(states: OPEN) { totalCount }" +
            "        openPullRequests: pullRequests(states: OPEN) { totalCount }" +
            "      }" +
            "    }" +
            "  }" +
            "}";

    private static OkHttpClient buildClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private GitHubApiService() {
    }

    /** 用户 + 仓库统计聚合结果。 */
    public static final class Overview {
        public final GitHubUser user;
        public final List<GitHubRepo> repos;

        public Overview(GitHubUser user, List<GitHubRepo> repos) {
            this.user = user;
            this.repos = repos;
        }
    }

    /**
     * 拉取用户基本信息（REST，无需 token）。
     * GET https://api.github.com/users/{username}
     */
    public static GitHubUser fetchUser(String username, String token) throws IOException {
        String json = get(API_BASE + "/users/" + username, token);
        return GSON.fromJson(json, GitHubUser.class);
    }

    /**
     * 拉取用户仓库列表（REST 兜底路径，已排除 fork）。
     * 注意：REST 的 open_issues_count 同时包含 issues 和 PR，无法区分。
     */
    public static List<GitHubRepo> fetchRepos(String username, String token) throws IOException {
        String json = get(API_BASE + "/users/" + username + "/repos?per_page=100&sort=updated", token);
        GitHubRepo[] repos = GSON.fromJson(json, GitHubRepo[].class);
        List<GitHubRepo> result = new ArrayList<>();
        if (repos != null) {
            for (GitHubRepo repo : repos) {
                if (repo != null && !repo.isFork()) {
                    result.add(repo);
                }
            }
        }
        return result;
    }

    /**
     * 拉取用户 + 仓库统计。优先走 GraphQL（issues/PR 精确拆分）；无 token 时退回 REST。
     */
    public static Overview fetchOverview(String username, String token) throws IOException {
        if (token == null || token.trim().isEmpty()) {
            return fetchOverviewViaRest(username, token);
        }

        JsonObject variables = new JsonObject();
        variables.addProperty("login", username);
        String json = postGraphQL(GRAPHQL_QUERY, variables, token);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray errors = root.getAsJsonArray("errors");
        if (errors != null && errors.size() > 0) {
            JsonObject first = errors.get(0).getAsJsonObject();
            String msg = first.has("message") ? first.get("message").getAsString() : "GraphQL error";
            throw new ApiException(ApiException.Kind.OTHER, msg);
        }

        JsonObject data = root.getAsJsonObject("data");
        JsonObject userObj = data != null ? data.getAsJsonObject("user") : null;
        if (userObj == null || userObj.isJsonNull()) {
            throw new ApiException(ApiException.Kind.NOT_FOUND, "GitHub user not found");
        }

        GitHubUser user = new GitHubUser();
        user.setLogin(getString(userObj, "login"));
        user.setName(getString(userObj, "name"));
        user.setAvatarUrl(getString(userObj, "avatarUrl"));

        List<GitHubRepo> repos = new ArrayList<>();
        JsonObject repositories = userObj.getAsJsonObject("repositories");
        if (repositories != null && repositories.has("nodes")) {
            for (JsonElement el : repositories.getAsJsonArray("nodes")) {
                JsonObject node = el.getAsJsonObject();
                GitHubRepo repo = new GitHubRepo();
                repo.setName(getString(node, "name"));
                repo.setStargazersCount(getInt(node, "stargazerCount"));
                int openIssuesTotal = getNestedTotal(node, "openIssues");
                int openPRs = getNestedTotal(node, "openPullRequests");
                // issues 连接包含 PR，需相减得到纯 issues 数量
//                repo.setOpenIssuesCount(Math.max(0, openIssuesTotal - openPRs));
                 repo.setOpenIssuesCount(openIssuesTotal);

                repo.setOpenPullRequestsCount(openPRs);
                repos.add(repo);
            }
        }
        return new Overview(user, repos);
    }

    private static Overview fetchOverviewViaRest(String username, String token) throws IOException {
        GitHubUser user = fetchUser(username, token);
        List<GitHubRepo> repos = fetchRepos(username, token);
        for (GitHubRepo repo : repos) {
            repo.setOpenPullRequestsCount(-1); // REST 无法区分 PR，标记为未知
        }
        return new Overview(user, repos);
    }

    public static int totalStars(List<GitHubRepo> repos) {
        return repos.stream().mapToInt(GitHubRepo::getStargazersCount).sum();
    }

    public static int totalIssues(List<GitHubRepo> repos) {
        return repos.stream().mapToInt(GitHubRepo::getOpenIssuesCount).sum();
    }

    public static int totalPullRequests(List<GitHubRepo> repos) {
        return repos.stream().mapToInt(GitHubRepo::getOpenPullRequestsCount).sum();
    }

    /**
     * 验证配置是否有效：能成功拉取用户即认为连接成功。
     * 成功返回 null；失败返回带类别的异常，便于 UI 层提示具体原因（超时/用户不存在/限流等）。
     */
    public static ApiException testConnection(String username, String token) {
        try {
            fetchUser(username, token);
            return null;
        } catch (ApiException e) {
            return e;
        } catch (Exception e) {
            // 兜底：任何非预期异常（如 NPE / 解析失败）都转成可展示的错误，避免后台线程静默失败导致 UI 卡在“连接中”。
            return new ApiException(ApiException.Kind.OTHER, e.getMessage(), e);
        }
    }

    private static String get(String url, String token) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (token != null && !token.trim().isEmpty()) {
            builder.header("Authorization", "token " + token.trim());
        }
        builder.header("Accept", "application/vnd.github+json");

        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            int code = response.code();
            if (code == 404) {
                throw new ApiException(ApiException.Kind.NOT_FOUND, "GitHub user not found (404)");
            }
            if (code == 403) {
                throw new ApiException(ApiException.Kind.RATE_LIMITED, "GitHub API rate limit exceeded (403)");
            }
            if (!response.isSuccessful()) {
                throw new ApiException(ApiException.Kind.OTHER, "HTTP " + code + " " + response.message());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        } catch (InterruptedIOException e) {
            throw new ApiException(ApiException.Kind.TIMEOUT, "network timeout", e);
        }
    }

    private static String postGraphQL(String query, JsonObject variables, String token) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.add("variables", variables);
        RequestBody requestBody = RequestBody.create(
                body.toString().getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE);

        Request request = new Request.Builder()
                .url(GRAPHQL_BASE)
                .post(requestBody)
                .header("Authorization", "Bearer " + token.trim())
                .header("Accept", "application/vnd.github+json")
                .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            int code = response.code();
            if (code == 401) {
                throw new ApiException(ApiException.Kind.OTHER, "GraphQL requires a valid access token (401)");
            }
            if (code == 403) {
                throw new ApiException(ApiException.Kind.RATE_LIMITED, "GraphQL API rate limited (403)");
            }
            if (!response.isSuccessful()) {
                throw new ApiException(ApiException.Kind.OTHER, "HTTP " + code + " " + response.message());
            }
            ResponseBody rb = response.body();
            return rb != null ? rb.string() : "";
        } catch (InterruptedIOException e) {
            throw new ApiException(ApiException.Kind.TIMEOUT, "network timeout", e);
        }
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }

    private static int getInt(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return (e == null || e.isJsonNull()) ? 0 : e.getAsInt();
    }

    private static int getNestedTotal(JsonObject obj, String key) {
        JsonObject nested = obj.getAsJsonObject(key);
        if (nested == null) {
            return 0;
        }
        JsonElement total = nested.get("totalCount");
        return (total == null || total.isJsonNull()) ? 0 : total.getAsInt();
    }

    /**
     * 带错误类别的异常，便于 UI 层把 HTTP 状态码映射为友好的中文提示。
     */
    public static final class ApiException extends IOException {
        public enum Kind {TIMEOUT, NOT_FOUND, RATE_LIMITED, OTHER}

        private final Kind kind;

        public ApiException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public ApiException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }
}
