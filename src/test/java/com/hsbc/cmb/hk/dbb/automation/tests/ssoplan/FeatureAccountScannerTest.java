package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link FeatureAccountScanner} 正则与 scenario 关联单测（内联 feature 文本，不落临时文件）。
 */
public class FeatureAccountScannerTest {

    @Test
    public void scansCanonicalAndCompactAndPrefixedForms() {
        String feature = "Feature: f\n"
                + "  Scenario: a\n"
                + "    Given logon DBB \"O63_SIT1\" environment as user \"WP7UAT2_2\"\n"
                + "  Scenario: b\n"
                + "    Given  logonDBB \"O63_SIT1\" environment as user \"WP7UAT2_2\"\n"
                + "  Scenario: c\n"
                + "    Given I logon DBB \"O88_SIT3\" environment as user \"amhb2g0680_2\" using the RoleElement page\n";
        List<UsedAccount> used = FeatureAccountScanner.scanContent(feature, "f.feature");

        assertEquals(3, used.size());
        assertTrue(used.stream().anyMatch(u -> u.env().equals("O63_SIT1") && u.user().equals("WP7UAT2_2")));
        assertTrue(used.stream().anyMatch(u -> u.env().equals("O88_SIT3") && u.user().equals("amhb2g0680_2")));
        assertTrue(used.stream().allMatch(u -> u.scenarioName() != null && !u.scenarioName().isEmpty()));
    }

    @Test
    public void scenarioOutlinePlaceholdersAreExcluded() {
        String feature = "Feature: f\n"
                + "  Scenario Outline: login\n"
                + "    Given logon DBB \"<env>\" environment as user \"<user>\"\n"
                + "    Examples:\n"
                + "      | env       | user        |\n"
                + "      | O63_SIT1  | WP7UAT2_2   |\n";
        List<UsedAccount> used = FeatureAccountScanner.scanContent(feature, "f.feature");

        assertTrue("Scenario Outline 占位不应计入校验", used.isEmpty());
    }

    @Test
    public void countsScenariosAndSkipsBackgroundLogon() {
        String feature = "Feature: f\n"
                + "  Background:\n"
                + "    Given logon DBB \"O63_SIT1\" environment as user \"WP7UAT2_2\"\n"
                + "  Scenario: s1\n"
                + "    Given logon DBB \"O63_SIT1\" environment as user \"WP7UAT2_2\"\n"
                + "  Scenario Outline: s2\n"
                + "    Given logon DBB \"<env>\" environment as user \"<user>\"\n";
        List<UsedAccount> used = FeatureAccountScanner.scanContent(feature, "f.feature");

        // 仅 Background + s1 为真实身份；Scenario Outline 被排除
        assertEquals(2, used.size());
        assertTrue(used.stream().allMatch(u -> u.scenarioName().equals("(background)")
                || u.scenarioName().equals("s1")));
    }
}
