package com.lyw.appgeneration.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.UUID;

@Slf4j
public class WebScreenshotUtils {

    private static final int DEFAULT_WIDTH = 1600;
    private static final int DEFAULT_HEIGHT = 900;
    private static final String[] CHROME_BINARY_CANDIDATES = {
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable"
    };
    private static final String[] CHROME_DRIVER_CANDIDATES = {
            "/usr/bin/chromedriver",
            "/usr/lib/chromium/chromedriver",
            "/usr/bin/chromium-chromedriver"
    };
    private static final ThreadLocal<WebDriver> DRIVER_LOCAL = new ThreadLocal<>();

    private static WebDriver currentDriver() {
        WebDriver driver = DRIVER_LOCAL.get();
        if (driver == null) {
            driver = initChromeDriver(DEFAULT_WIDTH, DEFAULT_HEIGHT);
            DRIVER_LOCAL.set(driver);
        }
        return driver;
    }

    private static void closeCurrentDriver() {
        WebDriver driver = DRIVER_LOCAL.get();
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                log.warn("关闭 WebDriver 失败", e);
            } finally {
                DRIVER_LOCAL.remove();
            }
        }
    }

    static void main() {

    }

    /**
     * 生成网页截图
     *
     * @param webUrl 网页 URL
     * @return 压缩后的截图文件路径，失败返回 null
     */
    public static String saveWebPageScreenshot(String webUrl) {
        if (StrUtil.isBlank(webUrl)) {
            log.error("网页 URL 不能为空");
            return null;
        }
        WebDriver driver = currentDriver();
        try {
            String rootPath = System.getProperty("user.dir") + File.separator + "tmp" + File.separator + "screenshots"
                    + File.separator + UUID.randomUUID().toString().substring(0, 8);
            FileUtil.mkdir(rootPath);
            final String imageSuffix = ".png";
            String imageSavePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + imageSuffix;
            driver.get(webUrl);
            waitForPageLoad(driver);
            byte[] screenshotBytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            saveImage(screenshotBytes, imageSavePath);
            log.info("原始截图保存成功: {}", imageSavePath);
            final String compressionSuffix = "_compressed.jpg";
            String compressedImagePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + compressionSuffix;
            compressImage(imageSavePath, compressedImagePath);
            log.info("压缩图片保存成功: {}", compressedImagePath);
            FileUtil.del(imageSavePath);
            return compressedImagePath;
        } catch (Exception e) {
            log.error("网页截图失败: {}", webUrl, e);
            return null;
        } finally {
            closeCurrentDriver();
        }
    }

    /**
     * 初始化 Chrome 浏览器驱动
     */
    private static WebDriver initChromeDriver(int width, int height) {
        try {
            configureChromeDriver();
            WebDriver driver = getWebDriver(width, height);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            return driver;
        } catch (Exception e) {
            log.error("初始化 Chrome 浏览器失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 Chrome 浏览器失败");
        }
    }

    private static void configureChromeDriver() {
        String driverPath = resolveExecutablePath(System.getenv("WEBDRIVER_CHROME_DRIVER"), CHROME_DRIVER_CANDIDATES);
        if (StrUtil.isNotBlank(driverPath)) {
            System.setProperty("webdriver.chrome.driver", driverPath);
            log.info("ChromeDriver 路径: {}", driverPath);
            System.setProperty("webdriver.chrome.logfile", "/app/tmp/chromedriver.log");
            System.setProperty("webdriver.chrome.verboseLogging", "true");
            return;
        }
        log.warn("未检测到本地 ChromeDriver，回退到 WebDriverManager 自动下载");
        WebDriverManager.chromedriver().setup();
    }

    private static @NonNull WebDriver getWebDriver(int width, int height) {
        ChromeOptions options = new ChromeOptions();
        String chromeBin = resolveExecutablePath(System.getenv("CHROME_BIN"), CHROME_BINARY_CANDIDATES);
        if (StrUtil.isNotBlank(chromeBin)) {
            options.setBinary(chromeBin);
            log.info("Chrome binary 路径: {}", chromeBin);
        } else {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "未检测到 Chrome 可执行文件，请检查 CHROME_BIN 或镜像安装");
        }
        options.addArguments("--headless");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-setuid-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--remote-debugging-port=9222");
        options.addArguments("--no-zygote");
        options.addArguments(String.format("--window-size=%d,%d", width, height));
        options.addArguments("--disable-extensions");
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        return new ChromeDriver(options);
    }

    static String resolveExecutablePath(String explicitPath, String... candidatePaths) {
        if (StrUtil.isNotBlank(explicitPath)) {
            if (new File(explicitPath).exists()) {
                return explicitPath;
            }
            log.warn("指定可执行文件不存在: {}", explicitPath);
        }
        for (String candidatePath : candidatePaths) {
            if (new File(candidatePath).exists()) {
                return candidatePath;
            }
        }
        return null;
    }

    /**
     * 保存图片到文件
     */
    private static void saveImage(byte[] imageBytes, String imagePath) {
        try {
            FileUtil.writeBytes(imageBytes, imagePath);
        } catch (Exception e) {
            log.error("保存图片失败: {}", imagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图片失败");
        }
    }

    /**
     * 等待页面加载完成
     */
    private static void waitForPageLoad(WebDriver driver) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(d -> ((JavascriptExecutor) d).executeScript("return document.readyState").equals("complete"));
            Thread.sleep(2000);
            log.info("页面加载完成");
        } catch (Exception e) {
            log.error("等待页面加载时出现异常，继续执行截图", e);
        }
    }

    /**
     * 压缩图片
     */
    private static void compressImage(String originalImagePath, String compressedImagePath) {
        final float compressionQuality = 0.3f;
        try {
            ImgUtil.compress(
                    FileUtil.file(originalImagePath),
                    FileUtil.file(compressedImagePath),
                    compressionQuality
            );
        } catch (Exception e) {
            log.error("压缩图片失败: {} -> {}", originalImagePath, compressedImagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "压缩图片失败");
        }
    }
}
