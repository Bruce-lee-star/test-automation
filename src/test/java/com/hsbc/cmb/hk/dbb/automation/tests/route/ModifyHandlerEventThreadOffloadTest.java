package com.hsbc.cmb.hk.dbb.automation.tests.route;

import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureContext;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteHandleType;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.rule.RouteRule;
import com.hsbc.cmb.hk.dbb.automation.framework.route.handler.ModifyHandler;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评审 F-08 / P1-4 回归网：{@link ModifyHandler} 的观测<b>必须下沉到工作线程</b>，
 * Playwright 事件线程不得同步等待真实响应。
 *
 * <p><b>实现基线（A-05）</b>：观测不再走 {@code page.waitForResponse}，改为
 * {@code req.existingResponse()} 本地轮询（{@code modifyPollScheduler} 每 25ms 递归轮询），
 * {@code route.resume(opts)} 由 {@code RouteEngine.scheduleDeferred} 调度到延迟线程执行。
 * 本测试据此断言（2026-09-27 同步适配，原 waitForResponse 桩已随实现下线）：</p>
 * <ol>
 *   <li>{@code handle(...)} 在调用线程上<b>毫秒级返回</b>——事件线程零阻塞；</li>
 *   <li>{@code resume} 由守护调度线程执行，而非调用（事件）线程；</li>
 *   <li>在途计数协议：提交前事件线程已 {@code increment}（等待窗口内 {@code activeRequests==1}），
 *       任务结束必然回到 0（不泄漏计数、不永久阻塞 awaitCompletion）；</li>
 *   <li>多次 handle 均不阻塞调用线程，route 均被放行（恰好终结一次）。</li>
 * </ol>
 *
 * <p>用 Playwright 接口 mock 驱动，无需真实浏览器。</p>
 */
public class ModifyHandlerEventThreadOffloadTest {

    /** 事件线程允许的最大返回耗时：真实无阻塞时应为毫秒级，2000ms 阻塞必然超此阈值。 */
    private static final long EVENT_THREAD_BUDGET_MS = 1000;

    @Test
    public void handleReturnsImmediatelyAndObservesOnWorkerThread() throws Exception {
        Fixture fx = new Fixture();

        long started = System.currentTimeMillis();
        ModifyHandler.handle(fx.route, fx.rule, 0);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(elapsed < EVENT_THREAD_BUDGET_MS,
                "F-08：事件线程不得同步等待观测（实测 handle 耗时 " + elapsed + "ms）");

        // A-05：resume 由延迟调度线程执行（RouteEngine.scheduleDeferred → DelayScheduler）
        verify(fx.route, timeout(5000)).resume(any(Route.ResumeOptions.class));

        Thread resumed = fx.resumeThread.get();
        assertNotSame(Thread.currentThread(), resumed,
                "resume 必须在工作线程执行，而不是调用（事件）线程");
        assertTrue(resumed.isDaemon(), "调度线程应为守护线程，不得阻止 JVM 退出");
    }

    @Test
    public void activeRequestsIsIncrementedBeforeSubmitAndReleasedAfterObservation() throws Exception {
        Fixture fx = new Fixture();
        ApiCaptureContext ctx = ApiCaptureContext.forContext(fx.context);

        assertEquals(0, ctx.getActiveRequests(), "前置：无在途请求");

        ModifyHandler.handle(fx.route, fx.rule, 0);

        //  提交前已在事件线程递增：此后（观测尚在进行）不得出现 activeRequests==0 的空窗，
        //  否则 awaitCompletion 会在「已放行但观测未完成」时误判「全部完成」。
        assertEquals(1, ctx.getActiveRequests(),
                "F-08：观测在途期间必须对 awaitCompletion 可见（提交前同步递增）");

        assertTrue(waitUntilZero(ctx, 5000), "观测结束后在途计数必须回到 0（配对递减，不得泄漏）");
    }

    @Test
    public void repeatedHandlesUseTheSameExecutorAndNeverBlockTheCaller() throws Exception {
        Fixture first = new Fixture();
        long s1 = System.currentTimeMillis();
        ModifyHandler.handle(first.route, first.rule, 0);
        assertTrue(System.currentTimeMillis() - s1 < EVENT_THREAD_BUDGET_MS,
                "F-08：首次 handle 不得阻塞调用线程");
        verify(first.route, timeout(5000)).resume(any(Route.ResumeOptions.class));

        Fixture second = new Fixture();
        long s2 = System.currentTimeMillis();
        ModifyHandler.handle(second.route, second.rule, 0);
        assertTrue(System.currentTimeMillis() - s2 < EVENT_THREAD_BUDGET_MS,
                "F-08：重复 handle 不得阻塞调用线程");
        verify(second.route, timeout(5000)).resume(any(Route.ResumeOptions.class));
    }

    /** 说明用断言：mock 的 request/frame/page 链自洽（防止 fixture 静默失效使断言失去意义）。 */
    @Test
    public void fixtureWiresTheSamePageChainItStubs() {
        Fixture fx = new Fixture();
        assertSame(fx.page, fx.route.request().frame().page(), "mock 链应指向同一 Page 实例");
        assertEquals("GET", fx.route.request().method());
    }

    private static boolean waitUntilZero(ApiCaptureContext ctx, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ctx.getActiveRequests() == 0) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return ctx.getActiveRequests() == 0;
    }

    private static final class Fixture {
        private final BrowserContext context = mock(BrowserContext.class);
        private final Page page = mock(Page.class);
        private final Frame frame = mock(Frame.class);
        private final Request request = mock(Request.class);
        private final Route route = mock(Route.class);
        private final RouteRule rule = new RouteRule();

        private final AtomicReference<Thread> resumeThread = new AtomicReference<>();

        private Fixture() {
            rule.setUrlPattern("/demo/api/users");
            rule.setType(RouteHandleType.MODIFY);

            when(request.url()).thenReturn("http://localhost:8888/demo/api/users");
            when(request.method()).thenReturn("GET");
            when(request.headers()).thenReturn(Collections.emptyMap());
            when(frame.page()).thenReturn(page);
            when(request.frame()).thenReturn(frame);
            when(route.request()).thenReturn(request);
            when(page.context()).thenReturn(context);
            when(page.isClosed()).thenReturn(false);
            // A-05：观测经 req.existingResponse() 轮询，立即返回 mock Response（轮询即刻完成，避免 30s 超时）
            when(request.existingResponse()).thenReturn(mock(Response.class));
            // resume 由 RouteEngine.scheduleDeferred（延迟线程）调度执行：记录其线程以断言「不在事件线程」
            doAnswer(invocation -> {
                resumeThread.set(Thread.currentThread());
                return null;
            }).when(route).resume(any(Route.ResumeOptions.class));
        }
    }
}
