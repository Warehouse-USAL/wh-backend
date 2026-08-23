package com.usal.whbackend.api.query;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.query.EntityQueryService;
import com.usal.whbackend.service.query.EntityRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(EntityQueryController.class)
@Import(GlobalExceptionHandler.class)
class EntityQueryControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean EntityQueryService entityQueryService;
  @MockitoBean JwtService jwtService;

  private static final String BODY =
      """
      {"filters":[{"field":"status","op":"in","value":["COMPLETED"]}],
       "sort":[{"field":"created_at","dir":"desc"}],
       "fields":["id","status"],"page":0,"size":50}
      """;

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void query_returnsItemsWithTheStandardPaginationEnvelope() throws Exception {
    when(entityQueryService.query(anyString(), any(), any()))
        .thenReturn(
            new PageImpl<>(
                List.of(Map.of("id", "ORD-1", "status", "COMPLETED")), PageRequest.of(0, 50), 1));

    mockMvc
        .perform(post("/query/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value("ORD-1"))
        .andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
        .andExpect(jsonPath("$.pagination.total_elements").value(1));
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void query_surfacesRejectionCodes() throws Exception {
    when(entityQueryService.query(anyString(), any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));

    mockMvc
        .perform(post("/query/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("UNKNOWN_FIELD"));
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void catalog_describesFieldsAndOperatorsButHidesSecrets() throws Exception {
    when(entityQueryService.catalog(any()))
        .thenReturn(List.of(new EntityRegistry().findByName("users").orElseThrow()));

    mockMvc
        .perform(get("/query/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entities[0].name").value("users"))
        .andExpect(jsonPath("$.entities[0].fields[?(@.name == 'email')]").exists())
        .andExpect(jsonPath("$.entities[0].fields[?(@.name == 'created_at')]").exists())
        // A field that is neither readable, sortable nor filterable is omitted entirely.
        .andExpect(jsonPath("$.entities[0].fields[?(@.name == 'password_hash')]").doesNotExist())
        .andExpect(jsonPath("$.entities[0].fields[?(@.name == 'passwordHash')]").doesNotExist());
  }
}
