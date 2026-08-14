package com.lyw.appgeneration.config;

import com.lyw.appgeneration.core.handler.VueTurnContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Task7TimeoutConfigTest {

    @Test
    void transportTimeoutsAreStrictlyLongerThanBusinessDeadline()
            throws Exception {
        String application = Files.readString(
                Path.of("src/main/resources/application.yml"));
        String nginx = Files.readString(Path.of("prod/nginx/nginx.conf"));
        long mvcMillis = extract(application,
                "request-timeout:\\s*(\\d+)");
        long readSeconds = extract(nginx,
                "proxy_read_timeout\\s+(\\d+)s");
        long sendSeconds = extract(nginx,
                "proxy_send_timeout\\s+(\\d+)s");

        long businessMillis = VueTurnContext.TURN_DEADLINE.toMillis();
        assertEquals(1_845_000L, mvcMillis);
        assertEquals(1_860L, readSeconds);
        assertEquals(1_860L, sendSeconds);
        assertTrue(businessMillis < mvcMillis);
        assertTrue(mvcMillis < Duration.ofSeconds(readSeconds).toMillis());
        assertTrue(mvcMillis < Duration.ofSeconds(sendSeconds).toMillis());
        assertTrue(nginx.contains(
                "location = /api/app/chat/gen/code"));
        assertTrue(nginx.contains("client_max_body_size 272k"));
        assertTrue(nginx.contains("proxy_buffering off"));
        assertTrue(nginx.contains("proxy_cache off"));
    }

    private long extract(String source, String expression) {
        var matcher = Pattern.compile(expression).matcher(source);
        assertTrue(matcher.find(), "未找到配置: " + expression);
        return Long.parseLong(matcher.group(1));
    }
}
