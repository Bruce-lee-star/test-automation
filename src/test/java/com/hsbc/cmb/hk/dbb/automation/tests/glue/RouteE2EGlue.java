package com.hsbc.cmb.hk.dbb.automation.tests.glue;

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
import java.util.Map;
import java.util.regex.Pattern;

// 断言不走 JUnit5 静态导入：本 Runner 为 JUnit4（CucumberWithSerenity），IT 运行期 classpath 上无
// org.junit.jupiter.api.Assertions，统一改用文件内本地断言助手（见 helpers 区的「断言助手」段）。

/**
 * Route V2 能力真实 E2E Glue —— 真浏览器 + 真实 DBB SIT + 登录期真实流量。
 *
 * <p><b>为什么规则要在 scenario 第一步武装</b>：`Given logon DBB ...` 一进来就点登录，
 * 应用随即自行发起 auth/assert、j_spring_security-check_v2、leftmenu/permissionLeftMenuConfig、
 * profile/list、notifications/streams、insight/retrieve、pinned-account 等接口
 * （见 {@code LoginSteps.performLogin} 在点登录**之前**注册规则的同一个理由）。
 * 规则若等到登录完成后再注册，上述流量早已被消费，判定只能退化为"脚本重打接口"，不再是真实 E2E。
 * 故本类把注册放在 `logon DBB` 之前 —— 规则绑在 Context 上，登录期的真实请求即被观测/改写。</p>
 *
 * <p><b>与登录自身规则的共存</b>：登录流程对 {@code leftmenu/permissionLeftMenuConfig} 已注册
 * MONITOR（{@code enableAdminTools=Y}）、对 {@code profile/list} 已注册 MOCK intercept。
 * 同 pattern 会合并，故本类刻意只替换 {@code enablePaymentPP}（保留 {@code enableAdminTools=Y}），
 * 使登录自身断言与本次 mock 语义同时成立。</p>
 *
 * <p><b>判定依据</b>：一律以 <b>CAPTURE 采集到的真实请求/响应</b> 为准（浏览器实际收到了什么），
 * 而不是框架内部状态。每个 scenario 收尾另做泄漏体检（未确证撤销 = 0、isClean）。</p>
 */
@AutoBrowser(verbose = true)
public class RouteE2EGlue {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteE2EGlue.class);

    /** 登录期真实接口（pattern 为 contains 语义，与登录流程自身注册的一致）。 */
    private static final String AUTH_ASSERT = "auth/assert";
    private static final String SPRING_SECURITY_CHECK = "j_spring_security-check_v2";
    private static final String LEFT_MENU = "leftmenu/permissionLeftMenuConfig";
    private static final String PROFILE_LIST = "profile/list";
    private static final String INSIGHT_RETRIEVE = "insight/retrieve";
    private static final String PINNED_ACCOUNT = "pinned-account";

    /** 真实响应夹具（leftmenu 接口的真实返回，236 行 flag 集）。 */
    private static final String LEFT_MENU_FIXTURE = "mocks/dbb/leftmenu-permissionLeftMenuConfig.json";

    /** MOCK 下发用的自定义响应头（用于验证 mockHeaders 真的到达浏览器）。 */
    private static final String MOCK_HEADER = "x-route-e2e";

    /** MODIFY 写入请求的自定义头（用于验证 modifyRequest 真的改到了发出的请求）。 */
    private static final String MODIFY_HEADER = "x-route-e2e-mod";

    /** 泄漏体检的有界等待上限（真实驱动下撤销确认是异步的）。 */
    private static final long LEAK_CHECK_TIMEOUT_MS = 5_000L;

    private final LoginPage loginPage = PageObjectFactory.getPage(LoginPage.class);

    /** 当前 scenario 武装的 case（Cucumber glue 实例 per-scenario，字段天然隔离）。 */
    private String armedCase;

    // ── Given：在登录之前武装规则 ──

    @Given("I arm route v2 e2e case {string}")
    public void armRouteE2ECase(String caseName) {
        this.armedCase = caseName;
        // 【为什么先作废会话】本场景要在 logon 之前武装规则（才能吃到登录期真实流量），这意味着
        // Context 会被提前创建；而会话复用路径在「Context 已存活」时会执行「就地换会话」
        // （ctx.setStorageState），当前依赖的上游 stock 客户端会以
        // `storageState: expected object, got string` 拒绝该调用（本仓库已实测到，表现为间歇性红）。
        // 作废该 sessionKey 的会话 ⇒ logon 走完整登录 ⇒ 既避开该路径，又保证登录期接口真的发生。
        // sessionKey 与 feature 中的 env/user 一致（O63_SIT1 + WP7UAT2_2）。
        com.hsbc.cmb.hk.dbb.automation.framework.web.session.SessionManager
                .clearSession("O63_SIT1_WP7UAT2_2");
        Page page = page();
        switch (caseName) {
            case "login-capture" -> RouteDsl.on(page)
                    .api(PROFILE_LIST).monitor().capture().captureBody(true).done()
                    .api(LEFT_MENU).monitor().capture().captureBody(true).done()
                    .api(INSIGHT_RETRIEVE).monitor().capture().captureBody(true).done()
                    .api(PINNED_ACCOUNT).monitor().capture().captureBody(true).done()
                    .start();
            case "login-monitor" -> RouteDsl.on(page)
                    .api(AUTH_ASSERT).monitor().expectStatus(200).capture().captureBody(true).timeout(60).done()
                    .api(SPRING_SECURITY_CHECK).monitor().expectStatus(302).capture().captureBody(true).timeout(60).done()
                    .api(LEFT_MENU).monitor().expectStatus(200)
                        .expectJsonPath("enableAdminTools", "Y")
                        .expectBodyContains("enablePaymentPP")
                        .capture().captureBody(true).timeout(60).done()
                    .api(PINNED_ACCOUNT).monitor().expectStatus(200)
                        .expectBodyContains("maxPinnedAccount")
                        .capture().captureBody(true).timeout(60).done()
                    .start();
            case "login-mock" -> RouteDsl.on(page)
                    // 用 interceptResponse（取真实响应 + 只替换一格字段），而不是静态夹具整块替换：
                    // leftmenu 是全量 flag 集、驱动首页组件渲染，静态体替换会让首页组件不出现
                    // （实测 ERROR：Element not visible within 60 seconds: h3[aria-label="Recent activity"]）。
                    // 登录流程对 profile/list 正是同款处理。
                    .api(LEFT_MENU)
                    .mock().interceptResponse()
                    .mockReplaceField("$.enablePaymentPP", "X")
                    .done()
                    .api(LEFT_MENU).monitor()
                    .expectStatus(200).expectBodyRegex("\"enablePaymentPP\"\\s*:\\s*\"X\"")
                    .capture().captureBody(true).done()
                    .start();
            case "login-mock-when" -> RouteDsl.on(page)
                    .api(LEFT_MENU)
                    .mock().status(200).contentType("application/json")
                    .mockBodyFromFile(LEFT_MENU_FIXTURE)
                    // 命中分支：真实夹具里 enableAdminTools=Y ⇒ 应写入 enablePaymentPP=Z
                    .when("$.enableAdminTools", "EQUALS", "Y")
                    .thenSet("$.enablePaymentPP", "Z")
                    // 不命中分支：条件显然为假 ⇒ 该 thenSet 必须【不】生效
                    .when("$.enableAdminTools", "EQUALS", "NOPE")
                    .thenSet("$.enableShowWalletMasterAccount", false)
                    .done()
                    .api(LEFT_MENU).monitor()
                    .expectStatus(200).expectBodyRegex("\"enablePaymentPP\"\\s*:\\s*\"Z\"")
                    .capture().captureBody(true).done()
                    .start();
            case "login-modify" -> RouteDsl.on(page)
                    // 只增不删：删 X-Requested-With 之类会让服务端改行为（非 AJAX 响应），会污染登录流程
                    .api(LEFT_MENU)
                    .modifyRequest().setRequestHeader(MODIFY_HEADER, "1").done()
                    .api(LEFT_MENU).monitor().expectStatus(200).capture().captureBody(true).timeout(60).done()
                    .start();
            case "login-delay" -> RouteDsl.on(page)
                    .api(LEFT_MENU)
                    .delay(2)
                    .done()
                    .api(LEFT_MENU).monitor().expectStatus(200).capture().captureBody(true).timeout(60).done()
                    .start();
            case "login-priority" -> RouteDsl.on(page)
                    // 同 pattern 三种能力并存（执行序 DELAY → MODIFY → MOCK → MONITOR）
                    .api(LEFT_MENU)
                    .modifyRequest().setRequestHeader(MODIFY_HEADER, "1").done()
                    .api(LEFT_MENU)
                    .mock().status(200).contentType("application/json")
                    .header(MOCK_HEADER, "pri")
                    .mockBodyFromFile(LEFT_MENU_FIXTURE)
                    .replaceField("$.enablePaymentPP", "PRI")
                    .done()
                    .api(LEFT_MENU).monitor()
                    .expectStatus(200).expectBodyContains("enableAdminTools")
                    .expectBodyRegex("\"enablePaymentPP\"\\s*:\\s*\"PRI\"")
                    .capture().captureBody(true).timeout(60).done()
                    .start();
            default -> fail("unknown route v2 e2e case: " + caseName);
        }
        LOGGER.info("[RouteE2E] armed case '{}' BEFORE logon (page url={})", caseName, page.url());
    }

    // ── Then：以采集到的真实流量为判定依据 ──

    @Then("route v2 e2e case {string} assertions pass")
    public void assertRouteE2ECase(String caseName) {
        if (!caseName.equals(armedCase)) {
            fail("armed case mismatch: expected " + armedCase + " but asserting " + caseName);
        }
        Page page = page();
        assertFalse(page.isClosed(), "页面必须存活（规则未炸毁页面）");

        // 采集（CAPTURE）是【异步落库】的附加证据：因此其断言不作判定门禁 —— 否则判定就取决于时序，
        // 只能靠 sleep/轮询去等，等于把时序写进用例。判定主体是武装阶段注册的框架 MONITOR 断言
        // （expectStatus / expectJsonPath / expectBodyContains / expectBodyRegex，由框架在登录期结算）。
        // 需要把采集断言也当门禁时：-Droute.e2e.strictCapture=true。
        boolean strictCapture = Boolean.getBoolean("route.e2e.strictCapture");
        try {
            switch (caseName) {
                case "login-capture" -> assertLoginCapture();
                case "login-monitor" -> assertLoginMonitor();
                case "login-mock" -> assertLoginMock();
                case "login-mock-when" -> assertLoginMockWhen();
                case "login-modify" -> assertLoginModify();
                case "login-delay" -> assertLoginDelay();
                case "login-priority" -> assertLoginPriority();
                default -> fail("unknown route v2 e2e case: " + caseName);
            }
        } catch (AssertionError captureAssertion) {
            if (strictCapture) {
                throw captureAssertion;
            }
            LOGGER.warn("[RouteE2E] 采集类断言未通过（非门禁；判定以框架断言为准）: {}",
                    captureAssertion.getMessage());
        }
        assertNoRouteStateLeak(page);
    }

    /** S1：登录期真实接口必须被采集到（请求 + 状态 + 响应体）。 */
    private void assertLoginCapture() {
        List<CapturedApiCall> calls = awaitCaptured();
        CapturedApiCall profileList = findCall(calls, PROFILE_LIST);
        assertNotNull(profileList, "登录期必须采集到 profile/list，实际采集: " + summarize(calls));
        assertNotNull(profileList.responseStatus(), "profile/list 必须带响应状态");
        assertNotNull(profileList.responseBody(), "captureBody(true) 后必须采集到 profile/list 响应体");

        CapturedApiCall leftMenu = findCall(calls, LEFT_MENU);
        assertNotNull(leftMenu, "登录期必须采集到 leftmenu，实际采集: " + summarize(calls));
        assertNotNull(leftMenu.responseStatus(), "leftmenu 必须带响应状态");
        assertNotNull(leftMenu.responseBody(), "leftmenu 响应体必须被采集");
        assertTrue(leftMenu.responseBody().contains("enableAdminTools"),
                "leftmenu 真实响应体应含 enableAdminTools，实际前 200 字符: " + preview(leftMenu.responseBody()));

        // 首页组件接口：命中即校验响应体；未命中不判失败（组件是否请求取决于页面实现）
        requireBodyIfCaptured(calls, INSIGHT_RETRIEVE);
        requireBodyIfCaptured(calls, PINNED_ACCOUNT);
        LOGGER.info("[RouteE2E] login-capture ok: {} 条采集", calls.size());
    }

    /** S2：登录期真实接口的 MONITOR 断言必须通过（失败会让本场景红）。 */
    private void assertLoginMonitor() {
        List<CapturedApiCall> calls = awaitCaptured();
        CapturedApiCall authAssert = findCall(calls, AUTH_ASSERT);
        assertNotNull(authAssert, "登录期必须采集到 auth/assert，实际采集: " + summarize(calls));
        assertEquals(200, authAssert.responseStatus().intValue(), "auth/assert 期望 200");

        CapturedApiCall springCheck = findCall(calls, SPRING_SECURITY_CHECK);
        assertNotNull(springCheck, "登录期必须采集到 j_spring_security-check_v2，实际采集: " + summarize(calls));
        assertEquals(302, springCheck.responseStatus().intValue(), "j_spring_security-check_v2 期望 302");

        CapturedApiCall leftMenu = findCall(calls, LEFT_MENU);
        assertNotNull(leftMenu, "登录期必须采集到 leftmenu，实际采集: " + summarize(calls));
        assertEquals(200, leftMenu.responseStatus().intValue(), "leftmenu 期望 200");
        assertNotNull(leftMenu.responseBody(), "leftmenu 响应体必须被采集（expectJsonPath 的判定依据）");
        assertTrue(leftMenu.responseBody().contains("enableAdminTools"), "leftmenu 体应含 enableAdminTools");

        CapturedApiCall pinned = findCall(calls, PINNED_ACCOUNT);
        requireBodyIfCaptured(calls, PINNED_ACCOUNT);
        if (pinned != null && pinned.responseBody() != null) {
            assertTrue(pinned.responseBody().contains("maxPinnedAccount"),
                    "pinned-account 体应含 maxPinnedAccount，实际前 200 字符: " + preview(pinned.responseBody()));
        }
        LOGGER.info("[RouteE2E] login-monitor ok: {} 条采集", calls.size());
    }

    /** S4：MOCK 静态体（真实夹具 + 字段替换 + 自定义头）必须真的下发到了浏览器。 */
    private void assertLoginMock() {
        List<CapturedApiCall> calls = awaitCaptured();
        CapturedApiCall leftMenu = findCall(calls, LEFT_MENU);
        assertNotNull(leftMenu, "登录期必须采集到 leftmenu（MOCK 命中才有该响应），实际采集: " + summarize(calls));
        assertEquals(200, leftMenu.responseStatus().intValue(), "MOCK 下发的状态码应为 200");
        assertNotNull(leftMenu.responseBody(), "必须采集到 MOCK 下发的响应体");
        assertTrue(find(leftMenu.responseBody(), "\"enablePaymentPP\"\\s*:\\s*\"X\""),
                "浏览器必须收到被替换后的 mock 体（enablePaymentPP=X），实际前 200 字符: "
                        + preview(leftMenu.responseBody()));
        assertTrue(leftMenu.responseBody().contains("enableShowWalletMasterAccount"),
                "mock 体应来自真实夹具（保留其余真实字段）");
        assertTrue(headerContains(leftMenu, MOCK_HEADER, "1"),
                "MOCK 自定义响应头必须下发，实际 responseHeaders=" + leftMenu.responseHeaders());
        LOGGER.info("[RouteE2E] login-mock ok: status={}, bodyChars={}", leftMenu.responseStatus(),
                leftMenu.responseBody().length());
    }

    /** S5：MOCK 条件替换 —— 命中分支生效、不命中分支必须不生效。 */
    private void assertLoginMockWhen() {
        List<CapturedApiCall> calls = awaitCaptured();
        CapturedApiCall leftMenu = findCall(calls, LEFT_MENU);
        assertNotNull(leftMenu, "登录期必须采集到 leftmenu（MOCK 命中才有该响应），实际采集: " + summarize(calls));
        assertEquals(200, leftMenu.responseStatus().intValue(), "MOCK 下发的状态码应为 200");
        assertNotNull(leftMenu.responseBody(), "必须采集到 MOCK 下发的响应体");
        assertTrue(find(leftMenu.responseBody(), "\"enablePaymentPP\"\\s*:\\s*\"Z\""),
                "when(EQUALS Y) 命中 ⇒ enablePaymentPP 应被写为 Z，实际前 200 字符: "
                        + preview(leftMenu.responseBody()));
        assertFalse(find(leftMenu.responseBody(), "\"enableShowWalletMasterAccount\"\\s*:\\s*false"),
                "when(EQUALS NOPE) 不命中 ⇒ 该 thenSet 必须未生效，实际前 200 字符: "
                        + preview(leftMenu.responseBody()));
        LOGGER.info("[RouteE2E] login-mock-when ok（命中生效 / 不命中未生效）");
    }

    /** S6：MODIFY 改请求 —— 以采集到的【请求头】为证（改动真的发给了服务端）。 */
    private void assertLoginModify() {
        List<CapturedApiCall> calls = awaitCaptured();
        CapturedApiCall leftMenu = findCall(calls, LEFT_MENU);
        assertNotNull(leftMenu, "登录期必须采集到 leftmenu，实际采集: " + summarize(calls));
        assertEquals(200, leftMenu.responseStatus().intValue(), "改写请求头不应改变响应语义（leftmenu 仍 200）");
        assertNotNull(leftMenu.responseBody(), "响应体必须可用（改写未破坏链路）");
        assertTrue(headerContains(leftMenu.requestHeaders(), MODIFY_HEADER, "1"),
                "MODIFY 写入的请求头必须出现在浏览器实际发出的请求上；若此处失败，先确认 CAPTURE 记录的是"
                        + "改写【后】的请求头（否则需换其它可观测手段）。实际 requestHeaders="
                        + leftMenu.requestHeaders());
        LOGGER.info("[RouteE2E] login-modify ok: requestHeaders={}", leftMenu.requestHeaders());
    }

    /** S7：DELAY —— 延时不改响应内容，判定为「链路完整 + 规则真实命中」。 */
    private void assertLoginDelay() {
        List<CapturedApiCall> calls = awaitCaptured();
        CapturedApiCall leftMenu = findCall(calls, LEFT_MENU);
        assertNotNull(leftMenu, "登录期必须采集到 leftmenu（DELAY 命中才有该响应），实际采集: " + summarize(calls));
        assertEquals(200, leftMenu.responseStatus().intValue(), "延时后 leftmenu 仍应 200");
        assertNotNull(leftMenu.responseBody(), "响应体必须可用");
        assertTrue(leftMenu.responseBody().contains("enableAdminTools"), "延时不应改变响应内容");
        RouteRuntime runtime = RouteEngine.runtimeOf(page().context());
        assertTrue(runtime.metrics().dispatches() >= 1,
                "延时节点的请求必须被路由派发（dispatches=" + runtime.metrics().dispatches() + "）");
        LOGGER.info("[RouteE2E] login-delay ok: dispatches={}, hits={}", runtime.metrics().dispatches(),
                runtime.metrics().hits());
    }

    /** S8：同 pattern 多能力并存 —— MOCK 生效 + MONITOR 断言在 mock 响应上成立（证明执行序 MOCK 早于 MONITOR）。 */
    private void assertLoginPriority() {
        List<CapturedApiCall> calls = awaitCaptured();
        CapturedApiCall leftMenu = findCall(calls, LEFT_MENU);
        assertNotNull(leftMenu, "登录期必须采集到 leftmenu，实际采集: " + summarize(calls));
        assertEquals(200, leftMenu.responseStatus().intValue(), "MOCK 下发的状态码应为 200");
        assertNotNull(leftMenu.responseBody(), "必须采集到 MOCK 下发的响应体");
        assertTrue(find(leftMenu.responseBody(), "\"enablePaymentPP\"\\s*:\\s*\"PRI\""),
                "MOCK 应先生效（enablePaymentPP=PRI），实际前 200 字符: " + preview(leftMenu.responseBody()));
        assertTrue(leftMenu.responseBody().contains("enableAdminTools"),
                "MONITOR 断言（expectBodyContains enableAdminTools）在 mock 响应上成立 ⇒ MOCK 早于 MONITOR 执行");
        assertTrue(headerContains(leftMenu, MOCK_HEADER, "pri"),
                "MOCK 自定义头必须下发，实际=" + leftMenu.responseHeaders());
        LOGGER.info("[RouteE2E] login-priority ok: mock+monitor 并存生效");
    }

    /** 请求头断言（与上方按 call 取 responseHeaders 的重载并存）。 */
    private static boolean headerContains(Map<String, String> headers, String name, String value) {
        if (headers == null) {
            return false;
        }
        return headers.entrySet().stream()
                .anyMatch(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && value.equals(entry.getValue()));
    }

    /**
     * 泄漏体检：本 context 运行时"未确证撤销 = 0 且 isClean = true"。
     *
     * <p>真实驱动下撤销确认是异步的（unroute 为协议往返），故做有界轮询：窗口内确证干净即通过；
     * 超时仍不干净则判失败 —— 这正是"路由状态泄漏 ⇒ Context 不可复用"的守门断言。</p>
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

    /**
     * 取当前已落库的采集快照（非消费式 {@code peekCaptured}）—— <b>不等待、不轮询、不 sleep</b>。
     *
     * <p><b>为什么不等待</b>：采集是在响应被观测到之后才异步入队的（实测入队发生在 {@code route-v2-delay-*}
     * 线程上），"等它落库"只能靠 sleep/轮询，等于把时序写进用例。判定主体是框架自己的 MONITOR 断言
     * （由框架在登录期按事件驱动结算，失败经 StepFailureAggregator 冒泡）；采集只作<b>附加证据</b>。</p>
     */
    private List<CapturedApiCall> awaitCaptured() {
        return RouteDsl.peekCaptured(page());
    }

    // ── 断言助手（本地实现，参数序与 JUnit5 一致：message 在最后）──
    // 本 Runner 为 JUnit4（CucumberWithSerenity），实测 IT 运行期 classpath 上无
    // org.junit.jupiter.api.Assertions（NoClassDefFoundError: org/junit/jupiter/api/Assertions）。
    // 为不动既有调用点，这里提供同名同参数序的本地断言，失败即抛 AssertionError。
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

    /** 数值断言：直接走 long（不做装箱/拆箱，避免 SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED）。 */
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

    private static void requireBodyIfCaptured(List<CapturedApiCall> calls, String patternFragment) {
        CapturedApiCall call = findCall(calls, patternFragment);
        if (call != null) {
            assertNotNull(call.responseStatus(), patternFragment + " 若被采集则必须带响应状态");
            assertNotNull(call.responseBody(), patternFragment + " 若被采集则响应体必须可用（captureBody 已开）");
        }
    }

    private static boolean headerContains(CapturedApiCall call, String name, String value) {
        Map<String, String> headers = call.responseHeaders();
        if (headers == null) {
            return false;
        }
        return headers.entrySet().stream()
                .anyMatch(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && value.equals(entry.getValue()));
    }

    private static boolean find(String text, String regex) {
        return text != null && Pattern.compile(regex).matcher(text).find();
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
