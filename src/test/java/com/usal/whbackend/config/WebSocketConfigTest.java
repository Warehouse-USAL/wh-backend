package com.usal.whbackend.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
        WebSocketConfig config = new WebSocketConfig(
                new OrderWebSocketHandler(),
                new UserOrderWebSocketHandler(),
                new VehicleWebSocketHandler(),
                new StockAlertWebSocketHandler());

        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(any(), anyString())).thenReturn(registration);
        when(registration.setAllowedOrigins(anyString())).thenReturn(registration);

        config.registerWebSocketHandlers(registry);

        verify(registry, times(4)).addHandler(any(), anyString());
        verify(registration, times(4)).setAllowedOrigins("*");
    }
}
