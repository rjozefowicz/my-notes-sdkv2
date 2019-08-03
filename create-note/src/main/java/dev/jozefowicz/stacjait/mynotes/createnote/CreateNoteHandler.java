package dev.jozefowicz.stacjait.mynotes.createnote;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jozefowicz.stacjait.mynotes.common.PersistedNote;
import software.amazon.awssdk.services.comprehend.ComprehendClient;
import software.amazon.awssdk.services.comprehend.model.DetectDominantLanguageRequest;
import software.amazon.awssdk.services.comprehend.model.DetectDominantLanguageResponse;
import software.amazon.awssdk.services.comprehend.model.DetectEntitiesRequest;
import software.amazon.awssdk.services.comprehend.model.DominantLanguage;
import software.amazon.awssdk.services.comprehend.model.Entity;
import software.amazon.awssdk.services.comprehend.model.LanguageCode;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.jozefowicz.stacjait.mynotes.common.APIGatewayProxyResponseEventBuilder.response;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public class CreateNoteHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final static String TABLE_NAME = System.getenv("TABLE_NAME");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ComprehendClient comprehendClient = ComprehendClient.create();
    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();

    public CreateNoteHandler() {
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {

            if (isNull(request.getRequestContext().getAuthorizer()) || request.getRequestContext().getAuthorizer().isEmpty()) {
                context.getLogger().log("Authorizer not configured");
                return response(401, null);
            }

            Map<String, String> claims = (Map<String, String>) request.getRequestContext().getAuthorizer().get("claims");
            final String userId = claims.get("cognito:username");

            if (isNull(request.getBody())) {
                return response(400, null);
            }

            final PersistedNote note = this.objectMapper.readValue(request.getBody(), PersistedNote.class);

            if (invalid(note)) {
                return response(400, null);
            }

            switch (request.getHttpMethod().toUpperCase()) {
                case "PUT":
                    if (nonNull(request.getPathParameters()) && request.getPathParameters().containsKey("id")) {
                        return put(userId, request.getPathParameters().get("id"), note);
                    }
                    return response(400, null);
                case "POST":
                    return post(userId, note);
                default:
                    return response(405, null);
            }
        } catch (Exception e) {
            context.getLogger().log("Exception while processing request");
            e.printStackTrace();
            return response(500, null);
        }
    }

    private APIGatewayProxyResponseEvent post(String userId, PersistedNote note) {
        PersistedNote newNote = PersistedNote.create(userId, note.getTitle(), note.getText(), analyze(note.getText()));
        persist(newNote);
        return response(200, null);
    }

    private boolean invalid(PersistedNote note) {
        return isNull(note.getText()) || note.getText().isEmpty() || isNull(note.getTitle()) || note.getTitle().isEmpty();
    }

    private APIGatewayProxyResponseEvent put(String userId, String noteId, PersistedNote note) {
        PersistedNote updated = PersistedNote.updated(userId, noteId, note.getTitle(), note.getText(), analyze(note.getText()));
        persist(updated);
        return response(200, null);
    }

    private List<String> analyze(String textToAnalyze) {
        DetectDominantLanguageResponse dominantLanguage = comprehendClient.detectDominantLanguage(DetectDominantLanguageRequest.builder().text(textToAnalyze).build());
        return new ArrayList<>(dominantLanguage
                .languages()
                .stream()
                .filter(language -> LanguageCode.fromValue(language.languageCode()) != LanguageCode.UNKNOWN_TO_SDK_VERSION)
                .map(DominantLanguage::languageCode)
                .map(code -> comprehendClient.detectEntities(DetectEntitiesRequest.builder().languageCode(code).text(textToAnalyze).build()))
                .flatMap(detectEntitiesResponse -> detectEntitiesResponse.entities().stream())
                .map(Entity::text)
                .collect(Collectors.toSet()));
    }

    private void persist(PersistedNote note) {
        Map<String, AttributeValue> item = mapToItem(note);
        dynamoDbClient.putItem(PutItemRequest
                .builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build());
    }

    private Map<String, AttributeValue> mapToItem(PersistedNote note) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(note.getUserId()).build());
        item.put("noteId", AttributeValue.builder().s(note.getNoteId()).build());
        item.put("title", AttributeValue.builder().s(note.getTitle()).build());
        item.put("text", AttributeValue.builder().s(note.getText()).build());
        item.put("type", AttributeValue.builder().s(note.getType().name()).build());
        if (nonNull(note.getLabels()) && !note.getLabels().isEmpty()) {
            item.put("labels", AttributeValue.builder().ss(note.getLabels()).build());
        }
        item.put("timestamp", AttributeValue.builder().n(Long.toString(note.getTimestamp())).build());
        return item;
    }

}
