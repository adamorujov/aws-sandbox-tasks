package com.task09;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaLayer;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.model.Architecture;
import com.syndicate.deployment.model.ArtifactExtension;
import com.syndicate.deployment.model.DeploymentRuntime;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

@LambdaHandler(
        lambdaName = "api_handler",
        roleName = "api_handler-role",
        runtime = DeploymentRuntime.JAVA11,
        architecture = Architecture.ARM64,
        isPublishVersion = true,
        aliasName = "${lambdas_alias_name}",
        logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED,
        layers = {"cmtr-2v9vbeef-sdk_layer"}
)
@LambdaUrlConfig(
        authType = AuthType.NONE,
        invokeMode = InvokeMode.BUFFERED
)
@LambdaLayer(
        layerName = "cmtr-2v9vbeef-sdk_layer",
        libraries = {"lib/open-meteo-sdk-1.0.0.jar"},  // ✅ lib/ qovluğundakı JAR
        runtime = DeploymentRuntime.JAVA11,
        architectures = {Architecture.ARM64},
        artifactExtension = ArtifactExtension.ZIP
)
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final OpenMeteoClient meteoClient = new OpenMeteoClient();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Map<String, Object> response = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        String rawPath = (String) event.getOrDefault("rawPath", "");
        String httpMethod = "";

        if (event.containsKey("requestContext")) {
            Map<String, Object> requestContext = (Map<String, Object>) event.get("requestContext");
            if (requestContext.containsKey("http")) {
                Map<String, Object> http = (Map<String, Object>) requestContext.get("http");
                httpMethod = (String) http.getOrDefault("method", "");
            }
        }

        if ("/weather".equals(rawPath) && "GET".equals(httpMethod)) {
            String weatherData = meteoClient.getWeatherForecast();
            response.put("statusCode", 200);
            response.put("headers", headers);
            response.put("body", weatherData);
        } else {
            String errorBody = String.format(
                "{\"statusCode\": 400, \"message\": \"Bad request syntax or unsupported method. Request path: %s. HTTP method: %s\"}",
                rawPath, httpMethod
            );
            response.put("statusCode", 400);
            response.put("headers", headers);
            response.put("body", errorBody);
        }

        return response;
    }
}