package com.API_POC.PetstoreApi;


import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;


import java.io.InputStream;


/**
* Processor שבודק שהבקשה הנכנסת תואמת ל-OpenAPI spec
* לפני שליחת הבקשה ל-Petstore API
*/
public class SwaggerValidatorProcessor implements Processor {


   private static final Logger LOG = LoggerFactory.getLogger(SwaggerValidatorProcessor.class);
   private OpenAPI openAPI;


   public SwaggerValidatorProcessor() {
       loadOpenAPISpec();
   }


   /**
    * טוען את קובץ ה-OpenAPI spec'
    */
   private void loadOpenAPISpec() {
       try {
           ClassPathResource resource = new ClassPathResource("swagger/petstore-openapi.json");
           InputStream inputStream = resource.getInputStream();
          
           // קריאת ה-InputStream למחרוזת JSON
           
           //למה להשתמש בstream?? ולא ב-readFile?
           String jsonContent = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
           inputStream.close();
          
           // פרסור ה-JSON ל-OpenAPI
           OpenAPIV3Parser parser = new OpenAPIV3Parser();
           openAPI = parser.readContents(jsonContent, null, null).getOpenAPI();
           LOG.info("OpenAPI spec loaded successfully");
       } catch (Exception e) {
           throw new RuntimeException("Failed to load OpenAPI spec", e);
       }
   }


   @Override
   public void process(Exchange exchange) throws Exception {
       // קבלת נתונים מהבקשה
       String requestPath = exchange.getIn().getHeader(Exchange.HTTP_PATH, String.class);
       String method = exchange.getIn().getHeader(Exchange.HTTP_METHOD, String.class);
      
       // מציאת ה-path pattern ב-spec שמתאים ל-path של הבקשה
       String specPath = findMatchingSpecPath(requestPath);
       if (specPath == null) {
           throw new IllegalArgumentException("Path not found in API spec: " + requestPath);
       }


       LOG.info("Validating request: {} {} (matches spec path: {})", method, requestPath, specPath);


       // מציאת ה-operation ב-spec לפי path
       io.swagger.v3.oas.models.PathItem pathItem = openAPI.getPaths().get(specPath);
       if (pathItem == null) {
           throw new IllegalArgumentException("Path not found in API spec: " + specPath);
       }


       // מציאת ה-operation לפי method
       io.swagger.v3.oas.models.Operation operation = null;
       if ("GET".equals(method)) {
           operation = pathItem.getGet();
       } else if ("POST".equals(method)) {
           operation = pathItem.getPost();
       } else if ("PUT".equals(method)) {
           operation = pathItem.getPut();
       } else if ("DELETE".equals(method)) {
           operation = pathItem.getDelete();
       }


       if (operation == null) {
           throw new IllegalArgumentException("Method " + method + " not allowed for path: " + specPath);
       }


       // ולידציה של path parameters
       validatePathParameters(exchange, operation, requestPath, specPath);


       // ולידציה של query parameters
       validateQueryParameters(exchange, operation);


       // ולידציה של request body (אם POST/PUT)
       if ("POST".equals(method) || "PUT".equals(method)) {
           validateRequestBody(exchange, operation);
       }


       LOG.info("Validation passed for {} {}", method, requestPath);
   }


   /**
    * בודק ש-path parameters נדרשים קיימים ותקינים
    */
   private void validatePathParameters(Exchange exchange, io.swagger.v3.oas.models.Operation operation,
                                      String requestPath, String specPath) {
       if (operation.getParameters() == null) {
           return;
       }


       for (io.swagger.v3.oas.models.parameters.Parameter param : operation.getParameters()) {
           if ("path".equals(param.getIn()) && Boolean.TRUE.equals(param.getRequired())) {
               String paramName = param.getName();
              
               // ניסיון לקרוא מהכותרת (Camel מכניס path params לכותרות)
               String value = exchange.getIn().getHeader(paramName, String.class);
              
               // אם לא נמצא בכותרת, נחלץ מה-path עצמו
               if (value == null || value.isEmpty()) {
                   value = extractPathParameterValue(requestPath, specPath, paramName);
               }
              
               if (value == null || value.isEmpty()) {
                   throw new IllegalArgumentException("Required path parameter missing: " + paramName);
               }


               // ולידציה של type - אם זה integer, בודק שהערך תקין
               if (param.getSchema() != null && "integer".equals(param.getSchema().getType())) {
                   try {
                       Long.parseLong(value);
                   } catch (NumberFormatException e) {
                       throw new IllegalArgumentException(
                           "Path parameter '" + paramName + "' must be an integer, got: " + value);
                   }
               }
           }
       }
   }


   /**
    * מחלץ את הערך של path parameter מה-path של הבקשה
    * למשל: requestPath=/pet/1, specPath=/pet/{petId}, paramName=petId -> "1"
    */
   private String extractPathParameterValue(String requestPath, String specPath, String paramName) {
       if (requestPath == null || specPath == null || paramName == null) {
           return null;
       }


       String[] requestParts = requestPath.split("/");
       String[] specParts = specPath.split("/");


       if (requestParts.length != specParts.length) {
           return null;
       }


       // מצא את המיקום של {paramName} ב-spec path
       for (int i = 0; i < specParts.length; i++) {
           String specPart = specParts[i];
           if (specPart.equals("{" + paramName + "}")) {
               // החזר את הערך המקביל מה-request path
               if (i < requestParts.length) {
                   return requestParts[i];
               }
           }
       }


       return null;
   }


   /**
    * בודק ש-query parameters נדרשים קיימים ותקינים
    */
   private void validateQueryParameters(Exchange exchange, io.swagger.v3.oas.models.Operation operation) {
       if (operation.getParameters() == null) {
           return;
       }


       for (io.swagger.v3.oas.models.parameters.Parameter param : operation.getParameters()) {
           if ("query".equals(param.getIn())) {
               String paramName = param.getName();
               String value = exchange.getIn().getHeader(paramName, String.class);


               if (Boolean.TRUE.equals(param.getRequired()) && (value == null || value.isEmpty())) {
                   throw new IllegalArgumentException("Required query parameter missing: " + paramName);
               }


               // ולידציה של enum (אם קיים)
               if (value != null && param.getSchema() != null && param.getSchema().getEnum() != null) {
                   if (!param.getSchema().getEnum().contains(value)) {
                       throw new IllegalArgumentException(
                           "Invalid enum value for '" + paramName + "'. Allowed values: " +
                           param.getSchema().getEnum() + ", got: " + value);
                   }
               }
           }
       }
   }


   /**
    * בודק ש-request body קיים ותקין (JSON)
    */
   private void validateRequestBody(Exchange exchange, io.swagger.v3.oas.models.Operation operation) {
       io.swagger.v3.oas.models.parameters.RequestBody requestBody = operation.getRequestBody();
       if (requestBody == null || !Boolean.TRUE.equals(requestBody.getRequired())) {
           return; // לא חובה
       }


       String body = exchange.getIn().getBody(String.class);
       if (body == null || body.trim().isEmpty()) {
           throw new IllegalArgumentException("Request body is required but missing");
       }


       // ולידציה בסיסית - יש JSON תקין
       try {
           com.fasterxml.jackson.databind.ObjectMapper mapper =
               new com.fasterxml.jackson.databind.ObjectMapper();
           mapper.readTree(body);
       } catch (Exception e) {
           throw new IllegalArgumentException("Invalid JSON in request body: " + e.getMessage());
       }
   }


   /**
    * מוצא את ה-path pattern ב-spec שמתאים ל-path של הבקשה
    * למשל: /pet/1 יתאים ל-/pet/{petId}
    */
   private String findMatchingSpecPath(String requestPath) {
       if (requestPath == null || openAPI.getPaths() == null) {
           return null;
       }


       // עבור על כל ה-paths ב-spec
       for (String specPath : openAPI.getPaths().keySet()) {
           if (matchesPath(requestPath, specPath)) {
               return specPath;
           }
       }


       return null;
   }


   /**
    * בודק אם request path מתאים ל-spec path pattern
    * למשל: /pet/1 מתאים ל-/pet/{petId}
    */
   private boolean matchesPath(String requestPath, String specPath) {
       if (requestPath == null || specPath == null) {
           return false;
       }


       // הפרדה לחלקים
       String[] requestParts = requestPath.split("/");
       String[] specParts = specPath.split("/");


       if (requestParts.length != specParts.length) {
           return false;
       }


       // השוואה חלק אחר חלק
       for (int i = 0; i < requestParts.length; i++) {
           String requestPart = requestParts[i];
           String specPart = specParts[i];


           // אם זה template variable ב-spec (מתחיל ב-{), זה מתאים לכל ערך
           if (specPart.startsWith("{") && specPart.endsWith("}")) {
               continue; // זה מתאים
           }


           // אחרת, צריך התאמה מדויקת
           if (!requestPart.equals(specPart)) {
               return false;
           }
       }


       return true;
   }
}







