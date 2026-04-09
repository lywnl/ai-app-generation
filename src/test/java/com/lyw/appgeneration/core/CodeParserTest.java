package com.lyw.appgeneration.core;

import com.lyw.appgeneration.ai.model.HtmlCodeResult;
import com.lyw.appgeneration.ai.model.MultiFileCodeResult;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CodeParserTest {

    @Test
    void parseHtmlCode() {
        // 测试带代码块标记的HTML
        String codeWithBlock = "```html\n<!DOCTYPE html>\n<html>\n<head>\n    <title>Test</title>\n</head>\n<body>\n    <h1>Hello World</h1>\n</body>\n</html>\n```";
        HtmlCodeResult result = CodeParser.parseHtmlCode(codeWithBlock);
        assertNotNull(result);
        assertTrue(result.getHtmlCode().contains("<!DOCTYPE html>"));
        assertTrue(result.getHtmlCode().contains("<h1>Hello World</h1>"));

        // 测试不带代码块标记的纯HTML
        String pureHtml = "<!DOCTYPE html><html><body>Test</body></html>";
        HtmlCodeResult result2 = CodeParser.parseHtmlCode(pureHtml);
        assertNotNull(result2);
        assertEquals(pureHtml, result2.getHtmlCode());

        // 测试空字符串
        HtmlCodeResult result3 = CodeParser.parseHtmlCode("");
        assertNotNull(result3);
        assertEquals("", result3.getHtmlCode());
    }

    @Test
    void parseMultiFileCode() {
        // 测试完整的HTML+CSS+JS代码
        String fullCode = """
            ```html
            <!DOCTYPE html>
            <html>
            <head>
                <link rel="stylesheet" href="style.css">
            </head>
            <body>
                <h1>Hello</h1>
                <script src="script.js"></script>
            </body>
            </html>
            ```

            ```css
            body {
                margin: 0;
                padding: 20px;
            }
            h1 {
                color: blue;
            }
            ```

            ```javascript
            document.addEventListener('DOMContentLoaded', function() {
                console.log('Loaded');
            });
            ```""";

        MultiFileCodeResult result = CodeParser.parseMultiFileCode(fullCode);
        assertNotNull(result);

        // 验证HTML
        assertTrue(result.getHtmlCode().contains("<!DOCTYPE html>"));
        assertTrue(result.getHtmlCode().contains("<h1>Hello</h1>"));

        // 验证CSS
        assertTrue(result.getCssCode().contains("margin: 0"));
        assertTrue(result.getCssCode().contains("color: blue"));

        // 验证JS
        assertTrue(result.getJsCode().contains("DOMContentLoaded"));
        assertTrue(result.getJsCode().contains("console.log"));

        // 测试只有部分代码的情况（只有HTML和CSS）
        String partialCode = """
            ```html
            <div>Content</div>
            ```

            ```css
            div { font-size: 16px; }
            ```""";

        MultiFileCodeResult result2 = CodeParser.parseMultiFileCode(partialCode);
        assertNotNull(result2);
        assertTrue(result2.getHtmlCode().contains("<div>Content</div>"));
        assertTrue(result2.getCssCode().contains("font-size: 16px"));
        assertNull(result2.getJsCode());

        // 测试空字符串
        MultiFileCodeResult result3 = CodeParser.parseMultiFileCode("");
        assertNotNull(result3);
        assertNull(result3.getHtmlCode());
        assertNull(result3.getCssCode());
        assertNull(result3.getJsCode());
    }
}