package org.apache.zeppelin.jdbc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ValidationResponse {
    private boolean preSubmitFail;
    private boolean failFast;
    private boolean failedByDeprecatedTable;
    private String errorHeader;
    private String message;
    private String version;
    private boolean isQueryUpdated;
    private String queryText;
    private String newQueryText;

    // Getters and Setters
    public boolean isPreSubmitFail() {
        return preSubmitFail;
    }

    public void setPreSubmitFail(boolean preSubmitFail) {
        this.preSubmitFail = preSubmitFail;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public void setFailFast(boolean failFast) {
        this.failFast = failFast;
    }

    public boolean isFailedByDeprecatedTable() {
        return failedByDeprecatedTable;
    }

    public void setFailedByDeprecatedTable(boolean failedByDeprecatedTable) {
        this.failedByDeprecatedTable = failedByDeprecatedTable;
    }

    public String getErrorHeader() {
        return errorHeader;
    }

    public void setErrorHeader(String errorHeader) {
        this.errorHeader = errorHeader;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isQueryUpdated() {
        return isQueryUpdated;
    }

    public void setQueryUpdated(boolean isQueryUpdated) {
        this.isQueryUpdated = isQueryUpdated;
    }
    
    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }
    
    public String getNewQueryText() {
        return newQueryText;
    }

    public void setNewQueryText(String newQueryText) {
        this.newQueryText = newQueryText;
    }

    public static ValidationResponse fromJson(String jsonResponse) {
        Gson gson = new Gson();
        ValidationResponse response = new ValidationResponse();

        JsonElement jsonElement = gson.fromJson(jsonResponse, JsonElement.class);

        if (jsonElement.isJsonObject()) {
            JsonObject jsonObject = jsonElement.getAsJsonObject();

            if (jsonObject.has("pre_submit_fail") && !jsonObject.get("pre_submit_fail").isJsonNull()) {
                response.setPreSubmitFail(jsonObject.get("pre_submit_fail").getAsBoolean());
            }
            if (jsonObject.has("fail_fast") && !jsonObject.get("fail_fast").isJsonNull()) {
                response.setFailFast(jsonObject.get("fail_fast").getAsBoolean());
            }
            if (jsonObject.has("failed_by_deprecated_table") && !jsonObject.get("failed_by_deprecated_table").isJsonNull()) {
                response.setFailedByDeprecatedTable(jsonObject.get("failed_by_deprecated_table").getAsBoolean());
            }
            if (jsonObject.has("error_header") && !jsonObject.get("error_header").isJsonNull()) {
                response.setErrorHeader(jsonObject.get("error_header").getAsString());
            } else {
                response.setErrorHeader("");
            }
            if (jsonObject.has("message") && !jsonObject.get("message").isJsonNull()) {
                response.setMessage(jsonObject.get("message").getAsString());
            } else {
                response.setMessage("");
            }
            if (jsonObject.has("version") && !jsonObject.get("version").isJsonNull()) {
                response.setVersion(jsonObject.get("version").getAsString());
            } else {
                response.setVersion("v1");
            }
            if (jsonObject.has("is_query_updated") && !jsonObject.get("is_query_updated").isJsonNull()) {
                response.setQueryUpdated(jsonObject.get("is_query_updated").getAsBoolean());
            } else {
                response.setQueryUpdated(false);
            }
            if (jsonObject.has("query_text") && !jsonObject.get("query_text").isJsonNull()) {
                response.setQueryText(jsonObject.get("query_text").getAsString());
            } else {
                response.setQueryText("");
            }
            if (jsonObject.has("new_query_text") && !jsonObject.get("new_query_text").isJsonNull()) {
                response.setNewQueryText(jsonObject.get("new_query_text").getAsString());
            } else {
                response.setNewQueryText(null);
            }
        } else {
            response.setPreSubmitFail(false);
            response.setFailFast(false);
            response.setFailedByDeprecatedTable(false);
            response.setErrorHeader(""); // Default error header
            response.setMessage(""); // Default message
            response.setVersion("v1"); // Default version
            response.setQueryUpdated(false);
            response.setQueryText("");
            response.setNewQueryText(null);
        }
        return response;
    }
}
