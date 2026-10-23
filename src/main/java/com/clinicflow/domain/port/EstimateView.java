package com.clinicflow.domain.port;
/** Always an estimate. basis explains how it was calculated and must be shown beside it. */
public record EstimateView(int minutes, String basis) {}
