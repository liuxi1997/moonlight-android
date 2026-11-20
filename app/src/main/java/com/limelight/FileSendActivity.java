package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog; // upstream logger

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Buffer;

/**
 * 文件桥发送页（remote_desk fork · G 线）：手机 → 被控 PC 单向推文件。
 *
 * 链路：本页 SAF 选文件 → OkHttp 分块流式 POST
 *      → VPS（frps: 50000+端口族偏移）→ frpc → 被控端 host/filebridge/filebridge.py
 *
 * 启动契约（Game 面板「发文件」钮挂载，本类不改 Game.java）：
 *   EXTRA_HOST_ADDRESS     String  VPS 地址（frps 所在机器）
 *   EXTRA_FILEBRIDGE_PORT  int     50000 + (httpsPort - 47984)，与 Sunshine 端口族同偏移
 *   EXTRA_PC_NAME          String  目标机器显示名
 *
 * token：首次发送弹输入框，按主机存 SharedPreferences（key = "token_" + host）。
 * 纯 HTTP 无 TLS（个人自用取舍，见 host/filebridge/README.md）；密文风险与收紧路径同见 README。
 */
public class FileSendActivity extends Activity {

    public static final String EXTRA_HOST_ADDRESS = "com.limelight.filebridge.EXTRA_HOST_ADDRESS";
    public static final String EXTRA_FILEBRIDGE_PORT = "com.limelight.filebridge.EXTRA_FILEBRIDGE_PORT";
    public static final String EXTRA_PC_NAME = "com.limelight.filebridge.EXTRA_PC_NAME";

    private static final String PREFS_FILE = "remote_desk_filebridge";
    private static final int REQUEST_PICK_FILE = 1001;
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");

    private String host;
    private int port;
    private String pcName;
    private SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient client;
    private Call currentCall;

    // SAF 选中的文件
    private Uri fileUri;
    private String fileName;
    private long fileSize;

    // UI（无 XML 布局，代码直建，复用上游 Activity 直继承风格）
    private TextView fileLabel;
    private ProgressBar progressBar;
    private TextView statusLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        host = getIntent().getStringExtra(EXTRA_HOST_ADDRESS);
        port = getIntent().getIntExtra(EXTRA_FILEBRIDGE_PORT, 50000);
        pcName = getIntent().getStringExtra(EXTRA_PC_NAME);
        if (pcName == null || pcName.isEmpty()) {
            pcName = host;
        }
        if (host == null || host.isEmpty()) {
            Toast.makeText(this, "缺少目标主机（EXTRA_HOST_ADDRESS）", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        prefs = getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);

        // 大文件经中继可慢：不限写超时，读响应给 30s
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        setContentView(buildUi());
        refreshTokenLabel();
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText(pcName + " · 发送文件");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(4));

        TextView endpoint = new TextView(this);
        endpoint.setText("经 " + host + ":" + port + "（frp 中继，HTTP 明文）");
        endpoint.setTextSize(13);
        endpoint.setPadding(0, 0, 0, dp(16));

        fileLabel = new TextView(this);
        fileLabel.setTextSize(15);
        fileLabel.setPadding(0, 0, 0, dp(8));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        statusLabel = new TextView(this);
        statusLabel.setTextSize(14);
        statusLabel.setPadding(0, dp(8), 0, dp(16));
        statusLabel.setText("选择文件后点「发送」");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button pickBtn = new Button(this);
        pickBtn.setText("选择文件");
        pickBtn.setOnClickListener(v -> launchPicker());

        Button sendBtn = new Button(this);
        sendBtn.setText("发送");
        sendBtn.setEnabled(false); // 选好文件才解锁
        sendBtn.setOnClickListener(v -> startUpload());

        Button tokenBtn = new Button(this);
        tokenBtn.setText("重设令牌");
        tokenBtn.setOnClickListener(v -> {
            prefs.edit().remove(tokenKey()).apply();
            refreshTokenLabel();
            promptForToken(true);
        });

        row.addView(pickBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(sendBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tokenBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(title);
        root.addView(endpoint);
        root.addView(fileLabel);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusLabel);
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private String tokenKey() {
        return "token_" + host;
    }

    private void refreshTokenLabel() {
        boolean noToken = prefs.getString(tokenKey(), "").isEmpty();
        // 提示区复用：文件未选时显示 token 状态
        if (fileUri == null) {
            statusLabel.setText(noToken ? "尚未保存 token，发送时会要求输入"
                    : "token 已按主机保存");
        }
    }

    private void promptForToken(final boolean force) {
        if (!force && !prefs.getString(tokenKey(), "").isEmpty()) {
            onTokenReady();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("32 位 hex token");
        new AlertDialog.Builder(this)
                .setTitle("文件桥 token")
                .setMessage("在被控 PC 上跑 host/filebridge/install-filebridge.ps1，控制台会打印 token")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("保存", (d, w) -> {
                    String token = input.getText().toString().trim();
                    if (token.isEmpty()) {
                        Toast.makeText(this, "token 不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString(tokenKey(), token).apply();
                    refreshTokenLabel();
                    onTokenReady();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void onTokenReady() {
        if (fileUri == null) {
            launchPicker();
        } else {
            startUpload();
        }
    }

    // ---------------- SAF 选文件 ----------------

    private void launchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_FILE);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "此设备没有文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_FILE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        fileUri = data.getData();
        fileName = resolveDisplayName(fileUri);
        fileSize = resolveSize(fileUri);

        if (fileName == null || fileName.isEmpty()) {
            fileName = "file_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        }
        if (fileSize <= 0) {
            // 服务端要求 Content-Length（Python 标准库不收 chunked），拿不到大小就如实拒绝
            fileUri = null;
            fileLabel.setText("");
            statusLabel.setText("无法确定文件大小，已取消");
            Toast.makeText(this, "无法确定文件大小", Toast.LENGTH_LONG).show();
            return;
        }

        fileLabel.setText(fileName + "（" + humanSize(fileSize) + "）");
        statusLabel.setText("已选择，点「发送」");
        Button sendBtn = findByText("发送");
        if (sendBtn != null) {
            sendBtn.setEnabled(true);
        }
    }

    private String resolveDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.getString(idx) != null) {
                    return c.getString(idx);
                }
            }
        } catch (Exception e) {
            LimeLog.warning("FileSend: query display name failed: " + e);
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
    }

    private long resolveSize(Uri uri) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) {
                long size = pfd.getStatSize();
                if (size > 0) {
                    return size;
                }
            }
        } catch (Exception e) {
            LimeLog.warning("FileSend: stat size failed: " + e);
        }
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception e) {
            LimeLog.warning("FileSend: query size failed: " + e);
        }
        return -1;
    }

    // ---------------- 上传 ----------------

    private void startUpload() {
        if (fileUri == null) {
            launchPicker();
            return;
        }
        String token = prefs.getString(tokenKey(), "");
        if (token.isEmpty()) {
            promptForToken(true);
            return;
        }

        setBusy(true);
        progressBar.setProgress(0);
        statusLabel.setText("连接中…");

        String url = "http://" + host + ":" + port
                + "/upload?filename=" + Uri.encode(fileName);

        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return OCTET_STREAM;
            }

            @Override
            public long contentLength() {
                return fileSize; // 固定长度：filebridge 用 Content-Length 做上限校验
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream in = getContentResolver().openInputStream(fileUri)) {
                    if (in == null) {
                        throw new IOException("无法读取所选文件");
                    }
                    byte[] buf = new byte[64 * 1024];
                    Buffer chunk = new Buffer();
                    long written = 0;
                    long lastPosted = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        chunk.write(buf, 0, n);
                        sink.write(chunk, n);
                        written += n;
                        // 每 256KB 或完成时刷一次进度，避免 2GB 文件产生数万次 UI 消息
                        if (written / (256 * 1024) != lastPosted / (256 * 1024) || written == fileSize) {
                            lastPosted = written;
                            postProgress(written);
                        }
                    }
                }
            }
        };

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/octet-stream")
                .post(body)
                .build();

        currentCall = client.newCall(request);
        final long startMs = System.currentTimeMillis();
        currentCall.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postUi(() -> {
                    setBusy(false);
                    if (call.isCanceled() || isFinishing()) {
                        return;
                    }
                    statusLabel.setText("失败：" + e.getMessage());
                    Toast.makeText(FileSendActivity.this,
                            "发送失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String bodyText = "";
                try {
                    bodyText = response.body() != null ? response.body().string() : "";
                } catch (Exception ignored) {
                }
                final int code = response.code();
                final String text = bodyText;
                postUi(() -> {
                    setBusy(false);
                    if (isFinishing()) {
                        return;
                    }
                    if (code == 200) {
                        long secs = Math.max(1, (System.currentTimeMillis() - startMs) / 1000);
                        progressBar.setProgress(100);
                        statusLabel.setText("完成（用时 " + secs + " 秒）");
                        Toast.makeText(FileSendActivity.this,
                                "已发送到 " + pcName, Toast.LENGTH_SHORT).show();
                    } else if (code == 401) {
                        statusLabel.setText("token 不对（401）");
                        Toast.makeText(FileSendActivity.this,
                                "token 不对，点「重设令牌」重填", Toast.LENGTH_LONG).show();
                    } else if (code == 413) {
                        statusLabel.setText("超过服务端大小上限");
                        Toast.makeText(FileSendActivity.this,
                                "文件超过 PC 端大小上限（默认 2GB）", Toast.LENGTH_LONG).show();
                    } else {
                        statusLabel.setText("HTTP " + code);
                        Toast.makeText(FileSendActivity.this,
                                "HTTP " + code + "：" + text, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void setBusy(boolean busy) {
        // 忙时整体只读，防重复提交；无 id 布局，按文本找回钮引用
        setEnabled("选择文件", !busy);
        setEnabled("发送", !busy && fileUri != null);
        setEnabled("重设令牌", !busy);
    }

    private void setEnabled(String label, boolean enabled) {
        Button b = findByText(label);
        if (b != null) {
            b.setEnabled(enabled);
        }
    }

    private Button findByText(String label) {
        return findByText((ViewGroup) getWindow().getDecorView(), label);
    }

    private Button findByText(ViewGroup group, String label) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button && label.contentEquals(((Button) child).getText())) {
                return (Button) child;
            }
            if (child instanceof ViewGroup) {
                Button b = findByText((ViewGroup) child, label);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    private void postProgress(final long written) {
        postUi(() -> {
            if (isFinishing()) {
                return;
            }
            int percent = (int) (written * 100 / fileSize);
            progressBar.setProgress(percent);
            statusLabel.setText("发送中 " + percent + "%（"
                    + humanSize(written) + " / " + humanSize(fileSize) + "）");
        });
    }

    private void postUi(Runnable r) {
        mainHandler.post(r);
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
        }
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024);
        }
        if (bytes >= 1024) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    @Override
    protected void onDestroy() {
        if (currentCall != null) {
            currentCall.cancel(); // OkHttp 会以 Canceled IOException 回调，onFailure 里已静音
            currentCall = null;
        }
        super.onDestroy();
    }
}
