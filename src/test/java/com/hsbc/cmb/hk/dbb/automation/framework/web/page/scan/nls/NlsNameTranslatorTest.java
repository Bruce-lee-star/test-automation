package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.nls;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A-4：codegen {@link NlsNameTranslator}（离线中文→英文标识符）契约测试。
 *
 * <p>覆盖：内置 UI 语义词典命中、拼音兜底、中英混合拼接、空值兜底、非法 Java 标识符首字符修正、
 * 输出恒为合法标识符。纯离线、无浏览器依赖。
 */
public class NlsNameTranslatorTest {

    @Test
    public void uiDictionary_hitsSemanticEnglish() {
        assertEquals("Login", NlsNameTranslator.toIdentifier("登录"));
        assertEquals("Btn", NlsNameTranslator.toIdentifier("按钮"));
        assertEquals("Dialog", NlsNameTranslator.toIdentifier("弹窗"));
        assertEquals("Input", NlsNameTranslator.toIdentifier("输入框"));
    }

    @Test
    public void pinyinFallback_forNonDictionaryChinese() {
        // "跳转" 不在语义词典 → 逐字拼音、首字母大写驼峰
        assertEquals("TiaoZhuan", NlsNameTranslator.toIdentifier("跳转"));
    }

    @Test
    public void mixedChineseAndEnglish_concatenatedAsCamel() {
        assertEquals("LoginButton", NlsNameTranslator.toIdentifier("登录 Button"));
    }

    @Test
    public void separatorSplit_keepsBothSemanticParts() {
        assertEquals("LoginBtn", NlsNameTranslator.toIdentifier("登录-按钮"));
    }

    @Test
    public void nullAndEmpty_fallBackToElement() {
        assertEquals("Element", NlsNameTranslator.toIdentifier(null));
        assertEquals("Element", NlsNameTranslator.toIdentifier(""));
        assertEquals("element", NlsNameTranslator.toIdentifier(null, false));
    }

    @Test
    public void leadingDigit_prefixedWithUnderscoreToStayValidJavaIdentifier() {
        assertEquals("_123abc", NlsNameTranslator.toIdentifier("123abc"));
    }

    @Test
    public void output_isAlwaysALegalJavaIdentifierFragment() {
        for (String raw : new String[]{"用户名称", "跳转 页 面", "登录@#$按钮", "中文English123", "提交状态"}) {
            String id = NlsNameTranslator.toIdentifier(raw);
            assertNotNull(id);
            assertTrue("输出必须是合法 Java 标识符片段，实际 raw='" + raw + "' -> '" + id + "'", id.matches("[A-Za-z_$][A-Za-z0-9_$]*"));
        }
    }
}
