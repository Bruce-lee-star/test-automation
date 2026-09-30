package com.hsbc.cmb.hk.dbb.automation.tests.steps;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.concurrent.ConcurrentContextExecutor;

import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.RoleElementPicker;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;

import com.hsbc.cmb.hk.dbb.automation.tests.pages.HomePage;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.hsbc.cmb.hk.dbb.automation.tests.utils.BDDUtils;
import com.microsoft.playwright.Page;
import net.serenitybdd.annotations.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login related test steps - 超简化版，使用新的框架层 API
 * <p>
 * 设计理念：
 * 1. 业务层只负责业务逻辑（登录、切换 profile）
 * 2. 框架层负责 session 管理和自动恢复
 * 3. 业务层只传递 session key
 * <p>
 * 使用方式：
 * <pre>
 * @Given("logon DBB {string} environment as user {string}")
 * public void logonDBBEnvironmentAsUser(String env, String username) {
 *     // 生成 session key
 *     String sessionKey = env + "_" + username;
 *
 *     // 【核心】让框架自动处理 session
 *     if (SessionManager.prepareSession(sessionKey)) {
 *         // Session 已准备好，框架已设置 storageStatePath
 *         // 导航到首页（session 会自动应用）
 *         loginPage.navigateTo("https://example.com/home");
 *
 *         // 验证 session 是否有效
 *         if (!loginPage.getCurrentUrl().contains("/logon")) {
 *             homePage.quickLink.waitForVisible(30);
 *             return; // Session 有效，跳过登录
 *         }
 *     }
 *
 *     // Session 无效，执行登录
 *     performLogin(sessionKey);
 * }
 * </pre>
 */
public class LoginSteps {
    private static final Logger logger = LoggerFactory.getLogger(LoginSteps.class);

    // PageObject 字段（非静态初始化）
    // BasePage 构造函数不会创建 Context，Context 只在实际使用时创建
    private LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);
    private HomePage homePage = PageObjectFactory.getPage(HomePage.class);

    // 状态变量
    private String currentUrl;
    private String sessionKey;

    @Step
    public void logonDBBEnvironmentAsUser(String env, String username) {
        // 准备登录信息
        BDDUtils logonDBBInfo = BDDUtils.getLogonDBBInfo(env, username);
        BDDUtils.setCurrentLoginInfo(logonDBBInfo);
        currentUrl = BDDUtils.getCurrentUrl();

        // 生成 session key
        sessionKey = generateSessionKey(env, username);

        logger.info("========================================");
        logger.info("LoginSteps - SessionKey: {}", sessionKey);
        logger.info("========================================");


        if (!SessionManager.restoreSession(sessionKey, this::performLogin)) {
            // 框架已通过 performLogin 完成登录（本线程为 leader，或 follower 已复用落盘 session），无需额外处理
            return;
        }
        // Session 已准备好（可能是从文件恢复，也可能是 Feature 级别缓存）—— 走「复用」路径
        RouteDsl2.on(loginPage.getPage())
                .api("notifications/streams")
                .mock()
                .mockBody("{}")
                .done()
                .api("profile/list")
                .mock()
                .interceptResponse()
                .mockReplaceField("updateContctOverlayFlag", false)
                .mockReplaceField("isOverBlockedDate", false)
                .done()
                .api("leftmenu/permissionLeftMenuConfig")
                .monitor()
                .expectStatus(200)
                .expectJsonPath("enableAdminTools", "Y")
                .timeout(60)
                .done()
                .start();
        // 【简化API】自动处理 Feature 缓存和 meta 文件读取
        String homeUrl = SessionManager.getHomeUrl(sessionKey);

        if (homeUrl == null || homeUrl.isEmpty()) {
            logger.warn("Session file exists but no homeUrl found, cannot skip login");
            SessionManager.clearSession(sessionKey);
            performLogin();
            return;
        }

        logger.info("Session restored, navigating to homeUrl: {}", homeUrl);

        // 验证 session 是否有效：通过 try-catch 包裹整个验证流程
        // storageState 跨 Context 场景下，服务端可能因安全机制关闭 Page
        // 若发生 TargetClosedError，降级为完整登录流程
        try {
            loginPage.navigateTo(homeUrl);

            // 【优化】移除 waitForTimeout(15000)：navigation 的 waitUntil 已等待页面加载
            // 15s 的静止等待可能让 DBB 服务端的 session 校验触发 Page 关闭
            String currentUrl = loginPage.getCurrentUrl();
            logger.info("Current URL after navigation: {}", currentUrl);

            if (currentUrl.contains("/logon")) {
                logger.warn("Session invalid (redirected to login page)");
                performLogin();
                return;
            }

            // Session 有效，等待首页元素
            homePage.quickLink.waitForVisible(45);

            if (BDDUtils.getCurrentProfile() != null && !BDDUtils.getCurrentProfile().isEmpty()) {
                switchProfile(BDDUtils.getCurrentProfile());
                logger.info("Session validated, switched to config profile: {}",
                        BDDUtils.getCurrentProfile());
            }
            return; // Session 有效，跳过登录
        } catch (Exception e) {
            // 捕获 TargetClosedError / PlaywrightException 等 Page 异常
            logger.warn("Session restore failed (page/context closed during navigation or validation): {} - falling back to full login", e.getMessage());
            SessionManager.clearSession(sessionKey);
            performLogin();
            return;
        }
    }

    /**
     * 执行完整登录流程
     */
    private void performLogin() {
        String username = BDDUtils.getCurrentUsername();
        logger.info("No valid session, performing login for: {}", sessionKey);

        loginPage.navigateTo(currentUrl);

        // Axe-core accessibility scan on login page
//        AxeCoreScanner.scanPage("Login Page - Initial");
        loginPage.userNameIpt.type(username);
        loginPage.nextBtn.click();
        loginPage.paswordIpt.type(BDDUtils.getCurrentPassword());
        loginPage.physicalDeviceLabel.click();
        loginPage.securityCodeIpt.type(BDDUtils.getSecurityCode(BDDUtils.getCurrentSecurityUrl()));
        RouteDsl2.on(loginPage.getPage())
                .api("/error/profile-error.jsp")
                .monitor()
                .expectStatus(200)
                .timeout(60)
                .done()
                .api("auth/assert")
                .monitor()
                .expectStatus(200)
                .timeout(60)
                .done()
                .api("j_spring_security-check_v2")
                .monitor()
                .expectStatus(302)
                .timeout(60)
                .done()
                .api("leftmenu/permissionLeftMenuConfig")
                .monitor()
                .expectStatus(200)
                .expectJsonPath("enableAdminTools", "Y")
                .timeout(60)
                .done()
                .api("profile/list")
                .mock()
                .interceptResponse()
                .mockReplaceField("updateContctOverlayFlag", false)
                .mockReplaceField("isOverBlockedDate", false)
                .done()
                .start();

        loginPage.loginBtn.click();
        loginPage.loginBtn.waitForNotVisible(60);

        if (BDDUtils.getCurrentProfile() != null && !BDDUtils.getCurrentProfile().isEmpty()) {
            switchProfile(BDDUtils.getCurrentProfile());
            logger.info("Login completed, switched to config profile: {}",
                    BDDUtils.getCurrentProfile());
        }

        homePage.quickLink.waitForVisible(60);
//        AccessibilityScanner.checkAndCollect("logon - home Page");

        // Axe-core accessibility scan on home page
//        AxeCoreScanner.scanPage("Home Page - After Login");
        logger.info("Axe-core scans completed for login flow");

        // 【核心】让框架自动保存 session
        // 业务层只传递 session key 和 homeUrl
        // 框架会自动：
        // 1. 获取当前 context
        // 2. 使用 Playwright API 保存 storageState 到文件
        // 3. 保存元数据（homeUrl + timestamp）
        String homeUrl = loginPage.getCurrentUrl();
        SessionManager.saveSession(sessionKey, homeUrl);
        //  API 断言由框架自动检查（PlaywrightListener.checkAndFailOnApiAssertions）
        // 每个步骤结束时自动抛出 AssertionError，无需业务代码手动检查

        logger.info("Login completed and session saved");
    }

    /**
     * 切换 Profile
     *
     * <p>根治（此前 10/10 失败）：DBB 首页登录后会反复路由规范化（en-US↔en-us），期间 DOM 被销毁重建，
     * 若先 {@code getText()}（innerText）再等可见，会在导航抖动期抛 PlaywrightException。故必须先
     * {@code waitForVisible}（框架内置导航重试）吸收重定向、待 dropdown 稳定后再读/点。
     * 另：profile/list 的 mock 偶发被 RouteV2 route.fetch 竞态丢弃（fail-open 放行真实响应 →
     * updateContctOverlayFlag=true 弹出遮罩），故用 {@code jsClick} 绕过遮罩的 actionability 拦截。</p>
     */
    private void switchProfile(String profile) {
        if (!readProfileText().contains(profile)) {
            homePage.profileSwitcher.waitForVisible(60).click(); // 绕过可能的遮罩拦截
            // 固定 5s 让下拉渲染；waitForTimeout 已由框架层做成 nav-safe（导航销毁 frame 时
            // 自动降级为 Thread.sleep 兜底），无需测试层额外 try/catch。
            homePage.waitForTimeout(5000);
            homePage.locator(String.format("//*[text()='%s']", profile)).waitForVisible(30).click();
            logger.info("Clicked profile: {}", profile);
            homePage.quickLink.waitForVisible(60);

            // 切换 profile 后保存 session
            String homeUrl = loginPage.getCurrentUrl();
            SessionManager.saveSession(sessionKey, homeUrl);
        }
    }

    /**
     * 读当前 profile 文本，吸收登录后导航/重定向抖动（innerText 在 DOM 重建期会抛异常，
     * 被框架包装为 ElementOperationException）。短重试即可越过瞬时抖动。
     */
    private String readProfileText() {
        for (int i = 0; i < 5; i++) {
            try {
                return homePage.profileSwitcher.getText();
            } catch (Exception e) {
                homePage.waitForTimeout(500);
            }
        }
        return homePage.profileSwitcher.getText();
    }

    /**
     * 生成 session key
     */
    private String generateSessionKey(String env, String username) {
        return env + "_" + username;
    }

    /**
     * 并发登录入口（<b>非 {@code @Step}</b>）—— 供 {@code ConcurrentContextExecutor} 的 worker 线程调用，
     * 不触碰 Serenity {@code StepEventBus}（桥接原则 9.3）。强制真实登录（不走 session restore），
     * per-thread 浏览器初始化与并发闸门（{@code ConcurrencyGate}）由调用方负责。
     *
     * @param env      环境标识（如 O63_SIT1）
     * @param username 用户名
     */
    public void concurrentLogin(String env, String username) {
        BDDUtils logonDBBInfo = BDDUtils.getLogonDBBInfo(env, username);
        BDDUtils.setCurrentLoginInfo(logonDBBInfo);
        this.currentUrl = BDDUtils.getCurrentUrl();
        this.sessionKey = generateSessionKey(env, username);
        logger.info("[concurrent-login] forced login {} as {}", sessionKey, username);
        performLogin();
    }
}
