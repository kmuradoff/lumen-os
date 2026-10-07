// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The signed update manifest (schema 1, tools/ota/make_manifest.py). Parsed only after its signature was verified. */
public final class UpdateManifest {
    public final String json;
    public final int schema;
    public final String device, channel, version, buildId;
    public final int versionCode, minVersionCode;
    public final long buildUtc;
    public final List<String> vendorIncremental = new ArrayList<>();
    public final List<String> blobsSets = new ArrayList<>();
    public final JSONObject changelog;
    public final List<Pkg> packages = new ArrayList<>();

    public static final class Pkg {
        public final String json;
        public final String type, fromBuildId, url, sha256, metadataUrl, metadataSha256;
        public final long size, payloadOffset, payloadSize, metadataSize;
        public final String[] properties;

        Pkg(JSONObject o) throws JSONException {
            json = o.toString();
            type = o.getString("type");
            fromBuildId = o.optString("from_build_id", "");
            url = o.getString("url");
            size = o.getLong("size");
            sha256 = o.getString("sha256").toLowerCase(Locale.ROOT);
            payloadOffset = o.getLong("payload_offset");
            payloadSize = o.getLong("payload_size");
            metadataUrl = o.getString("metadata_url");
            metadataSize = o.optLong("metadata_size", 0);
            metadataSha256 = o.optString("metadata_sha256", "").toLowerCase(Locale.ROOT);
            JSONArray pr = o.getJSONArray("payload_properties");
            properties = new String[pr.length()];
            for (int i = 0; i < pr.length(); i++) properties[i] = pr.getString(i);
            if (!sha256.matches("[0-9a-f]{64}")) throw new JSONException("bad sha256");
            if (!url.startsWith("https://") || !metadataUrl.startsWith("https://")) throw new JSONException("not https");
            if (payloadOffset <= 0 || payloadSize <= 0 || payloadOffset + payloadSize > size)
                throw new JSONException("bad payload range");
            if (!"full".equals(type) && !"delta".equals(type)) throw new JSONException("bad type " + type);
        }

        public static Pkg parse(String s) throws JSONException { return new Pkg(new JSONObject(s)); }

        public boolean isDelta() { return "delta".equals(type); }

        public String fileName() {
            int i = url.lastIndexOf('/');
            String n = url.substring(i + 1).replaceAll("[^A-Za-z0-9._-]", "_");
            return n.isEmpty() ? "update.zip" : n;
        }
    }

    public UpdateManifest(String json) throws JSONException {
        this.json = json;
        JSONObject o = new JSONObject(json);
        schema = o.getInt("schema");
        device = o.getString("device");
        channel = o.optString("channel", "stable");
        version = o.getString("version");
        versionCode = o.getInt("version_code");
        buildId = o.getString("build_id");
        buildUtc = o.optLong("build_utc", 0);
        JSONObject req = o.optJSONObject("requires");
        int minVc = 0;
        if (req != null) {
            JSONArray a = req.optJSONArray("vendor_incremental");
            for (int i = 0; a != null && i < a.length(); i++) vendorIncremental.add(a.getString(i));
            JSONArray b = req.optJSONArray("blobs_set");
            for (int i = 0; b != null && i < b.length(); i++) blobsSets.add(b.getString(i));
            minVc = req.optInt("min_version_code", 0);
        }
        minVersionCode = minVc;
        changelog = o.optJSONObject("changelog");
        JSONArray pk = o.getJSONArray("packages");
        for (int i = 0; i < pk.length(); i++) packages.add(new Pkg(pk.getJSONObject(i)));
        if (schema != 1) throw new JSONException("unsupported schema " + schema);
    }

    /** Delta from exactly this build if offered, else the full package (null if none). */
    public Pkg choose(String currentBuildId) {
        Pkg full = null;
        for (Pkg p : packages) {
            if (p.isDelta() && !currentBuildId.isEmpty() && currentBuildId.equals(p.fromBuildId)) return p;
            if (!p.isDelta() && full == null) full = p;
        }
        return full;
    }

    /** Changelog in the UI language, else English, else the first one. */
    public String changelogFor(Locale l) {
        if (changelog == null) return "";
        String tag = l.toLanguageTag();
        String[] keys = {tag, l.getLanguage() + "-" + l.getCountry(), l.getLanguage(), "en"};
        for (String k : keys) {
            if (changelog.has(k)) return changelog.optString(k, "");
        }
        java.util.Iterator<String> it = changelog.keys();
        return it.hasNext() ? changelog.optString(it.next(), "") : "";
    }
}
