package com.yourname.devpulse.settings;

import com.intellij.util.messages.Topic;

/**
 * 设置变更通知（project 级 MessageBus Topic）。
 * Settings -> Tools -> DevDashboard 中点击 OK / Apply 保存用户名、Token 后触发，
 * 侧边栏 DashboardPanel 订阅本 Topic，以便自动刷新展示的数据。
 * <p>
 * Topic 监听器只需声明为普通接口即可，无需继承任何基类；
 * MessageBus 的 {@link Topic} 与 subscribe/publish 都是泛型接口。
 */
public interface DevPulseSettingsListener {

    Topic<DevPulseSettingsListener> TOPIC = Topic.create(
            "devpulse.settings.changed", DevPulseSettingsListener.class);

    /** 用户名 / Token 等设置已被保存。订阅方收到后应重新读取设置并刷新。 */
    void settingsChanged();
}
