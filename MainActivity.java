package com.maintenx.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

/**
 * MaintenX shell: loads the bundled index.html in a WebView and adds the native
 * pieces a browser would normally provide (photo picker + camera, saving CSV/JSON
 * files to Downloads, print / save-as-PDF for reports, back button handling).
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final int REQ_FILE = 1001;

    private WebView web;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private Dialog childDialog;
    private WebView childWeb;

    /** Runs inside every page: blob downloads -> native save, window.print -> native print. */
    private static final String INJECT_JS =
        "(function(){if(window.__mxNative)return;window.__mxNative=1;"
        + "var blobs={};var oc=URL.createObjectURL.bind(URL);"
        + "URL.createObjectURL=function(b){var u=oc(b);try{blobs[u]=b}catch(e){}return u};"
        + "var ac=HTMLAnchorElement.prototype.click;"
        + "HTMLAnchorElement.prototype.click=function(){var a=this;"
        + "if(a.hasAttribute('download')&&a.href){var b=blobs[a.href];"
        + "if(b){var r=new FileReader();r.onloadend=function(){var s=String(r.result);"
        + "AndroidBridge.saveFile(a.getAttribute('download')||'maintenx-file',b.type||'application/octet-stream',s.substring(s.indexOf(',')+1));"
        + "delete blobs[a.href]};r.readAsDataURL(b);return}}"
        + "return ac.apply(this,arguments)};"
        + "window.print=function(){AndroidBridge.print()};"
        + "var wo=window.open.bind(window);"
        + "window.open=function(){var w=wo.apply(window,arguments);"
        + "try{if(w)w.print=function(){AndroidBridge.printChild()}}catch(e){}return w};"
        + "})();";

    /** Back button: close modal, else go to Home, else exit. */
    private static final String BACK_JS =
        "(function(){var m=document.getElementById('modal');"
        + "if(m&&m.classList.contains('show')){closeModal();return 'handled'}"
        + "if(typeof demoEntered!=='undefined'&&demoEntered&&typeof page!=='undefined'&&page!=='home'){setPage('home');return 'handled'}"
        + "return 'exit'})()";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(APP_HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);
        configure(web);
        web.addJavascriptInterface(new Bridge(web), "AndroidBridge");
        web.setWebViewClient(new AppClient(true));
        web.setWebChromeClient(new Chrome());

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    private void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
    }

    // ---------------------------------------------------------------- clients

    private class AppClient extends WebViewClient {
        private final boolean isMain;

        AppClient(boolean isMain) {
            this.isMain = isMain;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (APP_HOST.equals(u.getHost())) return false;
            String sc = u.getScheme();
            if ("http".equals(sc) || "https".equals(sc) || "mailto".equals(sc) || "tel".equals(sc)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
            return false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.evaluateJavascript(INJECT_JS, null);
        }
    }

    private class Chrome extends WebChromeClient {

        @Override
        public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = cb;
            cameraUri = null;

            Intent pick = p.createIntent();
            Intent chooser = new Intent(Intent.ACTION_CHOOSER);
            chooser.putExtra(Intent.EXTRA_INTENT, pick);
            chooser.putExtra(Intent.EXTRA_TITLE, "Select file");

            boolean wantsImage = false;
            String[] types = p.getAcceptTypes();
            if (types != null) {
                for (String t : types) {
                    if (t != null && t.startsWith("image")) wantsImage = true;
                }
            }
            if (wantsImage) {
                try {
                    File dir = new File(getCacheDir(), "captures");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = File.createTempFile("photo_", ".jpg", dir);
                    cameraUri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", f);
                    Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                } catch (IOException e) {
                    cameraUri = null;
                }
            }
            try {
                startActivityForResult(chooser, REQ_FILE);
            } catch (Exception e) {
                filePathCallback = null;
                cb.onReceiveValue(null);
                return false;
            }
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            closeChild();
            WebView child = new WebView(MainActivity.this);
            configure(child);
            child.addJavascriptInterface(new Bridge(child), "AndroidBridge");
            child.setWebViewClient(new AppClient(false));
            child.setWebChromeClient(new Chrome() {
                @Override
                public void onCloseWindow(WebView window) {
                    closeChild();
                }
            });
            showChild(child);
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(child);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("OK", (d, i) -> r.confirm())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setPositiveButton("OK", (d, i) -> r.confirm())
                    .setNegativeButton("Cancel", (d, i) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView v, String url, String msg, String def, final JsPromptResult r) {
            final EditText input = new EditText(MainActivity.this);
            if (def != null) input.setText(def);
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(msg)
                    .setView(input)
                    .setPositiveButton("OK", (d, i) -> r.confirm(input.getText().toString()))
                    .setNegativeButton("Cancel", (d, i) -> r.cancel())
                    .setOnCancelListener(d -> r.cancel())
                    .show();
            return true;
        }
    }

    // ---------------------------------------------- report window (window.open)

    private void showChild(WebView child) {
        childWeb = child;
        Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#062746"));
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad * 2, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Report preview");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button print = new Button(this);
        print.setText("Print / PDF");
        print.setOnClickListener(v -> printWebView(childWeb, "MaintenX report"));
        bar.addView(print);

        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> closeChild());
        bar.addView(close);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(child, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        d.setContentView(root);
        d.setOnCancelListener(x -> closeChild());
        childDialog = d;
        d.show();
        d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void closeChild() {
        if (childDialog != null) {
            try {
                childDialog.dismiss();
            } catch (Exception ignored) {
            }
            childDialog = null;
        }
        if (childWeb != null) {
            try {
                childWeb.destroy();
            } catch (Exception ignored) {
            }
            childWeb = null;
        }
    }

    private void printWebView(WebView w, String name) {
        if (w == null) return;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        PrintDocumentAdapter adapter = w.createPrintDocumentAdapter(name);
        pm.print(name, adapter, new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build());
    }

    // ------------------------------------------------------- JS <-> native

    private class Bridge {
        private final WebView target;

        Bridge(WebView target) {
            this.target = target;
        }

        @JavascriptInterface
        public void print() {
            runOnUiThread(() -> printWebView(target, "MaintenX"));
        }

        @JavascriptInterface
        public void printChild() {
            runOnUiThread(() -> printWebView(childWeb != null ? childWeb : target, "MaintenX report"));
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("Could not create file");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) throw new IOException("Could not open file");
                    os.write(data);
                }
                toast("Saved to Downloads: " + safe);
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
        }
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    // ---------------------------------------------------- activity plumbing

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || filePathCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && (data.getData() != null || data.getClipData() != null)) {
                result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            } else if (cameraUri != null) {
                result = new Uri[]{cameraUri};
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        cameraUri = null;
    }

    @Override
    public void onBackPressed() {
        if (childDialog != null && childDialog.isShowing()) {
            closeChild();
            return;
        }
        web.evaluateJavascript(BACK_JS, value -> {
            if (value == null || value.contains("exit")) finish();
        });
    }

    @Override
    protected void onDestroy() {
        closeChild();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
