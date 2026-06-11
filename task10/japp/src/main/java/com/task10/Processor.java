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

    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=52.52&longitude=13.41&hourly=temperature_2m";
    private static final String TABLE_NAME = System.getenv("target_table");

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public Map<String, Object> handleRequest(Object request, Context context) {
        try {
            // Open-Meteo API-dən data çək
            Subsegment subsegment = AWSXRay.beginSubsegment("OpenMeteoAPICall");
            String weatherData;
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(WEATHER_URL))
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                weatherData = response.body();
            } finally {
                AWSXRay.endSubsegment();
            }

            // DynamoDB-yə yaz
            Subsegment dbSubsegment = AWSXRay.beginSubsegment("DynamoDBPutItem");
            try {
                Map<String, AttributeValue> item = new HashMap<>();
                item.put("id", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
                item.put("forecast", AttributeValue.builder().s(weatherData).build());

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
}