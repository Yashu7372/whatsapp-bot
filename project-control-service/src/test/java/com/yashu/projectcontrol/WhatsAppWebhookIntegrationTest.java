package com.yashu.projectcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "project-control.whatsapp.verify-token=e2e-verify-token",
        "project-control.whatsapp.app-secret="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WhatsAppWebhookIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void metaVerificationReturnsChallengeWithoutBrowserAuthentication() throws Exception {
        mvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "e2e-verify-token")
                        .param("hub.challenge", "123456"))
                .andExpect(status().isOk())
                .andExpect(content().string("123456"));
    }

    @Test
    void wrongVerificationTokenFailsClosed() throws Exception {
        mvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong-token")
                        .param("hub.challenge", "123456"))
                .andExpect(status().isForbidden());
    }

    @Test
    void metaEventPostIsCsrfExemptButNormalApisRemainProtected() throws Exception {
        mvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().string("EVENT_RECEIVED"));

        mvc.perform(get("/api/v1/workflow-instances/00000000-0000-0000-0000-000000000001/assistant/reviewer-brief"))
                .andExpect(status().isUnauthorized());
    }
}
