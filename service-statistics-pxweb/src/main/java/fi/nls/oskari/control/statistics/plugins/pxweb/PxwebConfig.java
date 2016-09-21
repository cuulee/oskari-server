package fi.nls.oskari.control.statistics.plugins.pxweb;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by SMAKINEN on 19.9.2016.
 */
public class PxwebConfig {

    private String url;
    private Set<String> ignoredVariables = new HashSet<>();

    PxwebConfig(JSONObject json) {
        url = json.optString("url");
        JSONArray ignored = json.optJSONArray("ignoredVariables");
        if(ignored != null) {
            for (int i = 0; i < ignored.length(); i++) {
                ignoredVariables.add(ignored.optString(i));
            }
        }
    }

    public String getUrl() {
        return url;
    }

    public Set<String> getIgnoredVariables() {
        return ignoredVariables;
    }
}