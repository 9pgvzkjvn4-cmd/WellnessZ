package io.intimed.web;

import io.intimed.common.Json;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAPI 3.1 description of the public machine APIs, FHIR R4, intelligence and partner endpoints (IM-E07-BL-004,
 * partial), generated at runtime from Spring's {@link RequestMappingHandlerMapping} so that it can never drift from the
 * deployed controllers: paths, HTTP methods, path / query / header parameters, request bodies and the security scheme
 * per path family (OAuth2 client credentials for {@code /fhir} and {@code /api/v1}; console session + CSRF header for
 * {@code /api/intelligence} and {@code /api/partner}; self-authenticating public endpoints). Served unauthenticated at
 * {@code /api/public/openapi.json}. Schemas of request / response bodies are generic JSON objects (the reference
 * clients in {@code intimed/sdk} add the typed models for the most used resources).
 */
@RestController
public class OpenApiController {
    static final List<String> PREFIXES = List.of("/fhir/", "/api/v1/", "/oauth2/", "/.well-known/", "/api/enrol", "/api/webhooks/", "/api/intelligence", "/api/partner", "/api/public/");
    private static final Pattern VAR = Pattern.compile("\\{([^}/]+)}");
    private final RequestMappingHandlerMapping mapping;
    private volatile ObjectNode cached;

    public OpenApiController(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) { this.mapping = mapping; }

    @GetMapping(value = "/api/public/openapi.json", produces = "application/json")
    public ObjectNode openApi() {
        ObjectNode c = cached;
        if (c == null) cached = c = generate();
        return c;
    }

    ObjectNode generate() {
        ObjectNode doc = Json.obj().put("openapi", "3.1.0");
        doc.putObject("info").put("title", "IntiMed public APIs").put("version", "2.0.0")
                .put("description", "Machine APIs (OAuth2 client credentials), FHIR R4, intelligence and partner programme endpoints of the IntiMed device "
                        + "integration platform. Generated at runtime from the deployed request mappings.");
        doc.putArray("servers").addObject().put("url", "/").put("description", "this IntiMed instance");
        Map<String, ObjectNode> paths = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod hm = e.getValue();
            Set<String> patterns = info.getPathPatternsCondition() != null ? info.getPathPatternsCondition().getPatternValues() : info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (PREFIXES.stream().noneMatch(p::startsWith)) continue;
                ObjectNode item = paths.computeIfAbsent(p, k -> Json.obj());
                for (RequestMethod m : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    item.set(m.name().toLowerCase(Locale.ROOT), operation(p, m, hm, info));
                }
            }
        }
        ObjectNode ps = doc.putObject("paths");
        paths.forEach(ps::set);
        ObjectNode comps = doc.putObject("components");
        ObjectNode schemes = comps.putObject("securitySchemes");
        ObjectNode oauth = schemes.putObject("oauth2").put("type", "oauth2").put("description", "Client credentials (ES256 JWT, tenant claim, scopes)");
        ObjectNode scopes = oauth.putObject("flows").putObject("clientCredentials").put("tokenUrl", "/oauth2/token").putObject("scopes");
        for (String s : List.of("system/Observation.read", "system/Observation.write", "system/Device.read", "harness.run", "catalogue.read", "events.read")) scopes.put(s, s);
        schemes.putObject("consoleSession").put("type", "apiKey").put("in", "cookie").put("name", "INTIMED_SESSION")
                .put("description", "Console session (MFA for privileged roles); state-changing calls also need the X-XSRF-TOKEN header echoing the XSRF-TOKEN cookie");
        comps.putObject("schemas").putObject("Problem").put("type", "object").putObject("properties")
                .set("detail", Json.obj().put("type", "string"));
        return doc;
    }

    private ObjectNode operation(String path, RequestMethod m, HandlerMethod hm, RequestMappingInfo info) {
        String cls = hm.getBeanType().getSimpleName().replace("Controller", "");
        ObjectNode op = Json.obj().put("operationId", Character.toLowerCase(cls.charAt(0)) + cls.substring(1) + "_" + hm.getMethod().getName() + "_" + m.name().toLowerCase(Locale.ROOT))
                .put("summary", humanize(hm.getMethod().getName()));
        op.putArray("tags").add(tag(path));
        ArrayNode params = op.putArray("parameters");
        Matcher vm = VAR.matcher(path);
        while (vm.find()) params.addObject().put("name", vm.group(1)).put("in", "path").put("required", true).putObject("schema").put("type", "string");
        boolean body = false;
        for (MethodParameter mp : hm.getMethodParameters()) {
            RequestParam rp = mp.getParameterAnnotation(RequestParam.class);
            if (rp != null && !info.getConsumesCondition().getConsumableMediaTypes().toString().contains("x-www-form-urlencoded")) {
                String name = !rp.name().isEmpty() ? rp.name() : !rp.value().isEmpty() ? rp.value() : mp.getParameterName();
                boolean required = rp.required() && org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE.equals(rp.defaultValue());
                params.addObject().put("name", name).put("in", "query").put("required", required).putObject("schema").put("type", type(mp.getParameterType()));
            }
            RequestHeader rh = mp.getParameterAnnotation(RequestHeader.class);
            if (rh != null) {
                String name = !rh.name().isEmpty() ? rh.name() : rh.value();
                if (!"Authorization".equalsIgnoreCase(name)) params.addObject().put("name", name).put("in", "header").put("required", rh.required()).putObject("schema").put("type", "string");
            }
            if (mp.hasParameterAnnotation(RequestBody.class)) body = true;
            if (mp.hasParameterAnnotation(PathVariable.class)) { /* already from the template */ }
        }
        String form = info.getConsumesCondition().getConsumableMediaTypes().toString();
        if (form.contains("x-www-form-urlencoded")) {
            ObjectNode schema = op.putObject("requestBody").put("required", true).putObject("content").putObject("application/x-www-form-urlencoded").putObject("schema").put("type", "object");
            ObjectNode props = schema.putObject("properties");
            for (String f : List.of("grant_type", "scope", "client_id", "client_secret")) props.putObject(f).put("type", "string");
            schema.putArray("required").add("grant_type");
        } else if (body) {
            String ct = path.startsWith("/fhir/") ? "application/fhir+json" : "application/json";
            op.putObject("requestBody").put("required", true).putObject("content").putObject(ct).putObject("schema").put("type", "object");
        }
        ObjectNode responses = op.putObject("responses");
        String rct = path.startsWith("/fhir/") ? "application/fhir+json" : "application/json";
        responses.putObject("200").put("description", "OK").putObject("content").putObject(rct).putObject("schema").put("type", List.class.isAssignableFrom(hm.getMethod().getReturnType()) ? "array" : "object");
        responses.putObject("400").put("description", "Invalid request");
        responses.putObject("401").put("description", "Not authenticated");
        responses.putObject("403").put("description", "Missing role / scope");
        ArrayNode sec = op.putArray("security");
        if (path.startsWith("/fhir/") || path.startsWith("/api/v1/")) {
            if (!path.equals("/fhir/r4/metadata")) sec.addObject().putArray("oauth2");
        } else if (path.startsWith("/api/intelligence") || path.startsWith("/api/partner")) {
            sec.addObject().putArray("consoleSession");
        }
        if (sec.isEmpty()) op.remove("security");
        if (path.startsWith("/api/intelligence/explain")) op.put("description", "AI-generated explanations are labelled UNVERIFIED and always carry citations; disabled unless enabled globally and per tenant.");
        return op;
    }

    private static String tag(String path) {
        if (path.startsWith("/fhir/")) return "FHIR R4";
        if (path.startsWith("/api/v1/")) return "Machine API v1";
        if (path.startsWith("/api/intelligence")) return "Intelligence";
        if (path.startsWith("/api/partner")) return "Partner programme";
        if (path.startsWith("/api/public/")) return "Public";
        return "Authentication & ingress";
    }

    private static String type(Class<?> c) {
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class) return "integer";
        if (c == boolean.class || c == Boolean.class) return "boolean";
        if (c == double.class || c == Double.class) return "number";
        return "string";
    }

    private static String humanize(String name) {
        List<String> words = new ArrayList<>();
        for (String w : name.split("(?=[A-Z])")) words.add(w.toLowerCase(Locale.ROOT));
        String s = String.join(" ", words);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
