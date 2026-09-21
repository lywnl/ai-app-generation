package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.service.StaticPreviewResourceResolver;
import com.lyw.appgeneration.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StaticResourceControllerTest {
    @TempDir Path root;
    MockMvc mvc;
    StaticResourceController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new StaticResourceController(new StaticPreviewResourceResolver(root));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        Files.createDirectories(root.resolve("vue_project_7/dist"));
    }

    @Test
    void servesPublicResourcesThroughRealConverterAndPreservesHeaders() throws Exception {
        for (String file : new String[]{"index.html", "app.js", "app.css", "image.png", "font.woff2"}) {
            Files.writeString(root.resolve("vue_project_7/dist/" + file), "public:" + file);
            mvc.perform(get("/api/static/vue_project_7/dist/" + file).contextPath("/api"))
                    .andExpect(status().isOk()).andExpect(content().string("public:" + file))
                    .andExpect(header().string("Cache-Control", "no-store, must-revalidate"));
            mvc.perform(head("/api/static/vue_project_7/dist/" + file).contextPath("/api"))
                    .andExpect(status().isOk()).andExpect(content().string(""));
        }
        Files.writeString(root.resolve("vue_project_7/index.html"), "home");
        mvc.perform(get("/api/static/vue_project_7").contextPath("/api"))
                .andExpect(status().isMovedPermanently()).andExpect(header().string("Location", "/api/static/vue_project_7/"));
        mvc.perform(get("/api/static/vue_project_7/").contextPath("/api"))
                .andExpect(status().isOk()).andExpect(content().string("home"));
    }

    @Test
    void deniesPlanAndTemporaryFilesForEveryIdentity() throws Exception {
        for (String file : new String[]{".plan.json", ".plan-save.tmp"}) {
            Files.writeString(root.resolve("vue_project_7/" + file), "secret");
            for (String identity : new String[]{"anonymous", "owner", "other", "admin"}) {
                var getRequest = get("/api/static/vue_project_7/" + file).contextPath("/api");
                if (!"anonymous".equals(identity)) {
                    User user = new User();
                    user.setId("owner".equals(identity) ? 7L : 8L);
                    user.setUserRole("admin".equals(identity) ? "admin" : "user");
                    getRequest.sessionAttr("user_login", user);
                }
                mvc.perform(getRequest)
                        .andExpect(status().isNotFound()).andExpect(content().string(""));
                mvc.perform(head("/api/static/vue_project_7/" + file).contextPath("/api"))
                        .andExpect(status().isNotFound()).andExpect(content().string(""));
            }
        }
        mvc.perform(get("/api/static/vue_project_7/../vue_project_8/.plan.json").contextPath("/api"))
                .andExpect(status().isNotFound());
    }

    @Test
    void converterCannotFollowLinkInstalledAfterControllerPrecheck() throws Exception {
        Path file = Files.writeString(root.resolve("vue_project_7/dist/index.html"), "public");
        Path secret = Files.writeString(root.resolve("vue_project_7/.plan.json"), "secret");
        var request = new MockHttpServletRequest("GET", "/api/static/vue_project_7/dist/index.html");
        request.setContextPath("/api");
        var response = controller.serveStaticResource("vue_project_7", request);
        assertEquals(200, response.getStatusCode().value());
        Files.delete(file);
        Files.createSymbolicLink(file, secret);
        var output = new MockHttpOutputMessage();
        assertThrows(IOException.class, () -> new ResourceHttpMessageConverter().write(response.getBody(), null, output));
        assertFalse(output.getBodyAsString().contains("secret"));
    }

    @Test
    void repeatedHeadDoesNotHoldOpenFileDescriptors() throws Exception {
        Files.writeString(root.resolve("vue_project_7/dist/index.html"), "public");
        var os = (com.sun.management.UnixOperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        mvc.perform(head("/static/vue_project_7/dist/index.html")).andExpect(status().isOk());
        long before = os.getOpenFileDescriptorCount();
        for (int i = 0; i < 50; i++) mvc.perform(head("/static/vue_project_7/dist/index.html")).andExpect(status().isOk());
        assertTrue(os.getOpenFileDescriptorCount() <= before + 3);
    }
}
