package org.apache.zeppelin.jdbc;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

public class ValidationRequest {
    @SerializedName("query_text")
    private String queryText;
    
    @SerializedName("user")
    private String user;
    
    @SerializedName("interpreter_name")
    private String interpreterName;
    
    @SerializedName("raw_query_text")
    private String rawQueryText;

    public ValidationRequest(String queryText, String user, String interpreterName, String rawQueryText) {
        this.queryText = queryText;
        this.user = user;
        this.interpreterName = interpreterName;
        this.rawQueryText = rawQueryText;
    }

    public String toJson() {
        Gson gson = new Gson();
        return gson.toJson(this);
    }
}

