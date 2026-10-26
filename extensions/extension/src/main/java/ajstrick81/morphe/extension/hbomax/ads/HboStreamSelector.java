package ajstrick81.morphe.extension.hbomax.ads;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HBO Max Android TV — prefer the ad-free FALLBACK stream.
 *
 * <p>An HBO Playable on the ad-supported tier carries two streams
 * ({@code com.discovery.player.common.models.StreamInfo}): a {@code PRIMARY}
 * stream whose manifest is ad-stitched ({@code …/dash.mpd}) and a {@code FALLBACK}
 * stream whose manifest is the clean, non-ad-stitched variant
 * ({@code …/<hash>_fallback.mpd} on the alternate CDN). HBO only switches to the
 * FALLBACK stream through its resiliency recovery after a load failure; normal
 * playback uses PRIMARY.
 *
 * <p>{@code PlayableKt.getStreamInfo(Playable, StreamInfo$Type)} resolves a stream
 * by type. This helper is injected at its entry to remap a {@code PRIMARY} request
 * to {@code FALLBACK} — but only when the Playable actually advertises a FALLBACK
 * stream, so titles without one (or non-ad content) are left exactly as-is. The
 * result: the player loads the ad-free manifest from the first request, on fresh
 * start and resume alike — no ads, no SSAI markers/timer, no timeline gaps, and no
 * resume 39999 (unlike the downstream origin block).
 *
 * <p>All access is by reflection on non-obfuscated
 * {@code com.discovery.player.common.models} names, so the extension needs no
 * compile-time dependency on the app. Fail-open: any error returns the original
 * requested type.
 */
@SuppressWarnings("unused")
public final class HboStreamSelector implements Runnable {

    private static final String TAG = "HboStreamSelector";
    private static final String PRIMARY = "PRIMARY";
    private static final String FALLBACK = "FALLBACK";

    private static volatile int lastDiagnosedPlayable;

    private final String diagLabel;
    private final String diagUrl;

    HboStreamSelector(String label, String url) {
        this.diagLabel = label;
        this.diagUrl = url;
    }

    @Override
    public void run() {
        summarizeManifest(diagLabel, diagUrl);
    }

    private static String why(Throwable t) {
        StringBuilder sb = new StringBuilder(String.valueOf(t));
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 5; i++) {
            sb.append(" | ").append(st[i]);
        }
        Throwable c = t.getCause();
        if (c != null) {
            sb.append(" | cause: ").append(c);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // Diagnose: what do the PRIMARY and FALLBACK manifests contain?
    // Read-only. Logs under the tag "HboStreamSelector" (no query strings /
    // tokens are logged). Fail-open: never changes playback.
    // ---------------------------------------------------------------------

    private static void diagnose(Object playable, List<?> infos) {
        int id = System.identityHashCode(playable);
        if (id == lastDiagnosedPlayable) {
            return;
        }
        lastDiagnosedPlayable = id;
        for (Object streamInfo : infos) {
            if (streamInfo == null) {
                continue;
            }
            try {
                describe(streamInfo);
            } catch (Throwable t) {
                Log.w(TAG, "diag: describe failed: " + why(t));
            }
        }
    }

    private static String strip(String url) {
        int q = url.indexOf('?');
        String u = q >= 0 ? url.substring(0, q) : url;
        int scheme = u.indexOf("://");
        int slash = scheme >= 0 ? u.indexOf('/', scheme + 3) : -1;
        String host = slash >= 0 ? u.substring(0, slash) : u;
        String last = u.substring(u.lastIndexOf('/') + 1);
        return host + "/.../" + last + (q >= 0 ? "?<query-removed>" : "");
    }

    private static void describe(Object streamInfo) throws Exception {
        Object type = streamInfo.getClass().getMethod("getType").invoke(streamInfo);
        StringBuilder sb = new StringBuilder("diag stream ").append(type).append(':');
        final String[] mpdUrl = new String[1];
        for (Method m : streamInfo.getClass().getMethods()) {
            String mn = m.getName();
            if (m.getParameterTypes().length != 0 || m.getDeclaringClass() == Object.class
                    || mn.startsWith("component") || mn.equals("toString")
                    || mn.equals("hashCode") || mn.equals("getTimelinePayload")) {
                if (mn.equals("getTimelinePayload") && m.getParameterTypes().length == 0) {
                    try {
                        Object tp = m.invoke(streamInfo);
                        if (tp != null) {
                            sb.append(' ').append(summarizeTimeline(String.valueOf(tp)));
                        }
                    } catch (Throwable ignored) {
                        // diagnostic only
                    }
                }
                continue;
            }
            Class<?> r = m.getReturnType();
            String rn = r.getName();
            boolean simple = r == String.class || r.isEnum() || r.isPrimitive()
                    || rn.equals("java.net.URI") || rn.equals("java.net.URL")
                    || rn.equals("android.net.Uri");
            if (!simple) {
                continue;
            }
            Object v;
            try {
                v = m.invoke(streamInfo);
            } catch (Throwable t) {
                continue;
            }
            if (v == null) {
                continue;
            }
            String sv = String.valueOf(v);
            if (sv.startsWith("http")) {
                if (sv.contains(".mpd") && mpdUrl[0] == null) {
                    mpdUrl[0] = sv;
                }
                sv = strip(sv);
            } else if (sv.length() > 80) {
                sv = sv.substring(0, 80) + "...";
            }
            sb.append(' ').append(m.getName()).append('=').append(sv).append(';');
        }
        Log.i(TAG, sb.toString());
        if (mpdUrl[0] != null) {
            final String url = mpdUrl[0];
            final String label = String.valueOf(type);
            Thread t = new Thread(new HboStreamSelector(label, url), "HboManifestDiag");
            t.setDaemon(true);
            t.start();
        } else {
            Log.i(TAG, "diag " + type + ": no .mpd URL found via getters");
        }
    }

    private static String summarizeTimeline(String payload) {
        return "timeline[len=" + payload.length() + " events=" + count(payload, "\"event\"")
                + " actions=" + distinct(payload, "\"action\":\"([^\"]+)\"", 20)
                + " schemas=" + distinct(payload, "\"schema\":\"([^\"]+)\"", 20) + "];";
    }

    private static void summarizeManifest(String label, String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            if (code != 200) {
                Log.i(TAG, "diag " + label + " manifest HTTP " + code);
                return;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 3 * 1024 * 1024) {
                out.write(buf, 0, n);
            }
            String body = out.toString("UTF-8");
            Log.i(TAG, "diag " + label + " manifest bytes=" + out.size()
                    + " periods=" + count(body, "<Period"));
            Log.i(TAG, "diag " + label + " codecs=" + distinct(body, "codecs=\"([^\"]+)\""));
            Log.i(TAG, "diag " + label + " audioChannelConfig="
                    + distinct(body, "<AudioChannelConfiguration[^>]*value=\"([^\"]+)\""));
            Log.i(TAG, "diag " + label + " audioSets="
                    + distinct(body, "<AdaptationSet[^>]*contentType=\"audio\"[^>]*>", 12));
            Matcher pm = Pattern.compile("<Period\\b[^>]*>").matcher(body);
            int i = 0;
            while (pm.find() && i < 12) {
                Log.i(TAG, "diag " + label + " period[" + i + "] " + pm.group());
                i++;
            }
        } catch (Throwable t) {
            Log.w(TAG, "diag " + label + " manifest fetch failed: " + why(t));
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static int count(String body, String needle) {
        int n = 0;
        int i = 0;
        while ((i = body.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static Set<String> distinct(String body, String regex) {
        return distinct(body, regex, 50);
    }

    private static Set<String> distinct(String body, String regex, int max) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(regex).matcher(body);
        while (m.find() && out.size() < max) {
            out.add(m.groupCount() >= 1 ? m.group(1) : m.group());
        }
        return out;
    }

    /**
     * @param playable      the Playable whose stream is being resolved (getStreamInfo p0).
     * @param requestedType the StreamInfo$Type being requested (getStreamInfo p1).
     * @return FALLBACK type when PRIMARY was requested and a FALLBACK stream exists;
     *         otherwise the original requestedType (unchanged).
     */
    public static Object preferFallbackType(Object playable, Object requestedType) {
        try {
            if (playable == null || !(requestedType instanceof Enum)) {
                return requestedType;
            }
            if (!PRIMARY.equals(((Enum<?>) requestedType).name())) {
                return requestedType;
            }
            Object infosObj = playable.getClass().getMethod("getStreamInfos").invoke(playable);
            if (!(infosObj instanceof List)) {
                return requestedType;
            }
            diagnose(playable, (List<?>) infosObj);
            for (Object streamInfo : (List<?>) infosObj) {
                if (streamInfo == null) {
                    continue;
                }
                Object type = streamInfo.getClass().getMethod("getType").invoke(streamInfo);
                if (type instanceof Enum && FALLBACK.equals(((Enum<?>) type).name())) {
                    Log.i(TAG, "remapping PRIMARY -> FALLBACK stream (ad-free manifest)");
                    return type;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "preferFallbackType failed; using requested type", t);
        }
        return requestedType;
    }
}
