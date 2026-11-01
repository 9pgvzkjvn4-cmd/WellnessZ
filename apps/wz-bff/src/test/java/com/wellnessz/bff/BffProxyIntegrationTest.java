package com.wellnessz.bff;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpServer;

/**
 * The proxy behind the full BFF filter chain against a stub core: streamed SSE keeps the security headers, and the
 * workspace role rule admits NURSING_HEAD for {@code /workspace/device-fleet} only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BffProxyIntegrationTest {

    private static HttpServer core;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        BffSecurityTest.database(registry);
        if (core == null) {
            core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            core.createContext("/api/v1/device-capture/sessions/s1/events", ex -> {
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                OutputStream out = ex.getResponseBody();
                out.write("event:state\ndata:{\"state\":\"LISTENING\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write("event:preview\ndata:{\"status\":\"PENDING_VERIFICATION\"}\n\n".getBytes(StandardCharsets.UTF_8));
                out.close();
            });
            core.createContext("/api/v1/", ex -> {
                byte[] json = ("{\"path\":\"" + ex.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, json.length);
                ex.getResponseBody().write(json);
                ex.close();
            });
            core.start();
        }
        registry.add("wellnessz.bff.core-api-url", () -> "http://127.0.0.1:" + core.getAddress().getPort());
    }

    @MockitoBean
    OAuth2AuthorizedClientManager clients;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void authorizedClient() {
        ClientRegistration reg = ClientRegistration.withRegistrationId("wellnessz").clientId("bff").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").authorizationUri("https://idp.test/auth").tokenUri("https://idp.test/token").build();
        OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "at", Instant.now(), Instant.now().plusSeconds(300));
        when(clients.authorize(any())).thenReturn(new OAuth2AuthorizedClient(reg, "user", token));
    }

    static org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void eventStreamIsRelayedWithTheSecurityHeaders() throws Exception {
        mvc.perform(get("/bff/api/device-capture/sessions/s1/events").header("Accept", "text/event-stream").with(as("OH_CLINICIAN")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("text/event-stream")))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-cache")))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(Matchers.allOf(Matchers.containsString("event:state"), Matchers.containsString("event:preview"))));
    }

    @Test
    void nursingHeadIsAdmittedToTheDeviceFleetWorkspaceResourceOnly() throws Exception {
        mvc.perform(get("/bff/api/workspace/device-fleet").with(as("NURSING_HEAD")))
                .andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("/api/v1/workspace/device-fleet")));
        mvc.perform(get("/bff/api/workspace/cases").with(as("NURSING_HEAD"))).andExpect(status().isForbidden());
        mvc.perform(get("/bff/api/workspace/team-restrictions").with(as("NURSING_HEAD"))).andExpect(status().isForbidden());
        mvc.perform(get("/bff/api/workspace/device-fleet").with(as("MANAGER"))).andExpect(status().isOk());
        mvc.perform(get("/bff/api/workspace/device-fleet").with(as("EMPLOYEE"))).andExpect(status().isForbidden());
        mvc.perform(get("/bff/api/workspace/device-fleet").with(as("DEVICE_ADMIN"))).andExpect(status().isForbidden());
    }
}
