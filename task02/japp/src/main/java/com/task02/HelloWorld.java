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
public class HelloWorld implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {

        Map<String, Object> result = new HashMap<>();

        try {

            String path = "";
            String method = "";

            if (event.get("rawPath") != null) {
                path = event.get("rawPath").toString();
            } else if (event.get("path") != null) {
                path = event.get("path").toString();
            }

            Object rcObj = event.get("requestContext");
            if (rcObj instanceof Map) {
                Map<String, Object> rc = (Map<String, Object>) rcObj;

                Object httpObj = rc.get("http");
                if (httpObj instanceof Map) {
                    Map<String, Object> http = (Map<String, Object>) httpObj;

                    if (http.get("method") != null) {
                        method = http.get("method").toString();
                    }
                }
            }

            if (method.isEmpty() && event.get("httpMethod") != null) {
                method = event.get("httpMethod").toString();
            }

            // SUCCESS
            if ("/hello".equals(path) && "GET".equalsIgnoreCase(method)) {
                result.put("statusCode", 200);
                result.put("message", "Hello from Lambda");
                return result;
            }

            // ERROR
            result.put("statusCode", 400);
            result.put("message",
                    "Bad request syntax or unsupported method. Request path: "
                            + path + ". HTTP method: " + method
            );

            return result;

        } catch (Exception e) {

            // IMPORTANT: NEVER RETURN EMPTY
            result.put("statusCode", 400);
            result.put("message", "Bad request syntax or unsupported method. Request path: . HTTP method: ");

            return result;
        }
    }
}