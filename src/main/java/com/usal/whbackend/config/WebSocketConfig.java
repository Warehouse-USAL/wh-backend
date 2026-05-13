package com.usal.whbackend.config;

import com.usal.whbackend.api.websocket.JwtHandshakeInterceptor;
import com.usal.whbackend.api.websocket.OrderWebSocketHandler;
import com.usal.whbackend.api.websocket.StockAlertWebSocketHandler;
import com.usal.whbackend.api.websocket.UserOrderHandshakeInterceptor;
import com.usal.whbackend.api.websocket.UserOrderWebSocketHandler;
import com.usal.whbackend.api.websocket.VehicleWebSocketHandler;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final OrderWebSocketHandler orderWebSocketHandler;
  private final UserOrderWebSocketHandler userOrderWebSocketHandler;
  private final VehicleWebSocketHandler vehicleWebSocketHandler;
  private final StockAlertWebSocketHandler stockAlertWebSocketHandler;
  private final JwtService jwtService;

  public WebSocketConfig(
      OrderWebSocketHandler orderWebSocketHandler,
      UserOrderWebSocketHandler userOrderWebSocketHandler,
      VehicleWebSocketHandler vehicleWebSocketHandler,
      StockAlertWebSocketHandler stockAlertWebSocketHandler,
      JwtService jwtService) {
    this.orderWebSocketHandler = orderWebSocketHandler;
    this.userOrderWebSocketHandler = userOrderWebSocketHandler;
    this.vehicleWebSocketHandler = vehicleWebSocketHandler;
    this.stockAlertWebSocketHandler = stockAlertWebSocketHandler;
    this.jwtService = jwtService;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(orderWebSocketHandler, "/ws/v1/orders")
        .addInterceptors(
            new JwtHandshakeInterceptor(
                jwtService, Set.of("ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "ADMIN_SALES")))
        .setAllowedOrigins("*");

    registry
        .addHandler(userOrderWebSocketHandler, "/ws/v1/orders/{userId}")
        .addInterceptors(new UserOrderHandshakeInterceptor(jwtService))
        .setAllowedOrigins("*");

    registry
        .addHandler(vehicleWebSocketHandler, "/ws/v1/vehicles")
        .addInterceptors(
            new JwtHandshakeInterceptor(jwtService, Set.of("ADMIN_SYSTEM", "ADMIN_WAREHOUSE")))
        .setAllowedOrigins("*");

    registry
        .addHandler(stockAlertWebSocketHandler, "/ws/v1/stock/alerts")
        .addInterceptors(
            new JwtHandshakeInterceptor(
                jwtService, Set.of("ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "ADMIN_SALES")))
        .setAllowedOrigins("*");
  }
}
