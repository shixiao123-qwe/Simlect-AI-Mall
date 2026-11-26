package com.simlect.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record AiProductFilterDTO(
        @NotEmpty @Size(max=50) List<@NotBlank @Size(max=15) String> productIds,
        @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal minPrice,
        @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal maxPrice,
        @Size(max=80) String category,
        @Size(max=10) List<@Valid Attribute> hardAttributes) {
    public record Attribute(@NotBlank @Size(max=30) String name, @NotBlank @Size(max=100) String value,
                            @Pattern(regexp="eq|ne|gte|lte") String operator) {
        public Attribute(String name, String value) { this(name,value,"eq"); }
    }
}
