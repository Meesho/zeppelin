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
        } else {
            response.setPreSubmitFail(false);
            response.setFailFast(false);
            response.setFailedByDeprecatedTable(false);
            response.setErrorHeader(""); // Default error header
            response.setMessage(""); // Default message
            response.setVersion("v1"); // Default version
        }
        return response;
    }
}
