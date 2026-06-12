package com.task10;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.TracingMode;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

@LambdaHandler(
    lambdaName = "processor",
    roleName = "processor-role",
    tracingMode = TracingMode.Active,
    aliasName = "${lambdas_alias_name}"
)
@LambdaUrlConfig(
    authType = AuthType.NONE,
    invokeMode = InvokeMode.BUFFERED
)
@DependsOn(name = "Weather", resourceType = ResourceType.DYNAMODB_TABLE)
@EnvironmentVariables(value = {
    @EnvironmentVariable(key = "target_table", value = "${target_table}")
})
public class Processor implements RequestHandler<Object, Map<String, Object>> {

    private static final String WEATHER_URL = 
        "https://api.open-meteo.com/v1/forecast?latitude=50.4375&longitude=30.5&hourly=temperature_2m&timezone=Europe%2FKiev";

    private final AmazonDynamoDB dynamoDbClient = AmazonDynamoDBClientBuilder
            .defaultClient();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Object request, Context context) {
        LambdaLogger logger = context.getLogger();

        try {
            // Resolve table name
            String tableName = System.getenv("target_table");
            logger.log("Target table: " + tableName);

            if (tableName == null || tableName.isEmpty()) {
                throw new RuntimeException("Environment variable 'target_table' is not set!");
            }

            // Call Open-Meteo API
            logger.log("Calling Open-Meteo API...");
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(WEATHER_URL))
                    .GET()
                    .build();
            HttpResponse<String> httpResponse = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString()
            );
            String weatherJson = httpResponse.body();
            logger.log("API response status: " + httpResponse.statusCode());
            logger.log("API response body (first 500 chars): " + 
                weatherJson.substring(0, Math.min(500, weatherJson.length())));

            // Parse JSON to Map
            Map<String, Object> forecastMap = objectMapper.readValue(weatherJson, Map.class);

            // Write to DynamoDB
            String id = UUID.randomUUID().toString();
            logger.log("Generated ID: " + id);

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", new AttributeValue(id));
            item.put("forecast", convertToAttributeValue(forecastMap));

            PutItemRequest putItemRequest = new PutItemRequest()
                    .withTableName(tableName)
                    .withItem(item);

            dynamoDbClient.putItem(putItemRequest);
            logger.log("Successfully saved item with id: " + id);

            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 200);
            response.put("body", "Weather data saved successfully! ID: " + id);
            return response;

        } catch (Exception e) {
            logger.log("ERROR: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Error: " + e.getMessage(), e);
        }
    }

    private AttributeValue convertToAttributeValue(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            Map<String, AttributeValue> attributeMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                attributeMap.put(entry.getKey(), convertToAttributeValue(entry.getValue()));
            }
            return new AttributeValue().withM(attributeMap);
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            List<AttributeValue> attributeList = new java.util.ArrayList<>();
            for (Object item : list) {
                attributeList.add(convertToAttributeValue(item));
            }
            return new AttributeValue().withL(attributeList);
        } else if (obj instanceof Number) {
            return new AttributeValue().withN(obj.toString());
        } else if (obj instanceof Boolean) {
            return new AttributeValue().withBOOL((Boolean) obj);
        } else if (obj == null) {
            return new AttributeValue().withNULL(true);
        } else {
            return new AttributeValue(obj.toString());
        }
    }
}