package com.API_POC.PetstoreApi;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;


/**
* Route Builder שמספק נקודת קצה HTTP ומבצע קריאה ל-Petstore API
* עם ולידציה לפי OpenAPI spec לפני השליחה
*/
@Component
public class CallPetstoreApi extends RouteBuilder {


   private final ValidationErrorProcessor validationErrorProcessor = new ValidationErrorProcessor();
   private final ServerErrorProcessor serverErrorProcessor = new ServerErrorProcessor();


   @Override
   public void configure() throws Exception {
      
       // Exception Handler - מטפל בשגיאות ולידציה ומחזיר JSON
       onException(IllegalArgumentException.class)
           .handled(true)
           .process(validationErrorProcessor)
           .log("Validation error: ${exception.message}");
      
       // Exception Handler כללי לשגיאות אחרות
       onException(Exception.class)
           .handled(true)
           .process(serverErrorProcessor)
           .log("Server error: ${exception.message}");
      
       // Endpoint: GET /pet/{petId}
       from("platform-http:///pet/{petId}")
           .routeId("petstore-get-pet")
           .log("Received request: ${headers}")
          
           // ולידציה של הבקשה לפי OpenAPI spec
           .process(new SwaggerValidatorProcessor())
          
           // ניקוי כותרות HTTP מהבקשה הנכנסת
           .removeHeaders("CamelHttp*")
           .removeHeader(Exchange.HTTP_URI)
           .removeHeader(Exchange.HTTP_PATH)
           .removeHeader(Exchange.HTTP_QUERY)
          
           // הגדרת בקשה GET חיצונית
           .setHeader(Exchange.HTTP_METHOD, constant("GET"))
           .setHeader("Accept-Encoding", constant("identity"))
          
           // קריאה ל-Petstore API
           .toD("https://petstore3.swagger.io/api/v3/pet/${header.petId}")
          
           // המרת תשובה למחרוזת
           .convertBodyTo(String.class)
          
           // הגדרת Content-Type
           .setHeader("Content-Type", constant("application/json"))
          
           .log("Petstore API response: ${body}");
   }


   /**
    * Processor שמטפל בשגיאות ולידציה ומחזיר תשובת JSON
    */
   public class ValidationErrorProcessor implements Processor {


       @Override
       public void process(Exchange exchange) throws Exception {
           Exception exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
           String errorMessage = exception != null ? exception.getMessage() : "Validation error";


           ObjectMapper mapper = new ObjectMapper();
           ObjectNode errorResponse = mapper.createObjectNode();
           errorResponse.put("error", true);
           errorResponse.put("status", 400);
           errorResponse.put("message", errorMessage);
           errorResponse.put("type", "ValidationError");


           exchange.getMessage().setBody(mapper.writeValueAsString(errorResponse));
           exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 400);
           exchange.getMessage().setHeader("Content-Type", "application/json");
       }
   }


   /**
    * Processor שמטפל בשגיאות שרת ומחזיר תשובת JSON יפה
    */
   public class ServerErrorProcessor implements Processor {


       @Override
       public void process(Exchange exchange) throws Exception {
           Exception exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
           String errorMessage = exception != null ? exception.getMessage() : "Internal server error";


           ObjectMapper mapper = new ObjectMapper();
           ObjectNode errorResponse = mapper.createObjectNode();
           errorResponse.put("error", true);
           errorResponse.put("status", 500);
           errorResponse.put("message", errorMessage);
           errorResponse.put("type", "ServerError");


           exchange.getMessage().setBody(mapper.writeValueAsString(errorResponse));
           exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
           exchange.getMessage().setHeader("Content-Type", "application/json");
       }
   }
}





