package com.hsbc.cmb.hk.dbb.automation.framework.web.session;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-1 收拢验证（修复 CD-1 后）：{@link SessionManager} 的 Feature 级 Session 状态现在为
 * <b>进程级、按 sessionKey 索引</b>的并发映射（原 {@code ThreadLocal} 实现跨线程不可见，
 * 导致 feature 模式复用永远不可达 —— 见 CD-1）。本测试验证：主线程标记的 feature session
 * 能被<b>其它线程可见</b>，即同 JVM 内跨线程/跨 Scenario 复用成立。
 */
public class SessionManagerConcurrencyTest {

    @Test
    public void featureSessionVisibleAcrossThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            SessionManager.markFeatureSessionRestored("key1", "https://home.example.com");
            assertTrue( SessionManager.isFeatureSessionRestored("key1"), "主线程应标记为已恢复");
            assertTrue( SessionManager.isAnyFeatureSessionRestored(), "主线程 isAny 应为 true");
            assertEquals( "https://home.example.com",  SessionManager.getFeatureHomeUrl(), "主线程应读到 homeUrl");

            Future<Boolean> otherRestored = pool.submit(() -> SessionManager.isFeatureSessionRestored("key1"));
            Future<Boolean> otherAny = pool.submit(SessionManager::isAnyFeatureSessionRestored);
            Future<String> otherHome = pool.submit(SessionManager::getFeatureHomeUrl);

            assertTrue( otherRestored.get(5, TimeUnit.SECONDS),
                    "其它线程应能看到主线程的恢复标记（CD-1 修复：进程级共享缓存）");
            assertTrue( otherAny.get(5, TimeUnit.SECONDS),
                    "其它线程应能看到主线程的 isAny 标记（CD-1 修复）");
            // CD-1 修复验收点：进程级恢复状态（带 sessionKey 的 isFeatureSessionRestored / isAny）跨线程可见。
            // getFeatureHomeUrl() 为「无参读取」，按 ThreadLocal CURRENT_FEATURE_KEY 定位本线程最近标记的 key
            //（P1 评审：并行 feature 各线程独立 key，避免互相覆盖）——跨线程读 homeUrl 不在 API 契约内。
            // 故此处改为断言：其他线程可通过带 key 维度确认恢复状态，homeUrl 由主线程（30 行）验证即可。
            assertNull( otherHome.get(5, TimeUnit.SECONDS),
                    "getFeatureHomeUrl() 按线程定位（ThreadLocal），其它线程未标记时预期为 null（设计语义）");
        } finally {
            SessionManager.resetFeatureSession();
            pool.shutdown();
        }
    }

    @Test
    public void featureSessionClearedOnReset() {
        SessionManager.markFeatureSessionRestored("key2", "https://home2.example.com");
        assertTrue(SessionManager.isFeatureSessionRestored("key2"), "标记后应可见");

        SessionManager.resetFeatureSession();
        assertFalse(SessionManager.isFeatureSessionRestored("key2"), "重置后该 key 应不可见");
        assertFalse(SessionManager.isAnyFeatureSessionRestored(), "重置后 isAny 应为 false");
        assertNull(SessionManager.getFeatureHomeUrl(), "重置后 homeUrl 应为 null");
    }
}
