package com.task09;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;
import com.task09.layer.OpenMeteoClient;

@LambdaHandler(
    lambdaName = "api_handler",
    roleName = "api_handler-role",
    layers = {"sdk_layer"},
    aliasName = "${lambdas_alias_name}",
    isPublishVersion = true
)
@LambdaUrlConfig(
    authType = AuthType.NONE,
    invokeMode = InvokeMode.BUFFERED
)
@LambdaLayer(
    layerName = "sdk_layer",
    libraries = {"lib/weather-sdk-1.0.0.jar"},
    runtime = DeploymentRuntime.JAVA11,
    artifactExtension = ArtifactExtension.ZIP
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final OpenMeteoClient openMeteoClient = new OpenMeteoClient();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        context.getLogger().log("Event received: " + event.toString());

        String path = "";
        String method = "";

        if (event.containsKey("rawPath")) {
            path = (String) event.get("rawPath");
        }

        if (event.containsKey("requestContext")) {
            Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
            if (requestContext != null && requestContext.containsKey("http")) {
                Map<String, Object> http = (Map<String, Object>) requestContext.get("http");
                if (http != null) {
                    method = (String) http.getOrDefault("method", "");
                    if (path.isEmpty()) {
                        path = (String) http.getOrDefault("path", "");
                    }
                }
            }
        }

        context.getLogger().log("Path: " + path + ", Method: " + method);

        if ("/weather".equals(path) && "GET".equalsIgnoreCase(method)) {
            try {
                String weatherJson = openMeteoClient.getWeatherForecast();

                Map<String, Object> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");

                Map<String, Object> response = new HashMap<>();
                response.put("statusCode", 200);
                response.put("headers", headers);
                response.put("body", weatherJson);
                return response;

            } catch (Exception e) {
                context.getLogger().log("Error fetching weather: " + e.getMessage());

                Map<String, Object> headers = new HashMap<>();
                headers.put("Content-Type", "application/json");

                Map<String, Object> response = new HashMap<>();
                response.put("statusCode", 500);
                response.put("headers", headers);
                response.put("body", "{\"statusCode\": 500, \"message\": \"Internal server error\"}");
                return response;
            }

        } else {
            String message = String.format(
                "Bad request syntax or unsupported method. Request path: %s. HTTP method: %s",
                path, method
            );

            String body = String.format(
                "{\"statusCode\": 400, \"message\": \"%s\"}",
                message
            );

            Map<String, Object> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");

            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 400);
            response.put("headers", headers);
            response.put("body", body);
            return response;
        }
    }
}