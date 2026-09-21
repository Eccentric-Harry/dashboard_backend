package com.personal_dashboard.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Outcome of an immediate test push, one entry per registered device. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushTestResult {

    private int deviceCount;
    private int accepted;
    private List<DeviceOutcome> outcomes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeviceOutcome {
        private String subscriptionId;
        private String endpointOrigin;
        /** ACCEPTED | EXPIRED | RETRYABLE | PERMANENT */
        private String kind;
        private Integer statusCode;
        private String message;
    }
}
