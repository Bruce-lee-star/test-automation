package com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureLifecycle;
import com.hsbc.cmb.hk.dbb.automation.framework.route.core.capture.ApiCaptureManager;

/**
 * 回归护盾：锁定 {@link ApiCaptureLifecycle#start(Page)} 的 Page 级监听器<b>幂等注册</b>，
 * 并锁定「<b>绝不订阅 {@code page.onResponse}</b>」。
 *
 * <p><b>背景</b>：原实现会在 Page 启动时注册 {@code page.onResponse} 做「全局兜底被动捕获」，
 * 该订阅是驱动侧 {@code Object doesn't exist: response@…} 竞态的唯一触发源
 * （Playwright 在 {@code BrowserContextImpl.handleEvent} 的 response 分支解析已被回收的
 * {@code response@} 句柄时抛异常，且发生在业务 lambda 之前、无法拦截，会污染同连接在途调用）。
 * 该通道已于 2026-09-28 <b>整体删除</b>，响应侧观测改走 route 通道
 * （{@code Request#existingResponse()}）。
 *
 * <p>本测试用 Mockito 纯单测（mock Playwright {@code Page}/{@code BrowserContext}），
 * 不依赖浏览器运行时；与 {@link ApiCaptureLifecycle} 同包以访问其 package-private 静态方法。
 */
public class ApiCaptureLifecycleListenerTest {

    private Page page;
    private BrowserContext ctx;

    @BeforeEach
    public void setUp() {
        // 采集总开关保持开启，确保 start(page) 走到监听器注册分支
        ApiCaptureManager.setApiCaptureEnabled(true);
        page = mock(Page.class);
        ctx = mock(BrowserContext.class);
        when(page.context()).thenReturn(ctx);
    }

    @AfterEach
    public void tearDown() {
        // 清理静态会话集合，避免污染同包其它单测（如 activePageCount_empty_returns0）
        if (page != null) {
            ApiCaptureLifecycle.stop(page);
        }
        // 复位单例存储，避免按 url 隔离的断言跨测试残留
        ApiCaptureManager.getInstance().endApiCapture();
    }

    @Test
    public void start_samePageRepeatedly_registersCloseListenerOnlyOnce() {
        //  关键不变量：同一 Page 多次 start，Page 级 onClose 仅注册一次（幂等），
        //  否则会在页面关闭时重复 detach，且监听器累积至页面关闭才解绑
        ApiCaptureLifecycle.start(page);
        ApiCaptureLifecycle.start(page);
        ApiCaptureLifecycle.start(page);

        verify(page, times(1)).onClose(any());
        // Context 级关闭钩子同样幂等（复用 CONTEXT_CLOSE_REGISTERED 的 putIfAbsent 保护）
        verify(ctx, times(1)).onClose(any());
    }

    @Test
    public void stopThenStart_reRegistersCloseListener() {
        ApiCaptureLifecycle.start(page);   // 注册 1 次
        ApiCaptureLifecycle.stop(page);    // 清理注册标记，允许重注册
        ApiCaptureLifecycle.start(page);   // re-attach：应再次挂上监听器

        //  关键不变量：stop 后 start 能重新注册，否则页面 re-attach 后会话清理静默失效
        verify(page, times(2)).onClose(any());
    }

    @Test
    public void responseListenerIsNeverRegistered() {
        ApiCaptureLifecycle.start(page);

        //  被动捕获通道已整体删除：任何路径下都不得订阅 response 事件
        verify(page, never()).onResponse(any());
    }
}
