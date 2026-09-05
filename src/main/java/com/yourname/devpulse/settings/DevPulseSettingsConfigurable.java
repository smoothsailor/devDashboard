package com.yourname.devpulse.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.FormBuilder;
import com.yourname.devpulse.services.GitHubApiService;
import io.netty.util.internal.StringUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;

/**
 * 设置页面 (Settings -> Tools -> DevPulse)。
 * 用户名使用项目级 PropertiesComponent 存储，Token 使用 PasswordSafe 加密存储。
 */
public final class DevPulseSettingsConfigurable implements Configurable {
    private static final Logger LOG = Logger.getInstance(DevPulseSettingsConfigurable.class);

    public static final String KEY_USERNAME = "devpulse.github.username";
    private static final String TOKEN_SERVICE = "devpulse.github.token";

    private final Project project;
    private final JTextField usernameField;
    private final JBPasswordField tokenField;
    private final JLabel statusLabel;

    public DevPulseSettingsConfigurable(@NotNull Project project) {
        this.project = project;
        this.usernameField = new JTextField();
        this.tokenField = new JBPasswordField();
        this.statusLabel = new JLabel(" ");
        this.statusLabel.setForeground(JBColor.GRAY);
    }

    @Override
    @Nls
    public String getDisplayName() {
        return "DevDashboard";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        JButton testButton = new JButton("Test Connection");
        testButton.addActionListener(e -> testConnection());

        return FormBuilder.createFormBuilder()
                .addLabeledComponent("GitHub username:", usernameField)
                .addLabeledComponent("Access token (optional):", tokenField)
                .addComponent(testButton)
                .addComponent(statusLabel)
                .getPanel();
    }

    @Override
    public boolean isModified() {
        String savedUsername = getUsername(project);
        String savedToken = getToken(project);
        if (!savedUsername.equals(usernameField.getText().trim())) {
            return true;
        }
        String newToken = new String(tokenField.getPassword()).trim();
        if (savedToken == null) {
            return !newToken.isEmpty();
        }
        return !savedToken.equals(newToken);
    }

    @Override
    public void apply() {
        PropertiesComponent.getInstance(project).setValue(KEY_USERNAME, usernameField.getText().trim());

        String token = new String(tokenField.getPassword()).trim();
        CredentialAttributes attributes = new CredentialAttributes(TOKEN_SERVICE);
        if (token.isEmpty()) {
            PasswordSafe.getInstance().set(attributes, null);
        } else {
            PasswordSafe.getInstance().setPassword(attributes, token);
        }

        // 通知侧边栏面板：设置已保存，立即按新配置刷新（DevPulseSettingsListener.TOPIC 同包无需 import）
        project.getMessageBus().syncPublisher(DevPulseSettingsListener.TOPIC).settingsChanged();
    }

    @Override
    public void reset() {
        usernameField.setText(getUsername(project));
        String token = getToken(project);
        tokenField.setText(token == null ? "" : token);
    }

    private void testConnection() {
        String username = usernameField.getText().trim();
        if (StringUtil.isNullOrEmpty(username)) {
            statusLabel.setText("❌ Please enter username"); // 提示请输入username
            statusLabel.setForeground(JBColor.RED);         // 设置为红色警告色
            return; // 终止方法，不再执行后续的网络请求逻辑
        }
        String token = new String(tokenField.getPassword()).trim();
        statusLabel.setText("⏳ Testing connection...");
        statusLabel.setForeground(JBColor.GRAY);

        // 网络请求放到 IDEA 后台线程池，不阻塞 EDT。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            LOG.info("[DevDashboard] Starting connection test for user: " + username);
            GitHubApiService.ApiException error = GitHubApiService.testConnection(username, token);
            LOG.info("[DevDashboard] Connection test finished: " + (error == null ? "success" : error.getKind()));
            // 关键修复：设置窗口是“模态”对话框。从后台线程直接 invokeLater 默认用 NON_MODAL 模态，
            // 会被推迟到对话框关闭后才执行——这就是 UI 永远停在“正在测试连接”的真正原因。
            // 必须传 ModalityState.any()，让回调在模态窗口打开期间也能在 EDT 上执行。
            ApplicationManager.getApplication().invokeLater(() -> {
                LOG.info("[DevDashboard] Updating connection result on EDT");
                if (error == null) {
                    statusLabel.setText("✅ Connection successful");
                    statusLabel.setForeground(JBColor.GREEN);
                } else {
                    statusLabel.setText("❌ " + describeError(error));
                    statusLabel.setForeground(JBColor.RED);
                }
            }, ModalityState.any());
        });
    }

    private static String describeError(GitHubApiService.ApiException error) {
        switch (error.getKind()) {
            case TIMEOUT:
                return "Connection timed out. Check your network, or configure a proxy in Settings > System Settings > HTTP Proxy";
            case NOT_FOUND:
                return "Username not found";
            case RATE_LIMITED:
                return "GitHub API rate limit reached. Retry later or configure a token";
            default:
                return "Connection failed: " + (error.getMessage() != null ? error.getMessage() : "Check your username or token");
        }
    }

    public static String getUsername(Project project) {
        return PropertiesComponent.getInstance(project).getValue(KEY_USERNAME, "");
    }

    public static String getToken(Project project) {
        return PasswordSafe.getInstance().getPassword(new CredentialAttributes(TOKEN_SERVICE));
    }
}
