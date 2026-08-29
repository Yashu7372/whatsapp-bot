package com.yashu.projectcontrol;

import tools.jackson.databind.ObjectMapper;
import com.yashu.projectcontrol.access.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired IdentityService identityService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;

    @Test
    void passwordLoginCreatesSessionAndProtectedApisDoNotTrustActorHeaders() throws Exception {
        String email = "auth-user@test.demo";
        String password = "Project123!";
        identityService.createCredentialUser(
                "test:auth-user", email, "Authentication User", passwordEncoder.encode(password));

        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());

        String loginBody = objectMapper.writeValueAsString(new LoginRequest(email, password));
        var result = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Authentication User"));

        mvc.perform(get("/api/v1/projects/00000000-0000-0000-0000-000000000001/access")
                        .header("X-Project-Control-User", "00000000-0000-0000-0000-000000000999"))
                .andExpect(status().isUnauthorized());
    }

    private record LoginRequest(String email, String password) {}
}
