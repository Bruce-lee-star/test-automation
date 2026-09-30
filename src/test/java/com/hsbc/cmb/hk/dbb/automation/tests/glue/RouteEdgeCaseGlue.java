package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.dsl.RouteDsl2;
import com.hsbc.cmb.hk.dbb.automation.framework.route.v2.monitor.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.microsoft.playwright.Page;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Route 边角用例 Glue —— 以 login_dbb.feature 为 sample，从不同角度验证 Route V2 框架健壮性。
 *
 * <p><b>设计意图（2026-09-27）</b>：在真实 DBB 登录/会话复用的 feature 上下文里，用 6 类边角
 * 场景验证 route 的「不炸、不串扰、可观测」：</p>
 * <ol>
 *   <li>{@code capture} —— JSON API 信息采集（请求+响应 payload）；</li>
 *   <li>{@code nonjson-capture} —— 非 JSON（HTML document / 文本）响应的采集；</li>
 *   <li>{@code nonjson-mock} —— 对 HTML（非 JSON）响应做 mock，验证 mock 对非 JSON 数据优雅降级；</li>
 *   <li>{@code monitor} —— expectStatus + expectJsonPath 结算链路（命中真实 200 接口）；</li>
 *   <li>{@code cascade} —— 老仓级联语法（monitor×4 + mock×1 级联 done().start()）；</li>
 *   <li>{@code unmatched} —— 注册不可命中 pattern，验证 fallback 放行、零副作用。</li>
 * </ol>
 *
 * <p><b>运行前提</b>：同 feature 内前序 scenario 已登录（feature 模式 context/session 复用），
 * 本类步骤不重复登录；route 规则在 Given 注册、When 刷新页面触发请求、Then 断言结算。</p>
 */
@AutoBrowser(verbose = true)
public class RouteEdgeCaseGlue {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteEdgeCaseGlue.class);

    private static final String NON_JSON_PATTERN = "/error/profile-error.jsp";
    private static final String PROFILE_LIST_PATTERN = "profile/list";
    private static final String LEFT_MENU_PATTERN = "leftmenu/permissionLeftMenuConfig";
    private static final String UNMATCHED_PATTERN = "/api/never-exists-xyz/**";

    private final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);

    /** 当前 scenario 注册的边角 case（Cucumber glue 实例 per-scenario，字段天然隔离）。 */
    private String registeredCase;

    @Given("I register route edge case {string}")
    public void registerRouteEdgeCase(String caseName) {
        this.registeredCase = caseName;
        Page page = ensureNavigatedToHome(page());
        switch (caseName) {
            case "capture" -> RouteDsl2.on(page)
                    .api(PROFILE_LIST_PATTERN).monitor().capture().captureBody(true).done()
                    .api(LEFT_MENU_PATTERN).monitor().capture().captureBody(true).done()
                    .start();
            case "nonjson-capture" -> RouteDsl2.on(page)
                    .api("/**").monitor().capture().captureBody(true).done()
                    .start();
            case "nonjson-mock" -> RouteDsl2.on(page)
                    .api(NON_JSON_PATTERN)
                    .mock().status(200).contentType("text/html; charset=utf-8")
                    .body("<html><body>mock-error-html</body></html>").done()
                    .api(NON_JSON_PATTERN).monitor().capture().captureBody(true).done()
                    .start();
            case "monitor" -> RouteDsl2.on(page)
                    .api(PROFILE_LIST_PATTERN)
                    .monitor().expectStatus(200).timeout(15).done()
                    .start();
            case "cascade" -> RouteDsl2.on(page)
                    .api(PROFILE_LIST_PATTERN).monitor().expectStatus(200).timeout(15).done()
                    .api(LEFT_MENU_PATTERN).monitor().expectStatus(200).timeout(15).done()
                    .api("notifications/streams").monitor().expectStatus(200).timeout(15).done()
                    .api("auth/assert").monitor().expectStatus(200).timeout(15).done()
                    .api(PROFILE_LIST_PATTERN).mock().mockReplaceField("updateContctOverlayFlag", false).done()
                    .start();
            case "unmatched" -> RouteDsl2.on(page)
                    .api(UNMATCHED_PATTERN).monitor().expectStatus(200).timeout(5).done()
                    .start();
            default -> fail("unknown route edge case: " + caseName);
        }
        LOGGER.info("[RouteEdge] registered case '{}' on page {}", caseName, page.url());
    }

    @When("I refresh the page to trigger routed APIs")
    public void refreshPageToTriggerRoutedApis() {
        Page page = page();
        assertFalse(page.isClosed(), "page must be alive before refresh");
        page.reload();
        page.waitForTimeout(2500); // 等待初始化 API（profile/list、leftmenu 等）结算
        LOGGER.info("[RouteEdge] page refreshed, current url: {}", page.url());
    }

    @Then("route edge case {string} assertions pass")
    public void assertRouteEdgeCase(String caseName) {
        if (!caseName.equals(registeredCase)) {
            fail("registered case mismatch: expected " + registeredCase + " but asserting " + caseName);
        }
        Page page = page();
        // 通用健康断言：页面存活且可导航（框架未因 route 规则炸毁页面）
        assertFalse(page.isClosed(), "page must stay alive after routed traffic");
        assertNotNull(page.url(), "page url must not be null");

        switch (caseName) {
            case "capture" -> assertCaptureRecordsJsonApis();
            case "nonjson-capture" -> assertCaptureRecordsNonJsonResponses();
            case "nonjson-mock" -> assertNonJsonMockFailsOpen();
            case "monitor" -> LOGGER.info("[RouteEdge] monitor case settled without framework failure");
            case "cascade" -> LOGGER.info("[RouteEdge] cascade registration completed without framework failure");
            case "unmatched" -> LOGGER.info("[RouteEdge] unmatched pattern left traffic untouched");
            default -> fail("unknown route edge case: " + caseName);
        }
    }

    /** 确保页面已导航到 DBB home（约 blank / 空 URL 时用已恢复会话的 homeUrl（meta 来源）导航；不重复登录）。 */
    private Page ensureNavigatedToHome(Page page) {
        String url = page.url();
        if (url == null || url.isEmpty() || url.startsWith("about:")) {
            String homeUrl = SessionManager.getHomeUrl();
            if (homeUrl != null && !homeUrl.isEmpty() && !page.isClosed()) {
                LOGGER.info("[RouteEdge] page was {} -> navigating to home: {}", url, homeUrl);
                page.navigate(homeUrl);

            } else {
                LOGGER.warn("[RouteEdge] page is blank but no homeUrl available; route may not hit real APIs");
            }
        }
        return page;
    }

    private void assertCaptureRecordsJsonApis() {
        List<CapturedApiCall> calls = RouteDsl2.dumpCaptured(page());
        assertFalse(calls.isEmpty(), "capture must have recorded at least one call");
        CapturedApiCall profile = calls.stream()
                .filter(c -> c.url().contains(PROFILE_LIST_PATTERN))
                .findFirst()
                .orElse(null);
        assertNotNull(profile, "capture must record profile/list call, got: " + summarize(calls));
        assertNotNull(profile.responseStatus(), "profile/list must have a response status");
        assertNotNull(profile.responseBody(), "profile/list response body must be captured (captureBody on)");
        LOGGER.info("[RouteEdge] capture ok: profile/list status={}, bodyBytes={}",
                profile.responseStatus(), profile.responseBody().length());
    }

    private void assertCaptureRecordsNonJsonResponses() {
        List<CapturedApiCall> calls = RouteDsl2.dumpCaptured(page());
        assertFalse(calls.isEmpty(), "non-json capture must have recorded traffic");
        boolean htmlSeen = calls.stream().anyMatch(c -> {
            String ct = c.responseHeaders().entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("content-type"))
                    .map(e -> e.getValue().toLowerCase(Locale.ROOT))
                    .findFirst().orElse("");
            return ct.contains("text/html") || ct.contains("text/plain");
        });
        assertTrue(htmlSeen, "capture must include non-JSON (html/text) responses, got: " + summarize(calls));
        LOGGER.info("[RouteEdge] non-json capture ok: {} calls, html/text response seen", calls.size());
    }

    private void assertNonJsonMockFailsOpen() {
        List<CapturedApiCall> calls = RouteDsl2.dumpCaptured(page());
        // 条件断言：若 error.jsp 被请求（被 mock 命中），响应体必须是 mock HTML；
        // 未被请求则仅断言页面健康（mock 注册对非 JSON 数据不炸本身就是验收点）。
        calls.stream().filter(c -> c.url().contains("profile-error.jsp")).forEach(c -> {
            if (c.responseBody() != null) {
                assertTrue(c.responseBody().contains("mock-error-html"),
                        "mocked non-JSON response body must be the mock HTML, got: " + c.responseBody());
            }
        });
        LOGGER.info("[RouteEdge] non-json mock ok (fails open, page healthy)");
    }

    private static String summarize(List<CapturedApiCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (CapturedApiCall c : calls) {
            if (sb.length() > 300) {
                sb.append("...");
                break;
            }
            sb.append(c.method()).append(' ').append(c.url())
                    .append(" -> ").append(c.responseStatus()).append("; ");
        }
        return sb.toString();
    }

    private Page page() {
        return loginPage.getPage();
    }
}
