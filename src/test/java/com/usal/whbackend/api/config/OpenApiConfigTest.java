package com.usal.whbackend.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OpenApiConfigTest {

    @Test
    void smartWarehouseOpenAPI_isConfigured() {
        OpenAPI api = new OpenApiConfig().smartWarehouseOpenAPI();

        assertNotNull(api);
        assertEquals("SmartWarehouse API", api.getInfo().getTitle());
        assertEquals("v1.0", api.getInfo().getVersion());
    }
}
