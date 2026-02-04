package org.apache.zeppelin.jdbc;

public class ValidationRequest {
    private String queryText;
    private String user;
    private String interpreterName;
    private String rawQueryText;

    public ValidationRequest(String queryText, String user, String interpreterName, String rawQueryText) {
        this.queryText = queryText;
        this.user = user;
        this.interpreterName = interpreterName;
        this.rawQueryText = rawQueryText;
    }

    public String toJson() {
        return "{\"query_text\":\"" + queryText + "\",\"user\":\"" + user + "\",\"interpreter_name\":\"" + interpreterName + "\",\"raw_query_text\":\"" + rawQueryText + "\"}";
    }
}

