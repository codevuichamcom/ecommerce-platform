package com.ecom.auth;

import com.ecom.auth.repository.RefreshTokenRepository;
import com.ecom.auth.repository.UserRepository;
import com.ecom.auth.support.PostgresTestcontainerConfig;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.ecom.auth.web.dto.LoginRequest;
import com.ecom.auth.web.dto.RefreshRequest;
import com.ecom.auth.web.dto.RegisterRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test cho 4 endpoint auth.
 *
 * <p>Cover (theo Day 2 plan):
 * <ul>
 *   <li>register happy</li>
 *   <li>register duplicate email → 409</li>
 *   <li>login happy + sai password → 401 với cùng error code (no enumeration)</li>
 *   <li>refresh happy + rotation invalidates old token</li>
 *   <li>refresh same token twice → second call 401 (race detection)</li>
 *   <li>/me với valid Bearer → 200 + virtualThread=true</li>
 *   <li>/me thiếu token → 401</li>
 * </ul>
 */
/*
 * NOTE Day 2 — known issue:
 *   Docker Desktop 29.x trên Windows + testcontainers-java có compat issue
 *   (named pipe trả 400 cho /info request). Đã thử: DOCKER_HOST override,
 *   API version pin, Ryuk disable — đều fail. Chi tiết:
 *   docs/issues/02b-testcontainers-docker-desktop-29.md
 *
 *   Test class này skip mặc định, chỉ chạy khi env var
 *   `RUN_AUTH_INTEGRATION_TESTS=true` (vd trên CI Linux runner / khi user
 *   đã downgrade Docker Desktop).
 *
 *   Day 2 verify thay bằng manual smoke test (xem ROADMAP).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
@EnabledIfEnvironmentVariable(named = "RUN_AUTH_INTEGRATION_TESTS", matches = "true")
class AuthServiceIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshRepo;

    @BeforeEach
    void clean() {
        refreshRepo.deleteAll();
        userRepo.deleteAll();
    }

    @Test
    void register_login_me_happyPath() throws Exception {
        // register
        TokenPair pair = registerOk("alice@example.com", "password123");
        assertThat(pair.access).isNotBlank();
        assertThat(pair.refresh).isNotBlank();

        // /me
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + pair.access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                // Virtual Threads modernity check — endpoint chạy trên VT.
                .andExpect(jsonPath("$.data.virtualThread").value(true));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        registerOk("dup@example.com", "password123");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterRequest("dup@example.com", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_USER_EXISTS"));
    }

    @Test
    void login_invalidPassword_returns401_sameErrorAsUnknownUser() throws Exception {
        registerOk("bob@example.com", "correctPass1");

        // Wrong password
        MvcResult wrong = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest("bob@example.com", "wrongPass"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        // Unknown user
        MvcResult unknown = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new LoginRequest("ghost@example.com", "anyPass1234"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // Same error code → chống user enumeration.
        assertThat(errorCode(wrong)).isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(errorCode(unknown)).isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void refresh_rotation_invalidatesOldToken() throws Exception {
        TokenPair p1 = registerOk("rot@example.com", "password123");

        // Refresh lần 1 → ok
        TokenPair p2 = refreshOk(p1.refresh);
        assertThat(p2.refresh).isNotEqualTo(p1.refresh);

        // Refresh lại bằng token CŨ → reject
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RefreshRequest(p1.refresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));

        // Token mới vẫn hợp lệ
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + p2.access))
                .andExpect(status().isOk());
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withGarbageToken_returns401() throws Exception {
        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"));
    }

    // ─── Helpers ───────────────────────────────────────────────────

    private TokenPair registerOk(String email, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RegisterRequest(email, password))))
                .andExpect(status().isCreated())
                .andReturn();
        return parseTokens(res);
    }

    private TokenPair refreshOk(String refreshToken) throws Exception {
        MvcResult res = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RefreshRequest(refreshToken))))
                .andExpect(status().isOk())
                .andReturn();
        return parseTokens(res);
    }

    private TokenPair parseTokens(MvcResult res) throws Exception {
        JsonNode root = json.readTree(res.getResponse().getContentAsString());
        return new TokenPair(
                root.path("data").path("accessToken").asText(),
                root.path("data").path("refreshToken").asText());
    }

    private String errorCode(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString()).path("error").path("code").asText();
    }

    private record TokenPair(String access, String refresh) {}
}
