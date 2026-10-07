package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 评审 F-15 / P1-6 回归：codegen 生成物必须<b>可编译</b>，且再生成时<b>不覆盖手写代码</b>。
 *
 * <p>覆盖四个子项各自的可测切口（均为包级私有纯函数，无需拾取器 / 浏览器）：</p>
 * <ol>
 *   <li><b>保留字字段名</b>（{@code public PageElement new;} 非法）→ {@link RoleElementPageGenerator#safeFieldName(String)}；</li>
 *   <li><b>未定位拖拽</b>（{@code dragTo(/* 注释 *​/)} 零实参非法）→
 *       {@link RoleElementStepGenerator#dragToStatement(String, String)}；</li>
 *   <li><b>多语句操作塞进单表达式 lambda</b>（{@code printKey} 追加 {@code press} 后非法）→
 *       {@link RoleElementStepGenerator#lambdaBody(String)}；</li>
 *   <li><b>标记块增量合并</b>（原「命中首行即整文件覆盖」）→
 *       {@link RoleElementPageGenerator#mergeGeneratedBlocks(String, String)} /
 *       {@link RoleElementPageGenerator#withMergedImports(String, String)}。</li>
 * </ol>
 *
 * <p>为何打这些切口而非跑完整 generate：drag/press 等字段只能经 {@code RoleEntry} 的超长构造器注入
 * （无 setter），端到端 fixture 会随构造器演进脆断；而上述四处是四条修复路径的<b>唯一决策点</b>，
 * 断言它们即可精确固化"产物可编译"。</p>
 */
public class CodegenGeneratedSourceTest {

    // ───────────────────── ① 保留字字段名 ─────────────────────

    @Test
    public void reservedWordFieldNamesGetSuffixed() {
        assertEquals("newField", RoleElementPageGenerator.safeFieldName("new"));
        assertEquals("classField", RoleElementPageGenerator.safeFieldName("class"));
        assertEquals("forField", RoleElementPageGenerator.safeFieldName("for"));
        assertEquals("intField", RoleElementPageGenerator.safeFieldName("int"));
        assertEquals("nullField", RoleElementPageGenerator.safeFieldName("null"));
        assertEquals("trueField", RoleElementPageGenerator.safeFieldName("true"));
        assertEquals("_Field", RoleElementPageGenerator.safeFieldName("_"));
    }

    @Test
    public void normalFieldNamesAreLeftUntouched() {
        assertEquals("userName", RoleElementPageGenerator.safeFieldName("userName"));
        // 带语义后缀（new + Btn）已不是保留字，不应被改写——避免无谓的产物 churn
        assertEquals("newBtn", RoleElementPageGenerator.safeFieldName("newBtn"));
        assertEquals("classroom", RoleElementPageGenerator.safeFieldName("classroom"));
        assertNull(RoleElementPageGenerator.safeFieldName(null));
        assertEquals("", RoleElementPageGenerator.safeFieldName(""));
    }

    // ───────────────────── ② 未定位拖拽 ─────────────────────

    @Test
    public void unresolvedDragEmitsCommentInsteadOfZeroArgCall() {
        String stmt = RoleElementStepGenerator.dragToStatement("page.dragSrc", null);

        assertTrue("未定位时应退化为注释行，实际：" + stmt, stmt.startsWith("//"));
        assertFalse("F-15：不得产出 dragTo(/* 注释 */) 这类括号内零实参的非法 Java，实际：" + stmt, stmt.contains("dragTo("));
        assertTrue("注释应指明源元素，便于人工补目标：" + stmt, stmt.contains("page.dragSrc"));
    }

    @Test
    public void resolvedDragEmitsNormalCall() {
        assertEquals("page.dragSrc.dragTo(page.dropZone)",
                RoleElementStepGenerator.dragToStatement("page.dragSrc", "page.dropZone"));
    }

    // ───────────────────── ③ 多语句 → 块体 lambda ─────────────────────

    @Test
    public void multiStatementOperationIsWrappedIntoBlockLambda() {
        String op = "page.userIpt.fill(\"x\");\n        page.userIpt.press(\"Enter\")";
        String body = RoleElementStepGenerator.lambdaBody(op);

        assertTrue("多语句必须包成块体 lambda，否则 `() -> a(); b()` 非法 Java，实际：" + body, body.startsWith("{") && body.endsWith("}"));
        String flat = body.replaceAll("\\s+", " ").trim();
        assertEquals("块体应保留全部语句且末条以 ';' 收尾（块内语句缺分号同样非法）", "{ page.userIpt.fill(\"x\"); page.userIpt.press(\"Enter\"); }", flat);
    }

    @Test
    public void singleStatementOperationStaysInline() {
        // 单表达式保持既有产物形状：不得因本次修复产生大范围产物 churn
        assertEquals("page.okBtn.click()", RoleElementStepGenerator.lambdaBody("page.okBtn.click()"));
        assertEquals("", RoleElementStepGenerator.lambdaBody(null));
    }

    // ───────────────────── ⑤ 断言类产物（新增断言 Tab） ─────────────────────

    @Test
    public void visibleAssertionStatementHasExpectedShape() {
        assertEquals("assertThat(loginPage.userName.isVisible(), equalTo(true))",
                RoleElementAssertionGenerator.assertStatement("loginPage", "userName"));
    }

    /**
     * 断言类产物形状：独立类、落在 {@code .assertions} 子包、一次封装一个方法且方法体内是全部断言、
     * <b>注解不带描述文案</b>（用户要求"只要断言"）、Hamcrest 两个静态 import 齐备。
     */
    @Test
    public void assertionClassWrapsAllAssertionsOfOnePackageIntoOneMethod() {
        String src = RoleElementAssertionGenerator.renderClass("com.demo.pages.login", "LoginPageAssertions",
                "com.demo.pages.login.LoginPage",
                List.of(List.of(
                        RoleElementAssertionGenerator.assertStatement("loginPage", "userName"),
                        RoleElementAssertionGenerator.assertStatement("loginPage", "password"))));

        assertTrue("断言类必须在独立子包（与 .steps 对称），实际：" + src,
                src.contains("package com.demo.pages.login.assertions;"));
        assertTrue("必须 import 页面类", src.contains("import com.demo.pages.login.LoginPage;"));
        assertTrue("必须带 Hamcrest assertThat", src.contains("import static org.hamcrest.MatcherAssert.assertThat;"));
        assertTrue("必须带 equalTo", src.contains("import static org.hamcrest.Matchers.equalTo;"));
        assertTrue("一次封装 = 一个方法", src.contains("public void assertStep1()"));
        assertFalse("不得生成第二个方法（本次封装只有一个）", src.contains("public void assertStep2()"));
        assertTrue("方法体内必须是全部断言", src.contains("assertThat(loginPage.userName.isVisible(), equalTo(true));"));
        assertTrue("方法体内必须是全部断言", src.contains("assertThat(loginPage.password.isVisible(), equalTo(true));"));
        assertTrue("@Step 只保留裸注解", src.contains("    @Step\n"));
        assertFalse("不得带描述文案（用户要求只要断言），实际：" + src, src.contains("@Step(\""));
        assertFalse("断言类不得生成操作调用", src.contains(".click()") || src.contains(".fill("));
    }

    // ───────────────────── ④ 标记块增量合并 ─────────────────────

    private static final String BEGIN = RoleElementPageGenerator.FIELDS_BLOCK_BEGIN;
    private static final String END = RoleElementPageGenerator.FIELDS_BLOCK_END;

    private static String generated(String fieldLines, String... imports) {
        StringBuilder importsBlock = new StringBuilder();
        for (String imp : imports) {
            importsBlock.append("import ").append(imp).append(";\n");
        }
        return "// " + RoleElementPageGenerator.GENERATED_MARKER + "\n"
                + "package com.demo;\n\n"
                + importsBlock
                + "\n"
                + "public class DemoPage extends AbstractManagedPage {\n\n"
                + BEGIN + "\n"
                + fieldLines
                + END + "\n"
                + "}\n";
    }

    @Test
    public void mergeReplacesGeneratedBlockAndKeepsHandWrittenCode() {
        String existing = generated("    public PageElement oldFld;\n", "a.A")
                .replace("}\n", "    public void myHelper() { /* hand-written */ }\n}\n");
        String fresh = generated("    public PageElement newFld;\n", "a.A", "b.B");

        String merged = RoleElementPageGenerator.mergeGeneratedBlocks(existing, fresh);

        assertTrue("生成块内应替换为新字段", merged.contains("newFld"));
        assertFalse("旧字段应被块内替换移除", merged.contains("oldFld"));
        assertTrue("F-15：块外手写方法必须原样保留（不再整文件覆盖）", merged.contains("myHelper"));
        assertTrue("新生成的 import 应补齐（保证新字段可编译）", merged.contains("import b.B;"));
        assertTrue("既有 import 不得丢失", merged.contains("import a.A;"));
        assertTrue("包声明等块外内容应保留", merged.contains("package com.demo;"));
    }

    @Test
    public void mergeUnionsImportsWithoutDroppingHandWrittenImports() {
        String existing = generated("    public PageElement oldFld;\n", "a.A", "hand.Custom");
        String fresh = generated("    public PageElement newFld;\n", "a.A");

        String merged = RoleElementPageGenerator.mergeGeneratedBlocks(existing, fresh);

        assertTrue("手写方法可能依赖的既有 import 不得被新产物头部覆盖掉", merged.contains("import hand.Custom;"));
        assertEquals("重复 import 不应被重复插入", 1, countOccurrences(merged, "import a.A;"));
    }

    @Test
    public void withMergedImportsIsNoOpWhenNothingMissing() {
        String existing = generated("    public PageElement fld;\n", "a.A");
        assertSame("无缺失 import 时应原样返回（避免无谓改写）", existing, RoleElementPageGenerator.withMergedImports(existing, generated("x", "a.A")));
    }

    @Test
    public void legacyFileWithoutMarkersFallsBackToFullOverwrite() {
        String legacy = "// " + RoleElementPageGenerator.GENERATED_MARKER + "\n"
                + "package com.demo;\n\n"
                + "public class DemoPage extends AbstractManagedPage {\n\n"
                + "    public PageElement oldFld;\n"
                + "}\n";
        String fresh = generated("    public PageElement newFld;\n", "a.A");

        assertEquals("历史产物无标记块 → 回落整文件覆盖（调用方已先备份 .bak），保持向后兼容", fresh, RoleElementPageGenerator.mergeGeneratedBlocks(legacy, fresh));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
