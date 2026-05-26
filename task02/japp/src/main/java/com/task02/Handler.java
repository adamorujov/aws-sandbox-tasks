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

        Map<String, Object> response = new HashMap<>();

        try {
            String path = (String) event.get("rawPath");

            Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
            Map<String, Object> http = (Map<String, Object>) requestContext.get("http");
            String method = (String) http.get("method");

            if ("/hello".equals(path) && "GET".equalsIgnoreCase(method)) {

                response.put("statusCode", 200);
                response.put("body", "Hello from Lambda");
                return response;
            }

            response.put("statusCode", 400);
            response.put("body",
                    "Bad Request. Path: " + path + ", Method: " + method);

            return response;

        } catch (Exception e) {
            response.put("statusCode", 400);
            response.put("body", "Bad Request");
            return response;
        }
    }
}