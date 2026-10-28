package com.usal.whbackend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JacksonConfig;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.service.AuthService;
import com.usal.whbackend.service.LineService;
import com.usal.whbackend.service.OrderService;
import com.usal.whbackend.service.OrderStatusService;
import com.usal.whbackend.service.PositionService;
import com.usal.whbackend.service.ProductService;
import com.usal.whbackend.service.ReceptionService;
import com.usal.whbackend.service.RestockOrderService;
import com.usal.whbackend.service.StockAdjustmentService;
import com.usal.whbackend.service.UserService;
import com.usal.whbackend.service.VehicleService;
import com.usal.whbackend.service.VehicleTelemetryService;
import com.usal.whbackend.service.ZoneService;
import com.usal.whbackend.service.console.LogsQueryService;
import com.usal.whbackend.service.console.ServiceStatusService;
import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.service.console.stream.ConsoleStreamService;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.conversion.SuggestionConversionService;
import com.usal.whbackend.service.metrics.restock.RestockSuggestionService;
import com.usal.whbackend.service.query.EntityQueryService;
import com.usal.whbackend.service.storage.StorageService;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The console drives every Domains endpoint as SUPERADMIN. A 403 here means a {@code @PreAuthorize}
 * (or a URL rule) excludes SUPERADMIN. Any other status, including 400/404/500 from mocked
 * services, means the caller got past authorization.
 */
@WebMvcTest
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JacksonConfig.class})
class SuperadminReachTest {

  private static final String API_PACKAGE = "com.usal.whbackend.api";

  /**
   * Handlers whose {@code @PreAuthorize} deliberately omits SUPERADMIN. Empty: Infra-key endpoints
   * (/infra/**) carry no {@code @PreAuthorize} (InfraApiKeyFilter authenticates them), so the guard
   * never sees them.
   */
  private static final Set<String> EXCLUDED = Set.of();

  private static final String JSON = "{}";

  /*
   * Spring MVC validates a @Valid @RequestBody before the proxied controller method (and its
   * @PreAuthorize) runs, so "{}" would answer 400 even if SUPERADMIN were excluded. Every @Valid
   * handler below therefore gets the smallest valid body (snake_case, as on the wire); the test
   * also fails on any 400 raised before the handler (see BEFORE_AUTHORIZATION), so a body that
   * stops being valid cannot hide a 403, while a 400 the handler itself answers still passes.
   * No @Valid handler is left to the reflection guard alone.
   */
  private static final String RESTOCK =
      "{\"params\":{\"alpha\":0.5,\"recent_days\":7,\"long_days\":28,\"safety_days\":1,\"lead_time_days\":1,\"coverage_days\":1},\"filters\":{}}";
  private static final String CONVERSION =
      "{\"params\":{\"from\":\"2026-03-02T00:00:00Z\",\"to\":\"2026-03-04T00:00:00Z\",\"granularity\":\"day\"}}";
  private static final String USER =
      "{\"email\":\"a@b.co\",\"name\":\"n\",\"role\":\"ADMIN_SALES\",\"initial_password\":\"x\"}";
  private static final String PRODUCT =
      "{\"sku\":\"s\",\"name\":\"n\",\"category\":\"c\",\"height\":1,\"width\":1,"
          + "\"length\":1,\"weight\":1}";
  private static final String POSITION =
      "{\"position_name\":\"p\",\"maximum_capacity\":1,\"size_stock_to_save\":\"CAJA\"}";
  private static final String RECEPTION =
      "{\"product_id\":\"p\",\"quantity_received\":1,\"delivery_unit\":\"CAJA\","
          + "\"supplier\":\"s\"}";
  /*
   * The stream service is mocked, so a valid body answers 200 at once with no stream behind it:
   * the long-lived endpoint is reached without the test waiting on it.
   */
  private static final String STREAM = "{\"subscriptions\":[{\"id\":\"s\",\"kind\":\"status\"}]}";
  private static final String METRICS =
      "{\"metric\":\"m\",\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\","
          + "\"step\":\"1m\",\"agg\":\"avg\"}";

  /** {method, path, body-or-null}; "UPLOAD" marks the multipart upload. */
  private static final String[][] ENDPOINTS = {
    {"GET", "/users", null},
    {"GET", "/users/u1", null},
    {"POST", "/users", USER},
    {"PATCH", "/users/u1", JSON},
    {"POST", "/users/u1/reset-password", "{\"new_password\":\"x\"}"},
    {"GET", "/products", null},
    {"GET", "/products/categories", null},
    {"GET", "/products/p1", null},
    {"GET", "/products/p1/location", null},
    {"POST", "/products", PRODUCT},
    {"PATCH", "/products/p1", JSON},
    {"DELETE", "/products/p1", null},
    {"GET", "/orders", null},
    {"GET", "/orders/o1", null},
    {"POST", "/orders", JSON},
    {"POST", "/orders/o1/cancel", JSON},
    {"PATCH", "/internal/orders/o1/status", JSON},
    {"PATCH", "/internal/orders/o1/assign-vehicle", JSON},
    {"GET", "/vehicles", null},
    {"GET", "/vehicles/v1", null},
    {"POST", "/vehicles", JSON},
    {"PATCH", "/internal/vehicles/v1", JSON},
    {"GET", "/warehouse/zones", null},
    {"POST", "/warehouse/zones", "{\"zone_code\":\"A\",\"max_allowed_lines\":1}"},
    {"PATCH", "/warehouse/zones/z1", JSON},
    {"DELETE", "/warehouse/zones/z1", null},
    {"GET", "/warehouse/zones/z1/lines", null},
    {"POST", "/warehouse/zones/z1/lines", "{\"number_line\":1,\"max_allowed_positions\":1}"},
    {"PATCH", "/warehouse/lines/l1", JSON},
    {"DELETE", "/warehouse/lines/l1", null},
    {"GET", "/warehouse/lines/l1/positions", null},
    {"GET", "/warehouse/positions", null},
    {"GET", "/warehouse/positions/available?productId=p&deliveryUnit=CAJA&quantity=1", null},
    {"GET", "/warehouse/positions/pos1", null},
    {"POST", "/warehouse/lines/l1/positions", POSITION},
    {"PATCH", "/warehouse/positions/pos1", JSON},
    {"DELETE", "/warehouse/positions/pos1", null},
    {"GET", "/warehouse/positions/pos1/stock-adjustments", null},
    {"POST", "/warehouse/positions/pos1/stock-adjustments", "{\"quantity\":1}"},
    {"GET", "/products/p1/stock-adjustments", null},
    {
      "POST",
      "/warehouse/positions/validate-fit",
      "{\"product_id\":\"p\",\"quantity\":1,\"size\":\"CAJA\"}"
    },
    {
      "POST",
      "/restock/orders",
      "{\"product_id\":\"p\",\"quantity_requested\":1,\"supplier\":\"s\"}"
    },
    {"GET", "/restock/orders", null},
    {"GET", "/restock/orders/r1", null},
    {"POST", "/restock/receptions", RECEPTION},
    {"GET", "/restock/receptions", null},
    {"GET", "/restock/receptions/c1", null},
    {
      "PATCH",
      "/restock/receptions/c1",
      "{\"assignments\":[{\"position_id\":\"x\",\"quantity\":1}]}"
    },
    {"GET", "/api/v1/files", null},
    {"UPLOAD", "/api/v1/files/upload", null},
    {"DELETE", "/api/v1/files/images/a.jpg", null},
    {"GET", "/console/status", null},
    {"POST", "/console/logs/query", JSON},
    {"GET", "/console/ws-events/orders", null},
    {"POST", "/console/stream", STREAM},
    {"POST", "/metrics/query", METRICS},
    {"GET", "/metrics/catalog", null},
    {"POST", "/metrics/restock-suggestions", RESTOCK},
    {"POST", "/metrics/suggestion-conversion", CONVERSION},
    {"POST", "/metrics/restock-suggestions/apply", RESTOCK},
    // Public download (permitAll in SecurityConfig): product images are shown before login, so
    // it must stay reachable for every caller, SUPERADMIN included.
    {"GET", "/api/v1/files/images/a.jpg", null},
    {"POST", "/users/me/change-password", "{\"current_password\":\"a\",\"new_password\":\"b\"}"},
    {"PATCH", "/users/me", JSON},
    {"GET", "/query/catalog", null},
    {"POST", "/query/orders", JSON},
  };

  private static final String[][] SUPERADMIN_ONLY = {
    {"GET", "/console/status", null},
    {"POST", "/console/logs/query", JSON},
    {"GET", "/console/ws-events/orders", null},
    {"POST", "/console/stream", STREAM},
    {"GET", "/api/v1/files", null},
  };

  @Autowired WebApplicationContext context;
  @Autowired RequestMappingHandlerMapping handlerMapping;
  MockMvc mockMvc;
  @MockitoBean JwtService jwtService;
  @MockitoBean OrderService orderService;
  @MockitoBean OrderStatusService orderStatusService;
  @MockitoBean VehicleService vehicleService;
  @MockitoBean VehicleTelemetryService vehicleTelemetryService;
  @MockitoBean UserService userService;
  @MockitoBean AuthService authService;
  @MockitoBean ProductService productService;
  @MockitoBean ProductRepository productRepository;
  @MockitoBean ZoneService zoneService;
  @MockitoBean LineService lineService;
  @MockitoBean PositionService positionService;
  @MockitoBean StockAdjustmentService stockAdjustmentService;
  @MockitoBean RestockOrderService restockOrderService;
  @MockitoBean ReceptionService receptionService;
  @MockitoBean StorageService storageService;
  @MockitoBean ServiceStatusService serviceStatusService;
  @MockitoBean LogsQueryService logsQueryService;
  @MockitoBean WsEventBuffer wsEventBuffer;
  @MockitoBean ConsoleStreamService consoleStreamService;
  @MockitoBean MetricsQueryService metricsQueryService;
  @MockitoBean RestockSuggestionService restockSuggestionService;
  @MockitoBean SuggestionConversionService suggestionConversionService;
  @MockitoBean EntityQueryService entityQueryService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** What one call answered, and the exception Spring MVC resolved for it, if any. */
  private record Outcome(int status, Exception resolved) {}

  private int statusOf(String[] endpoint) throws Exception {
    return outcomeOf(endpoint).status();
  }

  private Outcome outcomeOf(String[] endpoint) throws Exception {
    RequestBuilder req;
    if ("UPLOAD".equals(endpoint[0])) {
      req =
          multipart(endpoint[1])
              .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", "x".getBytes()));
    } else {
      MockHttpServletRequestBuilder plain = request(HttpMethod.valueOf(endpoint[0]), endpoint[1]);
      if (endpoint[2] != null) {
        plain.contentType("application/json").content(endpoint[2]);
      }
      req = plain;
    }
    try {
      MvcResult result = mockMvc.perform(req).andReturn();
      return new Outcome(result.getResponse().getStatus(), result.getResolvedException());
    } catch (Exception e) {
      // An exception from a handler running against a mock service happens after authorization.
      return new Outcome(500, e);
    }
  }

  /**
   * Exceptions Spring MVC raises while resolving arguments, before the proxied method and its
   * {@code @PreAuthorize} run: a 400 for one of these could hide a 403. A 400 thrown from inside
   * the handler or its (mocked) service came after authorization and is a legitimate answer.
   */
  private static final List<Class<? extends Exception>> BEFORE_AUTHORIZATION =
      List.of(
          MethodArgumentNotValidException.class,
          HttpMessageNotReadableException.class,
          ServletRequestBindingException.class,
          TypeMismatchException.class,
          MissingServletRequestPartException.class,
          HandlerMethodValidationException.class);

  static boolean mayMaskAuthorization(Outcome outcome) {
    return outcome.status() == 400
        && (outcome.resolved() == null
            || BEFORE_AUTHORIZATION.stream().anyMatch(type -> type.isInstance(outcome.resolved())));
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void superadminCanCallEveryEndpointTheConsoleUses() throws Exception {
    List<String> forbidden = new ArrayList<>();
    List<String> unmapped = new ArrayList<>();
    for (String[] endpoint : ENDPOINTS) {
      String method = "UPLOAD".equals(endpoint[0]) ? "POST" : endpoint[0];
      if (handlerMapping.getHandler(new MockHttpServletRequest(method, endpoint[1].split("\\?")[0]))
          == null) {
        unmapped.add(method + " " + endpoint[1]);
      }
      Outcome outcome = outcomeOf(endpoint);
      int status = outcome.status();
      if (status == 403 || status == 401 || mayMaskAuthorization(outcome)) {
        forbidden.add(endpoint[0] + " " + endpoint[1] + " -> " + status);
      }
    }
    assertThat(unmapped).as("paths no handler serves").isEmpty();
    assertThat(forbidden).as("401/403, or 400 that could mask a 403").isEmpty();
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void adminSystemIsStillForbiddenOnSuperadminOnlyEndpoints() throws Exception {
    for (String[] endpoint : SUPERADMIN_ONLY) {
      assertThat(statusOf(endpoint)).as(endpoint[1]).isEqualTo(403);
    }
  }

  @Test
  void noControllerHandlerExcludesSuperadminUnlessListed() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    List<String> offenders = new ArrayList<>();
    Set<String> scanned = new java.util.HashSet<>();
    int handlers = 0;
    for (BeanDefinition bd : scanner.findCandidateComponents(API_PACKAGE)) {
      Class<?> controller = Class.forName(bd.getBeanClassName());
      scanned.add(controller.getSimpleName());
      PreAuthorize onClass = controller.getAnnotation(PreAuthorize.class);
      for (Method m : controller.getDeclaredMethods()) {
        if (AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) == null) {
          continue;
        }
        handlers++;
        PreAuthorize pa =
            m.isAnnotationPresent(PreAuthorize.class)
                ? m.getAnnotation(PreAuthorize.class)
                : onClass;
        String id = controller.getSimpleName() + "." + m.getName();
        if (pa != null && !permitsSuperadmin(pa.value()) && !EXCLUDED.contains(id)) {
          offenders.add(id + " " + pa.value());
        }
      }
    }
    assertThat(scanned).contains("FileController", "ServiceStatusController", "ZoneController");
    assertThat(handlers).isGreaterThan(50);
    assertThat(offenders).isEmpty();
  }

  private static final java.util.regex.Pattern QUOTED_SUPERADMIN =
      java.util.regex.Pattern.compile("'SUPERADMIN'");

  private static final java.util.regex.Pattern NEGATION =
      java.util.regex.Pattern.compile("!|\\bnot\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

  /**
   * Repo expressions: hasRole('X'), hasAnyRole(...), isAuthenticated(); only roles can exclude. A
   * negated expression ({@code !hasRole('SUPERADMIN')}, {@code not ...}) is never read as a grant:
   * it must be listed in {@link #EXCLUDED} or rewritten.
   */
  static boolean permitsSuperadmin(String expression) {
    if (NEGATION.matcher(expression).find()) {
      return false;
    }
    return QUOTED_SUPERADMIN.matcher(expression).find()
        || expression.contains("isAuthenticated")
        || expression.contains("permitAll");
  }

  @Test
  void negatedExpressionsNeverCountAsGrantingSuperadmin() {
    assertThat(permitsSuperadmin("hasAnyRole('SUPERADMIN', 'ADMIN_SALES')")).isTrue();
    assertThat(permitsSuperadmin("isAuthenticated()")).isTrue();
    assertThat(permitsSuperadmin("hasRole('ADMIN_SALES')")).isFalse();
    assertThat(permitsSuperadmin("!hasRole('SUPERADMIN')")).isFalse();
    assertThat(permitsSuperadmin("isAuthenticated() and !hasRole('SUPERADMIN')")).isFalse();
    assertThat(permitsSuperadmin("not hasRole('SUPERADMIN')")).isFalse();
  }

  @Test
  void onlyA400RaisedBeforeTheHandlerCanMaskA403() {
    assertThat(mayMaskAuthorization(new Outcome(400, null))).isTrue();
    assertThat(
            mayMaskAuthorization(
                new Outcome(400, new HttpMessageNotReadableException("x", emptyInput()))))
        .isTrue();
    assertThat(
            mayMaskAuthorization(
                new Outcome(400, new MissingServletRequestParameterException("q", "String"))))
        .isTrue();
    // Thrown by the handler or its service: authorization already passed.
    assertThat(mayMaskAuthorization(new Outcome(400, new IllegalArgumentException("x")))).isFalse();
    assertThat(mayMaskAuthorization(new Outcome(404, null))).isFalse();
  }

  private static HttpInputMessage emptyInput() {
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return InputStream.nullInputStream();
      }

      @Override
      public HttpHeaders getHeaders() {
        return new HttpHeaders();
      }
    };
  }
}
