package com.shop.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.DataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 1：用户域认证与网关安全。
 * 覆盖注册/登录/资料、错误口令、401、伪造身份头清洗、/inner 内网路径对外 404。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("01 认证与网关安全：注册登录 / 401 / 伪造身份头 / inner 404")
class AuthE2ETest {

    private static final String USER = "/api/user";

    @Test
    @DisplayName("注册→登录→查询我的资料：JWT 可用且身份为普通用户")
    void registerLoginAndProfile() {
        ApiClient anonymous = ApiClient.create();
        String username = DataFactory.uniqueUsername("auth");
        String phone = DataFactory.uniquePhone();
        String password = "E2e@123456";

        ApiClient.Raw reg = anonymous.mustPost(USER + "/auth/register", ApiClient.obj(
                "username", username,
                "phone", phone,
                "password", password,
                "nickname", username), "注册新用户");
        long userId = reg.data().asLong();
        assertTrue(userId > 0, "注册应返回 userId");

        ApiClient.Raw login = anonymous.mustPost(USER + "/auth/login",
                ApiClient.obj("account", username, "password", password), "登录");
        String token = login.data().path("token").asText();
        assertNotNull(token, "登录应返回 token");
        assertEquals(userId, login.data().path("userId").asLong(), "登录 userId 与注册一致");
        assertEquals(0, login.data().path("userType").asInt(-1), "自助注册只能是普通用户(userType=0)");

        ApiClient mine = ApiClient.create().withToken(token);
        JsonNode me = mine.get(USER + "/users/me").data();
        assertEquals(userId, me.path("userId").asLong(), "/users/me 身份与登录一致");
        assertEquals(username, me.path("username").asText());
        assertEquals(0, me.path("userType").asInt(-1));

        JsonNode level = mine.get(USER + "/users/level").data();
        assertEquals(userId, level.path("userId").asLong());
        assertTrue(level.path("growth").asLong() >= 0L, "新用户成长值非负");
    }

    @Test
    @DisplayName("错误口令登录被拒绝（业务码非 0，不发 token）")
    void wrongPasswordRejected() {
        ApiClient c = ApiClient.create();
        String username = DataFactory.uniqueUsername("authbad");
        c.mustPost(USER + "/auth/register", ApiClient.obj(
                "username", username,
                "phone", DataFactory.uniquePhone(),
                "password", "E2e@123456",
                "nickname", username), "注册新用户");

        ApiClient.Raw raw = c.post(USER + "/auth/login",
                ApiClient.obj("account", username, "password", "Wrong-Pass-9"));
        assertNotEquals(0, raw.bizCode(), "错误口令不应登录成功");
        assertTrue(raw.json() == null || raw.data().path("token").asText().isEmpty(),
                "失败登录不应返回 token");
    }

    @Test
    @DisplayName("无 Token 与伪造 Token 访问受保护资源均返回 401 / 10002")
    void unauthorizedWithoutValidToken() {
        ApiClient noToken = ApiClient.create();
        ApiClient.Raw none = noToken.get(USER + "/users/me");
        assertEquals(401, none.httpStatus, "无 token 应 401");
        assertEquals(10002, none.bizCode(), "网关未登录业务码 10002");

        ApiClient bad = ApiClient.create().withToken("not-a-jwt");
        ApiClient.Raw invalid = bad.get(USER + "/users/me");
        assertEquals(401, invalid.httpStatus, "伪造 token 应 401");
        assertEquals(10002, invalid.bizCode(), "坏 token 业务码 10002");
    }

    @Test
    @DisplayName("伪造 X-User-Id / X-User-Type / X-Merchant-Id 被网关清洗，无法越权")
    void forgedIdentityHeadersAreStripped() {
        // 1) 匿名 + 伪造身份头：仍然 401，不会被网关注入身份
        ApiClient.Raw forged = ApiClient.create().rawRequest("GET", USER + "/users/me", null, Map.of(
                "X-User-Id", "999999",
                "X-User-Type", "2",
                "X-Merchant-Id", "8888",
                "X-Internal-Token", "anything"));
        assertEquals(401, forged.httpStatus, "匿名伪造头不能通过鉴权");

        // 2) 合法用户 + 伪造平台管理员头：/users/me 仍是本人身份
        ApiClient c = ApiClient.create();
        String username = DataFactory.uniqueUsername("forged");
        String password = "E2e@123456";
        long userId = c.mustPost(USER + "/auth/register", ApiClient.obj(
                "username", username,
                "phone", DataFactory.uniquePhone(),
                "password", password,
                "nickname", username), "注册").data().asLong();
        String token = c.mustPost(USER + "/auth/login",
                ApiClient.obj("account", username, "password", password), "登录").data().path("token").asText();

        ApiClient.Raw me = ApiClient.create().withToken(token).rawRequest("GET", USER + "/users/me", null, Map.of(
                "X-User-Id", "1",
                "X-User-Name", "platform-admin",
                "X-User-Type", "2",
                "X-Merchant-Id", "8888"));
        assertEquals(0, me.bizCode());
        assertEquals(userId, me.data().path("userId").asLong(), "X-User-Id 伪造无效，必须取 JWT 身份");
        assertEquals(0, me.data().path("userType").asInt(-1), "X-User-Type 伪造无效，仍是普通用户");
    }

    @Test
    @DisplayName("/inner 内网路径的 4 种编码变体对外一律 404 / 10404（含 %2f、双重编码、分号、大小写）")
    void innerPathVariantsReturn404() {
        String[] variants = {
                USER + "/inner/users/me",                 // 明文
                USER + "/..%2finner/users/me",            // 单次 URL 编码穿越
                USER + "/%252e%252e/inner/users/me",      // 双重编码
                USER + "/inner;/users/me",                // 分号矩阵参数
        };
        for (String path : variants) {
            ApiClient.Raw raw = ApiClient.create().rawRequest("GET", path, null);
            assertEquals(404, raw.httpStatus, "内网路径变体必须 404: " + path);
            assertEquals(10404, raw.bizCode(), "内网路径变体业务码 10404: " + path);
        }

        // 大小写混淆编码：%69 = 'i'
        ApiClient.Raw mixedCase = ApiClient.create().rawRequest("GET",
                USER + "/%69nner/users/me", null);
        assertEquals(404, mixedCase.httpStatus, "%69nner 大小写变体也必须 404");
        assertEquals(10404, mixedCase.bizCode());
    }
}
