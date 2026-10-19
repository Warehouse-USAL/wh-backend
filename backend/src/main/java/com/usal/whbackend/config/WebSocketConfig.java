package com.usal.whbackend.config;

import com.usal.whbackend.api.websocket.JwtHandshakeInterceptor;
import com.usal.whbackend.api.websocket.OrderWebSocketHandler;
import com.usal.whbackend.api.websocket.StockAlertWebSocketHandler;
import com.usal.whbackend.api.websocket.UserOrderHandshakeInterceptor;
import com.usal.whbackend.api.websocket.UserOrderWebSocketHandler;
import com.usal.whbackend.api.websocket.VehicleWebSocketHandler;
import com.usal.whbackend.api.websocket.WsActivity;
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
  private final WsActivity wsActivity;

  public WebSocketConfig(
      OrderWebSocketHandler orderWebSocketHandler,
      UserOrderWebSocketHandler userOrderWebSocketHandler,
      VehicleWebSocketHandler vehicleWebSocketHandler,
      StockAlertWebSocketHandler stockAlertWebSocketHandler,
      JwtService jwtService,
      WsActivity wsActivity) {
    this.orderWebSocketHandler = orderWebSocketHandler;
    this.userOrderWebSocketHandler = userOrderWebSocketHandler;
    this.vehicleWebSocketHandler = vehicleWebSocketHandler;
    this.stockAlertWebSocketHandler = stockAlertWebSocketHandler;
    this.jwtService = jwtService;
    this.wsActivity = wsActivity;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(orderWebSocketHandler, "/ws/v1/orders")
        .addInterceptors(
            new JwtHandshakeInterceptor(
                jwtService,
                Set.of("SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "ADMIN_SALES"),
                WsActivity.ORDERS,
                wsActivity))
        .setAllowedOrigins("*");

    registry
        .addHandler(userOrderWebSocketHandler, "/ws/v1/orders/{userId}")
        .addInterceptors(new UserOrderHandshakeInterceptor(jwtService, wsActivity))
        .setAllowedOrigins("*");

    registry
        .addHandler(vehicleWebSocketHandler, "/ws/v1/vehicles")
        .addInterceptors(
            new JwtHandshakeInterceptor(
                jwtService,
                Set.of("SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE"),
                WsActivity.VEHICLES,
                wsActivity))
        .setAllowedOrigins("*");

    registry
        .addHandler(stockAlertWebSocketHandler, "/ws/v1/stock/alerts")
        .addInterceptors(
            new JwtHandshakeInterceptor(
                jwtService,
                Set.of("SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "ADMIN_SALES"),
                WsActivity.STOCK_ALERTS,
                wsActivity))
        .setAllowedOrigins("*");
  }
}
