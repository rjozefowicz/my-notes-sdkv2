package dev.jozefowicz.stacjait.mynotes.listnotes;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jozefowicz.stacjait.mynotes.common.ResponseNote;
import dev.jozefowicz.stacjait.mynotes.common.NoteType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.jozefowicz.stacjait.mynotes.common.APIGatewayProxyResponseEventBuilder.response;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class ListNotesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private  static final String TABLE_NAME = System.getenv("TABLE_NAME");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();

    public ListNotesHandler() {
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        try {
            if (!request.getHttpMethod().equalsIgnoreCase("get")) {
                return response(405, null);
            } else if (isNull(request.getRequestContext().getAuthorizer()) || request.getRequestContext().getAuthorizer().isEmpty()) {
                context.getLogger().log("Authorizer not configured");
                return response(401, null);
            }

            /**
             * "claims": {
             *         "sub": "fd3be9de-bd0d-44c9-85e5-bf24a4c5503d",
             *         "aud": "3dohu5vurk9rbc38of88of59k8",
             *         "email_verified": "true",
             *         "event_id": "8deee96b-2010-4d7e-b3d5-d2c4c7af6e99",
             *         "token_use": "id",
             *         "auth_time": "1562705770",
             *         "iss": "https://cognito-idp.eu-west-2.amazonaws.com/eu-west-2_JtSJaeBr1",
             *         "cognito:username": "fd3be9de-bd0d-44c9-85e5-bf24a4c5503d",
             *         "exp": "Mon Jul 15 21:49:52 UTC 2019",
             *         "iat": "Mon Jul 15 20:49:52 UTC 2019",
             *         "email": "radoslawjozefowicz+1@gmail.com"
             *     }
             */

            Map<String, String> claims = (Map<String, String>) request.getRequestContext().getAuthorizer().get("claims");
            final String userId = claims.get("cognito:username");

            QueryResponse queryResponse = dynamoDbClient.query(QueryRequest
                    .builder()
                    .tableName(TABLE_NAME)
                    .keyConditionExpression("#userId = :userId")
                    .expressionAttributeNames(Collections.singletonMap("#userId", "userId"))
                    .expressionAttributeValues(Collections.singletonMap(":userId", AttributeValue.builder().s(userId).build()))
                    .build());
            final Page page = new Page(queryResponse.items().stream().map(this::mapToNote).collect(Collectors.toList()), nonNull(queryResponse.lastEvaluatedKey()));
            return response(200, objectMapper.writeValueAsString(page));
        } catch (Exception e) {
            context.getLogger().log("Exception while processing request");
            e.printStackTrace();
            return response(500, null);
        }
    }

    private ResponseNote mapToNote(Map<String, AttributeValue> item) {
        return new ResponseNote(
                item.get("noteId").s(),
                item.get("title").s(),
                nonNull(item.get("text")) ? item.get("text").s() : null,
                Long.valueOf(item.get("timestamp").n()),
                NoteType.valueOf(item.get("type").s()),
                nonNull(item.get("size")) ? Long.valueOf(item.get("size").n()) : null,
                item.containsKey("labels") ? item.get("labels").ss() : null);
    }

}
