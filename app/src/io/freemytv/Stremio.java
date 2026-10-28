package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "Continue Watching" from Stremio: the titles you watched last in Stremio, newest first, as the home screen's
 * Stremio tab. They come from your Stremio library (the same one Stremio's own "Continue Watching" row is made of),
 * read with your account's auth key (config.properties "stremio_auth_key"; it opens your Stremio account, so keep it
 * private). OK on a card opens the title's page in Stremio (stremio:///detail/…), or plays it on with
 * stremio_autoplay=true (Stremio's Android TV app only).
 *
 * The rule for what's in it is Stremio's own (stremio-core, LibraryItem.is_in_continue_watching): not "other", not
 * removed (or only temporary), and a playback position saved.
 */
final class Stremio {
    private Stremio() {}

    /** The tab's kind (Recs.now("stremio")) and the pick's app key (Recs.serviceName). */
    static final String KIND = "stremio";
    static final boolean ON = !Config.STREMIO_AUTH_KEY.trim().isEmpty();
    private static final String API = "https://api.strem.io/api/datastoreGet";
    /** Cinemeta's images for an IMDb id: a wide background for the big card, and the poster for the narrow ones. */
    private static final String BACKGROUND = "https://images.metahub.space/background/large/";

    /** Up to `max` titles to continue, the last watched first. */
    static List<Recs.Pick> fetch(int max) throws IOException, org.json.JSONException {
        JSONObject body = new JSONObject().put("authKey", Config.STREMIO_AUTH_KEY.trim()).put("collection", "libraryItem")
                .put("ids", new JSONArray()).put("all", true);
        JSONObject answer = new JSONObject(post(API, body.toString()));
        if (answer.has("error")) {
            JSONObject err = answer.optJSONObject("error");
            String message = err != null ? err.optString("message", "error") : answer.optString("error");
            throw new IOException("Stremio: " + message);   // never the key
        }
        JSONArray items = answer.optJSONArray("result");
        List<JSONObject> watching = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            JSONObject state = it.optJSONObject("state");
            String type = it.optString("type");
            boolean kept = !it.optBoolean("removed") || it.optBoolean("temp");
            if (state == null || type.equals("other") || !kept || state.optLong("timeOffset") <= 0) continue;
            watching.add(it);
        }
        Collections.sort(watching, (a, b) -> Long.compare(lastWatched(b), lastWatched(a)));
        List<Recs.Pick> out = new ArrayList<>();
        for (JSONObject it : watching) {
            Recs.Pick p = pick(it);
            if (p != null) out.add(p);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static Recs.Pick pick(JSONObject it) {
        String id = it.optString("_id"), type = it.optString("type"), name = it.optString("name");
        if (id.isEmpty() || name.isEmpty()) return null;
        JSONObject state = it.optJSONObject("state");
        String poster = https(it.optString("poster"));
        String backdrop = id.startsWith("tt") ? BACKGROUND + id + "/img" : poster;
        if (backdrop == null) return null;   // no image at all: the card would stay gray
        Recs.Pick p = new Recs.Pick();
        p.id = (id.hashCode() & 0x3FFFFFFF) + 1;   // positive, apart from Plex's negative ids
        p.kind = KIND;
        p.title = name;
        p.backdrop = backdrop;
        p.poster = poster == null ? "" : poster;
        p.app = KIND;
        String video = state == null ? "" : state.optString("video_id", "");
        if (video.isEmpty() || "null".equals(video)) video = type.equals("movie") ? id : "";
        p.link = "stremio:///detail/" + enc(type) + "/" + enc(id) + (video.isEmpty() ? "" : "/" + enc(video))
                + (Config.STREMIO_AUTOPLAY && !video.isEmpty() ? "?autoPlay=true" : "");
        List<String> parts = new ArrayList<>();
        if (type.equals("series")) {
            parts.add(Ui.tr("Series"));
            String[] se = video.split(":");
            if (se.length >= 3) parts.add(Ui.trf("S{s} E{e}", "s", se[se.length - 2], "e", se[se.length - 1]));
        } else if (type.equals("movie")) {
            parts.add(Ui.tr("Movie"));
        }
        long left = state == null ? 0 : (state.optLong("duration") - state.optLong("timeOffset")) / 60_000L;
        if (left > 0) parts.add(Ui.trf("{n} min left", "n", left));
        parts.add("Stremio");
        p.meta = String.join(" · ", parts);
        p.detailed = true;
        return p;
    }

    /** The app that opens stremio:// links (Stremio for Android TV, or the phone build), or null. */
    static String pkg(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///library"));
        for (ResolveInfo ri : pm.queryIntentActivities(view, 0)) {
            if (ri.activityInfo != null && !ri.activityInfo.packageName.equals(HomeMode.PKG)) return ri.activityInfo.packageName;
        }
        for (String known : new String[] {"com.stremio.one", "com.stremio.tv"}) {
            if (HomeApps.launchIntent(pm, known) != null) return known;
        }
        return null;
    }

    /** "2026-10-27T21:14:02.123Z" → milliseconds; 0 when missing. */
    private static long lastWatched(JSONObject it) {
        JSONObject state = it.optJSONObject("state");
        String at = state == null ? "" : state.optString("lastWatched", "");
        if (at.isEmpty() || "null".equals(at)) at = it.optString("_mtime", "");
        if (at.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(at.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
    }

    /** Images only over https (the app allows no plain http); Cinemeta's http addresses also answer on https. */
    private static String https(String url) {
        if (url == null || url.isEmpty() || "null".equals(url)) return null;
        if (url.startsWith("https://")) return url;
        if (url.startsWith("http://")) return "https://" + url.substring(7);
        return null;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String post(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);   // the body carries the key: it never follows a redirect
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try {
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) {
                out.write(data);
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Stremio returned HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
