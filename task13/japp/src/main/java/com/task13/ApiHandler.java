package com.task13;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syndicate.deployment.annotations.environment.EnvironmentVariable;
import com.syndicate.deployment.annotations.environment.EnvironmentVariables;
import com.syndicate.deployment.annotations.lambda.LambdaHandler;
import com.syndicate.deployment.annotations.resources.DependsOn;
import com.syndicate.deployment.model.DeploymentRuntime;
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
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

@LambdaHandler(
    lambdaName = "api_handler",
    roleName = "api_handler-role",
    isPublishVersion = true,
    runtime = DeploymentRuntime.JAVA21,
    timeout = 60,
    memory = 150,
    aliasName = "${lambdas_alias_name}",
    logsExpiration = RetentionSetting.SYNDICATE_ALIASES_SPECIFIED)
@DependsOn(resourceType = ResourceType.COGNITO_USER_POOL, name = "${booking_userpool}")
@EnvironmentVariables(
    value = {
      @EnvironmentVariable(key = "REGION", value = "${region}"),
      @EnvironmentVariable(key = "TABLES_TABLE", value = "${tables_table}"),
      @EnvironmentVariable(key = "RESERVATIONS_TABLE", value = "${reservations_table}"),
      @EnvironmentVariable(
          key = "COGNITO_ID",
          value = "${booking_userpool}",
          valueTransformer = ValueTransformer.USER_POOL_NAME_TO_USER_POOL_ID),
      @EnvironmentVariable(
          key = "CLIENT_ID",
          value = "${booking_userpool}",
          valueTransformer = ValueTransformer.USER_POOL_NAME_TO_CLIENT_ID)
    })
public class ApiHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
  private static final Pattern PASSWORD_PATTERN = Pattern.compile("^[A-Za-z0-9$%^*_\\-]{12,}$");
  private static final Map<String, String> RESPONSE_HEADERS =
      Map.of(
          "Content-Type", "application/json",
          "Access-Control-Allow-Headers",
              "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token",
          "Access-Control-Allow-Origin", "*",
          "Access-Control-Allow-Methods", "*",
          "Accept-Version", "*");

  private final Region region;
  private final DynamoDbClient dynamoDb;
  private final CognitoIdentityProviderClient cognito;
  private final String tablesTable;
  private final String reservationsTable;
  private final String userPoolId;
  private final String clientId;
  private LambdaLogger logger;

  public ApiHandler() {
    this.region = Region.of(requiredEnv("REGION"));
    this.dynamoDb = DynamoDbClient.builder().region(region).build();
    this.cognito = CognitoIdentityProviderClient.builder().region(region).build();
    this.tablesTable = requiredEnv("TABLES_TABLE");
    this.reservationsTable = requiredEnv("RESERVATIONS_TABLE");
    this.userPoolId = requiredEnv("COGNITO_ID");
    this.clientId = requiredEnv("CLIENT_ID");
  }

  private static APIGatewayProxyResponseEvent getOnly(String method, ThrowingHandler handler)
      throws Exception {
    if (!"GET".equals(method)) {
      return response(400, new MessageResponse("Unsupported method"));
    }
    return handler.handle();
  }

  private static APIGatewayProxyResponseEvent postOnly(String method, ThrowingHandler handler)
      throws Exception {
    if (!"POST".equals(method)) {
      return response(400, new MessageResponse("Unsupported method"));
    }
    return handler.handle();
  }

  private static JsonNode body(APIGatewayProxyRequestEvent request) throws Exception {
    String body = request.getBody();
    if (body == null || body.isBlank()) {
      throw new BadRequestException("Request body is required");
    }
    return MAPPER.readTree(body);
  }

  private static String route(APIGatewayProxyRequestEvent request) {
    String resource = request.getResource();
    if (resource != null && !resource.isBlank()) {
      return resource;
    }

    String path = Objects.toString(request.getPath(), "");
    if (path.matches(".*/tables/[^/]+$")) {
      return "/tables/{tableId}";
    }
    if (path.endsWith("/signup")) {
      return "/signup";
    }
    if (path.endsWith("/signin")) {
      return "/signin";
    }
    if (path.endsWith("/tables")) {
      return "/tables";
    }
    if (path.endsWith("/reservations")) {
      return "/reservations";
    }
    return path;
  }

  private static String pathParam(APIGatewayProxyRequestEvent request, String name) {
    Map<String, String> params = request.getPathParameters();
    if (params != null && params.get(name) != null && !params.get(name).isBlank()) {
      return params.get(name);
    }

    String path = Objects.toString(request.getPath(), "");
    int slash = path.lastIndexOf('/');
    if (slash >= 0 && slash + 1 < path.length()) {
      return path.substring(slash + 1);
    }
    throw new BadRequestException("Missing path parameter: " + name);
  }

  private static String text(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || value.isNull() || value.asText().isBlank()) {
      throw new BadRequestException("Missing required field: " + field);
    }
    return value.asText();
  }

  private static String email(JsonNode body) {
    String value = text(body, "email");
    if (!EMAIL_PATTERN.matcher(value).matches()) {
      throw new BadRequestException("Invalid email");
    }
    return value;
  }

  private static String password(JsonNode body) {
    String value = text(body, "password");
    if (!PASSWORD_PATTERN.matcher(value).matches()) {
      throw new BadRequestException("Invalid password");
    }
    return value;
  }

  private static int integer(JsonNode body, String field) {
    JsonNode value = body.get(field);
    if (value == null || !value.canConvertToInt()) {
      throw new BadRequestException("Missing integer field: " + field);
    }
    return value.intValue();
  }

  private static boolean bool(JsonNode body) {
    JsonNode value = body.get("isVip");
    if (value == null || !value.isBoolean()) {
      throw new BadRequestException("Missing boolean field: " + "isVip");
    }
    return value.booleanValue();
  }

  private static String date(JsonNode body) {
    String value = text(body, "date");
    try {
      LocalDate.parse(value);
      return value;
    } catch (DateTimeParseException e) {
      throw new BadRequestException("Invalid date");
    }
  }

  private static String time(JsonNode body, String field) {
    String value = text(body, field);
    try {
      LocalTime.parse(value);
      return value;
    } catch (DateTimeParseException e) {
      throw new BadRequestException("Invalid time");
    }
  }

  private static AttributeValue s(int value) {
    return AttributeValue.builder().s(String.valueOf(value)).build();
  }

  private static AttributeValue n(int value) {
    return AttributeValue.builder().n(String.valueOf(value)).build();
  }

  private static int intValue(Map<String, AttributeValue> item, String key) {
    AttributeValue value = item.get(key);
    if (value == null) {
      return 0;
    }
    if (value.n() != null) {
      return Integer.parseInt(value.n());
    }
    return Integer.parseInt(value.s());
  }

  private static String stringValue(Map<String, AttributeValue> item, String key) {
    AttributeValue value = item.get(key);
    return value == null ? "" : value.s();
  }

  private static String requiredEnv(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing environment variable: " + key);
    }
    return value;
  }

  private static APIGatewayProxyResponseEvent response(int statusCode, Object body) {
    try {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(RESPONSE_HEADERS)
          .withBody(MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(400)
          .withHeaders(RESPONSE_HEADERS)
          .withBody("{\"message\":\"There was an error in the request.\"}");
    }
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent request, Context context) {
    logger = context.getLogger();
    if (request == null) {
      return response(400, new MessageResponse("Bad request: request is null"));
    }

    String method = Objects.toString(request.getHttpMethod(), "");
    String resource = route(request);
    logger.log("API request received: %s %s%n".formatted(method, resource));

    try {
      return switch (resource) {
        case "/signup" -> postOnly(method, () -> signup(body(request)));
        case "/signin" -> postOnly(method, () -> signin(body(request)));
        case "/tables" -> handleTables(method, request);
        case "/tables/{tableId}" -> getOnly(method, () -> getTable(pathParam(request, "tableId")));
        case "/reservations" -> handleReservations(method, request);
        default -> response(400, new MessageResponse("Bad request syntax or unsupported resource"));
      };
    } catch (BadRequestException e) {
      logger.log("Bad request: %s%n".formatted(e.getMessage()));
      return response(400, new MessageResponse(e.getMessage()));
    } catch (Exception e) {
      logger.log("Request failed. %s: %s%n".formatted(e.getClass().getName(), e.getMessage()));
      return response(400, new MessageResponse("There was an error in the request."));
    }
  }

  private APIGatewayProxyResponseEvent handleTables(
      String method, APIGatewayProxyRequestEvent request) throws Exception {
    return switch (method) {
      case "GET" -> listTables();
      case "POST" -> createTable(body(request));
      default -> response(400, new MessageResponse("Unsupported method for /tables"));
    };
  }

  private APIGatewayProxyResponseEvent handleReservations(
      String method, APIGatewayProxyRequestEvent request) throws Exception {
    return switch (method) {
      case "GET" -> listReservations();
      case "POST" -> createReservation(body(request));
      default -> response(400, new MessageResponse("Unsupported method for /reservations"));
    };
  }

  private APIGatewayProxyResponseEvent signup(JsonNode body) {
    String firstName = text(body, "firstName");
    String lastName = text(body, "lastName");
    String email = email(body);
    String password = password(body);

    try {
      cognito.adminCreateUser(
          AdminCreateUserRequest.builder()
              .userPoolId(userPoolId)
              .username(email)
              .temporaryPassword(password)
              .messageAction(MessageActionType.SUPPRESS)
              .userAttributes(
                  AttributeType.builder().name("email").value(email).build(),
                  AttributeType.builder().name("email_verified").value("true").build(),
                  AttributeType.builder().name("given_name").value(firstName).build(),
                  AttributeType.builder().name("family_name").value(lastName).build())
              .build());
    } catch (UsernameExistsException ignored) {
      throw new BadRequestException("User already exists");
    }

    cognito.adminSetUserPassword(
        AdminSetUserPasswordRequest.builder()
            .userPoolId(userPoolId)
            .username(email)
            .password(password)
            .permanent(true)
            .build());

    return response(200, new MessageResponse("Sign-up process is successful"));
  }

  private APIGatewayProxyResponseEvent signin(JsonNode body) {
    String email = email(body);
    String password = password(body);

    AdminInitiateAuthResponse auth =
        cognito.adminInitiateAuth(
            AdminInitiateAuthRequest.builder()
                .userPoolId(userPoolId)
                .clientId(clientId)
                .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", email, "PASSWORD", password))
                .build());

    String idToken = auth.authenticationResult().idToken();
    if (idToken == null || idToken.isBlank()) {
      throw new BadRequestException("Authentication failed");
    }
    return response(200, Map.of("idToken", idToken));
  }

  private APIGatewayProxyResponseEvent createTable(JsonNode body) {
    int id = integer(body, "id");
    int number = integer(body, "number");
    int places = integer(body, "places");
    boolean isVip = bool(body);

    Map<String, AttributeValue> item = new LinkedHashMap<>();
    item.put("id", s(id));
    item.put("number", n(number));
    item.put("places", n(places));
    item.put("isVip", AttributeValue.builder().bool(isVip).build());
    if (body.hasNonNull("minOrder")) {
      item.put("minOrder", n(integer(body, "minOrder")));
    }

    dynamoDb.putItem(PutItemRequest.builder().tableName(tablesTable).item(item).build());
    return response(200, Map.of("id", id));
  }

  private APIGatewayProxyResponseEvent listTables() {
    List<Map<String, Object>> tables =
        dynamoDb.scan(ScanRequest.builder().tableName(tablesTable).build()).items().stream()
            .map(this::tableFrom)
            .sorted(Comparator.comparing(table -> (Integer) table.get("id")))
            .toList();
    return response(200, Map.of("tables", tables));
  }

  private APIGatewayProxyResponseEvent getTable(String tableId) {
    Map<String, AttributeValue> item =
        dynamoDb
            .getItem(
                GetItemRequest.builder()
                    .tableName(tablesTable)
                    .key(Map.of("id", AttributeValue.builder().s(tableId).build()))
                    .build())
            .item();

    if (item == null || item.isEmpty()) {
      throw new BadRequestException("Table not found");
    }
    return response(200, tableFrom(item));
  }

  private APIGatewayProxyResponseEvent createReservation(JsonNode body) {
    int tableNumber = integer(body, "tableNumber");
    String clientName = text(body, "clientName");
    String phoneNumber = text(body, "phoneNumber");
    String date = date(body);
    String slotTimeStart = time(body, "slotTimeStart");
    String slotTimeEnd = time(body, "slotTimeEnd");

    if (!LocalTime.parse(slotTimeStart).isBefore(LocalTime.parse(slotTimeEnd))) {
      throw new BadRequestException("slotTimeStart must be before slotTimeEnd");
    }
    if (!tableNumberExists(tableNumber)) {
      throw new BadRequestException("Table not found");
    }
    if (hasReservationConflict(tableNumber, date, slotTimeStart, slotTimeEnd)) {
      throw new BadRequestException("Conflicting reservation");
    }

    String reservationId = UUID.randomUUID().toString();
    Map<String, AttributeValue> item = new LinkedHashMap<>();
    item.put("id", AttributeValue.builder().s(reservationId).build());
    item.put("tableNumber", n(tableNumber));
    item.put("clientName", AttributeValue.builder().s(clientName).build());
    item.put("phoneNumber", AttributeValue.builder().s(phoneNumber).build());
    item.put("date", AttributeValue.builder().s(date).build());
    item.put("slotTimeStart", AttributeValue.builder().s(slotTimeStart).build());
    item.put("slotTimeEnd", AttributeValue.builder().s(slotTimeEnd).build());

    dynamoDb.putItem(PutItemRequest.builder().tableName(reservationsTable).item(item).build());
    return response(200, Map.of("reservationId", reservationId));
  }

  private APIGatewayProxyResponseEvent listReservations() {
    List<Map<String, Object>> reservations =
        dynamoDb.scan(ScanRequest.builder().tableName(reservationsTable).build()).items().stream()
            .map(this::reservationFrom)
            .sorted(Comparator.comparing(reservation -> reservation.get("date").toString()))
            .toList();
    return response(200, Map.of("reservations", reservations));
  }

  private boolean tableNumberExists(int tableNumber) {
    return dynamoDb.scan(ScanRequest.builder().tableName(tablesTable).build()).items().stream()
        .anyMatch(item -> intValue(item, "number") == tableNumber);
  }

  private boolean hasReservationConflict(
      int tableNumber, String date, String slotTimeStart, String slotTimeEnd) {
    LocalTime requestedStart = LocalTime.parse(slotTimeStart);
    LocalTime requestedEnd = LocalTime.parse(slotTimeEnd);

    for (Map<String, AttributeValue> item :
        dynamoDb.scan(ScanRequest.builder().tableName(reservationsTable).build()).items()) {
      if (intValue(item, "tableNumber") != tableNumber || !stringValue(item, "date").equals(date)) {
        continue;
      }
      LocalTime existingStart = LocalTime.parse(stringValue(item, "slotTimeStart"));
      LocalTime existingEnd = LocalTime.parse(stringValue(item, "slotTimeEnd"));
      if (requestedStart.isBefore(existingEnd) && requestedEnd.isAfter(existingStart)) {
        return true;
      }
    }
    return false;
  }

  private Map<String, Object> tableFrom(Map<String, AttributeValue> item) {
    Map<String, Object> table = new LinkedHashMap<>();
    table.put("id", intValue(item, "id"));
    table.put("number", intValue(item, "number"));
    table.put("places", intValue(item, "places"));
    table.put("isVip", item.get("isVip").bool());
    if (item.containsKey("minOrder")) {
      table.put("minOrder", intValue(item, "minOrder"));
    }
    return table;
  }

  private Map<String, Object> reservationFrom(Map<String, AttributeValue> item) {
    Map<String, Object> reservation = new LinkedHashMap<>();
    reservation.put("tableNumber", intValue(item, "tableNumber"));
    reservation.put("clientName", stringValue(item, "clientName"));
    reservation.put("phoneNumber", stringValue(item, "phoneNumber"));
    reservation.put("date", stringValue(item, "date"));
    reservation.put("slotTimeStart", stringValue(item, "slotTimeStart"));
    reservation.put("slotTimeEnd", stringValue(item, "slotTimeEnd"));
    return reservation;
  }

  private interface ThrowingHandler {
    APIGatewayProxyResponseEvent handle() throws Exception;
  }

  private static final class BadRequestException extends RuntimeException {
    private BadRequestException(String message) {
      super(message);
    }
  }

  private record MessageResponse(String message) {}
}
