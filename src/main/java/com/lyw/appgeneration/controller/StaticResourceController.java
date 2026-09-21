package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.service.StaticPreviewResourceResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/static")
public class StaticResourceController {

    private final StaticPreviewResourceResolver resourceResolver;

    public StaticResourceController(StaticPreviewResourceResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    /**
     * 提供静态资源访问，支持目录重定向
     * 访问格式：http://localhost:9025/api/static/{deployKey}[/{fileName}]
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveStaticResource(
            @PathVariable("deployKey") String deployKey,
            HttpServletRequest request) {
        try {
            var resolved = resourceResolver.resolve(deployKey, request.getRequestURI(), request.getContextPath());
            if (resolved.directoryRedirect()) {
                HttpHeaders headers = new HttpHeaders();
                headers.add("Location", request.getRequestURI() + "/");
                return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
            }
            Resource resource = resolved.resource();
            long lastModified = resource.lastModified();
            var response = ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore().mustRevalidate())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header(HttpHeaders.EXPIRES, "0")
                    .header("X-File-Last-Modified", String.valueOf(lastModified))
                    .lastModified(lastModified)
                    .header("Content-Type", getContentTypeWithCharset(resource.getFilename()));
            if ("HEAD".equals(request.getMethod())) {
                return response.contentLength(resource.contentLength()).build();
            }
            return response.body(resource);
        } catch (IOException | IllegalArgumentException exception) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * 根据文件扩展名返回带字符编码的 Content-Type
     */
    private String getContentTypeWithCharset(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
