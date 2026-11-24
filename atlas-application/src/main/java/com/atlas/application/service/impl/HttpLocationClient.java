package com.atlas.application.service.impl;

import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.dto.request.LocationReservationItemRequest;
import com.atlas.order.exception.LocationClientException;
import com.atlas.order.service.LocationClient;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "atlas.order-inventory-reservation.enabled", havingValue = "true")
public class HttpLocationClient implements LocationClient {
    private final Map<UUID, URI> endpoints;
    private final Map<UUID, RestClient> clients = new ConcurrentHashMap<>();
    private final RestClient.Builder builder;
    private final String serviceToken;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public HttpLocationClient(RestClient.Builder builder,
            @Value("${atlas.order-inventory-reservation.location-endpoints:}") String endpointConfiguration,
            @Value("${atlas.order-inventory-reservation.service-token:}") String serviceToken,
            @Value("${atlas.order-inventory-reservation.connect-timeout:250ms}") Duration connectTimeout,
            @Value("${atlas.order-inventory-reservation.read-timeout:PT2S}") Duration readTimeout) {
        this.builder = builder;
        this.endpoints = parseEndpoints(endpointConfiguration);
        this.serviceToken = serviceToken;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        if (serviceToken == null || serviceToken.length() < 32) {
            throw new IllegalStateException("Inventory service token must contain at least 32 characters");
        }
        if (endpoints.isEmpty()) throw new IllegalStateException("Inventory location endpoints are required");
    }

    @Override
    public ReservationWorkflowResponse.RemoteReservation reserve(UUID fulfillmentLocationId, UUID orderId, String idempotencyKey,
            List<LocationReservationItemRequest> items) {
        ReserveRequest request = new ReserveRequest(orderId,
                items.stream().map(item -> new RequestItem(item.sellerId(), item.skuId(), item.quantity())).toList());
        try {
            InventoryResponse response = client(fulfillmentLocationId).post()
                    .uri("/internal/v1/fulfillment-locations/{locationId}/reservations", fulfillmentLocationId)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(request)
                    .retrieve()
                    .body(InventoryResponse.class);
            if (response == null || response.reservationId() == null || response.expiresAt() == null) {
                throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                        "EMPTY_RESPONSE", "Inventory service returned no reservation identity or expiry");
            }
            return new ReservationWorkflowResponse.RemoteReservation(response.reservationId(), response.status(), response.expiresAt());
        } catch (RestClientResponseException exception) {
            throw clientFailure(exception);
        } catch (ResourceAccessException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory service result is unknown");
        } catch (RestClientException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory service result is unknown");
        }
    }

    @Override
    public void cancel(UUID fulfillmentLocationId, UUID orderId, String idempotencyKey) {
        try {
            client(fulfillmentLocationId).post()
                    .uri("/internal/v1/fulfillment-locations/{locationId}/reservations/cancel", fulfillmentLocationId)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new CancellationRequest(orderId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw clientFailure(exception);
        } catch (ResourceAccessException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory cancellation result is unknown");
        } catch (RestClientException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory cancellation result is unknown");
        }
    }

    @Override
    public ReservationWorkflowResponse.RemoteReservation claim(UUID fulfillmentLocationId, UUID reservationId) {
        return transition(fulfillmentLocationId, reservationId, "claim", Set.of("CLAIMED"));
    }

    @Override
    public ReservationWorkflowResponse.RemoteReservation release(UUID fulfillmentLocationId, UUID reservationId) {
        return transition(fulfillmentLocationId, reservationId, "release",
                Set.of("RELEASED", "EXPIRED", "CANCELLED"));
    }

    @Override
    public ReservationWorkflowResponse.RemoteReservation restore(UUID fulfillmentLocationId, UUID reservationId) {
        return transition(fulfillmentLocationId, reservationId, "restore", Set.of("RELEASED"));
    }

    @Override
    public ReservationWorkflowResponse.RemoteReservation restock(UUID fulfillmentLocationId, UUID reservationId, String restockKey,
            List<LocationReservationItemRequest> items) {
        RestockRequest request = new RestockRequest(
                items.stream().map(item -> new RequestItem(item.sellerId(), item.skuId(), item.quantity())).toList());
        try {
            InventoryResponse response = client(fulfillmentLocationId).post()
                    .uri("/internal/v1/fulfillment-locations/{locationId}/reservations/{reservationId}/restock",
                            fulfillmentLocationId, reservationId)
                    .header("Idempotency-Key", restockKey)
                    .body(request)
                    .retrieve()
                    .body(InventoryResponse.class);
            if (response == null || !reservationId.equals(response.reservationId())) {
                throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                        "EMPTY_OR_UNEXPECTED_RESPONSE", "Inventory restock result is unknown");
            }
            return new ReservationWorkflowResponse.RemoteReservation(response.reservationId(), response.status(), response.expiresAt());
        } catch (RestClientResponseException exception) {
            throw clientFailure(exception);
        } catch (RestClientException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory restock result is unknown");
        }
    }

    @Override
    public long availableStock(UUID fulfillmentLocationId, UUID sellerId, String skuId) {
        try {
            StockResponse response = client(fulfillmentLocationId).get()
                    .uri("/internal/v1/fulfillment-locations/{locationId}/availability/{sellerId}/{skuId}",
                            fulfillmentLocationId, sellerId, skuId)
                    .retrieve()
                    .body(StockResponse.class);
            if (response == null) {
                throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                        "EMPTY_RESPONSE", "Inventory stock is unknown");
            }
            return response.available();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) return 0;
            throw clientFailure(exception);
        } catch (RestClientException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory stock is unknown");
        }
    }

    private ReservationWorkflowResponse.RemoteReservation transition(UUID fulfillmentLocationId, UUID reservationId,
            String operation, Set<String> acceptedStates) {
        try {
            InventoryResponse response = client(fulfillmentLocationId).post()
                    .uri("/internal/v1/fulfillment-locations/{locationId}/reservations/{reservationId}/{operation}",
                            fulfillmentLocationId, reservationId, operation)
                    .retrieve()
                    .body(InventoryResponse.class);
            if (response == null || !reservationId.equals(response.reservationId())
                    || !acceptedStates.contains(response.status())) {
                throw new LocationClientException(
                        LocationClientException.Failure.RETRYABLE_UNKNOWN,
                        "EMPTY_OR_UNEXPECTED_RESPONSE", "Inventory location transition result is unknown");
            }
            return new ReservationWorkflowResponse.RemoteReservation(response.reservationId(), response.status(), response.expiresAt());
        } catch (RestClientResponseException exception) {
            throw clientFailure(exception);
        } catch (ResourceAccessException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory location transition result is unknown");
        } catch (RestClientException exception) {
            throw new LocationClientException(LocationClientException.Failure.RETRYABLE_UNKNOWN,
                    "INVENTORY_UNAVAILABLE", "Inventory location transition result is unknown");
        }
    }

    private RestClient client(UUID fulfillmentLocationId) {
        URI endpoint = endpoints.get(fulfillmentLocationId);
        if (endpoint == null) {
            throw new LocationClientException(LocationClientException.Failure.PERMANENT,
                    "LOCATION_ENDPOINT_MISSING", "No inventory service endpoint is configured for the location");
        }
        return clients.computeIfAbsent(fulfillmentLocationId, ignored -> {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(connectTimeout);
            requestFactory.setReadTimeout(readTimeout);
            return builder.clone().baseUrl(endpoint.toString()).requestFactory(requestFactory)
                    .defaultHeader("X-Atlas-Service-Token", serviceToken).build();
        });
    }

    private LocationClientException clientFailure(RestClientResponseException exception) {
        String code = "INVENTORY_HTTP_" + exception.getStatusCode().value();
        try {
            Map<?, ?> body = exception.getResponseBodyAs(Map.class);
            Object bodyCode = body.get("code");
            if (bodyCode instanceof String value && !value.isBlank()) code = value;
        } catch (RuntimeException ignored) {
            // 계약 밖 응답은 업무 데이터로 해석하지 않고 HTTP status로만 분류한다.
        }
        int status = exception.getStatusCode().value();
        if ("INSUFFICIENT_STOCK".equals(code) || "RESERVATION_CANCELLED".equals(code)
                || "RESERVATION_EXPIRED".equals(code)) {
            return new LocationClientException(
                    LocationClientException.Failure.DEFINITIVE_REJECTION, code, "Inventory rejected the command");
        }
        if (status == 429 || status >= 500) {
            return new LocationClientException(
                    LocationClientException.Failure.RETRYABLE_UNKNOWN, code, "Inventory service is unavailable");
        }
        return new LocationClientException(
                LocationClientException.Failure.PERMANENT, code, "Inventory command was rejected");
    }

    private Map<UUID, URI> parseEndpoints(String configuration) {
        if (configuration == null || configuration.isBlank()) return Map.of();
        Map<UUID, URI> parsed = new HashMap<>();
        for (String entry : configuration.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalStateException("Inventory endpoints must use locationId=url;locationId=url format");
            }
            URI previous = parsed.put(UUID.fromString(parts[0].trim()), URI.create(parts[1].trim()));
            if (previous != null) throw new IllegalStateException("Inventory location endpoint is duplicated");
        }
        return Map.copyOf(parsed);
    }

    private record ReserveRequest(UUID orderId, List<RequestItem> items) {
    }

    private record StockResponse(long available) {
    }

    private record RestockRequest(List<RequestItem> items) {
    }

    private record RequestItem(UUID sellerId, String skuId, int quantity) {
    }

    private record CancellationRequest(UUID orderId) {
    }

    private record InventoryResponse(UUID reservationId, String status, Instant expiresAt) {
    }

}
