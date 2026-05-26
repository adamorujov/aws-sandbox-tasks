package com.task02;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

@LambdaHandler(
    lambdaName = "hello_world",
    roleName = "hello_world-role",
    timeout = 30,
    memory = 128,
    isPublishVersion = true,
    aliasName = "${lambdas_alias_name}",
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
    authType = AuthType.NONE,
    invokeMode = InvokeMode.BUFFERED
)
public class Handler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {

        Map<String, Object> result = new HashMap<>();

        // PATH oxu
        String path = event.get("rawPath") != null
                ? event.get("rawPath").toString()
                : "";

        // METHOD oxu
        String method = "";
        try {
            Map<String, Object> rc = (Map<String, Object>) event.get("requestContext");
            if (rc != null) {
                Map<String, Object> http = (Map<String, Object>) rc.get("http");
                if (http != null && http.get("method") != null) {
                    method = http.get("method").toString();
                }
            }
        } catch (Exception ignored) {}

        // SUCCESS CASE
        if ("/hello".equals(path) && "GET".equalsIgnoreCase(method)) {
            result.put("statusCode", 200);
            result.put("message", "Hello from Lambda");
            return result;
        }

        // ERROR CASE
        result.put("statusCode", 400);
        result.put("message",
                "Bad request syntax or unsupported method. Request path: "
                        + path + ". HTTP method: " + method);
        return result;
    }
}
