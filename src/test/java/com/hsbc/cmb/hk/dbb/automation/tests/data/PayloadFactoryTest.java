package com.hsbc.cmb.hk.dbb.automation.tests.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * D-5：数据工厂契约——模板加载、变体覆盖（基底保留）、唯一化、缺失模板报错。
 */
public class PayloadFactoryTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    public void login_overridesCredentials() {
        JsonObject o = json(PayloadFactory.login("u123", "p456").toJson());

        assertEquals("u123", o.get("username").getAsString());
        assertEquals("p456", o.get("password").getAsString());
    }

    @Test
    public void createUser_keepsTemplateFields_andAppliesOverrides() {
        JsonObject o = json(PayloadFactory.createUser(Map.of("name", "Neo")).toJson());

        assertEquals("Neo", o.get("name").getAsString());
        // 模板未覆盖字段应保留
        assertEquals("USER", o.get("role").getAsString());
        assertEquals("dave@example.com", o.get("email").getAsString());
    }

    @Test
    public void uniqueUser_generatesDistinctData() {
        JsonObject a = json(PayloadFactory.uniqueUser().toJson());
        JsonObject b = json(PayloadFactory.uniqueUser().toJson());

        assertTrue(a.get("name").getAsString().startsWith("user_"));
        assertNotEquals(a.get("name").getAsString(), b.get("name").getAsString());
        assertNotEquals(a.get("email").getAsString(), b.get("email").getAsString());
    }

    @Test
    public void missingTemplate_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Payload.of("this-template-does-not-exist"));
    }

    @Test
    public void blankTemplateName_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Payload.of("  "));
    }
}
