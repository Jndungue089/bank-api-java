package com.sb24.api.service;

import com.sb24.api.domain.PendingTransfer;

/** Port to the interbank clearing system (in Angola: the national switch). Real implementations would call it over mTLS. */
public interface InterbankGateway {
    record Decision(boolean accepted, String reason) {}

    /** Transient problems (timeouts, 5xx) must be thrown so the queue retries; business rejections are returned. */
    Decision submit(PendingTransfer transfer, int attempt) throws TransientGatewayException;

    class TransientGatewayException extends RuntimeException {
        public TransientGatewayException(String message) { super(message); }
    }
}
