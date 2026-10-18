package com.usal.whbackend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
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
import com.usal.whbackend.service.UserService;
import com.usal.whbackend.service.VehicleService;
import com.usal.whbackend.service.VehicleTelemetryService;
import com.usal.whbackend.service.ZoneService;
import com.usal.whbackend.service.console.LogsQueryService;
import com.usal.whbackend.service.console.ServiceStatusService;
import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.restock.RestockSuggestionService;
import com.usal.whbackend.service.query.EntityQueryService;
import com.usal.whbackend.service.storage.StorageService;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * The console drives every Domains endpoint as SUPERADMIN. A 403 here means a {@code @PreAuthorize}
 * (or a URL rule) excludes SUPERADMIN. Any other status, including 400/404/500 from mocked
 * services, means the caller got past authorization.
 */
@WebMvcTest
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class SuperadminReachTest {

  private static final String API_PACKAGE = "com.usal.whbackend.api";

  /**
   * Handlers whose {@code @PreAuthorize} deliberately omits SUPERADMIN. Empty: Infra-key endpoints
   * (/infra/**) carry no {@code @PreAuthorize} (InfraApiKeyFilter authenticates them), so the guard
   * never sees them.
   */
  private static final Set<String> EXCLUDED = Set.of();

  private static final String JSON = "{}";

  /** {method, path, body-or-null}; "UPLOAD" marks the multipart upload. */
  private static final String[][] ENDPOINTS = {
    {"GET", "/users", null},
    {"GET", "/users/u1", null},
    {"POST", "/users", JSON},
    {"PATCH", "/users/u1", JSON},
    {"POST", "/users/u1/reset-password", JSON},
    {"GET", "/products", null},
    {"GET", "/products/categories", null},
    {"GET", "/products/p1", null},
    {"GET", "/products/p1/location", null},
    {"POST", "/products", JSON},
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
    {"POST", "/warehouse/zones", JSON},
    {"PATCH", "/warehouse/zones/z1", JSON},
    {"DELETE", "/warehouse/zones/z1", null},
    {"GET", "/warehouse/zones/z1/lines", null},
    {"POST", "/warehouse/zones/z1/lines", JSON},
    {"PATCH", "/warehouse/lines/l1", JSON},
    {"DELETE", "/warehouse/lines/l1", null},
    {"GET", "/warehouse/lines/l1/positions", null},
    {"GET", "/warehouse/positions", null},
    {"GET", "/warehouse/positions/available", null},
    {"GET", "/warehouse/positions/pos1", null},
    {"POST", "/warehouse/lines/l1/positions", JSON},
    {"PATCH", "/warehouse/positions/pos1", JSON},
    {"DELETE", "/warehouse/positions/pos1", null},
    {"POST", "/warehouse/positions/validate-fit", JSON},
    {"POST", "/restock/orders", JSON},
    {"GET", "/restock/orders", null},
    {"GET", "/restock/orders/r1", null},
    {"POST", "/restock/receptions", JSON},
    {"GET", "/restock/receptions", null},
    {"GET", "/restock/receptions/c1", null},
    {"PATCH", "/restock/receptions/c1", JSON},
    {"GET", "/api/v1/files", null},
    {"UPLOAD", "/api/v1/files/upload", null},
    {"DELETE", "/api/v1/files/images/a.jpg", null},
    {"GET", "/console/status", null},
    {"POST", "/console/logs/query", JSON},
    {"GET", "/console/ws-events/orders", null},
    {"POST", "/metrics/query", JSON},
    {"GET", "/metrics/catalog", null},
    {"GET", "/query/catalog", null},
    {"POST", "/query/orders", JSON},
  };

  private static final String[][] SUPERADMIN_ONLY = {
    {"GET", "/console/status", null},
    {"POST", "/console/logs/query", JSON},
    {"GET", "/console/ws-events/orders", null},
    {"GET", "/api/v1/files", null},
  };

  @Autowired WebApplicationContext context;
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
  @MockitoBean RestockOrderService restockOrderService;
  @MockitoBean ReceptionService receptionService;
  @MockitoBean StorageService storageService;
  @MockitoBean ServiceStatusService serviceStatusService;
  @MockitoBean LogsQueryService logsQueryService;
  @MockitoBean WsEventBuffer wsEventBuffer;
  @MockitoBean MetricsQueryService metricsQueryService;
  @MockitoBean RestockSuggestionService restockSuggestionService;
  @MockitoBean EntityQueryService entityQueryService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private int statusOf(String[] endpoint) throws Exception {
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
      return mockMvc.perform(req).andReturn().getResponse().getStatus();
    } catch (Exception e) {
      // An exception from a handler running against a mock service happens after authorization.
      return 500;
    }
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void superadminCanCallEveryEndpointTheConsoleUses() throws Exception {
    List<String> forbidden = new ArrayList<>();
    for (String[] endpoint : ENDPOINTS) {
      int status = statusOf(endpoint);
      if (status == 403 || status == 401) {
        forbidden.add(endpoint[0] + " " + endpoint[1] + " -> " + status);
      }
    }
    assertThat(forbidden).isEmpty();
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
    for (BeanDefinition bd : scanner.findCandidateComponents(API_PACKAGE)) {
      Class<?> controller = Class.forName(bd.getBeanClassName());
      PreAuthorize onClass = controller.getAnnotation(PreAuthorize.class);
      for (Method m : controller.getDeclaredMethods()) {
        if (AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class) == null) {
          continue;
        }
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
    assertThat(offenders).isEmpty();
  }

  /** Repo expressions: hasRole('X'), hasAnyRole(...), isAuthenticated(); only roles can exclude. */
  private static boolean permitsSuperadmin(String expression) {
    return expression.contains("SUPERADMIN")
        || expression.contains("isAuthenticated")
        || expression.contains("permitAll");
  }
}
