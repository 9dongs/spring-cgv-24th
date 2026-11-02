package com.spring_cgv_24th.domain.payment.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class TossPaymentGateway implements PaymentGateway {

    private static final Set<String> REJECTION_CODES = Set.of(
            "REJECT_CARD_PAYMENT", "REJECT_ACCOUNT_PAYMENT", "REJECT_CARD_COMPANY",
            "INVALID_REJECT_CARD", "INVALID_STOPPED_CARD", "INVALID_CARD_LOST_OR_STOLEN");

    private final RestClient client;

    public TossPaymentGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public Result confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        return execute(() -> client.post().uri("/v1/payments/confirm")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ConfirmBody(paymentKey, orderId, amount))
                .retrieve().body(Result.class));
    }

    @Override
    public Result find(String paymentKey) {
        return execute(() -> client.get().uri("/v1/payments/{paymentKey}", paymentKey)
                .retrieve().body(Result.class));
    }

    private Result execute(Supplier<Result> request) {
        try {
            return request.get();
        } catch (RestClientResponseException error) {
            // 중복 처리·인증 오류·서버 오류를 결제 거절로 오인하지 않는다.
            TossError body;
            try {
                body = error.getResponseBodyAs(TossError.class);
            } catch (RuntimeException decodingFailure) {
                throw new Failure(false);
            }
            throw new Failure(error.getStatusCode().is4xxClientError()
                    && body != null && body.code() != null && REJECTION_CODES.contains(body.code()));
        } catch (RestClientException error) {
            throw new Failure(false);
        }
    }

    private record ConfirmBody(String paymentKey, String orderId, long amount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TossError(String code) {
    }
}
