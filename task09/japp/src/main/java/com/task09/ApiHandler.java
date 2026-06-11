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
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

@LambdaHandler(
    lambdaName = "api_handler",
    roleName = "api_handler-role",
    runtime = DeploymentRuntime.JAVA11,
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
    layers = {"sdk_layer"},
    isPublishVersion = true,
    aliasName = "${lambdas_alias_name}"
)
@LambdaLayer(
    layerName = "sdk_layer",
    libraries = {"lib/weather-sdk-1.0.0.jar"},
    runtime = DeploymentRuntime.JAVA11,
    artifactExtension = ArtifactExtension.ZIP
)
@LambdaUrlConfig(
    authType = AuthType.NONE,
    invokeMode = InvokeMode.BUFFERED
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final OpenMeteoClient meteoClient = new OpenMeteoClient();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        String rawPath = "/";
        String httpMethod = "GET";

        if (event.containsKey("rawPath")) {
            rawPath = (String) event.get("rawPath");
        }

        if (event.containsKey("requestContext")) {
            Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
            if (requestContext.containsKey("http")) {
                Map<String, Object> http = (Map<String, Object>) requestContext.get("http");
                if (http.containsKey("method")) {
                    httpMethod = (String) http.get("method");
                }
                if (http.containsKey("path")) {
                    rawPath = (String) http.get("path");
                }
            }
        }

        context.getLogger().log("Path: " + rawPath + ", Method: " + httpMethod);

        if ("/weather".equals(rawPath) && "GET".equalsIgnoreCase(httpMethod)) {
            return handleWeatherRequest(context);
        } else {
            return handleBadRequest(rawPath, httpMethod);
        }
    }

    private Map<String, Object> handleWeatherRequest(Context context) {
        try {
            String forecastJson = meteoClient.getWeatherForecast();

            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 200);

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            response.put("headers", headers);
            response.put("body", forecastJson);

            return response;

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());

            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 500);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            response.put("headers", headers);
            response.put("body", "{\"statusCode\":500,\"message\":\"" + e.getMessage() + "\"}");
            return response;
        }
    }

    private Map<String, Object> handleBadRequest(String path, String method) {
        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 400);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        response.put("headers", headers);

        response.put("body",
            "{\"statusCode\":400,\"message\":\"Bad request syntax or unsupported method. " +
            "Request path: " + path + ". HTTP method: " + method + "\"}");

        return response;
    }
}