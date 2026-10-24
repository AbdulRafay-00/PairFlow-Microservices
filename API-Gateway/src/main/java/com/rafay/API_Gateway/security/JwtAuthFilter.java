// package com.rafay.API_Gateway.security;

// import io.jsonwebtoken.ExpiredJwtException;
// import io.jsonwebtoken.JwtException;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.http.HttpStatus;
// import org.springframework.http.server.reactive.ServerHttpRequest;
// import org.springframework.stereotype.Component;
// import org.springframework.web.server.ServerWebExchange;
// import org.springframework.web.server.WebFilter;
// import org.springframework.web.server.WebFilterChain;
// import reactor.core.publisher.Mono;

// import java.net.URI;
// import java.util.List;

// @Component
// public class JwtAuthFilter implements WebFilter {

//     private final JwtUtil jwtUtil;
//     private final String loginRedirectUrl;

//     private static final List<String> PUBLIC_PATHS = List.of(
//             "/login/portal",
//             "/auth/register",
//             "/actuator/health"
//     );

//     public JwtAuthFilter(
//             JwtUtil jwtUtil,
//             @Value("${app.jwt.login-redirect-url}") String loginRedirectUrl) {
//         this.jwtUtil = jwtUtil;
//         this.loginRedirectUrl = loginRedirectUrl;
//     }

//     @Override
//     public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

//         String path = exchange.getRequest().getURI().getPath();

//         // Skip auth for public paths
//         if (isPublicPath(path)) {
//             return chain.filter(exchange);
//         }

//         String authHeader = exchange.getRequest()
//                 .getHeaders()
//                 .getFirst("Authorization");

//         String token = jwtUtil.extractTokenFromHeader(authHeader);

//         // No token → redirect to login
//         if (token == null) {
//             return redirect(exchange, loginRedirectUrl);
//         }

//         try {
//             var claims = jwtUtil.validateToken(token);

//             // Forward userId downstream as a header
//             ServerHttpRequest mutatedRequest = exchange.getRequest()
//                     .mutate()
//                     .header("X-User-Id", claims.getSubject())
//                     .build();

//             return chain.filter(exchange.mutate().request(mutatedRequest).build());

//         } catch (ExpiredJwtException e) {
//             return redirect(exchange, loginRedirectUrl + "?reason=expired");

//         } catch (JwtException e) {
//             return redirect(exchange, loginRedirectUrl + "?reason=invalid");
//         }
//     }

//     private Mono<Void> redirect(ServerWebExchange exchange, String url) {
//         exchange.getResponse().setStatusCode(HttpStatus.FOUND);
//         exchange.getResponse().getHeaders().setLocation(URI.create(url));
//         return exchange.getResponse().setComplete();
//     }

//     private boolean isPublicPath(String path) {
//         return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
//     }
// }
















package com.rafay.API_Gateway.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtAuthFilter implements WebFilter {

    private final JwtUtil jwtUtil;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/login/portal",
            "/user-service/login/portal",
            "/auth/register",
            "/actuator/health"
    );

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
                .getHeaders()
                .getFirst("Authorization");

        String token = jwtUtil.extractTokenFromHeader(authHeader);

        if (token == null) {
            return writeErrorResponse(exchange, "missing_token");
        }

        try {
            var claims = jwtUtil.validateToken(token);

            ServerHttpRequest mutatedRequest = exchange.getRequest()
                    .mutate()
                    .header("X-User-Id", claims.getSubject())
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (ExpiredJwtException e) {
            return writeErrorResponse(exchange, "session_expired");

        } catch (JwtException e) {
            return writeErrorResponse(exchange, "invalid_token");
        }
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"status\": 401, \"reason\": \"" + reason + "\"}";
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
}
