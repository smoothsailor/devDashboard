package com.yourname.devpulse;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.yourname.devpulse.settings.DevPulseSettingsConfigurable;
import org.jetbrains.annotations.NotNull;

/**
 * 启动时触发，仅做简单的配置预检查，不发起网络请求（避免阻塞 IDE 启动）。
 */
public final class DevPulseStartupActivity implements StartupActivity {
    private static final Logger LOG = Logger.getInstance(DevPulseStartupActivity.class);

    @Override
    public void runActivity(@NotNull Project project) {
        String username = DevPulseSettingsConfigurable.getUsername(project);
        if (username == null || username.isBlank()) {
            LOG.warn("[DevDashboard] Please configure GitHub username.");
        }
    }
}
