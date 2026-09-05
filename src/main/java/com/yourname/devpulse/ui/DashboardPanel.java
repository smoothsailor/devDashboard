package com.yourname.devpulse.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.yourname.devpulse.model.GitHubRepo;
import com.yourname.devpulse.model.GitHubUser;
import com.yourname.devpulse.services.DataCacheService;
import com.yourname.devpulse.services.GitHubApiService;
import com.yourname.devpulse.settings.DevPulseSettingsConfigurable;
import com.yourname.devpulse.settings.DevPulseSettingsListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.intellij.openapi.ui.Messages.showErrorDialog;
import static com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService;

/**
 * UI 面板（继承 JPanel）。整体 BorderLayout，中部用 GridLayout 排列 4 个卡片。
 * 网络请求在后台线程执行，UI 更新统一走 EDT。
 */
public final class DashboardPanel extends JPanel {
    private static final String FEEDBACK_URL = "https://github.com/smoothsailor/devDashboard/issues/new";

    private static final long REFRESH_INTERVAL_MINUTES = 60;

    private final Project project;
    private final ScheduledExecutorService scheduler;

    private final JLabel usernameLabel = new JLabel();
    private final JLabel starsValue = new JLabel();
    private final JLabel issuesValue = new JLabel();
    private final JLabel prsValue = new JLabel();
    private final JLabel todayValue = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JButton refreshButton = new JButton("🔄 Refresh");

    // 最近一次成功数据，拉取失败时保持不变
    private int lastStars = -1;
    private int lastIssues = -1;
    private int lastPRs = -1;
    private int lastTodayDelta = 0;
    private long lastSuccessTime = -1;

    public DashboardPanel(@NotNull Project project) {
        super(new BorderLayout(0, 12));
        this.project = project;
        this.scheduler = getAppScheduledExecutorService();

        buildUi();
        refresh(); // 侧边栏打开时立即触发一次拉取

        // 订阅设置变更：Settings 中保存新用户名/Token 后，面板自动刷新（否则会一直显示旧数据）。
        // 以 project 为父 Disposable，随项目关闭一起释放；refresh() 内部会重新读取最新配置。
        project.getMessageBus().connect(project).subscribe(
                DevPulseSettingsListener.TOPIC,
                new DevPulseSettingsListener() {
                    @Override
                    public void settingsChanged() {
                        refresh();
                    }
                });

        scheduler.scheduleWithFixedDelay(() -> {
            if (isShowing()) {
                refresh();
            }
        }, REFRESH_INTERVAL_MINUTES, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void buildUi() {
        setBorder(JBUI.Borders.empty(16));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JLabel title = new JLabel("📊 DevDashboard");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(JBColor.foreground());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        usernameLabel.setFont(usernameLabel.getFont().deriveFont(16f));
        usernameLabel.setForeground(JBColor.foreground());
        usernameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        usernameLabel.setText("👤 ");

        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(usernameLabel);
        add(header, BorderLayout.NORTH);

        JPanel cards = new JPanel(new GridLayout(2, 2, 12, 12));
        cards.setOpaque(false);
        cards.add(buildCard("⭐ Stars", starsValue));
        cards.add(buildCard("📈 Stars Today", todayValue));
        cards.add(buildCard("🐛 Issues", issuesValue));
        cards.add(buildCard("🔀 PRs", prsValue));
        add(cards, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setOpaque(false);
        statusLabel.setForeground(JBColor.GRAY);
        statusLabel.setText("⏳ Loading...");
        refreshButton.addActionListener(e -> refresh());
        statusRow.add(statusLabel, BorderLayout.CENTER);

        // 操作按钮同一行：Refresh 在前，Report Issue 在后
        JButton feedbackButton = new JButton("💬 Report Issue");
        feedbackButton.setToolTipText("Submit feedback on GitHub");
        feedbackButton.addActionListener(e -> openFeedbackLink());
        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionsRow.setOpaque(false);
        actionsRow.add(refreshButton);
        actionsRow.add(feedbackButton);
        statusRow.add(actionsRow, BorderLayout.EAST);
        footer.add(statusRow);

        add(footer, BorderLayout.SOUTH);
    }

    private JPanel buildCard(String title, JLabel value) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(JBColor.PanelBackground);
        card.setBorder(JBUI.Borders.empty(12, 16));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(JBColor.GRAY);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        value.setFont(value.getFont().deriveFont(Font.BOLD, 24f));
        value.setForeground(JBColor.foreground());
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        value.setText("--");

        card.add(titleLabel);
        card.add(Box.createVerticalStrut(6));
        card.add(value);
        return card;
    }

    private void refresh() {
        String username = DevPulseSettingsConfigurable.getUsername(project);
        if (username == null || username.isBlank()) {
            ApplicationManager.getApplication().invokeLater(this::showNotConfigured);
            return;
        }
        String token = DevPulseSettingsConfigurable.getToken(project);
        ApplicationManager.getApplication().invokeLater(this::showLoading);
        ApplicationManager.getApplication().executeOnPooledThread(() -> fetchData(username, token));
    }

    private void fetchData(String username, String token) {
        try {
            GitHubApiService.Overview overview = GitHubApiService.fetchOverview(username, token);
            GitHubUser user = overview.user;
            List<GitHubRepo> repos = overview.repos;

            int totalStars = GitHubApiService.totalStars(repos);
            int totalIssues = GitHubApiService.totalIssues(repos);
            int totalPRs = GitHubApiService.totalPullRequests(repos);
            int todayDelta = DataCacheService.computeAndStoreDelta(username, totalStars);

            lastStars = totalStars;
            lastIssues = totalIssues;
            lastPRs = totalPRs;
            lastTodayDelta = todayDelta;
            lastSuccessTime = System.currentTimeMillis();

            String displayName = resolveName(user, username);
            ApplicationManager.getApplication().invokeLater(() -> {
                usernameLabel.setText("👤 " + displayName);
                starsValue.setText(formatCount(totalStars));
                issuesValue.setText(String.valueOf(totalIssues));
                prsValue.setText(totalPRs < 0 ? "--" : String.valueOf(totalPRs));
                todayValue.setText((todayDelta >= 0 ? "+" : "") + todayDelta);
                statusLabel.setText("✅ Last updated: " + relativeTime());
                statusLabel.setForeground(JBColor.GRAY);
                refreshButton.setEnabled(true);
            });
        } catch (Exception e) {
            ApplicationManager.getApplication().invokeLater(() -> showError(e));
        }
    }

    private static String resolveName(GitHubUser user, String fallback) {
        if (user != null) {
            if (user.getName() != null && !user.getName().isBlank()) {
                return user.getName();
            }
            if (user.getLogin() != null && !user.getLogin().isBlank()) {
                return user.getLogin();
            }
        }
        return fallback;
    }

    private void showLoading() {
        refreshButton.setEnabled(false);
        starsValue.setText("--");
        issuesValue.setText("--");
        prsValue.setText("--");
        todayValue.setText("--");
        statusLabel.setText("⏳ Loading...");
        statusLabel.setForeground(JBColor.GRAY);
    }

    private void showNotConfigured() {
        usernameLabel.setText("👤 Please configure a username in Settings > Tools > DevDashboard");
        starsValue.setText("--");
        issuesValue.setText("--");
        prsValue.setText("--");
        todayValue.setText("--");
        statusLabel.setText("Username not configured");
        statusLabel.setForeground(JBColor.GRAY);
        refreshButton.setEnabled(false);
    }

    private void showError(Exception e) {
        refreshButton.setEnabled(true);
        if (lastStars >= 0) {
            // 拉取失败时保留原有数据
            starsValue.setText(formatCount(lastStars));
            issuesValue.setText(String.valueOf(lastIssues));
            prsValue.setText(lastPRs < 0 ? "--" : String.valueOf(lastPRs));
            todayValue.setText((lastTodayDelta >= 0 ? "+" : "") + lastTodayDelta);
        }
        statusLabel.setText(mapError(e));
        statusLabel.setForeground(JBColor.RED);
    }

    private String mapError(Exception e) {
        if (e instanceof GitHubApiService.ApiException) {
            GitHubApiService.ApiException ae = (GitHubApiService.ApiException) e;
            switch (ae.getKind()) {
                case TIMEOUT:
                    return "❌ Network timeout. Please try again later";
                case NOT_FOUND:
                    return "❌ User not found";
                case RATE_LIMITED:
                    return "⚠️ GitHub API rate limit reached. Configure a token or try again later";
                default:
                    return "❌ Unknown error: " + e.getMessage();
            }
        }
        return "❌ Unknown error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private String relativeTime() {
        if (lastSuccessTime < 0) {
            return "just now";
        }
        long minutes = (System.currentTimeMillis() - lastSuccessTime) / 60_000L;
        return minutes < 1 ? "just now" : minutes + " min ago";
    }

    /**
     * 打开浏览器跳转到 GitHub Issues 新建页面。
     */
    private void openFeedbackLink() {
        try {
            Desktop.getDesktop().browse(new URI(FEEDBACK_URL));
        } catch (IOException | URISyntaxException e) {
            showErrorDialog(
                "Failed to open browser. Please visit manually:\n\n" + FEEDBACK_URL,
                "Open Feedback Link Failed"
            );
        }
    }

    /**
     * Star 数量格式化：大于1000显示为 1.2k，大于1000000显示为 1.2M。
     */
    public static String formatCount(int count) {
        if (count >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1_000.0);
        }
        return String.valueOf(count);
    }
}
