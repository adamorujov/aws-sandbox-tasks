package com.task13;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.ResourceType;
import com.syndicate.deployment.model.RetentionSetting;
import com.syndicate.deployment.model.environment.ValueTransformer;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminSetUserPasswordRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;
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
       aliasName = "${lambdas_alias_name}",
       logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED
)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(value = {
       @EnvironmentVariable(key = "REGION", value = "${region}"),
       @EnvironmentVariable(key = "COGNITO_ID", value = "${booking_userpool}", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID),
       @EnvironmentVariable(key = "CLIENT_ID", value = "${booking_userpool}", valueTransformer = ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID),
       @EnvironmentVariable(key = "TABLES_TABLE", value = "${tables_table}"),
       @EnvironmentVariable(key = "RESERVATIONS_TABLE", value = "${reservations_table}")
})
public class ApiHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String region = System.getenv("REGION");
    private final String cognitoUserPoolId = System.getenv("COGNITO_ID");
    private final String cognitoClientId = System.getenv("CLIENT_ID");
    private final String tablesTable = System.getenv("TABLES_TABLE");
    private final String reservationsTable = System.getenv("RESERVATIONS_TABLE");

    private final CognitoIdentityProviderClient cognitoClient;
    private final DynamoDbClient dynamoDbClient;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
          "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );

    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
          "^[a-zA-Z0-9$%^*\\-_]{12,}$"
    );

    public ApiHandler() {
       Region awsRegion = region != null ? Region.of(region) : Region.EU_CENTRAL_1;
       this.cognitoClient = CognitoIdentityProviderClient.builder()
             .region(awsRegion)
             .build();
       this.dynamoDbClient = DynamoDbClient.builder()
             .region(awsRegion)
             .build();
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
       // Log the entire raw input for debugging
       context.getLogger().log("RAW EVENT: " + event);

       try {
          // Extract fields from the raw event map
          String httpMethod = extractString(event, "httpMethod");
          String resource = extractString(event, "resource");
          String path = extractString(event, "path");
          String body = extractBody(event);
          Map<String, String> headers = extractMapOfStrings(event, "headers");
          Map<String, String> pathParameters = extractMapOfStrings(event, "pathParameters");

          context.getLogger().log("httpMethod: " + httpMethod);
          context.getLogger().log("resource: " + resource);
          context.getLogger().log("path: " + path);
          context.getLogger().log("body: " + body);
          context.getLogger().log("headers: " + headers);
          context.getLogger().log("pathParameters: " + pathParameters);

          // Use path for routing (more reliable than resource)
          String routePath = path != null ? path : resource;

          // Remove trailing slash if present
          if (routePath != null && routePath.length() > 1 && routePath.endsWith("/")) {
             routePath = routePath.substring(0, routePath.length() - 1);
          }

          context.getLogger().log("Routing: method=" + httpMethod + ", routePath=" + routePath);

          // Route based on path and method
          if ("/signup".equals(routePath) && "POST".equals(httpMethod)) {
             return handleSignup(body, context);
          } else if ("/signin".equals(routePath) && "POST".equals(httpMethod)) {
             return handleSignin(body, context);
          } else if ("/tables".equals(routePath) && "GET".equals(httpMethod)) {
             return handleGetTables(context);
          } else if ("/tables".equals(routePath) && "POST".equals(httpMethod)) {
             return handlePostTable(body, context);
          } else if (routePath != null && routePath.matches("/tables/.+") && "GET".equals(httpMethod)) {
             return handleGetTableById(routePath, pathParameters, context);
          } else if ("/reservations".equals(routePath) && "POST".equals(httpMethod)) {
             return handlePostReservation(body, context);
          } else if ("/reservations".equals(routePath) && "GET".equals(httpMethod)) {
             return handleGetReservations(context);
          } else {
             return buildResponse(400, Map.of("message", "Unsupported route: " + httpMethod + " " + routePath));
          }
       } catch (Exception e) {
          context.getLogger().log("UNHANDLED ERROR: " + e.getMessage());
          e.printStackTrace();
          return buildResponse(400, Map.of("message", "Error: " + e.getMessage()));
       }
    }

    // ==================== SIGNUP ====================
    private Map<String, Object> handleSignup(String body, Context context) {
       try {
          Map<String, Object> requestBody = parseBody(body);

          String firstName = (String) requestBody.get("firstName");
          String lastName = (String) requestBody.get("lastName");
          String email = (String) requestBody.get("email");
          String password = (String) requestBody.get("password");

          if (firstName == null || lastName == null || email == null || password == null) {
             return buildResponse(400, Map.of("message", "Missing required fields"));
          }

          if (!EMAIL_PATTERN.matcher(email).matches()) {
             return buildResponse(400, Map.of("message", "Invalid email format"));
          }

          if (!PASSWORD_PATTERN.matcher(password).matches()) {
             return buildResponse(400, Map.of("message", "Invalid password format"));
          }

          context.getLogger().log("Creating user: " + email);

          // AdminCreateUser
          AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                .userPoolId(cognitoUserPoolId)
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
          context.getLogger().log("User created successfully");

          // AdminSetUserPassword to confirm user
          AdminSetUserPasswordRequest setPasswordRequest = AdminSetUserPasswordRequest.builder()
                .userPoolId(cognitoUserPoolId)
                .username(email)
                .password(password)
                .permanent(true)
                .build();

          cognitoClient.adminSetUserPassword(setPasswordRequest);
          context.getLogger().log("User password set and confirmed");

          return buildResponse(200, Map.of("message", "Sign-up process is successful"));

       } catch (UsernameExistsException e) {
          context.getLogger().log("User already exists: " + e.getMessage());
          return buildResponse(400, Map.of("message", "User already exists"));
       } catch (Exception e) {
          context.getLogger().log("Signup error: " + e.getMessage());
          return buildResponse(400, Map.of("message", "Sign-up failed: " + e.getMessage()));
       }
    }

    // ==================== SIGNIN ====================
    private Map<String, Object> handleSignin(String body, Context context) {
       try {
          Map<String, Object> requestBody = parseBody(body);

          String email = (String) requestBody.get("email");
          String password = (String) requestBody.get("password");

          if (email == null || password == null) {
             return buildResponse(400, Map.of("message", "Missing email or password"));
          }

          context.getLogger().log("Signing in user: " + email);

          Map<String, String> authParams = new HashMap<>();
          authParams.put("USERNAME", email);
          authParams.put("PASSWORD", password);

          AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                .userPoolId(cognitoUserPoolId)
                .clientId(cognitoClientId)
                .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                .authParameters(authParams)
                .build();

          AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

          context.getLogger().log("Auth successful");

          // Return ID token (NOT access token)
          String idToken = authResponse.authenticationResult().idToken();

          return buildResponse(200, Map.of("idToken", idToken));

       } catch (NotAuthorizedException e) {
          context.getLogger().log("Auth failed - not authorized: " + e.getMessage());
          return buildResponse(400, Map.of("message", "Invalid credentials"));
       } catch (UserNotFoundException e) {
          context.getLogger().log("Auth failed - user not found: " + e.getMessage());
          return buildResponse(400, Map.of("message", "User not found"));
       } catch (Exception e) {
          context.getLogger().log("Signin error: " + e.getMessage());
          return buildResponse(400, Map.of("message", "Sign-in failed: " + e.getMessage()));
       }
    }

    // ==================== GET /tables ====================
    private Map<String, Object> handleGetTables(Context context) {
       try {
          context.getLogger().log("Getting all tables from: " + tablesTable);

          ScanRequest scanRequest = ScanRequest.builder()
                .tableName(tablesTable)
                .build();

          ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

          List<Map<String, Object>> tables = scanResponse.items().stream()
                .map(this::convertDynamoItemToTable)
                .collect(Collectors.toList());

          Map<String, Object> responseBody = new HashMap<>();
          responseBody.put("tables", tables);

          return buildResponse(200, responseBody);

       } catch (Exception e) {
          context.getLogger().log("GetTables error: " + e.getMessage());
          return buildResponse(400, Map.of("message", "Failed to get tables: " + e.getMessage()));
       }
    }

    // ==================== POST /tables ====================
    private Map<String, Object> handlePostTable(String body, Context context) {
       try {
          Map<String, Object> requestBody = parseBody(body);

          int id = toInt(requestBody.get("id"));
          int number = toInt(requestBody.get("number"));
          int places = toInt(requestBody.get("places"));
          boolean isVip = toBoolean(requestBody.get("isVip"));

          context.getLogger().log("Creating table with id: " + id);

          Map<String, AttributeValue> item = new HashMap<>();
          item.put("id", AttributeValue.builder().s(String.valueOf(id)).build());
          item.put("number", AttributeValue.builder().n(String.valueOf(number)).build());
          item.put("places", AttributeValue.builder().n(String.valueOf(places)).build());
          item.put("isVip", AttributeValue.builder().bool(isVip).build());

          if (requestBody.containsKey("minOrder") && requestBody.get("minOrder") != null) {
             int minOrder = toInt(requestBody.get("minOrder"));
             item.put("minOrder", AttributeValue.builder().n(String.valueOf(minOrder)).build());
          }

          PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(tablesTable)
                .item(item)
                .build();

          dynamoDbClient.putItem(putItemRequest);

          context.getLogger().log("Table created successfully with id: " + id);

          return buildResponse(200, Map.of("id", id));

       } catch (Exception e) {
          context.getLogger().log("PostTable error: " + e.getMessage());
          return buildResponse(400, Map.of("message", "Failed to create table: " + e.getMessage()));
       }
    }

    // ==================== GET /tables/{tableId} ====================
    private Map<String, Object> handleGetTableById(String path, Map<String, String> pathParameters, Context context) {
       try {
          String tableIdStr = null;

          if (pathParameters != null && pathParameters.containsKey("tableId")) {
             tableIdStr = pathParameters.get("tableId");
          }

          if (tableIdStr == null || tableIdStr.isEmpty()) {
             String[] segments = path.split("/");
             if (segments.length >= 3) {
                tableIdStr = segments[2];
             }
          }

          if (tableIdStr == null || tableIdStr.isEmpty()) {
             return buildResponse(400, Map.of("message", "Missing tableId path parameter"));
          }

          context.getLogger().log("Extracted tableId: " + tableIdStr);

          int tableId;
          try {
             tableId = Integer.parseInt(tableIdStr);
          } catch (NumberFormatException e) {
             return buildResponse(400, Map.of("message", "Invalid tableId: must be an integer"));
          }

          context.getLogger().log("Getting table by id: " + tableId);

          Map<String, AttributeValue> key = new HashMap<>();

          key.put("id", AttributeValue.builder().s(String.valueOf(tableId)).build());

          GetItemRequest getItemRequest = GetItemRequest.builder()
                .tableName(tablesTable)
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
    private Map<String, Object> handlePostReservation(String body, Context context) {
       try {
          Map<String, Object> requestBody = parseBody(body);

          int tableNumber = toInt(requestBody.get("tableNumber"));
          String clientName = (String) requestBody.get("clientName");
          String phoneNumber = (String) requestBody.get("phoneNumber");
          String date = (String) requestBody.get("date");
          String slotTimeStart = (String) requestBody.get("slotTimeStart");
          String slotTimeEnd = (String) requestBody.get("slotTimeEnd");

          context.getLogger().log("Creating reservation for table number: " + tableNumber);

          // Validate that the table exists by tableNumber
          if (!tableExistsByNumber(tableNumber, context)) {
             return buildResponse(400, Map.of("message", "Table with number " + tableNumber + " does not exist"));
          }

          // Check for overlapping reservations
          if (hasOverlappingReservation(tableNumber, date, slotTimeStart, slotTimeEnd, context)) {
             return buildResponse(400, Map.of("message", "Overlapping reservation exists for this table"));
          }

          // Create reservation
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
                .tableName(reservationsTable)
                .item(item)
                .build();

          dynamoDbClient.putItem(putItemRequest);

          context.getLogger().log("Reservation created with id: " + reservationId);

          return buildResponse(200, Map.of("reservationId", reservationId));

       } catch (Exception e) {
          context.getLogger().log("PostReservation error: " + e.getMessage());
          return buildResponse(400, Map.of("message", "Failed to create reservation: " + e.getMessage()));
       }
    }

    // ==================== GET /reservations ====================
    private Map<String, Object> handleGetReservations(Context context) {
       try {
          context.getLogger().log("Getting all reservations from: " + reservationsTable);

          ScanRequest scanRequest = ScanRequest.builder()
                .tableName(reservationsTable)
                .build();

          ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);

          List<Map<String, Object>> reservations = scanResponse.items().stream()
                .map(this::convertDynamoItemToReservation)
                .collect(Collectors.toList());

          Map<String, Object> responseBody = new HashMap<>();
          responseBody.put("reservations", reservations);

          return buildResponse(200, responseBody);

       } catch (Exception e) {
          context.getLogger().log("GetReservations error: " + e.getMessage());
          return buildResponse(400, Map.of("message", "Failed to get reservations: " + e.getMessage()));
       }
    }

    // ==================== HELPER METHODS ====================

    /**
     * Safely extract a String value from the event map.
     */
    private String extractString(Map<String, Object> event, String key) {
       if (event == null || !event.containsKey(key)) {
          return null;
       }
       Object value = event.get(key);
       return value != null ? value.toString() : null;
    }

    /**
     * Extract the body from the event.
     * Body can be a String (JSON) or already a Map (if API Gateway parsed it).
     */
    @SuppressWarnings("unchecked")
    private String extractBody(Map<String, Object> event) {
       if (event == null || !event.containsKey("body")) {
          return null;
       }
       Object bodyObj = event.get("body");
       if (bodyObj == null) {
          return null;
       }
       if (bodyObj instanceof String) {
          return (String) bodyObj;
       }
       if (bodyObj instanceof Map) {
          // Body is already a Map, serialize it back to JSON string
          try {
             return objectMapper.writeValueAsString(bodyObj);
          } catch (JsonProcessingException e) {
             return bodyObj.toString();
          }
       }
       return bodyObj.toString();
    }

    /**
     * Extract a Map<String, String> from the event (for headers, pathParameters, queryStringParameters).
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> extractMapOfStrings(Map<String, Object> event, String key) {
       if (event == null || !event.containsKey(key)) {
          return null;
       }
       Object value = event.get(key);
       if (value == null) {
          return null;
       }
       if (value instanceof Map) {
          Map<String, String> result = new HashMap<>();
          Map<?, ?> rawMap = (Map<?, ?>) value;
          for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
             if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
             }
          }
          return result;
       }
       return null;
    }

    private boolean tableExistsByNumber(int tableNumber, Context context) {
       context.getLogger().log("Checking if table exists with number: " + tableNumber);

       ScanRequest scanRequest = ScanRequest.builder()
             .tableName(tablesTable)
             .filterExpression("#num = :tableNumber")
             .expressionAttributeNames(Map.of("#num", "number"))
             .expressionAttributeValues(Map.of(
                   ":tableNumber", AttributeValue.builder().n(String.valueOf(tableNumber)).build()
             ))
             .build();

       ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
       boolean exists = !scanResponse.items().isEmpty();
       context.getLogger().log("Table exists: " + exists);
       return exists;
    }

    private boolean hasOverlappingReservation(int tableNumber, String date, String slotTimeStart, String slotTimeEnd, Context context) {
       context.getLogger().log("Checking for overlapping reservations");

       ScanRequest scanRequest = ScanRequest.builder()
             .tableName(reservationsTable)
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

          // Check overlap: new start < existing end AND new end > existing start
          if (slotTimeStart.compareTo(existingEnd) < 0 && slotTimeEnd.compareTo(existingStart) > 0) {
             context.getLogger().log("Overlapping reservation found");
             return true;
          }
       }

       return false;
    }

    private Map<String, Object> convertDynamoItemToTable(Map<String, AttributeValue> item) {
       Map<String, Object> table = new HashMap<>();
       // ✅ FIX: Read id as String (.s()) then parse to int for response
       table.put("id", Integer.parseInt(item.get("id").s()));
       table.put("number", Integer.parseInt(item.get("number").n()));
       table.put("places", Integer.parseInt(item.get("places").n()));
       table.put("isVip", item.get("isVip").bool());

       if (item.containsKey("minOrder") && item.get("minOrder") != null && item.get("minOrder").n() != null) {
          table.put("minOrder", Integer.parseInt(item.get("minOrder").n()));
       }

       return table;
    }

    private Map<String, Object> convertDynamoItemToReservation(Map<String, AttributeValue> item) {
       Map<String, Object> reservation = new HashMap<>();
       reservation.put("tableNumber", Integer.parseInt(item.get("tableNumber").n()));
       reservation.put("clientName", item.get("clientName").s());
       reservation.put("phoneNumber", item.get("phoneNumber").s());
       reservation.put("date", item.get("date").s());
       reservation.put("slotTimeStart", item.get("slotTimeStart").s());
       reservation.put("slotTimeEnd", item.get("slotTimeEnd").s());
       return reservation;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(String body) throws JsonProcessingException {
       if (body == null || body.isEmpty()) {
          return new HashMap<>();
       }
       return objectMapper.readValue(body, Map.class);
    }

    private int toInt(Object value) {
       if (value instanceof Integer) {
          return (Integer) value;
       } else if (value instanceof Number) {
          return ((Number) value).intValue();
       } else if (value instanceof String) {
          return Integer.parseInt((String) value);
       }
       throw new IllegalArgumentException("Cannot convert to int: " + value);
    }

    private boolean toBoolean(Object value) {
       if (value instanceof Boolean) {
          return (Boolean) value;
       } else if (value instanceof String) {
          return Boolean.parseBoolean((String) value);
       }
       return false;
    }

    /**
     * Build the API Gateway proxy response as a Map.
     */
    private Map<String, Object> buildResponse(int statusCode, Map<String, Object> body) {
       Map<String, Object> response = new HashMap<>();
       response.put("statusCode", statusCode);

       Map<String, String> headers = new HashMap<>();
       headers.put("Content-Type", "application/json");
       headers.put("Access-Control-Allow-Origin", "*");
       headers.put("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
       headers.put("Access-Control-Allow-Headers", "Content-Type,Authorization");
       response.put("headers", headers);

       try {
          response.put("body", objectMapper.writeValueAsString(body));
       } catch (JsonProcessingException e) {
          response.put("body", "{\"message\": \"Error serializing response\"}");
       }

       return response;
    }
}