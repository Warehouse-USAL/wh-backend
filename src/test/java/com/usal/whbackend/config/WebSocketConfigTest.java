package com.usal.whbackend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.websocket.OrderWebSocketHandler;
import com.usal.whbackend.api.websocket.StockAlertWebSocketHandler;
import com.usal.whbackend.api.websocket.UserOrderWebSocketHandler;
import com.usal.whbackend.api.websocket.VehicleWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class WebSocketConfigTest {

  @Test
  void registerWebSocketHandlers_registersAllEndpoints() {
    JwtService jwtService = mock(JwtService.class);
    WebSocketConfig config =
        new WebSocketConfig(
            new OrderWebSocketHandler(),
            new UserOrderWebSocketHandler(),
            new VehicleWebSocketHandler(),
            new StockAlertWebSocketHandler(),
            jwtService);

    WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
    WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
    when(registry.addHandler(any(), anyString())).thenReturn(registration);
    when(registration.addInterceptors(any())).thenReturn(registration);
    when(registration.setAllowedOrigins(anyString())).thenReturn(registration);

    config.registerWebSocketHandlers(registry);

    verify(registry, times(4)).addHandler(any(), anyString());
    verify(registration, times(4)).setAllowedOrigins("*");
  }
}
