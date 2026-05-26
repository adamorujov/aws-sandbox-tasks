package com.task02;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.model.RetentionSetting;

@LambdaHandler(
    lambdaName = "hello_world",
	roleName = "hello_world-role",
	timeout = 30,
	memory = 128,
	isPublishVersion = true,
	aliasName = "${lambdas_alias_name}",
	logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
public class HelloWorld implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {

        String path = (String) event.get("rawPath");
        String method = (String) event.get("requestContext") != null
                ? (String)((Map<String, Object>)((Map<String, Object>)event.get("requestContext")).get("http")).get("method")
                : "";

        Map<String, Object> response = new HashMap<>();

        if ("/hello".equals(path) && "GET".equals(method)) {

            response.put("statusCode", 200);
            response.put("headers", Map.of(
                    "Content-Type", "text/plain"
            ));
            response.put("message", "Hello from Lambda");

        } else {

            response.put("statusCode", 400);
            response.put("headers", Map.of(
                    "Content-Type", "text/plain"
            ));
            response.put("message",
                    String.format(
                            "Bad request syntax or unsupported method. Request path: %s. HTTP method: %s",
                            path,
                            method
                    )
            );
        }

        return response;
    }
}
