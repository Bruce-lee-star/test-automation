package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.config.BrowserOverrideManager;
import com.hsbc.cmb.hk.dbb.automation.tests.steps.LoginSteps;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import net.serenitybdd.annotations.Steps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logon Glue Code - 登录相关的步骤定义
 *
 * <p>企业级解决方案：添加 @AutoBrowser 注解，浏览器自动管理。</p>
 *
 * <p><b>SSO 并发闸门不在此接线（2026-09-28 收口）</b>：相同 sessionKey 的「进程内串行」由
 * <b>框架会话路径</b>承担 —— {@code SessionStore.restoreSession} 入口即
 * {@code SessionStore.acquireSessionGate(sessionKey)}（键 = sessionKey），并由框架在每个 scenario 收尾
 * （{@code PlaywrightListener.cleanupThreadLocals()} → {@code SessionManager.releaseSessionGate()}）无条件释放。
 * 业务层<b>不得</b>再自建分区键 / acquire / release —— 那会形成「两把键不同的锁」，且释放依赖业务 {@code @After}
 * （遗漏即泄漏，同身份后续场景卡到 fail-closed 超时）。</p>
 *
 * <p>需要更细的身份隔离粒度时，调整 {@code sessionKey}（数据声明，见 {@code LoginSteps.generateSessionKey}），
 * 而不是在业务层加锁。</p>
 *
 * @author Automation Framework
 * @version 3.2
 */
@AutoBrowser(verbose = true)
public class LogonGlue {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogonGlue.class);

    @Steps
    private LoginSteps loginSteps;

    /**
     * 登录步骤（纯业务编排，不含任何并发闸门逻辑）。
     *
     * @param env      环境标识，如 "O63_SIT1"
     * @param username 用户名，如 "WP7UAT2_2"
     */
    @Given("logon DBB {string} environment as user {string}")
    public void logonDBBEnvironmentAsUserGlue(String env, String username) {
        LOGGER.info("========================================");
        LOGGER.info("LogonGlue - Scenario: {} as {}", env, username);
        LOGGER.info("  Effective browser: {}", BrowserOverrideManager.getEffectiveBrowserType());
        LOGGER.info("  Default browser: {}", BrowserOverrideManager.getDefaultBrowserType());
        LOGGER.info("========================================");

        loginSteps.logonDBBEnvironmentAsUser(env, username);
    }

    /**
     * Cucumber 原生标签桥接（2026-09-27，P0）：JUnit5 执行器下 SerenityReporterParallel 旁挂，
     * TestOutcome.getTags() 恒空，@firefox/@edge 浏览器覆盖失效（实测回落 chromium）。
     * 此处从 Cucumber Scenario 直接读取 tags 写入 BrowserOverrideManager.setScenarioTags——
     * setScenarioTags 会经 extractBrowserFromTags 自动 setOverrideBrowser，浏览器启动决策点即生效。
     * Cucumber @Before 在 scenario 首步前执行，早于浏览器懒启动（getPage），无竞态。
     */
    @Before
    public void bridgeScenarioTagsToBrowserOverride(Scenario scenario) {
        if (scenario == null) {
            return;
        }
        java.util.Collection<String> rawTags = scenario.getSourceTagNames();
        if (rawTags == null || rawTags.isEmpty()) {
            return;
        }
        String[] tags = rawTags.toArray(new String[0]);
        BrowserOverrideManager.setScenarioTags(tags);
    }
}
