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

        String path = "";
        String method = "";

        try {
            if (event.get("rawPath") != null) {
                path = event.get("rawPath").toString();
            }

            Object rcObj = event.get("requestContext");
            if (rcObj != null) {
                Map<String, Object> requestContext = (Map<String, Object>) rcObj;

                Object httpObj = requestContext.get("http");
                if (httpObj != null) {
                    Map<String, Object> http = (Map<String, Object>) httpObj;
                    method = http.get("method").toString();
                }
            }

            // SUCCESS CASE
            if ("/hello".equals(path) && "GET".equalsIgnoreCase(method)) {

                response.put("statusCode", 200);
                response.put("message", "Hello from Lambda");
                return response;
            }

            // ERROR CASE (REQUIRED FORMAT EXACT)
            response.put("statusCode", 400);
            response.put("message",
                "Bad request syntax or unsupported method. Request path: "
                + path + ". HTTP method: " + method
            );

            return response;

        } catch (Exception e) {

            response.put("statusCode", 400);
            response.put("message",
                "Bad request syntax or unsupported method. Request path: "
                + path + ". HTTP method: " + method
            );

            return response;
        }
    }
}