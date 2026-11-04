package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

class AuthorizeBeforeBindingInterceptorTest {

  static class Controller {
    @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
    public void guarded() {}

    @PreAuthorize("hasRole('SUPERADMIN') or #id == authentication.name")
    public void usesArguments(String id) {}

    public void open() {}
  }

  @PreAuthorize("hasRole('SUPERADMIN')")
  static class GuardedClass {
    public void method() {}
  }

  private final AuthorizeBeforeBindingInterceptor interceptor =
      new AuthorizeBeforeBindingInterceptor();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private boolean call(Object bean, String method, Class<?>... params) throws Exception {
    return interceptor.preHandle(
        new MockHttpServletRequest(),
        new MockHttpServletResponse(),
        new HandlerMethod(bean, bean.getClass().getMethod(method, params)));
  }

  private void login(String role) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "u1", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
  }

  @Test
  void aCallerWithoutTheRoleIsDeniedBeforeAnyBodyIsRead() {
    login("OPERATOR");
    assertThatThrownBy(() -> call(new Controller(), "guarded"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aCallerWithTheRoleGoesThrough() throws Exception {
    login("ADMIN_WAREHOUSE");
    assertThat(call(new Controller(), "guarded")).isTrue();
  }

  @Test
  void theClassLevelAnnotationApplies() {
    login("OPERATOR");
    assertThatThrownBy(() -> call(new GuardedClass(), "method"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void methodsWithoutTheAnnotationAreLeftAlone() throws Exception {
    login("OPERATOR");
    assertThat(call(new Controller(), "open")).isTrue();
  }

  @Test
  void expressionsThatNeedArgumentsAreLeftToMethodSecurity() throws Exception {
    login("OPERATOR");
    assertThat(call(new Controller(), "usesArguments", String.class)).isTrue();
  }

  @Test
  void anonymousAndMissingAuthenticationAreLeftToTheSecurityChain() throws Exception {
    assertThat(call(new Controller(), "guarded")).isTrue();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "k", "anon", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertThat(call(new Controller(), "guarded")).isTrue();
  }

  @Test
  void nonControllerHandlersAreIgnored() throws Exception {
    login("OPERATOR");
    assertThat(
            interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()))
        .isTrue();
  }
}
