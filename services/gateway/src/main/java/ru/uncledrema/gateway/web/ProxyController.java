package ru.uncledrema.gateway.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import ru.uncledrema.gateway.services.PrivilegeClient;
import ru.uncledrema.gateway.services.TicketClient;
import ru.uncledrema.gateway.dto.UserInfoDto;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class ProxyController {
    private final RestTemplate restTemplate;
    private final PrivilegeClient privilegeClient;
    private final TicketClient ticketClient;

    @Value("${downstream.flights:http://localhost:8060}")
    private String flightsBase;

    @Value("${downstream.tickets:http://localhost:8070}")
    private String ticketsBase;

    @Value("${downstream.privileges:http://localhost:8050}")
    private String privilegesBase;

    @GetMapping("/me")
    public ResponseEntity<UserInfoDto> me(@RequestHeader(value = "X-User-Name") String username) {
        var privilegeInfo = privilegeClient.getPrivilegeForUser(username).orElseThrow();
        var tickets = ticketClient.getTickets(username).orElseThrow();
        return ResponseEntity.ok(new UserInfoDto(
                tickets,
                privilegeInfo
        ));
    }

    @RequestMapping(path = "/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyAll(HttpServletRequest request, @RequestBody(required = false) byte[] body) {
        String fullPath = request.getRequestURI(); // /api/v1/...
        String prefix = "/api/v1";
        String forwardPath = fullPath.startsWith(prefix) ? fullPath.substring(prefix.length()) : fullPath; // /flights/...
        String query = request.getQueryString();
        String targetBase = selectTarget(forwardPath);
        log.info("full path: {}, forward path: {}, query: {}, target base: {}", fullPath, forwardPath, query, targetBase);

        // если нет целевого сервиса (не flights/tickets/privilege) — 404
        if (targetBase == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(("No downstream service for path: " + forwardPath).getBytes(StandardCharsets.UTF_8));
        }

        String url = targetBase + forwardPath + (query != null ? "?" + query : "");
        log.info("redirecting to URL: {}", url);

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        HttpHeaders headers = copyRequestHeaders(request);

        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<byte[]> resp = restTemplate.exchange(URI.create(url), method, entity, byte[].class);
            HttpHeaders outHeaders = filterResponseHeaders(resp.getHeaders());
            return new ResponseEntity<>(resp.getBody(), outHeaders, resp.getStatusCode());
        } catch (HttpStatusCodeException ex) {
            byte[] respBody = ex.getResponseBodyAsByteArray();
            HttpHeaders outHeaders = filterResponseHeaders(ex.getResponseHeaders());
            return new ResponseEntity<>(respBody, outHeaders, ex.getStatusCode());
        }
    }

    private String selectTarget(String forwardPath) {
        String p = forwardPath.toLowerCase();
        if (p.startsWith("/flights") || p.startsWith("/airports")) return flightsBase;
        if (p.startsWith("/tickets")) return ticketsBase;
        if (p.startsWith("/privilege") || p.startsWith("/privileges")) return privilegesBase;
        return null;
    }

    private HttpHeaders copyRequestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if ("host".equalsIgnoreCase(name) || "content-length".equalsIgnoreCase(name)) continue;
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                headers.add(name, values.nextElement());
            }
        }
        return headers;
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders in) {
        HttpHeaders out = new HttpHeaders();
        if (in == null) return out;
        in.forEach((k, v) -> {
            if ("transfer-encoding".equalsIgnoreCase(k) || "connection".equalsIgnoreCase(k)) return;
            out.put(k, v);
        });
        return out;
    }
}
