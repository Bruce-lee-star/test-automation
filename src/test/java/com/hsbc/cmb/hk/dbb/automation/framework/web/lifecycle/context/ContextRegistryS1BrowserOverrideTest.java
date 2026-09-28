package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.core.FrameworkState;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserCleanupImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserRestartImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.browser.BrowserStartup;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.ContextRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.page.PageRegistryImpl;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.state.PlaywrightRuntimeState;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S1 回归测试（feature 模式浏览器覆盖失效修复）。
 *
 * <p>问题背景：feature 模式（{@code restart.browser.for.each=feature}）下同一 feature 内
 * {@link ContextRegistryImpl#getContext()} 会复用线程级 {@code CURRENT_CONTEXT_BY_THREAD}，
 * 而旧实现在复用判定<b>之前</b>从不重新校验浏览器类型，导致后续 scenario 的
 * {@code @firefox}/{@code @edge} 标签覆盖来不及生效。</p>
 *
 * <p>修复形态（{@code ContextRegistryImpl.getContext()} 第 62–72 行）：在复用判定之前，若已确立
 * configId（{@code configId != null} 守卫，保持非常规路径原行为），先调用
 * {@link PlaywrightManager#getBrowser()} —— 其内含「configId 浏览器类型 ≠ 期望类型 →
 * handleBrowserTypeSwitch 关闭旧浏览器并顺带关闭旧 Context」逻辑，使被复用的旧 Context 因浏览器
 * 断开而失效、进而在锁内重建到正确浏览器。</p>
 *
 * <p>本测试<b>不启动真实浏览器</b>：经 {@link PlaywrightRuntime#setInstance} 注入一个组合根，
 * 保留真实的 {@link BrowserRegistryImpl}/{@link ContextRegistryImpl}（从而保留 S1 逻辑与切换检测），
 * 但把 {@code browserStartup} 替换为 mock（避免 {@code initializeBrowser} 真实拉起浏览器），
 * 并用 {@link BrowserOverrideManager#setOverrideBrowser(String)} 设定有效浏览器类型。
 * mock Browser 在被 close 时翻转 {@code isConnected()}，以忠实模拟「旧浏览器断开 → 旧 Context 失效」。</p>
 */
public class ContextRegistryS1BrowserOverrideTest {

    /** 默认 feature 复用路径用的 configId 形态（browserType_headless[_channel]）。 */
    private static final String CHROMIUM_CONFIG = "chromium_headless_";
    /** mock startup 生成的「切换后」configId（须含 '_' 且以期望类型开头，避免二次误切换）。 */
    private static final String FIREFOX_CONFIG = "firefox_headless_s1";

    private volatile boolean priorInitialized;
    private BrowserStartup mockStartup;

    @BeforeEach
    public void setUp() {
        priorInitialized = FrameworkState.getInstance().isInitialized();
        FrameworkState.getInstance().markInitialized();

        // 注入组合根：保留真实 registry（含 S1 逻辑），仅以 mock startup 绕开真实浏览器启动。
        mockStartup = mock(BrowserStartup.class);
        when(mockStartup.ensureBrowserInstalledForType()).thenReturn(true);
        when(mockStartup.generateConfigId()).thenReturn(FIREFOX_CONFIG);
        // initializeBrowser 不真实启动：仅把一个「已连接」的 mock Browser 注入状态根，
        // 供切换后 BrowserRegistryImpl 取回。
        org.mockito.Mockito.doAnswer(inv -> {
            String cfg = inv.getArgument(0);
            Browser nb = mock(Browser.class, RETURNS_DEEP_STUBS);
            when(nb.isConnected()).thenReturn(true);
            PlaywrightRuntime.instance().state.putBrowser(BrowserRegistryImpl.INSTANCE.keyFor(cfg), nb);
            return null;
        }).when(mockStartup).initializeBrowser(anyString());

        PlaywrightRuntime.setInstance(new PlaywrightRuntime(
                BrowserRegistryImpl.INSTANCE,
                ContextRegistryImpl.INSTANCE,
                PageRegistryImpl.INSTANCE,
                mockStartup,
                BrowserRestartImpl.INSTANCE,
                BrowserCleanupImpl.INSTANCE,
                PlaywrightRuntimeState.INSTANCE));
    }

    @AfterEach
    public void tearDown() throws Exception {
        // 复位顺序：先恢复默认组合根与覆盖，再清理状态根，最后复位 frameworkState 到进入前的值。
        PlaywrightRuntime.resetInstance();
        PlaywrightManager.resetProvider();
        BrowserOverrideManager.clearOverrideBrowser();
        PlaywrightRuntimeState.INSTANCE.clearBrowsers();
        TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
        setInitialized(priorInitialized);
    }

    // ====================== 测试 1：S1 正向 —— 有效类型变化触发切换并使旧 Context 失效重建 ======================

    @Test
    public void s1_overrideChangesType_invalidatesThreadedContextAndRebuilds() {
        // 安排：已确立 chromium configId + 一个「已连接」的旧浏览器/旧 Context（feature 模式复用态）。
        BrowserOverrideManager.setOverrideBrowser("firefox");
        TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY, CHROMIUM_CONFIG);

        Browser oldBrowser = connectedBrowser();
        BrowserContext oldContext = mock(BrowserContext.class, RETURNS_DEEP_STUBS);
        when(oldContext.browser()).thenReturn(oldBrowser);
        PlaywrightRuntime.instance().state.putBrowser(BrowserRegistryImpl.INSTANCE.keyFor(CHROMIUM_CONFIG), oldBrowser);
        TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, oldContext);

        // 动作：feature 模式复用路径下请求 Context。
        BrowserContext result = PlaywrightManager.getContext();

        // 断言：旧 Context 因浏览器切换被 closeContext 失效，getContext 重建出「不同于旧」的新 Context。
        org.junit.jupiter.api.Assertions.assertNotSame(oldContext, result,
                "S1 失效：类型变化后旧 Context 应被重建，而非被复用");
        // PlaywrightContextManager.closeContext 调用的是带 CloseOptions 的 close(CloseOptions)。
        verify(oldContext, atLeastOnce()).close(any());
        // 佐证切换确由 getBrowser() 触发：configId 已从 chromium 变为 firefox。
        String afterConfig = TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY);
        assertTrue(afterConfig != null && afterConfig.startsWith("firefox"),
                "S1 失效：getBrowser() 应触发浏览器类型切换，configId 应变为 firefox 前缀，实际=" + afterConfig);
    }

    // ====================== 测试 2：S1 不破坏正常复用 —— 类型一致时仍复用旧 Context ======================

    @Test
    public void s1_normalReuseNoTypeChange_preservesThreadedContext() {
        // 安排：override 与 configId 同为 chromium（无切换），存在已连接的旧浏览器/旧 Context。
        BrowserOverrideManager.setOverrideBrowser("chromium");
        TestContextHolder.get().set(PlaywrightManager.CURRENT_CONFIG_ID_KEY, CHROMIUM_CONFIG);

        Browser oldBrowser = connectedBrowser();
        BrowserContext oldContext = mock(BrowserContext.class, RETURNS_DEEP_STUBS);
        when(oldContext.browser()).thenReturn(oldBrowser);
        PlaywrightRuntime.instance().state.putBrowser(BrowserRegistryImpl.INSTANCE.keyFor(CHROMIUM_CONFIG), oldBrowser);
        TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, oldContext);

        // 动作：类型一致，S1 的 getBrowser() 是 no-op（不切换），应保持 feature 复用语义。
        BrowserContext result = PlaywrightManager.getContext();

        // 断言：旧 Context 被原样复用，且从未被 close（S1 不引入误失效）。
        assertSame(oldContext, result, "S1 回归：类型一致时应保持 feature 模式复用，不应重建");
        verify(oldContext, never()).close();
    }

    // ====================== 测试 3：S1 非常规路径守卫 —— configId 为 null 时不调 getBrowser ======================

    @Test
    public void s1_nullConfigIdGuard_skipsGetBrowserAndReuses() {
        // 安排：configId 未确立（场景收尾后、下个 scenario 初始化前的非常规路径），
        // 但存在一个「已连接」的旧浏览器/旧 Context，且 override 指向 firefox（若误调 getBrowser 会抛/切换）。
        BrowserOverrideManager.setOverrideBrowser("firefox");
        // 显式移除 CURRENT_CONFIG_ID_KEY，模拟「场景收尾后 configId 已清空」的非常规路径
        // （框架 scenario 初始化可能给线程设上 configId，故此处强制清零以使守卫分支可被确定性触发）。
        TestContextHolder.get().remove(PlaywrightManager.CURRENT_CONFIG_ID_KEY);

        Browser oldBrowser = connectedBrowser();
        BrowserContext oldContext = mock(BrowserContext.class, RETURNS_DEEP_STUBS);
        when(oldContext.browser()).thenReturn(oldBrowser);
        TestContextHolder.get().set(PlaywrightManager.CONTEXT_KEY, oldContext);

        // 动作：configId == null → S1 守卫（读取原始键）跳过 getBrowser()，不应触发切换/抛异常。
        BrowserContext result = PlaywrightManager.getContext();

        // 断言：路径与修复前一致——复用旧 Context，旧 Context 未被 close。
        assertSame(oldContext, result, "S1 守卫回归：configId 为 null 时应复用旧 Context");
        verify(oldContext, never()).close();
        // 类型切换未被触发：configId 不应被 S1 改写为 firefox。
        String afterConfig = TestContextHolder.get().get(PlaywrightManager.CURRENT_CONFIG_ID_KEY);
        assertTrue(afterConfig == null || !afterConfig.startsWith("firefox"),
                "S1 守卫回归：configId 为 null 时不应触发浏览器类型切换");
    }

    // ====================== 辅助 ======================

    /** 构造一个「初始已连接、被 close 后翻转 isConnected」的 mock Browser（忠实模拟真实断开语义）。 */
    private static Browser connectedBrowser() {
        Browser browser = mock(Browser.class, RETURNS_DEEP_STUBS);
        AtomicBoolean connected = new AtomicBoolean(true);
        when(browser.isConnected()).thenAnswer(inv -> connected.get());
        // BrowserCleanupImpl.closeBrowserInstance 调用 browser.close(CloseOptions)
        org.mockito.Mockito.doAnswer(inv -> {
            connected.set(false);
            return null;
        }).when(browser).close(any(com.microsoft.playwright.Browser.CloseOptions.class));
        return browser;
    }

    /** 经反射复位 {@link FrameworkState#initialized}，避免跨测试污染。 */
    private static void setInitialized(boolean value) throws Exception {
        Field f = FrameworkState.getInstance().getClass().getDeclaredField("initialized");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(FrameworkState.getInstance())).set(value);
    }
}
