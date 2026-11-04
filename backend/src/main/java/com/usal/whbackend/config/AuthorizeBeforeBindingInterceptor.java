package com.usal.whbackend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Decides {@code @PreAuthorize} before the request body is read and validated.
 *
 * <p>Method security wraps the controller method, but Spring MVC binds and validates the arguments
 * before calling it, so a caller without the role used to get a 400 that described the schema
 * instead of a 403. This interceptor runs earlier (before argument resolution) and applies the same
 * expression; the annotation still protects the method as before.
 *
 * <p>It only ever denies a clear case: an authenticated caller whose roles fail an expression that
 * needs nothing but the caller. Anonymous callers and expressions that reference method arguments
 * or beans are left to the method-security proxy, which stays the single source of truth.
 */
@Component
public class AuthorizeBeforeBindingInterceptor implements HandlerInterceptor {

  private final DefaultMethodSecurityExpressionHandler expressionHandler =
      new DefaultMethodSecurityExpressionHandler();
  private final ExpressionParser parser = new SpelExpressionParser();

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    PreAuthorize annotation =
        AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), PreAuthorize.class);
    if (annotation == null) {
      annotation =
          AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), PreAuthorize.class);
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (annotation == null
        || auth == null
        || auth instanceof AnonymousAuthenticationToken
        || !auth.isAuthenticated()
        || dependsOnArgumentsOrBeans(annotation.value())) {
      return true;
    }
    MethodInvocation invocation = new SimpleMethodInvocation(method.getBean(), method.getMethod());
    EvaluationContext context = expressionHandler.createEvaluationContext(auth, invocation);
    if (!ExpressionUtils.evaluateAsBoolean(parser.parseExpression(annotation.value()), context)) {
      throw new AccessDeniedException("Access Denied");
    }
    return true;
  }

  private static boolean dependsOnArgumentsOrBeans(String expression) {
    return expression.contains("#") || expression.contains("@");
  }
}
