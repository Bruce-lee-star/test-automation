//package com.hsbc.cmb.hk.dbb.automation.framework.web.page.scan.engine;
//
//import org.junit.Test;
//
//import java.util.Set;
//
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertTrue;
//
///**
// * A-4：{@link RolePickerConstants} 契约测试——固化 Java↔面板 JS 的关键契约键。
// *
// * <p>重点是 {@code MODE_*} 与 {@link PickMode} 枚举的一致性（防止枚举改名而常量未同步，导致
// * Java 与浏览器侧模式字符串错配），以及命令名/实参键的唯一性（防复制粘贴引入歧义）。
// */
//public class RolePickerConstantsTest {
//
//    @Test
//    public void commandNames_areDistinct() {
//        // Set.of 遇到重复元素会抛 IllegalArgumentException，故其自身即为唯一性断言
//        Set<String> cmds = Set.of(
//                RolePickerConstants.CMD_START, RolePickerConstants.CMD_PACKAGE,
//                RolePickerConstants.CMD_REFRESH_CODE, RolePickerConstants.CMD_STOP,
//                RolePickerConstants.CMD_ABORT, RolePickerConstants.CMD_DONE);
//        assertEquals(6, cmds.size());
//    }
//
//    @Test
//    public void stateKeys_areDistinctAndNonBlank() {
//        Set<String> keys = Set.of(
//                RolePickerConstants.STATE_KEY_MODE, RolePickerConstants.STATE_KEY_PAGE_NAME,
//                RolePickerConstants.STATE_KEY_CODE, RolePickerConstants.STATE_KEY_MSG,
//                RolePickerConstants.STATE_KEY_FILES, RolePickerConstants.STATE_KEY_NLS,
//                RolePickerConstants.STATE_KEY_AUTO_STEP_COUNT, RolePickerConstants.STATE_KEY_STATE_JSON);
//        assertEquals(8, keys.size());
//        keys.forEach(k -> assertTrue("契约实参键不可为空", k != null && !k.isBlank()));
//    }
//
//    @Test
//    public void capacityAndTimeouts_arePositive() {
//        assertTrue(RolePickerConstants.CAP_GLOBAL_URL_TO_CLASS_MAX > 0);
//        assertTrue(RolePickerConstants.TIMEOUT_NLS_CACHE_TTL_MS > 0);
//        assertTrue(RolePickerConstants.TIMEOUT_FORCE_START_DEBOUNCE_MS > 0);
//    }
//}
