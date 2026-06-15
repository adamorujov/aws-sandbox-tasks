package com.task12;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.environment.ValueTransformer;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRespondToAuthChallengeRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

@LambdaHandler(
        lambdaName = "api_handler",
        roleName = "api_handler-role",
        isPublishVersion = true,
        aliasName = "${lambdas_alias_name}"
)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(value = {
        @EnvironmentVariable(key = "REGION", value = "${region}"),
        @EnvironmentVariable(key = "COGNITO_ID", value = "${booking_userpool}", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID),
        @EnvironmentVariable(key = "CLIENT_ID", value = "${booking_userpool}", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID),
        @EnvironmentVariable(key = "tables_table", value = "${tables_table}"),
        @EnvironmentVariable(key = "reservations_table", value = "${reservations_table}")
})
public class ApiHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final Gson gson = new GsonBuilder().create();
    private final CognitoIdentityProviderClient cognitoClient;
    private final DynamoDbClient dynamoDbClient;
    private final String userPoolId;
    private final String clientId;
    private final String tablesTableName;
    private final String reservationsTableName;

    public ApiHandler() {
        String regionStr = System.getenv("REGION");
        Region region = Region.of(regionStr);
        this.userPoolId = System.getenv("COGNITO_ID");
        this.clientId = System.getenv("CLIENT_ID");
        this.tablesTableName = System.getenv("tables_table");
        this.reservationsTableName = System.getenv("reservations_table");

        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(region)
                .build();

        this.dynamoDbClient = DynamoDbClient.builder()
                .region(region)
                .build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        context.getLogger().log("Event: " + gson.toJson(event));

        String path = event.getResource();
        if (path == null || path.isEmpty()) {
            path = event.getPath();
        }
        String method = event.getHttpMethod();

        context.getLogger().log("Path: " + path + ", Method: " + method);

        try {
            if ("/signup".equals(path) && "POST".equals(method)) {
                return handleSignup(event, context);
            } else if ("/signin".equals(path) && "POST".equals(method)) {
                return handleSignin(event, context);
            } else if ("/tables".equals(path) && "GET".equals(method)) {
                return handleGetTables(context);
            } else if ("/tables".equals(path) && "POST".equals(method)) {
                return handleCreateTable(event, context);
            } else if ("/tables/{tableId}".equals(path) && "GET".equals(method)) {
                return handleGetTableById(event, context);
            } else if ("/reservations".equals(path) && "POST".equals(method)) {
                return handleCreateReservation(event, context);
            } else if ("/reservations".equals(path) && "GET".equals(method)) {
                return handleGetReservations(context);
            } else {
                return buildResponse(400, Map.of("message", "Bad request. Path: " + path));
            }
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Error: " + e.getMessage()));
        }
    }

    // ==================== SIGNUP ====================
    private APIGatewayProxyResponseEvent handleSignup(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, Object> body = gson.fromJson(event.getBody(), Map.class);

        String firstName = (String) body.get("firstName");
        String lastName = (String) body.get("lastName");
        String email = (String) body.get("email");
        String password = (String) body.get("password");

        context.getLogger().log("Signup request for email: " + email);

        try {
            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .temporaryPassword(password)
                    .userAttributes(
                            AttributeType.builder().name("email").value(email).build(),
                            AttributeType.builder().name("given_name").value(firstName).build(),
                            AttributeType.builder().name("family_name").value(lastName).build(),
                            AttributeType.builder().name("email_verified").value("true").build()
                    )
                    .messageAction(MessageActionType.SUPPRESS)
                    .build();

            cognitoClient.adminCreateUser(createUserRequest);

            AdminSetUserPasswordRequest setPasswordRequest = AdminSetUserPasswordRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .password(password)
                    .permanent(true)
                    .build();

            cognitoClient.adminSetUserPassword(setPasswordRequest);

            context.getLogger().log("User created successfully: " + email);
            return buildResponse(200, Map.of("message", "User has been successfully signed up."));

        } catch (Exception e) {
            context.getLogger().log("Signup error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Sign up failed: " + e.getMessage()));
        }
    }

    // ==================== SIGNIN ====================
    private APIGatewayProxyResponseEvent handleSignin(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, Object> body = gson.fromJson(event.getBody(), Map.class);

        String email = (String) body.get("email");
        String password = (String) body.get("password");

        context.getLogger().log("Signin request for email: " + email);

        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", email);
            authParams.put("PASSWORD", password);

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .userPoolId(userPoolId)
                    .clientId(clientId)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            if (authResponse.challengeName() != null) {
                context.getLogger().log("Challenge received: " + authResponse.challengeName());

                if (authResponse.challengeName() == ChallengeNameType.NEW_PASSWORD_REQUIRED) {
                    Map<String, String> challengeResponses = new HashMap<>();
                    challengeResponses.put("USERNAME", email);
                    challengeResponses.put("NEW_PASSWORD", password);

                    AdminRespondToAuthChallengeRequest challengeRequest = AdminRespondToAuthChallengeRequest.builder()
                            .userPoolId(userPoolId)
                            .clientId(clientId)
                            .challengeName(ChallengeNameType.NEW_PASSWORD_REQUIRED)
                            .challengeResponses(challengeResponses)
                            .session(authResponse.session())
                            .build();

                    AdminRespondToAuthChallengeResponse challengeResponse =
                            cognitoClient.adminRespondToAuthChallenge(challengeRequest);

                    String idToken = challengeResponse.authenticationResult().idToken();
                    return buildResponse(200, Map.of("idToken", idToken));
                }
            }

            String idToken = authResponse.authenticationResult().idToken();
            context.getLogger().log("Signin successful for: " + email);
            return buildResponse(200, Map.of("idToken", idToken));

        } catch (Exception e) {
            context.getLogger().log("Signin error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Sign in failed: " + e.getMessage()));
        }
    }

    // ==================== GET /tables ====================
    private APIGatewayProxyResponseEvent handleGetTables(Context context) {
        try {
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(tablesTableName)
                    .build();

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

            List<Map<String, Object>> tables = scanResponse.items().stream()
                    .map(this::convertDynamoItemToTable)
                    .collect(Collectors.toList());

            return buildResponse(200, Map.of("tables", tables));

        } catch (Exception e) {
            context.getLogger().log("GetTables error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Failed to get tables: " + e.getMessage()));
        }
    }

    // ==================== POST /tables ====================
    private APIGatewayProxyResponseEvent handleCreateTable(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, Object> body = gson.fromJson(event.getBody(), Map.class);

        try {
            int id = ((Number) body.get("id")).intValue();
            int number = ((Number) body.get("number")).intValue();
            int places = ((Number) body.get("places")).intValue();
            boolean isVip = (Boolean) body.get("isVip");

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().n(String.valueOf(id)).build());
            item.put("number", AttributeValue.builder().n(String.valueOf(number)).build());
            item.put("places", AttributeValue.builder().n(String.valueOf(places)).build());
            item.put("isVip", AttributeValue.builder().bool(isVip).build());

            if (body.containsKey("minOrder") && body.get("minOrder") != null) {
                int minOrder = ((Number) body.get("minOrder")).intValue();
                item.put("minOrder", AttributeValue.builder().n(String.valueOf(minOrder)).build());
            }

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(tablesTableName)
                    .item(item)
                    .build();

            dynamoDbClient.putItem(putItemRequest);

            context.getLogger().log("Table created with id: " + id);
            return buildResponse(200, Map.of("id", id));

        } catch (Exception e) {
            context.getLogger().log("CreateTable error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Failed to create table: " + e.getMessage()));
        }
    }

    // ==================== GET /tables/{tableId} ====================
    private APIGatewayProxyResponseEvent handleGetTableById(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String tableIdStr = event.getPathParameters().get("tableId");
            int tableId = Integer.parseInt(tableIdStr);

            Map<String, AttributeValue> key = new HashMap<>();
            key.put("id", AttributeValue.builder().n(String.valueOf(tableId)).build());

            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(tablesTableName)
                    .key(key)
                    .build();

            GetItemResponse getItemResponse = dynamoDbClient.getItem(getItemRequest);

            if (!getItemResponse.hasItem() || getItemResponse.item().isEmpty()) {
                return buildResponse(400, Map.of("message", "Table not found"));
            }

            Map<String, Object> table = convertDynamoItemToTable(getItemResponse.item());
            return buildResponse(200, table);

        } catch (Exception e) {
            context.getLogger().log("GetTableById error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Failed to get table: " + e.getMessage()));
        }
    }

    // ==================== POST /reservations ====================
    private APIGatewayProxyResponseEvent handleCreateReservation(APIGatewayProxyRequestEvent event, Context context) {
        Map<String, Object> body = gson.fromJson(event.getBody(), Map.class);

        try {
            int tableNumber = ((Number) body.get("tableNumber")).intValue();
            String clientName = (String) body.get("clientName");
            String phoneNumber = (String) body.get("phoneNumber");
            String date = (String) body.get("date");
            String slotTimeStart = (String) body.get("slotTimeStart");
            String slotTimeEnd = (String) body.get("slotTimeEnd");

            // Masanın mövcudluğunu yoxla (tableNumber sahəsinə görə)
            if (!checkTableExists(tableNumber)) {
                return buildResponse(400, Map.of("message",
                        "Table with number " + tableNumber + " does not exist."));
            }

            // Vaxt üst-üstə düşməsini yoxla
            if (checkReservationOverlap(tableNumber, date, slotTimeStart, slotTimeEnd)) {
                return buildResponse(400, Map.of("message",
                        "Reservation overlaps with an existing one."));
            }

            String reservationId = UUID.randomUUID().toString();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(reservationId).build());
            item.put("tableNumber", AttributeValue.builder().n(String.valueOf(tableNumber)).build());
            item.put("clientName", AttributeValue.builder().s(clientName).build());
            item.put("phoneNumber", AttributeValue.builder().s(phoneNumber).build());
            item.put("date", AttributeValue.builder().s(date).build());
            item.put("slotTimeStart", AttributeValue.builder().s(slotTimeStart).build());
            item.put("slotTimeEnd", AttributeValue.builder().s(slotTimeEnd).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(reservationsTableName)
                    .item(item)
                    .build();

            dynamoDbClient.putItem(putItemRequest);

            context.getLogger().log("Reservation created: " + reservationId);
            return buildResponse(200, Map.of("reservationId", reservationId));

        } catch (Exception e) {
            context.getLogger().log("CreateReservation error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Failed to create reservation: " + e.getMessage()));
        }
    }

    // ==================== GET /reservations ====================
    private APIGatewayProxyResponseEvent handleGetReservations(Context context) {
        try {
            ScanRequest scanRequest = ScanRequest.builder()
                    .tableName(reservationsTableName)
                    .build();

            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

            List<Map<String, Object>> reservations = scanResponse.items().stream()
                    .map(this::convertDynamoItemToReservation)
                    .collect(Collectors.toList());

            return buildResponse(200, Map.of("reservations", reservations));

        } catch (Exception e) {
            context.getLogger().log("GetReservations error: " + e.getMessage());
            return buildResponse(400, Map.of("message", "Failed to get reservations: " + e.getMessage()));
        }
    }

    // ==================== HELPER METHODS ====================

    private boolean checkTableExists(int tableNumber) {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(tablesTableName)
                .filterExpression("#num = :tableNumber")
                .expressionAttributeNames(Map.of("#num", "number"))
                .expressionAttributeValues(Map.of(
                        ":tableNumber", AttributeValue.builder().n(String.valueOf(tableNumber)).build()
                ))
                .build();

        ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
        return !scanResponse.items().isEmpty();
    }

    private boolean checkReservationOverlap(int tableNumber, String date,
                                            String slotTimeStart, String slotTimeEnd) {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(reservationsTableName)
                .filterExpression("tableNumber = :tableNumber AND #d = :date")
                .expressionAttributeNames(Map.of("#d", "date"))
                .expressionAttributeValues(Map.of(
                        ":tableNumber", AttributeValue.builder().n(String.valueOf(tableNumber)).build(),
                        ":date", AttributeValue.builder().s(date).build()
                ))
                .build();

        ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

        for (Map<String, AttributeValue> item : scanResponse.items()) {
            String existingStart = item.get("slotTimeStart").s();
            String existingEnd = item.get("slotTimeEnd").s();

            // Overlap: newStart < existingEnd AND newEnd > existingStart
            if (slotTimeStart.compareTo(existingEnd) < 0 && slotTimeEnd.compareTo(existingStart) > 0) {
                return true;
            }
        }

        return false;
    }

    private Map<String, Object> convertDynamoItemToTable(Map<String, AttributeValue> item) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", Integer.parseInt(item.get("id").n()));
        table.put("number", Integer.parseInt(item.get("number").n()));
        table.put("places", Integer.parseInt(item.get("places").n()));
        table.put("isVip", item.get("isVip").bool());
        if (item.containsKey("minOrder") && item.get("minOrder") != null
                && item.get("minOrder").n() != null) {
            table.put("minOrder", Integer.parseInt(item.get("minOrder").n()));
        }
        return table;
    }

    private Map<String, Object> convertDynamoItemToReservation(Map<String, AttributeValue> item) {
        Map<String, Object> reservation = new LinkedHashMap<>();
        reservation.put("tableNumber", Integer.parseInt(item.get("tableNumber").n()));
        reservation.put("clientName", item.get("clientName").s());
        reservation.put("phoneNumber", item.get("phoneNumber").s());
        reservation.put("date", item.get("date").s());
        reservation.put("slotTimeStart", item.get("slotTimeStart").s());
        reservation.put("slotTimeEnd", item.get("slotTimeEnd").s());
        return reservation;
    }

    private APIGatewayProxyResponseEvent buildResponse(int statusCode, Map<String, Object> body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type,Authorization");

        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setHeaders(headers);
        response.setBody(gson.toJson(body));
        return response;
    }
}