package ilenreste.unpeu.recettesback.configuration;

import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;

/**
 * Keeps the mappings generated from {@code openapi.yaml} from restricting what a client may
 * {@code Accept}. See {@code docs/openapi.md}.
 * <p>
 * The generator copies every media type that appears in <em>any</em> response of an operation into a
 * {@code produces} condition on the whole mapping. For an operation that returns nothing on success,
 * that list is only {@code application/problem+json}, so a client that asks for
 * {@code Accept: application/json} - which is what most HTTP wrappers send on every call - is refused
 * with a 406 by the handler mapping before the controller runs, where the hand-written controllers
 * answered 204. The same goes for {@code POST /auth/login}, whose token is plain text. A condition like
 * that is a negotiation rule the API never had, and adding one in the name of documenting it would be a
 * behaviour change.
 * <p>
 * So the condition is dropped from every method a generated interface declares. Content negotiation is
 * then exactly what it always was: Spring's message converters decide whether they can write the body
 * for the {@code Accept} the client sent. The media types stay in the contract, where they belong, and
 * in the response annotations springdoc reads; only the runtime restriction goes. {@code consumes} is kept: it only turns a
 * request body of the wrong type from a 415 raised by the converter into the same 415 raised sooner.
 * <p>
 * Only those mappings, and not every one: Spring Boot's own {@code BasicErrorController} tells its HTML
 * and JSON handlers for {@code /error} apart by {@code produces}, and the application will not start
 * without it.
 */
@Configuration
public class GeneratedMappingsConfig {

    /** The {@code apiPackage} of the openapi-generator plugin in {@code pom.xml}. */
    private static final String GENERATED_API_PACKAGE = "ilenreste.unpeu.recettesback.api";

    @Bean
    WebMvcRegistrations mappingsWithoutProducesConditions() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new RequestMappingHandlerMapping() {
                    @Override
                    protected RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {
                        RequestMappingInfo mapping = super.getMappingForMethod(method, handlerType);
                        return mapping != null && isDeclaredByGeneratedApi(method, handlerType)
                                ? mapping.mutate().produces().build()
                                : mapping;
                    }
                };
            }
        };
    }

    /**
     * A controller method the generated interface declares, as opposed to one written beside it: the
     * two paged searches carry their own {@code produces}, by hand, and keep it.
     */
    private static boolean isDeclaredByGeneratedApi(Method method, Class<?> handlerType) {
        Method declared = ClassUtils.getInterfaceMethodIfPossible(method, handlerType);
        return GENERATED_API_PACKAGE.equals(declared.getDeclaringClass().getPackageName());
    }
}
