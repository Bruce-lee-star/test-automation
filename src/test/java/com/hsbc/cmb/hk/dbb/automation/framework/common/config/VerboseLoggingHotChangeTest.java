package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 日志门控契约（2026-09-28 重写，取代原 P2-4 的 {@code syncRootLevel} 用例）。
 *
 * <p><b>为什么重写</b>：门控键由 {@code serenity.logging} 改为 {@code framework.verbose.logging} /
 * {@code framework.trace.logging} 后，"提级"只作用于<b>本项目包</b> logger
 * （{@code com.hsbc.cmb.hk.dbb.automation}），<b>绝不再动 logback root</b>。旧实现抬 root 会把
 * 第三方（cucumber / serenity / 驱动）的 DEBUG 一并放开 —— 实测导致日志刷屏；原
 * {@code VerboseLogging.syncRootLevel(String)} 已随该语义移除，故本类同步改写。</p>
 *
 * <p><b>仍然保留的两条旧契约</b>（改用新口径验证）：
 * <ol>
 *   <li><b>支持热改</b>：运行中改配置，下一次查询即生效；</li>
 *   <li><b>支持降级</b>：关闭 verbose/trace 时<b>还原首次触碰前的原始级别</b>
 *       （不硬编码 INFO、不抹掉 logback.xml 的配置）。</li>
 * </ol>
 * 另加一条新的回归哨兵：<b>root 级别不得被本机制改动</b>。</p>
 */
public class VerboseLoggingHotChangeTest {

    private static final String FRAMEWORK_VERBOSE = ConfigKeys.WEB_FRAMEWORK_VERBOSE_LOGGING.key();
    private static final String FRAMEWORK_TRACE = ConfigKeys.WEB_FRAMEWORK_TRACE_LOGGING.key();
    private static final String PROJECT_LOGGER = "com.hsbc.cmb.hk.dbb.automation";

    /** 本用例模拟的"logback.xml 自定义原始级别"（非 INFO，用于证明确实是还原而非硬编码）。 */
    private static final Level SIMULATED_ORIGINAL = Level.WARN;

    private String prevVerbose;
    private String prevTrace;
    private Level prevProjectLevel;
    private Level prevRootLevel;

    @Before
    public void setUp() {
        prevVerbose = System.getProperty(FRAMEWORK_VERBOSE);
        prevTrace = System.getProperty(FRAMEWORK_TRACE);
        prevProjectLevel = projectLevel();
        prevRootLevel = rootLevel();
        //  「原始级别」是进程内一次捕获 —— 复位后每个用例都能在已知原始级别下驱动同步逻辑
        VerboseLogging.resetForTests();
        setProjectLevel(SIMULATED_ORIGINAL);
    }

    @After
    public void tearDown() {
        //  显式写回原值（不用 clearProperty：清除会落到 Serenity 启动期快照，值不确定）
        System.setProperty(FRAMEWORK_VERBOSE, prevVerbose != null ? prevVerbose : "false");
        System.setProperty(FRAMEWORK_TRACE, prevTrace != null ? prevTrace : "false");
        VerboseLogging.resetForTests();
        setProjectLevel(prevProjectLevel); // null ⇒ 还原为"继承"（由 logback-test.xml 接管）
        setRootLevel(prevRootLevel);
    }

    @Test
    public void verboseRaisesProjectLoggerToDebug_rootUntouched() {
        Level rootBefore = rootLevel();

        System.setProperty(FRAMEWORK_VERBOSE, "true");
        System.setProperty(FRAMEWORK_TRACE, "false");

        assertTrue("framework.verbose.logging=true ⇒ 详细日志开", VerboseLogging.isVerboseEnabled());
        assertEquals("verbose 应把本项目包 logger 提升到 DEBUG", Level.DEBUG, projectLevel());
        assertEquals("root 级别绝不能被改动（旧实现抬 root 会把第三方 DEBUG 一并放开）", rootBefore, rootLevel());
    }

    @Test
    public void hotChangeDisableRestoresOriginalProjectLevel() {
        System.setProperty(FRAMEWORK_VERBOSE, "true");
        System.setProperty(FRAMEWORK_TRACE, "false");
        assertTrue(VerboseLogging.isVerboseEnabled());
        assertEquals("前置：verbose 已提级", Level.DEBUG, projectLevel());

        System.setProperty(FRAMEWORK_VERBOSE, "false");

        assertFalse("热改：关闭后查询必须立即返回 false", VerboseLogging.isVerboseEnabled());
        assertEquals("关闭 verbose 必须还原**原始级别**（不得硬编码 INFO；不得永久停在 DEBUG）", SIMULATED_ORIGINAL, projectLevel());
    }

    @Test
    public void traceIsAppliedAndDowngraded() {
        System.setProperty(FRAMEWORK_VERBOSE, "false");
        System.setProperty(FRAMEWORK_TRACE, "true");
        assertTrue(VerboseLogging.isTraceEnabled());
        assertEquals("trace 应把本项目包 logger 提升到 TRACE", Level.TRACE, projectLevel());

        System.setProperty(FRAMEWORK_TRACE, "false");

        assertFalse("热改：关闭 trace 后查询必须立即返回 false", VerboseLogging.isTraceEnabled());
        assertEquals("关闭 trace 应还原原始级别（热改可降级）", SIMULATED_ORIGINAL, projectLevel());
    }

    private static Level projectLevel() {
        return ((Logger) LoggerFactory.getLogger(PROJECT_LOGGER)).getLevel();
    }

    private static Level rootLevel() {
        return ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).getLevel();
    }

    private static void setProjectLevel(Level level) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(PROJECT_LOGGER).setLevel(level);
    }

    private static void setRootLevel(Level level) {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(level);
    }
}
