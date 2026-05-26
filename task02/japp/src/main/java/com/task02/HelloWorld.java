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
        isPublishVersion = true,
        aliasName = "${lambdas_alias_name}",
        logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@LambdaUrlConfig(
        authType = AuthType.NONE,
        invokeMode = InvokeMode.BUFFERED
)
public class HelloWorld implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {

        String method = "UNKNOWN";
        String path = "/";

        try {
            // Null-safe şəkildə requestContext-dən HTTP method və path alırıq
            Object rcObj = event.get("requestContext");
            if (rcObj instanceof Map) {
                Map<?, ?> requestContext = (Map<?, ?>) rcObj;
                Object httpObj = requestContext.get("http");
                if (httpObj instanceof Map) {
                    Map<?, ?> http = (Map<?, ?>) httpObj;
                    if (http.get("method") != null) method = http.get("method").toString();
                    if (http.get("path") != null) path = http.get("path").toString();
                }
            }
        } catch (Exception e) {
            context.getLogger().log("Error parsing event: " + e.getMessage());
        }

        context.getLogger().log("Method: " + method + ", Path: " + path);

        Map<String, Object> response = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        if ("/hello".equals(path) && "GET".equals(method)) {
            response.put("statusCode", 200);
            response.put("headers", headers);
            response.put("body", "{\"statusCode\": 200, \"message\": \"Hello from Lambda\"}");
        } else {
            String errorMessage = String.format(
                    "Bad request syntax or unsupported method. Request path: %s. HTTP method: %s",
                    path, method
            );
            response.put("statusCode", 400);
            response.put("headers", headers);
            response.put("body", String.format(
                    "{\"statusCode\": 400, \"message\": \"%s\"}", errorMessage
            ));
        }

        return response;
    }
}