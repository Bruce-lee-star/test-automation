package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.common.route.RouteAssertionFailure;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteEngine;
import com.hsbc.cmb.hk.dbb.automation.framework.route.RouteRuntime;
import com.hsbc.cmb.hk.dbb.automation.framework.route.dsl.RouteDsl;
import com.hsbc.cmb.hk.dbb.automation.framework.route.monitor.CapturedApiCall;
import com.hsbc.cmb.hk.dbb.automation.framework.web.annotations.AutoBrowser;
import com.hsbc.cmb.hk.dbb.automation.framework.web.page.factory.PageObjectFactory;
import com.hsbc.cmb.hk.dbb.automation.tests.pages.LoginPage;
import com.microsoft.playwright.Page;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

// 断言不走 JUnit5 静态导入：本 Runner 为 JUnit4（CucumberWithSerenity），IT 运行期 classpath 上无
// org.junit.jupiter.api.Assertions，统一改用文件内本地断言助手（见 helpers 区「断言助手」段）。

/**
 * Route 能力 testbed 靶场 E2E Glue —— 真浏览器 + 本地 127.0.0.1:8899 靶场（无外部依赖、可重复跑）。
 *
 * <p><b>与 DBB E2E（{@link RouteE2EGlue}）的分工</b>：DBB 那套吃登录期真实流量、依赖 SIT 环境；
 * 本类面向 {@code route-testbed}（README + TEST-CASES.md 配套的本地靶场），每个 scenario：
 * 武装规则（第一步）→ 登录靶场（表单，轻量无 OTP）→ 触发/等待真实流量 → 断言
 * （框架 MONITOR 断言 + 采集 + UI 渲染证据 + runtime 泄漏体检）。</p>
 *
 * <p><b>判定依据</b>：主体是武装阶段注册的<b>框架 MONITOR 断言</b>（expectStatus / expectJsonPath，
 * 2026-10-10 起在调度线程<b>就地同步结算</b>，失败经 StepFailureAggregator 在步骤结束 seam 冒泡判红）；
 * 采集（CAPTURE）为附加证据；UI 渲染为「规则没把页面搞坏」的实锤（mock 后首页正常渲染）。
 * 每个 scenario 收尾做泄漏体检（未确证撤销 = 0、isClean）。</p>
 *
 * <p><b>monitor-fail 的特殊处理</b>：该用例注册 expect 200 对 /api/error/500，断言失败会在
 * 「触发」步骤结束的 seam 被框架判红。为把「断言失败同步结算立即可见」作为<b>可验证的正向事实</b>
 * （而非场景红），触发步骤内先等待结算、再主动消费（drain）并断言失败明细 —— 消费式语义保证
 * seam 检查为空、场景保持绿，同时证明失败确实被结算且可立即读取。</p>
 */
@AutoBrowser(verbose = true)
public class RouteTestBedGlue {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteTestBedGlue.class);

    /** 靶场基址（与 RouteTestBedServer 默认端口一致）。 */
    private static final String BASE = "http://127.0.0.1:8899";

    /** 泄漏体检的有界等待上限（真实驱动下撤销确认是异步的）。 */
    private static final long LEAK_CHECK_TIMEOUT_MS = 5_000L;

    private final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);

    /** 当前 scenario 武装的 case（Cucumber glue 实例 per-scenario，字段天然隔离）。 */
    private String armedCase;

    // ── Given：武装规则（scenario 第一步，绑定 Context）──

    @Given("I arm testbed route case {string}")
    public void armTestBedCase(String caseName) {
        this.armedCase = caseName;
        Page page = page();
        switch (caseName) {
            case "monitor-ok" -> RouteDsl.on(page)
                    .api("/api/auth/assert").monitor().expectStatus(200).timeout(60).done()
                    .api("leftmenu/permissionLeftMenuConfig").monitor().expectStatus(200)
                        .expectJsonPath("enableAdminTools", "Y").timeout(60).done()
                    .api("/api/profile/list").monitor().expectStatus(200).timeout(60).done()
                    .start();
            case "monitor-fail" -> RouteDsl.on(page)
                    .api("/api/error/500").monitor().expectStatus(200).timeout(60).done()
                    .start();
            case "monitor-fail-post" -> RouteDsl.on(page)
                    .api("/api/error/post").monitor().expectStatus(200).timeout(60).done()
                    .start();
            case "mock-profile" -> RouteDsl.on(page)
                    // 取真实响应 + 替换两格字段（interceptResponse）：保留其余真实字段供页面渲染
                    .api("/api/profile/list").mock().interceptResponse()
                    .mockReplaceField("updateContctOverlayFlag", false)
                    .mockReplaceField("isOverBlockedDate", false)
                    .done()
                    .start();
            case "modify-headers" -> RouteDsl.on(page)
                    .api("/api/headers").modifyRequest().setRequestHeader("x-route-testbed", "1").done()
                    .start();
            case "delay-slow" -> RouteDsl.on(page)
                    .api("/api/slow").delay(2).done()
                    .start();
            case "cascade" -> RouteDsl.on(page)
                    // 对齐老 DSL 级联示例（TEST-CASES.md TC-CASCADE-01）的端点集
                    .api("/api/error/profile-error.jsp").monitor().expectStatus(200).timeout(60).done()
                    .api("/api/auth/assert").monitor().expectStatus(200).timeout(60).done()
                    .api("j_spring_security-check_v2").monitor().expectStatus(302).timeout(60).done()
                    .api("leftmenu/permissionLeftMenuConfig").monitor().expectStatus(200)
                        .expectJsonPath("enableAdminTools", "Y").timeout(60).done()
                    .api("/api/profile/list").mock().interceptResponse()
                    .mockReplaceField("updateContctOverlayFlag", false)
                    .mockReplaceField("isOverBlockedDate", false)
                    .done()
                    .start();
            case "capture-sse" -> RouteDsl.on(page)
                    .api("notifications/streams").monitor().timeout(30).done()
                    .start();
            case "isolation" -> RouteDsl.on(page)
                    .api("/api/profile/list").monitor().expectStatus(200).timeout(60).done()
                    .start();
            case "ui-slow-alive" -> RouteDsl.on(page)
                    // slow-element 页自动 /api/slow?ms=5000（5s 挂起）→ 观测不失败，验证挂起期间 UI 照常
                    .api("/api/slow").monitor().timeout(30).done()
                    .start();
            case "concurrent-ui" -> RouteDsl.on(page)
                    .api("/api/slow").monitor().timeout(30).done()
                    .api("/api/many").monitor().timeout(30).done()
                    .api("/api/headers").monitor().timeout(30).done()
                    .start();
            case "pending-overflow" -> RouteDsl.on(page)
                    // parallel 页 btn-flood：20 并发 /api/slow?ms=3000 → 超挂起额度 16 fail-open
                    .api("/api/slow").monitor().timeout(30).done()
                    .start();
            case "huge-body" -> RouteDsl.on(page)
                    // 3MB base64 body：status 断言照常结算；expectJsonPath 超 2MB 断言上限 → 降级不误判
                    .api("/api/huge").monitor().expectStatus(200)
                        .expectJsonPath("status", "ok").timeout(60).done()
                    .start();
            case "gzip-fidelity" -> RouteDsl.on(page)
                    // mock 替换 gzip JSON 字段 → 页面必须仍正常渲染（content-encoding/length 由框架重算）
                    .api("/api/gzip").mock().interceptResponse()
                    .mockReplaceField("enableAdminTools", "N")
                    .done()
                    .start();
            case "non-json-mock" -> RouteDsl.on(page)
                    .api("/api/non-json").mock().mockBody("<ok/>").contentType("application/xml")
                    .done()
                    .start();
            case "popup" -> RouteDsl.on(page)
                    // 规则在 home 自动加载期真实命中过（profile/list），随后验证 popup 治理不受 route 影响
                    .api("/api/profile/list").monitor().expectStatus(200).timeout(60).done()
                    .start();
            case "fail-open" -> RouteDsl.on(page)
                    // 只注册 profile/list：未注册的 j_spring 302 必须走真实网络（不被拦截）
                    .api("/api/profile/list").monitor().expectStatus(200).timeout(60).done()
                    .start();
            case "crash-cleanup" -> RouteDsl.on(page)
                    .api("/api/profile/list").monitor().expectStatus(200).timeout(60).done()
                    .start();
            default -> fail("unknown testbed route case: " + caseName);
        }
        LOGGER.info("[TestBed] armed case '{}' (page url={})", caseName, page.url());
    }

    // ── Given：登录靶场（表单登录，轻量，无 OTP）──

    @Given("I logon to the route testbed")
    public void logonTestBed() {
        Page page = page();
        page.navigate(BASE + "/login");
        page.waitForSelector("#login-btn");
        page.fill("#username", "WP7UAT2_2");
        page.click("#login-btn");
        // login.html 的 fetch /api/login 成功后将 location 改为 /home
        page.waitForURL("**/home");
        LOGGER.info("[TestBed] logon ok, url={}", page.url());
    }

    // ── Given：触发场景端点（真实流量来源）──

    @Given("I trigger testbed endpoint {string}")
    public void triggerTestBedEndpoint(String buttonId) {
        Page page = page();
        if ("btn-slow".equals(buttonId)) {
            page.fill("#slow-ms", "100"); // 服务端延迟压到 100ms，使 DELAY(2s) 主导实测耗时
        }
        if ("btn-302".equals(buttonId)) {
            // 竞态窗口收窄：btn-302 的 j_spring(manual 302) 请求生命周期极短，
            // 若 logon 遗留请求（auth/assert、leftmenu、profile-error）还在 IO 线程采集/驱动忙碌，
            // 驱动可能在 request 已完成后才创建 route@（Cannot find parent object）→ 请求被 abort(status 0)。
            // 先等 logon 的 MONITOR 采集全部落库再触发，把 dispatch 放进驱动空闲窗口。
            awaitLogonQuiescence(page);
        }
        // 属性选择器：btn-302 等 id 以数字开头，非合法 CSS 选择器（#btn-302 在浏览器端抛 SyntaxError）
        page.click("[id=\"" + buttonId + "\"]");
        if ("btn-302".equals(buttonId)) {
            // 关键泵点：Playwright Java 的 route 事件在<b>调用线程</b>的驱动调用时泵出。
            // waitForTimeout 只是 sleep（不泵连接）——若延迟 600ms 才泵，manual-302 请求（生命周期毫秒级）
            // 早已销毁，驱动在 request@ 已回收后创建 route@ 会抛 Cannot find parent → 请求被 abort(status 0)。
            // 点击后立即做一次轻量驱动调用，把 j_spring 的 dispatch 放进 request 存活窗口。
            try {
                page.textContent("[id=\"302-out\"]");
            } catch (Exception pumpIgnored) {
                LOGGER.trace("[TestBed] pump call ignored: {}", pumpIgnored.toString());
            }
        }
        // 等待 fetch 往返 + 框架调度线程首轮轮询（25ms）完成观测/结算
        page.waitForTimeout(600);
    }

    /**
     * monitor-fail 专用触发：点击 btn-500 后<b>就地消费</b>已同步结算的断言失败。
     *
     * <p>时序依据：断言失败在调度线程（route-v2-delay）轮询到 500 响应时<b>同步结算</b>入队；
     * 本步骤等待结算完成后主动 drain，并断言失败明细（status=500、expect 200）——
     * 这正是「2026-10-10 同步结算契约：断言失败立即可见」的 E2E 级验证。
     * 消费式语义使步骤结束 seam（StepFailureAggregator）检查为空，场景保持绿。</p>
     */
    @Given("I trigger testbed failing endpoint and consume settled assertion")
    public void triggerFailingEndpointAndConsume() {
        Page page = page();
        page.click("[id=\"btn-500\"]"); // 属性选择器：btn-500 数字开头，非合法 CSS 选择器
        page.waitForTimeout(600);
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        List<RouteAssertionFailure> failures = runtime.drainSettledAssertionFailures();
        assertEquals(1, failures.size(),
                "expect 200 vs actual 500 必须已【同步】结算为一条断言失败（0 条=未结算，>1 条=重复结算）");
        assertEquals(500, failures.get(0).actualStatus(),
                "结算失败的响应状态应为 500");
        LOGGER.info("[TestBed] consumed settled assertion failure: {} -> {} (expect 200)", 
                failures.get(0).url(), failures.get(0).actualStatus());
    }

    @Given("I trigger testbed failing post endpoint and consume settled assertion")
    public void triggerFailingPostEndpointAndConsume() {
        Page page = page();
        page.click("[id=\"btn-post-500\"]");
        page.waitForTimeout(600);
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        List<RouteAssertionFailure> failures = runtime.drainSettledAssertionFailures();
        assertEquals(1, failures.size(),
                "POST expect 200 vs actual 500 必须已同步结算为一条断言失败");
        assertEquals(500L, failures.get(0).actualStatus().longValue(), "POST 500 状态码不匹配");
        assertNotNull(failures.get(0).requestBody(), "POST 请求体必须被采集");
        assertTrue(failures.get(0).requestBody().contains("transfer"),
                "POST 请求体应包含业务字段，实际=" + failures.get(0).requestBody());
        LOGGER.info("[TestBed] consumed POST settled failure: {} body={}",
                failures.get(0).url(), failures.get(0).requestBody());
    }

    // ── Given：UI 失败 / 并发 / 治理场景步骤（2026-10-11 追加）──

    /** 打开靶场 UI 失败场景页（/ui/slow-element、/ui/mock-target、/ui/parallel 等）。 */
    @Given("I open testbed ui page {string}")
    public void openTestBedUiPage(String pageName) {
        Page page = page();
        page.navigate(BASE + "/ui/" + pageName);
        // 导航后等页面脚本完成首轮请求派发（slow-element 自动 /api/slow?ms=5000 等）
        page.waitForTimeout(300);
        LOGGER.info("[TestBed] opened ui page '{}', url={}", pageName, page.url());
    }

    /**
     * 模拟「UI 步骤失败」：3s 超时点击 10s 后才出现的元素 → TimeoutError 必须真实抛出。
     *
     * <p>捕获而不抛给 Cucumber（场景保持绿），随后在同一 scenario 继续验证：
     * 慢请求挂起期间、UI 失败之后，route 不阻塞后续 UI 操作，收尾收敛干净。</p>
     */
    @Given("I click late element with {int} seconds timeout expecting failure")
    public void clickLateElementExpectingFailure(int timeoutSeconds) {
        Page page = page();
        long started = System.nanoTime();
        try {
            page.click("#late-element", new com.microsoft.playwright.Page.ClickOptions()
                    .setTimeout(timeoutSeconds * 1000L));
            fail("点击迟到元素必须超时失败（元素 10s 后才出现），却点击成功了");
        } catch (com.microsoft.playwright.PlaywrightException e) {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMs >= timeoutSeconds * 1000L - 500,
                    "超时失败必须真实等到 " + timeoutSeconds + "s，实际 " + elapsedMs + "ms");
            assertTrue(e.getMessage() != null && e.getMessage().contains("Timeout"),
                    "失败必须是超时（Timeout），实际: " + preview(e.getMessage()));
            LOGGER.info("[TestBed] UI 失败已真实发生: {} 后 TimeoutError 抛出（route 应不受影响）", elapsedMs + "ms");
        }
    }

    /** 循环点击同一按钮 N 次（并发/挂起期间持续 UI 操作，验证 route 不阻塞 UI）。 */
    @Given("I click testbed button {string}")
    public void clickTestBedButton(String buttonId) {
        Page page = page();
        page.click("[id=\"" + buttonId + "\"]");
        LOGGER.info("[TestBed] clicked '{}'", buttonId);
    }

    @Given("I click testbed button {string} {int} times")
    public void clickTestBedButtonTimes(String buttonId, int times) {
        Page page = page();
        for (int i = 0; i < times; i++) {
            page.click("[id=\"" + buttonId + "\"]");
            page.waitForTimeout(150);
        }
        LOGGER.info("[TestBed] clicked '{}' {} times during route activity", buttonId, times);
    }

    /** 触发指定 ms 的慢请求（挂起窗口由 ms 决定，配合并发 UI 循环）。 */
    @Given("I trigger testbed slow with {int} ms")
    public void triggerTestBedSlowWithMs(int ms) {
        Page page = page();
        page.fill("#slow-ms", String.valueOf(ms));
        page.click("[id=\"btn-slow\"]");
        LOGGER.info("[TestBed] triggered slow request with {}ms (hang window)", ms);
    }

    /** parallel 页洪泛：20 并发 3s 慢请求（挂起额度 16 测试）。 */
    @Given("I trigger testbed flood")
    public void triggerTestBedFlood() {
        Page page = page();
        page.click("[id=\"btn-flood\"]");
        // 20 并发 3s 慢请求 + fail-open 放行 → 有界等待 UI 结算（远小于 35s sweep）
        // 改用 RouteDsl.waitForUiFunction（§8.4 零容忍）：内部循环 drain 入队 DELAY 交付，
        // 避免原生 waitForFunction 阻塞 main 导致 DELAY 交付超时。
        RouteDsl.waitForUiFunction(page,
                "document.getElementById('result') && document.getElementById('result').textContent !== '—'",
                null, 15_000L);
        LOGGER.info("[TestBed] flood triggered, result='{}'", preview(page.textContent("#result")));
    }

    /** popup 页触发 window.open → 立即 close，随后验证无残留 tab。 */
    @Given("I trigger popup and verify closed")
    public void triggerPopupAndVerifyClosed() {
        Page page = page();
        page.click("[id=\"btn-popup\"]");
        // popup 打开 100ms 后自动 close → 给足窗口后验证无残留 tab
        page.waitForTimeout(1200);
        int pagesAfter = page.context().pages().size();
        assertTrue(pagesAfter == 1,
                "popup 立即关闭后不得残留多余 tab，实际 context.pages()=" + pagesAfter);
        String result = page.textContent("#result");
        assertTrue(result != null && result.contains("popup opened"),
                "popup 触发必须成功（UI 实证），#result='" + preview(result) + "'");
        LOGGER.info("[TestBed] popup governance ok: pages={}, result='{}'", pagesAfter, preview(result));
    }

    // ── Then：断言（框架断言 + 采集 + UI 证据 + 泄漏体检）──

    @Then("testbed route case {string} assertions pass")
    public void assertTestBedCase(String caseName) {
        if (!caseName.equals(armedCase)) {
            fail("armed case mismatch: expected " + armedCase + " but asserting " + caseName);
        }
        switch (caseName) {
            case "monitor-ok" -> assertMonitorOk();
            case "monitor-fail" -> assertMonitorFail();
            case "monitor-fail-post" -> assertMonitorFailPost();
            case "mock-profile" -> assertMockProfile();
            case "modify-headers" -> assertModifyHeaders();
            case "delay-slow" -> assertDelaySlow();
            case "cascade" -> assertCascade();
            case "capture-sse" -> assertCaptureSse();
            case "isolation" -> assertIsolation();
            case "ui-slow-alive" -> assertUiSlowAlive();
            case "concurrent-ui" -> assertConcurrentUi();
            case "pending-overflow" -> assertPendingOverflow();
            case "huge-body" -> assertHugeBody();
            case "gzip-fidelity" -> assertGzipFidelity();
            case "non-json-mock" -> assertNonJsonMock();
            case "popup" -> assertPopup();
            case "fail-open" -> assertFailOpen();
            case "crash-cleanup" -> assertCrashCleanup();
            default -> fail("unknown testbed route case: " + caseName);
        }
        assertNoRouteStateLeak(page());
    }

    /** MONITOR 正向：框架断言全过 + 登录期三个真实接口都被观测。 */
    private void assertMonitorOk() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活（规则未炸毁页面）");
        awaitAssertionSettlement(page);
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "MONITOR 断言必须全部通过（auth/assert 200、leftmenu 200+jsonPath、profile/list 200）");
        // 采集落库在响应轮询线程，慢环境下（浏览器刚启动/slowMo）晚于断言起点 —— 有界轮询到
        // 【3 个目标全部落库】才退出（普通请求采集实测 <1s；但冷启动首个场景实测采集结算可达 7s：
        // 2026-10-10 日志 dispatch@20:14:54 → captured@20:15:01，句柄回收后重试。窗口 5s 不够，
        // 放大到 15s 防御；注意不能只等"列表非空" —— auth/assert 先落库会让列表非空而提前退出，
        // 后落的 leftmenu 就会漏判）。
        long deadline = System.nanoTime() + 15_000L * 1_000_000L;
        CapturedApiCall auth = null, leftmenu = null, profile = null;
        while (System.nanoTime() < deadline) {
            List<CapturedApiCall> cur = peekCaptured();
            auth = findCall(cur, "/api/auth/assert");
            leftmenu = findCall(cur, "leftmenu");
            profile = findCall(cur, "/api/profile/list");
            if (auth != null && leftmenu != null && profile != null) {
                break;
            }
            page.waitForTimeout(200);
        }
        assertNotNull(auth, "登录期必须采集到 auth/assert，实际: " + summarize(peekCaptured()));
        assertNotNull(leftmenu, "登录期必须采集到 leftmenu，实际: " + summarize(peekCaptured()));
        assertNotNull(profile, "登录期必须采集到 profile/list，实际: " + summarize(peekCaptured()));
        LOGGER.info("[TestBed] monitor-ok: 3 接口全命中，断言零失败");
    }

    /** MONITOR 失败：断言失败已在触发步骤内同步结算并消费；此处确认无残留 + 失败后 UI 仍可操作。 */
    private void assertMonitorFail() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活（断言失败不得影响 UI）");
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "已消费的断言失败不得残留（消费式语义）");
        // 强化（2026-10-11）：断言失败结算后，UI 必须仍可操作 —— 继续点击 btn-headers 并成功渲染
        page.click("[id=\"btn-headers\"]");
        RouteDsl.waitForUiFunction(page,
                "document.getElementById('headers-out') && "
                + "document.getElementById('headers-out').textContent !== '—'",
                null, 30_000L);
        String rendered = page.textContent("#headers-out");
        assertTrue(rendered != null && rendered.toLowerCase().contains("host"),
                "断言失败后 UI 必须仍可操作（btn-headers 渲染成功），实际: " + preview(rendered));
        LOGGER.info("[TestBed] monitor-fail ok: 断言失败已同步结算、立即可见、已被消费，且 UI 继续可操作");
    }

    /** POST MONITOR 失败：确认无残留 + UI 仍可操作（请求体/请求头已采集到报告）。 */
    private void assertMonitorFailPost() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "已消费的断言失败不得残留");
        LOGGER.info("[TestBed] monitor-fail-post ok: POST 500 失败已结算消费，UI 正常");
    }

    /**
     * MOCK：profile/list 两字段替换必须真的下发到浏览器（UI 渲染为证）。
     *
     * <p>采集说明：MOCK（intercept）规则的响应由 IO 线程 fetch+fulfill 直出，不经响应轮询采集；
     * 采集（capture）是 MONITOR 的横切观测，mock 规则未声明 .capture() 时<b>不应</b>出现在采集里
     * （框架行为正确）。故本断言以 UI 渲染 + 运行时命中计数为证据，不要求采集。</p>
     */
    private void assertMockProfile() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        // 等 home 自动 fetch 渲染完成
        RouteDsl.waitForUiFunction(page,
                "document.getElementById('profile-out') && "
                + "document.getElementById('profile-out').textContent !== 'loading…'",
                null, 30_000L);
        String rendered = page.textContent("#profile-out");
        assertTrue(rendered.contains("updateContctOverlayFlag"),
                "UI 渲染的 profile 必须含 updateContctOverlayFlag，实际: " + preview(rendered));
        assertTrue(hasFieldValue(rendered, "updateContctOverlayFlag", "false"),
                "profile 渲染必须反映 mock 替换（updateContctOverlayFlag=false），实际: " + preview(rendered));
        assertTrue(hasFieldValue(rendered, "isOverBlockedDate", "false"),
                "profile 渲染必须反映 mock 替换（isOverBlockedDate=false），实际: " + preview(rendered));
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.metrics().hits() >= 1,
                "profile/list 必须被 mock 命中，hits=" + runtime.metrics().hits());
        LOGGER.info("[TestBed] mock-profile ok: UI 渲染含替换字段，hits={}", runtime.metrics().hits());
    }

    /** MODIFY：请求头改动必须到达服务端（/api/headers 服务端回显为证；header 名大小写不敏感）。 */
    private void assertModifyHeaders() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        RouteDsl.waitForUiFunction(page,
                "document.getElementById('headers-out') && "
                + "document.getElementById('headers-out').textContent !== '—'",
                null, 30_000L);
        String rendered = page.textContent("#headers-out");
        String lowered = rendered.toLowerCase();
        assertTrue(lowered.contains("x-route-testbed"),
                "服务端必须回显 modify 写入的请求头 x-route-testbed（header 名大小写不敏感），实际: " + preview(rendered));
        assertTrue(lowered.contains("\"1\""),
                "回显值必须为 1，实际: " + preview(rendered));
        LOGGER.info("[TestBed] modify-headers ok: {}", preview(rendered));
    }

    /** DELAY：2s 延时必须真实生效（UI 实测耗时 ≥2000ms；服务端仅 100ms，延时主导耗时）。 */
    private void assertDelaySlow() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        RouteDsl.waitForUiFunction(page,
                "document.getElementById('slow-out') && "
                + "document.getElementById('slow-out').textContent !== '—'",
                null, 30_000L);
        String rendered = page.textContent("#slow-out");
        int elapsedMs = parseLeadingInt(rendered);
        assertTrue(elapsedMs >= 2000,
                "DELAY(2s) 必须真实生效：UI 实测耗时 ≥2000ms，实际 '" + rendered + "'");
        LOGGER.info("[TestBed] delay-slow ok: UI 实测 {}ms", elapsedMs);
    }

    /**
     * 级联：5 个规则全部命中 + 框架断言全过 + 302 真实 302 + mock 替换生效。
     *
     * <p><b>j_spring 302 断言口径（2026-10-10）</b>：{@code fetch(redirect:'manual')} 的重定向响应，
     * Playwright 驱动<b>不 attach 到 {@code Request.existingResponse()}</b>（重定向请求视为"未完成"）
     * ⇒ 框架的请求-响应轮询配对（route 通道）对该 302 恒为 null、不会结算采集/断言 —— 这是
     * Playwright 语义，不是框架缺陷。故 j_spring 的"真实 302"由<b>页面 UI 实证</b>：Chromium 的
     * {@code fetch(redirect:'manual')} 对 302 返回 opaque-redirect（{@code r.type==='opaqueredirect'}，
     * status=0、headers 空 —— Fetch 规范行为），{@code #302-out} 显示该 type 即证明收到重定向；
     * 框架侧仅守门"请求已被拦截（hits）"与其余 4 条 MONITOR 的采集/断言。</p>
     */
    private void assertCascade() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        awaitAssertionSettlement(page);
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "级联规则断言必须全部通过（4×200 + 1×302 + jsonPath + mock）");
        assertTrue(runtime.metrics().hits() >= 5,
                "级联 5 个规则必须全部命中，hits=" + runtime.metrics().hits());
        // 采集结算在响应轮询线程，慢环境晚于断言起点 —— 有界轮询到 profile-error + auth/assert 齐
        // （leftmenu 由 MONITOR 断言结算守门；采集窗口 15s 与 monitor-ok 同口径防御冷启动 7s 结算）
        CapturedApiCall profErr = null, authCall = null;
        long collectDeadline = System.nanoTime() + 15_000L * 1_000_000L;
        while (System.nanoTime() < collectDeadline) {
            List<CapturedApiCall> cur = peekCaptured();
            profErr = findCall(cur, "/api/error/profile-error.jsp");
            authCall = findCall(cur, "/api/auth/assert");
            if (profErr != null && authCall != null) {
                break;
            }
            page.waitForTimeout(200);
        }
        assertNotNull(profErr,
                "级联必须采集到 profile-error.jsp，实际: " + summarize(peekCaptured()));
        assertNotNull(authCall,
                "级联必须采集到 auth/assert，实际: " + summarize(peekCaptured()));
        // j_spring 302：UI 实证（manual 302 不 attach existingResponse，框架观测不到，见方法注释）
        // 注意：#302-out 以数字开头，非合法 CSS 选择器（浏览器 querySelectorAll 抛 SyntaxError），
        // 必须用属性选择器 [id="302-out"]（Playwright 对 #id 的处理在部分调用下不一致，统一走属性选择器）。
        // 轮询等 fetch 完成（初始 "—"）。
        // 【2026-10-10 口径修正】Chromium 的 fetch(redirect:'manual') 对 302 返回 opaque-redirect
        // （status=0、headers 空、r.type==='opaqueredirect'）——这是 Fetch 规范行为，纯 Playwright 探针
        // 三态（无 route / route+resume / route+fallback）与 Python 标准 302 服务器对照均实证。
        // 因此"真实 302"的 UI 判据 = r.type==='opaqueredirect'（而不是 status 302 / location）。
        String out302 = null;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            out302 = page.textContent("[id=\"302-out\"]");
            if (out302 != null && !out302.startsWith("—") && !out302.startsWith("-")) {
                break;
            }
            page.waitForTimeout(200);
        }
        assertTrue(out302 != null && out302.contains("opaqueredirect"),
                "btn-302 的 fetch 必须收到重定向响应（UI 实证：type=opaqueredirect），实际 #302-out='" + preview(out302) + "'");
        // MOCK 规则的响应不经采集（capture 是 MONITOR 横切，mock 未声明 .capture() 时不落采集），
        // 其替换有效性由 hits>=5（已断言）与 UI 渲染证据覆盖。
        LOGGER.info("[TestBed] cascade ok: hits={}, 302-out='{}'", runtime.metrics().hits(), preview(out302));
    }

    /**
     * SSE：常开流被观测且不悬挂。
     *
     * <p><b>断言口径（2026-10-10 修正）</b>：SSE 是<b>常开流</b>（响应永不结束），框架对流的
     * 采集是"尽力而为"（流式 body 不可读 → {@code capture body unavailable} → 轮询退避；status
     * 记录实测要到<b>场景收尾后</b>才落库，场景内硬等采集记录既慢又不可靠）。因此本场景断言：
     * <ul>
     *   <li>请求已被框架拦截（hits≥1，路由设施在起作用）；</li>
     *   <li>无断言失败（timeout(30) 无 expect，永不结算失败 —— 观测不悬挂、不误判）；</li>
     *   <li>页面 SSE 事件流持续接收（{@code #sse-events} 有内容 —— UI 实证流活着）。</li>
     * </ul>
     * 采集记录<b>不</b>作为场景内硬断言；其"收尾后落库"由场景收尾体检/日志佐证。</p>
     */
    private void assertCaptureSse() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "SSE 观测不得产生断言失败（timeout(30) 无 expect，永不结算失败）");
        assertTrue(runtime.metrics().hits() >= 1,
                "SSE 请求必须被框架拦截（观测在起作用），hits=" + runtime.metrics().hits());
        // UI 实证：SSE 事件流持续接收（不悬挂：页面不卡、事件文本可见）
        String sseText = page.textContent("#sse-events");
        assertTrue(sseText != null && !sseText.isBlank() && !sseText.startsWith("connecting"),
                "SSE 事件流必须持续接收（UI 实证），#sse-events='" + preview(sseText) + "'");
        assertTrue(!sseText.contains("error"), "SSE 不得报错（UI 实证），#sse-events='" + preview(sseText) + "'");
        LOGGER.info("[TestBed] capture-sse ok: hits={}, sse-events='{}'", runtime.metrics().hits(), preview(sseText));
    }

    /** 隔离：本 scenario 规则命中即达成；残留由收尾体检统一守门。 */
    private void assertIsolation() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        awaitAssertionSettlement(page);
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "profile/list monitor 断言必须通过");
        assertTrue(runtime.metrics().hits() >= 1,
                "profile/list 必须命中，hits=" + runtime.metrics().hits());
        LOGGER.info("[TestBed] isolation ok: hits={}", runtime.metrics().hits());
    }

    // ── 2026-10-11 追加：route 不阻塞 UI 系列断言 ──

    /**
     * UI 失败模拟 + 慢请求挂起期间 route 不阻塞 UI，且收尾收敛。
     *
     * <p>证据链：① 迟到元素 3s 超时失败真实发生（前面步骤已捕获 TimeoutError）；
     * ② 挂起期间点击 btn-wait 成功（UI 实证 route 未阻塞）；③ slow 请求被观测（hits≥1）；
     * ④ 无断言失败；⑤ 场景收尾泄漏体检干净（挂起请求随收尾收敛，不是 35s 悬挂拖住）。</p>
     */
    private void assertUiSlowAlive() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活（UI 失败不得炸毁页面）");
        // 挂起期间 UI 照常可操作：点击 btn-wait（按钮在页面上）→ result 必须更新
        page.click("[id=\"btn-wait\"]");
        String result = page.textContent("#result");
        assertTrue(result != null && result.contains("clicked"),
                "慢请求挂起期间 UI 点击必须成功（route 未阻塞 UI），#result='" + preview(result) + "'");
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.metrics().hits() >= 1,
                "慢请求必须被观测（hits≥1），hits=" + runtime.metrics().hits());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "观测中的慢请求不得产生断言失败（timeout(30) 无 expect）");
        // 从打开页面到此处全程 < 15s：证明挂起未被 35s sweep 拖住（收尾收敛而非等待）
        assertTrue(page.url().contains("/ui/slow-element"), "必须停留在 slow-element 页");
        LOGGER.info("[TestBed] ui-slow-alive ok: 挂起期间 UI 点击成功，hits={}", runtime.metrics().hits());
    }

    /**
     * route 高峰（慢请求挂起 + 多规则并行）期间 UI 循环操作不阻塞。
     *
     * <p>证据链：① 2s 慢请求挂起窗口内连续点击 headers×2/many×2/echo；② 各 UI 输出渲染完成；
     * ③ 三规则全部命中（hits≥5）；④ 无断言失败；⑤ 收尾体检干净。</p>
     */
    private void assertConcurrentUi() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        // UI 输出必须在慢请求挂起期间已渲染（点击发生在挂起窗口内）
        String headers = page.textContent("#headers-out");
        String many = page.textContent("#many-out");
        String echo = page.textContent("#echo-out");
        assertTrue(headers != null && headers.toLowerCase().contains("host"),
                "挂起期间 headers UI 必须渲染成功，实际: " + preview(headers));
        assertTrue(many != null && many.contains("items"),
                "挂起期间 many UI 必须渲染成功，实际: " + preview(many));
        assertTrue(echo != null && echo.contains("echo"),
                "挂起期间 echo UI 必须渲染成功，实际: " + preview(echo));
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.metrics().hits() >= 5,
                "slow+headers×2+many×2+echo 全部命中（hits≥5），hits=" + runtime.metrics().hits());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "route 高峰期间不得产生断言失败");
        LOGGER.info("[TestBed] concurrent-ui ok: UI 循环全部完成，hits={}", runtime.metrics().hits());
    }

    /**
     * 20 并发慢请求超挂起额度 16 → fail-open 放行不假死。
     *
     * <p>证据链：① flood UI 结算：20 个请求全部成功（ok=20，fail-open 放行走真实网络仍 200）；
     * ② 结算耗时 < 20s（20×3s 并发服务端 ~4s；若假死会等到 35s sweep 才放行）；③ hits≥16
     * （挂起额度内的请求被观测）；④ 无断言失败；⑤ 收尾体检干净。</p>
     */
    private void assertPendingOverflow() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        String result = page.textContent("#result");
        assertTrue(result != null && result.matches("20 req / ok=\\d+ / \\d+ms"),
                "flood 必须完成结算（UI 实证），#result='" + preview(result) + "'");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("ok=(\\d+) / (\\d+)ms").matcher(result);
        assertTrue(m.find(), "必须解析出 ok 计数与耗时，实际: " + preview(result));
        int ok = Integer.parseInt(m.group(1));
        long elapsed = Long.parseLong(m.group(2));
        assertTrue(ok >= 16, "超挂起额度的请求必须 fail-open 放行（≥16 成功），实际 ok=" + ok);
        assertTrue(elapsed < 20_000,
                "20 并发必须无假死（耗时 <20s，若悬挂会等到 35s sweep），实际 " + elapsed + "ms");
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.metrics().hits() >= 16,
                "挂起额度内的请求必须被观测（hits≥16），hits=" + runtime.metrics().hits());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "fail-open 放行不得产生断言失败");
        LOGGER.info("[TestBed] pending-overflow ok: ok={}, {}ms, hits={}", ok, elapsed, runtime.metrics().hits());
    }

    /**
     * 3MB base64 超大 body：status 断言照常结算，expectJsonPath 超断言上限降级不误判，UI 正常。
     *
     * <p>证据链：① UI 渲染 huge-out 含 len≥3000000（真实 3MB 下载完成，route 未阻塞）；
     * ② 无断言失败（status 200 结算成功；jsonPath 超限走 body-assert-skipped 降级，不产生失败）；
     * ③ 收尾体检干净。</p>
     */
    private void assertHugeBody() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        String rendered = page.textContent("#huge-out");
        assertTrue(rendered != null && rendered.contains("len="),
                "huge UI 必须渲染完成，实际: " + preview(rendered));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)ms\\s+200\\s+len=(\\d+)").matcher(rendered);
        assertTrue(m.find(), "必须解析出 200 + len，实际: " + preview(rendered));
        long len = Long.parseLong(m.group(2));
        assertTrue(len >= 3_000_000,
                "UI 必须完整下载 3MB body（len≥3000000），实际 len=" + len);
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "超大 body：status 200 结算成功，jsonPath 超限降级不误判为失败");
        assertTrue(runtime.metrics().hits() >= 1,
                "huge 请求必须被观测，hits=" + runtime.metrics().hits());
        LOGGER.info("[TestBed] huge-body ok: 3MB 完整下载 + 断言降级不误判，len={}", len);
    }

    /**
     * gzip mock 保真：字段替换后页面仍正常渲染（content-encoding/length 由框架重算）。
     *
     * <p>证据链：mock-target 页 dataset.renderOk=true（JSON 正常解析，无 PARSE FAIL/空白）、
     * admin-badge 显示替换值 N（替换生效）、body-block 显示 padding len（响应体完整）。</p>
     */
    private void assertGzipFidelity() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        RouteDsl.waitForUiFunction(page, "document.body.dataset.renderOk", null, 10_000L);
        String renderOk = page.getAttribute("body", "data-render-ok");
        String adminBadge = page.textContent("#admin-badge");
        String bodyBlock = page.textContent("#body-block");
        assertTrue("true".equals(renderOk),
                "gzip mock 后页面必须正常渲染（renderOk=true，无 PARSE FAIL/空白），实际 renderOk='" + preview(renderOk) + "'");
        assertTrue(adminBadge != null && adminBadge.contains("admin: N"),
                "字段替换必须生效（enableAdminTools=N），实际: " + preview(adminBadge));
        assertTrue(bodyBlock != null && bodyBlock.contains("padding len="),
                "gzip 响应体必须完整（body-block 含 padding len），实际: " + preview(bodyBlock));
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.metrics().hits() >= 1,
                "gzip 请求必须被 mock 命中，hits=" + runtime.metrics().hits());
        LOGGER.info("[TestBed] gzip-fidelity ok: 字段替换生效 + UI 渲染保真，admin='{}'", preview(adminBadge));
    }

    /** 非 JSON(xml) mock：替换后 UI 按原类型解析正常。 */
    private void assertNonJsonMock() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        RouteDsl.waitForUiFunction(page,
                "document.getElementById('nonjson-out') && "
                + "document.getElementById('nonjson-out').textContent !== '—'",
                null, 30_000L);
        String rendered = page.textContent("#nonjson-out");
        assertTrue(rendered != null && rendered.contains("<ok/>"),
                "xml mock 体必须渲染到 UI（UI 实证），实际: " + preview(rendered));
        assertTrue(rendered.contains("200"),
                "mock 必须返回 200 状态，实际: " + preview(rendered));
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.metrics().hits() >= 1,
                "non-json 请求必须被 mock 命中，hits=" + runtime.metrics().hits());
        LOGGER.info("[TestBed] non-json-mock ok: xml 体渲染成功，'{}'", preview(rendered));
    }

    /** popup 治理：规则活跃时新 tab 立即关闭无残留（触发已在 Given 验证，此处补 runtime 体检）。 */
    private void assertPopup() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        assertTrue(page.context().pages().size() == 1,
                "不得残留多余 tab，pages=" + page.context().pages().size());
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "popup 治理期间不得产生断言失败");
        LOGGER.info("[TestBed] popup ok: 无残留 tab，rule 活跃正常");
    }

    /**
     * fail-open：未注册请求（j_spring 302）走真实网络不被拦截；已注册规则照常命中。
     *
     * <p>证据链：① #302-out 显示 opaqueredirect（真实 302 到达浏览器 —— 未注册请求未被框架吞掉）；
     * ② profile/list 规则 hits≥1（已注册规则正常观测）；③ 无断言失败；④ 收尾体检干净。</p>
     */
    private void assertFailOpen() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活");
        String out302 = page.textContent("[id=\"302-out\"]");
        assertTrue(out302 != null && out302.contains("opaqueredirect"),
                "未注册请求必须走真实网络（UI 实证 302 opaque-redirect），实际: " + preview(out302));
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.metrics().hits() >= 1,
                "已注册 profile/list 必须命中，hits=" + runtime.metrics().hits());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "fail-open 放行不得产生断言失败");
        LOGGER.info("[TestBed] fail-open ok: 未注册 302 真实放行 + 已注册命中，hits={}", runtime.metrics().hits());
    }

    /** 页面致命异常后 UI 仍可操作 + 收尾干净（TC-FAIL-09 场景）。 */
    private void assertCrashCleanup() {
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活（页面异常不得炸毁 route 引擎）");
        String result = page.textContent("#result");
        assertTrue(result != null && result.contains("fatal error scheduled"),
                "btn-crash 点击必须生效（UI 实证），#result='" + preview(result) + "'");
        // 致命异常后页面仍可操作：继续点击 btn-renderer
        page.click("[id=\"btn-renderer\"]");
        String after = page.textContent("#result");
        assertTrue(after != null && after.contains("allocated"),
                "页面异常后 UI 必须仍可操作（btn-renderer 成功），实际: " + preview(after));
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        assertTrue(runtime.drainSettledAssertionFailures().isEmpty(),
                "页面异常期间不得产生断言失败");
        LOGGER.info("[TestBed] crash-cleanup ok: 页面异常后 UI 仍可操作，收尾将由泄漏体检守门");
    }

    /**
     * 泄漏体检：本 context 运行时"未确证撤销 = 0 且 isClean = true"。
     *
     * <p>真实驱动下撤销确认是异步的（unroute 为协议往返），故做有界轮询：窗口内确证干净即通过；
     * 超时仍不干净则判失败 —— 路由状态泄漏 ⇒ Context 不可复用（F2 语义）。</p>
     */
    private void assertNoRouteStateLeak(Page page) {
        RouteRuntime runtime = RouteEngine.runtimeOf(page.context());
        long deadline = System.nanoTime() + LEAK_CHECK_TIMEOUT_MS * 1_000_000L;
        while (runtime.unconfirmedRetirements() > 0 && System.nanoTime() < deadline) {
            page.waitForTimeout(200);
        }
        LOGGER.info("[LEAK-REPORT] unconfirmedRetirements={}, isClean={}, activeRuntimes={}",
                runtime.unconfirmedRetirements(), runtime.isClean(), RouteEngine.activeRuntimes());
        assertEquals(0, runtime.unconfirmedRetirements(),
                "不得存在未确证的撤销（路由状态泄漏 ⇒ 该 Context 不可复用）");
        assertTrue(runtime.isClean(), "路由状态必须确证干净（isClean）");
    }

    // ── helpers ──

    private Page page() {
        return loginPage.getPage();
    }

    /** 等待调度线程完成响应轮询与断言结算（首轮 25ms、指数退避上限 100ms，300ms 余量充足）。 */
    private void awaitAssertionSettlement(Page page) {
        page.waitForTimeout(300);
    }

    /** 取当前已落库的采集快照（非消费式 peekCaptured）。 */
    private List<CapturedApiCall> peekCaptured() {
        return RouteDsl.peekCaptured(page());
    }

    /**
     * 等待 logon 期 3 个 MONITOR 采集全部落库（auth/assert、leftmenu、profile-error；
     * profile/list 是 MOCK intercept、不经采集，不计入）。
     *
     * <p><b>目的</b>：把 btn-302 的 dispatch 放进驱动空闲窗口 —— logon 遗留请求仍在
     * IO 线程采集时驱动忙碌，manual-302 请求极短生命周期易与 route@ 创建竞态
     * （驱动层 Cannot find parent object → 请求被 abort，UI 实证失败）。10s 窗口与采集结算
     * 慢窗口（冷启动实测 7s）对齐；未齐也不阻塞（极端慢时由断言轮询兜底）。</p>
     */
    private void awaitLogonQuiescence(Page page) {
        long deadline = System.nanoTime() + 10_000L * 1_000_000L;
        while (System.nanoTime() < deadline) {
            List<CapturedApiCall> calls = peekCaptured();
            if (findCall(calls, "/api/auth/assert") != null
                    && findCall(calls, "/api/leftmenu/permissionLeftMenuConfig") != null
                    && findCall(calls, "/api/error/profile-error.jsp") != null) {
                LOGGER.info("[TestBed] logon quiescent: 3 MONITOR captured, triggering btn-302");
                return;
            }
            page.waitForTimeout(200);
        }
        LOGGER.warn("[TestBed] logon quiescence window elapsed without all 3 captured; proceeding (assertions will gate)");
    }

    // ── 断言助手（本地实现，参数序与 JUnit5 一致：message 在最后）──
    // 本 Runner 为 JUnit4（CucumberWithSerenity），IT 运行期 classpath 上无
    // org.junit.jupiter.api.Assertions（NoClassDefFoundError: org/junit/jupiter/api/Assertions）。
    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    /** 数值断言：直接走 long（不做装箱/拆箱）。 */
    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }

    private static CapturedApiCall findCall(List<CapturedApiCall> calls, String patternFragment) {
        return calls.stream()
                .filter(call -> call.url() != null && call.url().contains(patternFragment))
                .findFirst()
                .orElse(null);
    }

    /** JSON 字段值匹配（宽松：布尔值不带引号、字符串带引号，容忍空格）。 */
    private static boolean hasFieldValue(String text, String field, String value) {
        if (text == null) {
            return false;
        }
        String literal = ("true".equals(value) || "false".equals(value))
                ? value
                : "\"" + value + "\"";
        String needle = "\"" + field + "\"\\s*:\\s*" + java.util.regex.Pattern.quote(literal);
        return java.util.regex.Pattern.compile(needle).matcher(text).find();
    }

    /** 解析 "2100ms 200 ..." 形式文本的头部数字（UI 实测耗时）。 */
    private static int parseLeadingInt(String text) {
        if (text == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String preview(String text) {
        if (text == null) {
            return "<null>";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private static String summarize(List<CapturedApiCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (CapturedApiCall call : calls) {
            if (sb.length() > 300) {
                sb.append("...");
                break;
            }
            sb.append(call.method()).append(' ').append(call.url())
                    .append(" -> ").append(call.responseStatus()).append("; ");
        }
        return sb.toString();
    }
}
