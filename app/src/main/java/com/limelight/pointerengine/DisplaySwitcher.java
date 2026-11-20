package com.limelight.pointerengine;

import android.os.Looper;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.xmlpull.v1.XmlPullParserException;

import com.limelight.LimeLog;
import com.limelight.nvstream.ConnectionContext;
import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;

/**
 * 多显示器切换（M1 · remote_desk fork · D 线）
 *
 * 纯逻辑类：零 View、零 Game.java 引用。UI 接线在 Game（A 线承诺）：
 * 面板「切显示器」→ listDesktopApps() 弹 N 项选择 → 选中项经 SwitchSink.restartStreamWithApp(appId)
 * 交还宿主执行流重启（Game 侧 conn.stop() → 按新 appId 重建 NvConnection → conn.start()，
 * 其内部 startApp 自动处理 resume/quitAndLaunch，见 NvConnection.java:310-345）。
 *
 * 依赖 E 线被控端先用 Generate-DisplayApps.ps1 生成 Desktop-N 条目（docs/modules/deploy-client.md:34 契约）。
 *
 * 线程约束：本类所有方法都会发起 HTTPS 请求，禁止在主线程调用
 * （Android NetworkOnMainThreadException；方法内已加 Looper 硬校验，违反即抛 IllegalStateException）。
 *
 * 错误分类：统一抛 {@link SwitchException}（IOException 子类），reason 对应
 *   401 = 未配对（NvHTTP.java:324 证书不匹配也会合成 401）
 *   599 = 会话属他人，需先在原设备退出（NvHTTP.java:822 quitApp 合成；NvConnection.java:355）
 *   470 = 不可恢复他人设备启动的会话（NvConnection.java:322）
 * 599/470 实际发生在宿主重启流阶段（NvConnection.startApp 内部），宿主侧可用
 * SwitchException.fromHostResponse() 对 HostHttpResponseException 做同一套分类。
 */
public class DisplaySwitcher {

    /** E 线生成器写出的应用名前缀（Desktop-1..N） */
    public static final String DESKTOP_APP_PREFIX = "Desktop-";

    private final ComputerDetails.AddressTuple address;
    private final int httpsPort;
    private final String uniqueId;
    private final X509Certificate serverCert;
    private final LimelightCryptoProvider cryptoProvider;

    // NvHTTP 构造涉及 SSL 状态初始化，惰性创建并复用（httpsPort==0 时其内部会经 serverinfo 补齐并缓存）
    private final AtomicReference<NvHTTP> httpRef = new AtomicReference<NvHTTP>();

    /**
     * @param address     主机地址（HTTP 基址；与 Game extras 的 host/port 同源）
     * @param port        HTTP 端口（Game.java:324 的 EXTRA_PORT，默认 47989；显式传入以对齐 extras 两参写法，
     *                    与 address.port 不一致时以本参为准重建 tuple）
     * @param httpsPort   HTTPS 端口（Game.java:325 的 EXTRA_HTTPS_PORT，0 = 未知，由 NvHTTP 经 serverinfo 补齐）
     * @param uniqueId    本机配对 UID（与 Game extras 的 uniqueId 同源）
     * @param serverCert  已配对主机的固定证书（可为 null = 未配对/走默认信任链）
     * @param cryptoProvider 客户端证书提供者（PlatformBinding.getCryptoProvider(context)）
     */
    public DisplaySwitcher(ComputerDetails.AddressTuple address, int port, int httpsPort,
                           String uniqueId, X509Certificate serverCert,
                           LimelightCryptoProvider cryptoProvider) {
        // AddressTuple 构造器会剥离 IPv6 方括号并校验参数（ComputerDetails.java:16-31），统一走它
        this.address = (port == address.port) ? address : new ComputerDetails.AddressTuple(address.address, port);
        this.httpsPort = httpsPort;
        this.uniqueId = uniqueId;
        this.serverCert = serverCert;
        this.cryptoProvider = cryptoProvider;
    }

    /** 会话切换的执行流交还接口：宿主（Game）实现，收到回调后按新 appId 重建 NvConnection 并 conn.start() */
    public interface SwitchSink {
        void restartStreamWithApp(int appId);
    }

    /**
     * 切换错误（IOException 子类，可按 reason 分类提示）。
     */
    public static class SwitchException extends IOException {
        private static final long serialVersionUID = 2026112001L;

        public static final int REASON_OTHER = 0;
        public static final int REASON_UNPAIRED = 401;        // 未配对
        public static final int REASON_CANNOT_RESUME = 470;   // 会话非本设备启动，无法 resume
        public static final int REASON_SESSION_BUSY = 599;    // 会话属他人，先在原设备退出
        public static final int REASON_NO_DESKTOP_APPS = 1;   // 被控端没有 Desktop-N 条目（E 线未部署）
        public static final int REASON_BAD_RESPONSE = 2;      // 应答非合法 XML/缺失字段

        private final int reason;

        public SwitchException(int reason, String message) {
            super(message);
            this.reason = reason;
        }

        public SwitchException(int reason, String message, Throwable cause) {
            super(message);
            this.reason = reason;
            initCause(cause);
        }

        public int getReason() {
            return reason;
        }

        /** 把 HTTP 层异常归一到本分类（供宿主重启流阶段复用，599/470 在该阶段产生） */
        public static SwitchException fromHostResponse(HostHttpResponseException e) {
            int code = e.getErrorCode();
            if (code == REASON_UNPAIRED) {
                return new SwitchException(REASON_UNPAIRED,
                        "主机未配对（Error code: 401），请先在应用列表完成配对", e);
            }
            else if (code == REASON_SESSION_BUSY) {
                return new SwitchException(REASON_SESSION_BUSY,
                        "当前会话由其他设备启动，请先在原设备退出串流后重试（Error code: 599）", e);
            }
            else if (code == REASON_CANNOT_RESUME) {
                return new SwitchException(REASON_CANNOT_RESUME,
                        "会话非本设备启动，无法恢复；请在原设备退出后再切换（Error code: 470）", e);
            }
            else {
                return new SwitchException(REASON_OTHER, e.getMessage(), e);
            }
        }
    }

    /**
     * 拉取应用列表并过滤出 Desktop-N 条目。
     * 调 applist 端点（NvHTTP.java:691-705），按名称前缀 {@link #DESKTOP_APP_PREFIX} 过滤，保持列表序。
     *
     * @throws SwitchException 401=未配对；REASON_NO_DESKTOP_APPS=被控端未生成 Desktop 条目
     * @throws IOException 网络不可达等
     */
    public List<NvApp> listDesktopApps() throws IOException {
        assertNotMainThread();

        List<NvApp> desktopApps = new ArrayList<NvApp>();
        try {
            for (NvApp app : httpConn().getAppList()) {
                if (app.getAppName() != null && app.getAppName().startsWith(DESKTOP_APP_PREFIX)) {
                    desktopApps.add(app);
                }
            }
        }
        catch (HostHttpResponseException e) {
            throw SwitchException.fromHostResponse(e);
        }
        catch (XmlPullParserException e) {
            throw new SwitchException(SwitchException.REASON_BAD_RESPONSE,
                    "applist 应答解析失败：" + e.getMessage(), e);
        }

        LimeLog.info("DisplaySwitcher: " + desktopApps.size() + " Desktop app(s) found");
        return desktopApps;
    }

    /**
     * 按列表序取第 index 个 Desktop 条目（0 基：index 0 = 第一个 Desktop 条目，即 Desktop-1）。
     * 每次实时拉取（Sunshine 重新生成条目后 ID 可能变化）。
     *
     * @throws IndexOutOfBoundsException index 越界
     */
    public NvApp getAppByIndex(int index) throws IOException {
        List<NvApp> desktopApps = listDesktopApps();
        if (index < 0 || index >= desktopApps.size()) {
            throw new IndexOutOfBoundsException(
                    "Desktop app index " + index + " out of range: " + desktopApps.size());
        }
        return desktopApps.get(index);
    }

    /**
     * 切换到目标 Desktop 应用：先做预检（可达性/配对/目标存在，避免宿主 conn.stop() 之后才发现失败），
     * 通过后经 SwitchSink 把执行流交还宿主重建连接。
     *
     * @throws SwitchException 分类错误（401 未配对 / 无 Desktop 条目 / 目标不在列表等）
     * @throws IOException 网络不可达等
     */
    public void switchTo(int appId, SwitchSink sink) throws IOException {
        preflight(appId);
        sink.restartStreamWithApp(appId);
    }

    /** 同 {@link #switchTo(int, SwitchSink)}，直接传 NvApp（通常来自 getAppByIndex/listDesktopApps） */
    public void switchTo(NvApp app, SwitchSink sink) throws IOException {
        if (app == null) {
            throw new SwitchException(SwitchException.REASON_OTHER, "切换目标为空，请先 listDesktopApps() 选择");
        }
        switchTo(app.getAppId(), sink);
    }

    /**
     * 调试通道：不经宿主重启流，直接向被控端发 launch 请求（不建立 RTSP/串流会话）。
     * 自建 ConnectionContext（riKey=AES-128 随机、riKeyId=SecureRandom，对齐 NvConnection.java:64-87 的私有构造式——
     * NvConnection 的 context/riKey 均私有，公共面无 in-session 换 app 入口，故此处独立生成）。
     * verb 固定 "launch"：每次请求携带新 rikey/rikeyid，Sunshine 按新会话处理；
     * NvHTTP 缺 sessionUrl0 时已有 httpsPort+26 的 RTSP 合成补丁（NvHTTP.java:797-802）。
     * 目标为 Sunshine（Desktop-N 属 Sunshine/E 线契约），isNvidiaServerSoftware 恒 false
     * （launchApp 内 GFE 的 SOPS/帧率特殊分支不会触发，NvHTTP.java:762-778）。
     *
     * @return true = gamesession 已建立（NvHTTP.java:793-808）
     */
    public boolean launchDirect(int appId) throws IOException {
        return launchDirect(appId, 1920, 1080, 60);
    }

    /** 同 {@link #launchDirect(int)}，可指定 launch 请求里的 mode 分辨率/帧率（1080p60 为手机基线档） */
    public boolean launchDirect(int appId, int width, int height, int refreshRate) throws IOException {
        assertNotMainThread();

        ConnectionContext ctx = new ConnectionContext();
        ctx.riKey = generateRiAesKey();
        ctx.riKeyId = new SecureRandom().nextInt();
        ctx.isNvidiaServerSoftware = false;
        ctx.negotiatedWidth = width;
        ctx.negotiatedHeight = height;
        ctx.streamConfig = new StreamConfiguration.Builder()
                .setResolution(width, height)
                .setRefreshRate(refreshRate)
                .setLaunchRefreshRate(refreshRate)
                .build();

        try {
            return httpConn().launchApp(ctx, "launch", appId, false);
        }
        catch (HostHttpResponseException e) {
            throw SwitchException.fromHostResponse(e);
        }
        catch (XmlPullParserException e) {
            throw new SwitchException(SwitchException.REASON_BAD_RESPONSE,
                    "launch 应答解析失败：" + e.getMessage(), e);
        }
    }

    // 与 NvConnection.generateRiAesKey()（NvConnection.java:71-83）同式：RI 密钥固定 128 位 AES
    private static SecretKey generateRiAesKey() {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(128);
            return keyGen.generateKey();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    // 预检：可达 + 已配对 + Desktop 条目存在 + 目标在列表内。失败即抛分类异常，宿主无需先拆当前流
    private void preflight(int appId) throws IOException {
        List<NvApp> desktopApps = listDesktopApps();
        if (desktopApps.isEmpty()) {
            throw new SwitchException(SwitchException.REASON_NO_DESKTOP_APPS,
                    "被控端没有 " + DESKTOP_APP_PREFIX + "N 条目，请先在被控端运行 Generate-DisplayApps.ps1");
        }
        for (NvApp app : desktopApps) {
            if (app.getAppId() == appId) {
                return;
            }
        }
        throw new SwitchException(SwitchException.REASON_OTHER,
                "目标应用（ID " + appId + "）不在 Desktop 列表中，请刷新后重试");
    }

    private NvHTTP httpConn() throws IOException {
        NvHTTP conn = httpRef.get();
        if (conn == null) {
            // NvHTTP 五参签名（NvHTTP.java:203）；构造失败（非法地址等）包成 SwitchException 统一分类
            conn = new NvHTTP(address, httpsPort, uniqueId, serverCert, cryptoProvider);
            if (!httpRef.compareAndSet(null, conn)) {
                conn = httpRef.get();
            }
        }
        return conn;
    }

    // 契约：所有 HTTP 调用必须后台线程；主线程调用直接快速失败并指明原因
    private static void assertNotMainThread() {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "DisplaySwitcher 的 HTTP 调用禁止在主线程执行（会触发 NetworkOnMainThreadException），请放入工作线程");
        }
    }
}
