package com.clinicflow.domain.port;
import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long totalItems) {}
