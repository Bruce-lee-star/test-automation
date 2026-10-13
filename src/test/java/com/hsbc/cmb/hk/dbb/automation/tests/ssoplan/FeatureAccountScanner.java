package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * feature 文件扫描器：抽取每个 scenario 引用的 SSO 登录身份（设计文档 §5.6.4 dry-run 报告数据源）。
 *
 * <p>兼容真实步骤的多种写法（均匹配子串，不要求 {@code Given}/{@code And}/{@code I } 前缀）：
 * <ul>
 *   <li>{@code logon DBB "O63_SIT1" environment as user "WP7UAT2_2"}</li>
 *   <li>{@code logonDBB "O63_SIT1" environment as user "WP7UAT2_2"}（无空格变体）</li>
 *   <li>{@code I logon DBB "..." environment as user "..." using the RoleElement page}（前缀/后缀）</li>
 * </ul>
 * {@code Scenario Outline} 的 {@code <env>}/<user>} 占位被 {@link UsedAccount#isConcrete()} 排除，不计入校验。
 * </p>
 */
public final class FeatureAccountScanner {

    private static final Pattern LOGON = Pattern.compile(
            "logon\\s*DBB\\s+\"([^\"]+)\"\\s+environment\\s+as\\s+user\\s+\"([^\"]+)\"");

    private FeatureAccountScanner() {
    }

    /** 扫描结果：文件数 / scenario 数 / 抽取到的身份列表。 */
    public static final class ScanResult {
        public final int featureFiles;
        public final int scenarios;
        public final List<UsedAccount> used;

        public ScanResult(int featureFiles, int scenarios, List<UsedAccount> used) {
            this.featureFiles = featureFiles;
            this.scenarios = scenarios;
            this.used = List.copyOf(used);
        }
    }

    /** 扫描目录树下的所有 {@code *.feature}。目录不存在时返回空结果（不抛异常，避免误 fail）。 */
    public static ScanResult scan(Path featuresDir) throws IOException {
        List<UsedAccount> all = new ArrayList<>();
        int files = 0;
        int scenarios = 0;
        if (Files.exists(featuresDir)) {
            List<Path> featurePaths;
            try (var walk = Files.walk(featuresDir)) {
                featurePaths = walk.filter(f -> f.getFileName() != null
                                && f.getFileName().toString().endsWith(".feature"))
                        .collect(Collectors.toList());
            }
            for (Path p : featurePaths) {
                files++;
                String rel = featuresDir.relativize(p).toString();
                String content = Files.readString(p, StandardCharsets.UTF_8);
                scenarios += countScenarios(content);
                all.addAll(scanContent(content, rel));
            }
        }
        return new ScanResult(files, scenarios, all);
    }

    /** 扫描单段 feature 文本（供单测与文件扫描复用）。 */
    public static List<UsedAccount> scanContent(String content, String sourceName) {
        List<UsedAccount> list = new ArrayList<>();
        String current = "(background)";
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("Scenario")) { // 含 Scenario / Scenario Outline
                int ci = t.indexOf(':');
                current = ci >= 0 ? t.substring(ci + 1).trim() : "(unnamed)";
            }
            Matcher m = LOGON.matcher(line);
            while (m.find()) {
                UsedAccount ua = new UsedAccount(sourceName, current,
                        m.group(1).trim(), m.group(2).trim());
                if (ua.isConcrete()) {
                    list.add(ua);
                }
            }
        }
        return list;
    }

    private static int countScenarios(String content) {
        int n = 0;
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("Scenario:") || t.startsWith("Scenario Outline:")) {
                n++;
            }
        }
        return n;
    }
}
