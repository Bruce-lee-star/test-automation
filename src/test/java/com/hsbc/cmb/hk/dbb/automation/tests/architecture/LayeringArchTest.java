package com.hsbc.cmb.hk.dbb.automation.tests.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构分层门禁（A-3 补充，主链路分层覆盖）：固化「地基不向上依赖、主代码不耦合测试框架、主代码不用忙等」三条约束。
 *
 * <p>评审 doc13《A-3》要求新增 {@code LayeringArchTest}，覆盖 5 条规则：
 * <ul>
 *   <li><b>core 不向上依赖</b>（本类 {@link #coreMustNotDependUpward}）：{@code framework.common} 不得依赖
 *       {@code framework.web}/{@code framework.route}/{@code framework.api} 等上层模块。</li>
 *   <li><b>route 不依赖 web</b>：已由 {@code ArchitectureTest#routeMustNotDependOnWeb}（L6）覆盖，本类不复述。</li>
 *   <li><b>无循环依赖</b>：已由 {@code ArchitectureTest#frameworkSlicesMustBeFreeOfCycles}（G1）覆盖，本类不复述。</li>
 *   <li><b>主代码不得依赖 JUnit 5</b>（{@link #frameworkCodeMustNotDependOnJunit5}）：全仓统一 JUnit 4（runner 侧 Serenity CucumberWithSerenity 本就是 JUnit 4），本规则防止主代码误引入 jupiter / platform API。</li>
 *   <li><b>主代码不调用 Thread.sleep</b>（本类 {@link #frameworkCodeMustNotCallThreadSleep}）：框架主代码不得忙等，
 *       一律走 Playwright 原生等待（{@code page.waitFor*}/{@code Locator.waitFor*}）或 {@code PageWaits} 退避。</li>
 * </ul>
 *
 * <p>放置于 test-automation 而非 core 模块：分层规则需跨模块可见所有 framework 类，
 * 仅 test-automation 的测试 classpath 同时包含全部模块编译产物（core 单测 classpath 看不到 web/route 类，
 * 「core 不向上依赖」会退化为无对象可查、形同虚设）。故与 {@code ArchitectureTest} 同包，统一在此门禁。</p>
 */
public class LayeringArchTest {

    private static final String BASE_PACKAGE = "com.hsbc.cmb.hk.dbb.automation.framework";

    /** 测试代码根包（步骤层门禁需扫描测试类，故不能加 {@code DoNotIncludeTests}）。 */
    private static final String TEST_BASE_PACKAGE = "com.hsbc.cmb.hk.dbb.automation";

    /**
     * 地基不向上依赖（A-3）：{@code framework.common} 作为最底层地基，不得反向依赖任何上层模块
     * （web / route / api）。{@code framework.common.reporting} 属 common 自身子包，不算向上依赖，故不纳入目标。
     * <p>说明：{@code ArchitectureTest} 已有 {@code commonMustNotDependOnWeb}(L2) 与
     * {@code commonMustNotDependOnApi}(L4) 两条针对性规则；本规则以「common 整体不向上」做总闸，
     * 覆盖尚未显式列出的新上层（如 route），防止后续引入 common→route 越过层。</p>
     */
    @Test
    public void coreMustNotDependUpward() {
        noClasses()
                .that().resideInAPackage("..framework.common..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("..framework.web..")
                                .or(JavaClass.Predicates.resideInAPackage("..framework.route.."))
                                .or(JavaClass.Predicates.resideInAPackage("..framework.api..")))
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

        /**
     * 涓讳唬鐮佷笉寰椾緷璧?JUnit 5锛堝洖褰掑畧鍗紝2026-10 鍙ｅ緞锛夛細鍏ㄤ粨缁熶竴 JUnit 4 鈥斺€?runner 渚?
     * 锛圫erenity 鐨?{@code CucumberWithSerenity}锛夋湰灏辨槸 JUnit 4锛屽弻寮曟搸骞跺瓨鍙甫鏉?surefire provider
     * 鎶夋嫨涓?junit-platform 鐗堟湰瀵归綈鎴愭湰銆傛湰瑙勫垯闃叉涓讳唬鐮侊紙璇級閲嶆柊寮曞叆 jupiter / platform API銆?
     */
    @Test
    public void frameworkCodeMustNotDependOnJunit5() {
        noClasses()
                .that().resideInAPackage("..framework..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage("..org.junit.jupiter..")
                                .or(JavaClass.Predicates.resideInAPackage("..org.junit.platform..")))
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /**
     * 主代码不忙等（A-3）：框架生产代码不得调用 {@link Thread#sleep(long)} / {@link Thread#sleep(long, int)}。
     * <p>忙等既不可观测（无日志、无超时上界）又挤占并发度；框架统一走 Playwright 原生等待
     * （{@code page.waitFor*}/{@code Locator.waitFor*}）或 {@code PageWaits} 的带抖动退避。
     * 本规则仅扫主代码（{@code DoNotIncludeTests}），测试代码可保留受控的等待用于同步断言。</p>
     */
    @Test
    public void frameworkCodeMustNotCallThreadSleep() {
        noClasses()
                .that().resideInAPackage("..framework..")
                .should().callMethod(Thread.class, "sleep", long.class)
                .check(new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages(BASE_PACKAGE));
    }

    /**
     * 步骤层不忙等（B-4）：{@code tests} 包下以 {@code Steps} 结尾的步骤类不得直接调用
     * {@link Thread#sleep(long)}/{@link Thread#sleep(long, int)}。
     * <p>步骤层应以业务语义表达等待——有界轮询走 {@code tests.utils.AsyncWaits}，页面等待走
     * Playwright 自动等待；硬等待既不可观测（无超时语义）又易 flaky。
     * 受控等待允许集中在 {@code AsyncWaits}（不以 {@code Steps} 结尾，不在本禁令范围），
     * 与 {@link #frameworkCodeMustNotCallThreadSleep} 的「测试代码可保留受控等待」原则一致。</p>
     * <p>本规则<b>必须扫描测试类</b>，故不加 {@code DoNotIncludeTests}，单独以测试根包导入。</p>
     */
    @Test
    public void stepsMustNotCallThreadSleep() {
        noClasses()
                .that(JavaClass.Predicates.resideInAPackage("..automation.tests..")
                        .and(JavaClass.Predicates.simpleNameEndingWith("Steps")))
                .should().callMethod(Thread.class, "sleep", long.class)
                .check(new ClassFileImporter()
                        .importPackages(TEST_BASE_PACKAGE));
    }

        /**
     * 姝ラ灞備笉鐩磋繛娴嬭瘯妗嗘灦鏂█ API锛圔-5锛屽彛寰勯殢 2026-10 鐨?JUnit4 缁熶竴鏇存柊锛夛細{@code tests} 鍖呬笅浠?
     * {@code Steps} 缁撳熬鐨勬楠ょ被涓嶅緱鐩存帴渚濊禆 {@code org.junit.Assert}锛堟棫鍙ｅ緞绂佺殑鏄?jupiter 鐨?Assertions锛夈€?
     * <p>姝ラ灞傛柇瑷€缁熶竴缁?{@code tests.verify.RouteDemoVerifications} 鏀跺彛鈥斺€旀棦瑙ｈ€︽祴璇曟鏋?API锛?
     * 鍙堜负銆屾柇瑷€涓嬫矇鍒?Service / Verifier銆嶆彁渚涘敮涓€鏇挎崲閿氱偣銆傛湰瑙勫垯蹇呴』鎵弿娴嬭瘯绫伙紝鏁呭崟鐙互娴嬭瘯鏍瑰寘瀵煎叆銆?/p>
     */
    @Test
    public void stepsMustNotUseJunitAssertionsDirectly() {
        noClasses()
                .that(JavaClass.Predicates.resideInAPackage("..automation.tests..")
                        .and(JavaClass.Predicates.simpleNameEndingWith("Steps")))
                .should().dependOnClassesThat().haveFullyQualifiedName("org.junit.Assert")
                .check(new ClassFileImporter()
                        .importPackages(TEST_BASE_PACKAGE));
    }
}
