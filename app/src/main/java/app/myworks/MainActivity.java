package app.myworks;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    WebView web;
    ValueCallback<Uri[]> fileCb;
    OutputStream out; String outName;
    final List<String[]> shared = new ArrayList<>(); // name, mime, path

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String p = u.getPath();
                if (p != null && p.startsWith("/__shared/")) {
                    try {
                        int i = Integer.parseInt(p.substring(10));
                        synchronized (shared) {
                            String[] f = shared.get(i);
                            Map<String, String> sh = new HashMap<>();
                            sh.put("Access-Control-Allow-Origin", "*");
                            return new WebResourceResponse(f[1], null, 200, "OK", sh, new FileInputStream(f[2]));
                        }
                    } catch (Exception e) {
                        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                    }
                }
                if (p == null || p.equals("/")) p = "/index.html";
                String mime = mimeOf(p);
                Map<String, String> h = new HashMap<>();
                h.put("Access-Control-Allow-Origin", "*");
                try {
                    InputStream in = getAssets().open(p.substring(1));
                    return new WebResourceResponse(mime, "utf-8", 200, "OK", h, in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(i, "Choose files"), 7); }
                catch (Exception e) { fileCb = null; cb.onReceiveValue(null); return false; }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            String name = URLUtil.guessFileName(url, cd, mime);
            if (url.startsWith("blob:")) {
                String js = "(async()=>{try{const r=await fetch('" + url + "');const b=await r.blob();"
                    + "AndroidBridge.begin(" + JSONq(name) + "," + JSONq(mime == null ? "application/octet-stream" : mime) + ");"
                    + "const C=1572864;for(let o=0;o<b.size;o+=C){const d=await new Promise(res=>{const f=new FileReader();"
                    + "f.onload=()=>res(f.result.split(',')[1]);f.readAsDataURL(b.slice(o,o+C));});AndroidBridge.chunk(d);}"
                    + "AndroidBridge.end();}catch(e){AndroidBridge.fail(String(e));}})();";
                web.evaluateJavascript(js, null);
            }
        });

        collectShared(getIntent());
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        collectShared(i);
        web.evaluateJavascript("window.takeShared&&window.takeShared()", null);
    }

    void collectShared(Intent in) {
        if (in == null) return;
        String a = in.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (uris.isEmpty()) return;
        File dir = new File(getCacheDir(), "shared");
        dir.mkdirs();
        synchronized (shared) {
            for (Uri u : uris) {
                try {
                    String name = null;
                    try (android.database.Cursor c = getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst()) name = c.getString(0);
                    } catch (Exception ignored) {}
                    if (name == null) name = "shared-" + System.currentTimeMillis();
                    String mime = getContentResolver().getType(u);
                    if (mime == null) mime = "application/octet-stream";
                    File f = new File(dir, shared.size() + "-" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                    try (InputStream is = getContentResolver().openInputStream(u); OutputStream os = new FileOutputStream(f)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    shared.add(new String[]{name, mime, f.getAbsolutePath()});
                } catch (Exception ignored) {}
            }
        }
    }

    static String JSONq(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static String mimeOf(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html")) return "text/html";
        if (l.endsWith(".js") || l.endsWith(".mjs")) return "text/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".json") || l.endsWith(".webmanifest")) return "application/json";
        return "application/octet-stream";
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 7 && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int i = 0; i < n; i++) r[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) r = new Uri[]{data.getData()};
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
        }
        super.onActivityResult(req, res, data);
    }

    @Override public void onBackPressed() {
        String js = "(function(){var a=document.getElementById('app');var d=document.querySelector('dialog[open]');"
            + "if(d){d.close();return 1;}"
            + "if(a&&a.classList.contains('show-detail')){a.classList.remove('show-detail');return 1;}return 0;})()";
        web.evaluateJavascript(js, v -> { if (!"1".equals(v)) finish(); });
    }

    class Bridge {
        @JavascriptInterface public String sharedList() {
            StringBuilder sb = new StringBuilder("[");
            synchronized (shared) {
                for (int i = 0; i < shared.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("{\"i\":").append(i).append(",\"name\":").append(JSONq(shared.get(i)[0]))
                      .append(",\"type\":").append(JSONq(shared.get(i)[1])).append("}");
                }
            }
            return sb.append("]").toString();
        }
        @JavascriptInterface public void sharedClear() {
            synchronized (shared) {
                for (String[] f : shared) new File(f[2]).delete();
                shared.clear();
            }
        }
        @JavascriptInterface public void begin(String name, String mime) {
            try {
                outName = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, outName);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    out = getContentResolver().openOutputStream(u);
                } else {
                    File d = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    out = new FileOutputStream(new File(d, outName));
                }
            } catch (Exception e) { fail(String.valueOf(e)); }
        }
        @JavascriptInterface public void chunk(String b64) {
            try { if (out != null) out.write(Base64.decode(b64, Base64.DEFAULT)); } catch (Exception e) { fail(String.valueOf(e)); }
        }
        @JavascriptInterface public void end() {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            out = null;
            toast("Saved to Downloads: " + outName);
        }
        @JavascriptInterface public void fail(String m) {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            out = null;
            toast("Could not save: " + m);
        }
        void toast(String m) { runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show()); }
    }
}
