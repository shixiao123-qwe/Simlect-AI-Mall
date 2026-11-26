package com.simlect.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StockReadBatchDTO(
        @NotEmpty @Size(max=50) List<@NotBlank @Size(max=15) String> productIds) { }
