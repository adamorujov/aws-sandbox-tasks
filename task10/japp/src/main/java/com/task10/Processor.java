package com.task10;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.lambda.LambdaUrlConfig;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.TracingMode;
import com.syndicate.deployment.model.lambda.url.AuthType;
import com.syndicate.deployment.model.lambda.url.InvokeMode;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

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
public class Processor implements RequestHandler<Object, Map<String, Object>> {

    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=50.4375&longitude=30.5&hourly=temperature_2m&timezone=Europe%2FKiev";
    private static final String TABLE_NAME = System.getenv("target_table");

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> handleRequest(Object request, Context context) {
        try {
            // X-Ray subsegment - API call
            Subsegment apiSubsegment = AWSXRay.beginSubsegment("OpenMeteoAPICall");
            String weatherJson;
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(WEATHER_URL))
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(
                    httpRequest, 
                    HttpResponse.BodyHandlers.ofString()
                );
                weatherJson = response.body();
            } finally {
                AWSXRay.endSubsegment();
            }

            // JSON-u Map-ə çevir
            Map<String, Object> forecastMap = objectMapper.readValue(weatherJson, Map.class);

            // DynamoDB-yə Map kimi yaz
            Subsegment dbSubsegment = AWSXRay.beginSubsegment("DynamoDBPutItem");
            try {
                Map<String, AttributeValue> item = new HashMap<>();
                item.put("id", AttributeValue.builder()
                    .s(UUID.randomUUID().toString())
                    .build());
                item.put("forecast", convertToAttributeValue(forecastMap));

                PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .item(item)
                    .build();

                dynamoDbClient.putItem(putItemRequest);
            } finally {
                AWSXRay.endSubsegment();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 200);
            response.put("body", "Weather data saved successfully!");
            return response;

        } catch (IOException | InterruptedException e) {
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
            return AttributeValue.builder().m(attributeMap).build();
        } else if (obj instanceof java.util.List) {
            java.util.List<Object> list = (java.util.List<Object>) obj;
            java.util.List<AttributeValue> attributeList = new java.util.ArrayList<>();
            for (Object item : list) {
                attributeList.add(convertToAttributeValue(item));
            }
            return AttributeValue.builder().l(attributeList).build();
        } else if (obj instanceof Number) {
            return AttributeValue.builder().n(obj.toString()).build();
        } else if (obj instanceof Boolean) {
            return AttributeValue.builder().bool((Boolean) obj).build();
        } else if (obj == null) {
            return AttributeValue.builder().nul(true).build();
        } else {
            return AttributeValue.builder().s(obj.toString()).build();
        }
    }
}