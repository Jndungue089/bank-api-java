package com.sb24.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("SB24 Simulated Banking API").version("0.2.0")
                        .description("""
                                Educational simulation of a retail banking backend (not affiliated with any bank).

                                **Auth:** `POST /api/v1/auth/login` -> use `accessToken` as `Bearer` (RS256, 10 min); renew with `/auth/refresh` (rotating).
                                **Money:** `POST /transfers` and `POST /payments` require an `Idempotency-Key` header.
                                **Limits:** every endpoint is rate limited; `429` carries `Retry-After`, `X-RateLimit-*`.
                                **Amounts:** JSON numbers with at most 2 decimals (strings and floats with more precision are rejected).
                                Public keys for token verification: `/.well-known/jwks.json`."""))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }

    /** Adds the standard problem+json error responses (400/401/429) to every operation. */
    @Bean
    OpenApiCustomizer standardErrors() {
        Schema<?> problem = new ObjectSchema().addProperty("code", new StringSchema()).addProperty("detail", new StringSchema())
                .addProperty("requestId", new StringSchema()).addProperty("status", new Schema<Integer>().type("integer"));
        return openApi -> openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
            add(op, "400", "Dados inválidos (validação estrita)", problem, null);
            add(op, "401", "Não autenticado ou token inválido", problem, null);
            add(op, "429", "Limite de pedidos excedido", problem,
                    new Header().description("Segundos até poder repetir").schema(new Schema<Integer>().type("integer")));
        }));
    }

    /** Shows, on every operation, the permission the caller must hold (looked up in the DB per request). */
    @Bean
    org.springdoc.core.customizers.OperationCustomizer permissionDocs() {
        return (operation, handlerMethod) -> {
            var rp = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), com.sb24.api.access.RequiresPermission.class);
            if (rp != null) {
                String note = "**Permissão necessária:** `" + rp.value() + "` (RBAC) + políticas ABAC aplicáveis ao recurso.";
                operation.setDescription(operation.getDescription() == null ? note : operation.getDescription() + "\n\n" + note);
                operation.addExtension("x-permission", rp.value());
            } else if (org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), com.sb24.api.access.PublicEndpoint.class)
                    || org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), com.sb24.api.access.PublicEndpoint.class)) {
                operation.setDescription("Endpoint público (sem token).");
                operation.setSecurity(java.util.List.of());
            }
            return operation;
        };
    }

    private static void add(Operation op, String code, String description, Schema<?> schema, Header retryAfter) {
        if (op.getResponses().containsKey(code)) return;
        ApiResponse r = new ApiResponse().description(description)
                .content(new Content().addMediaType("application/problem+json", new MediaType().schema(schema)));
        if (retryAfter != null) r.addHeaderObject("Retry-After", retryAfter);
        op.getResponses().addApiResponse(code, r);
    }
}
