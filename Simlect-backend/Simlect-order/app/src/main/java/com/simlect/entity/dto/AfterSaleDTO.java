package com.simlect.entity.dto;

import jakarta.validation.constraints.*;

/** Only application parameters: amounts and eligibility are never supplied by an LLM. */
public final class AfterSaleDTO {
    private AfterSaleDTO() {}
    public record Review(@NotBlank @Pattern(regexp="[a-f0-9]{32}") String requestId,
                         @NotNull Boolean approved,@NotBlank @Size(max=300) String note) {}
    public record Inspect(@NotBlank @Pattern(regexp="[a-f0-9]{32}") String requestId,
                          @NotNull Boolean accepted,@NotNull Boolean restockable,@NotBlank @Size(max=300) String note) {}
    public record ReturnParcel(@NotBlank @Pattern(regexp="[a-f0-9]{32}") String requestId,
                               @NotBlank @Size(max=50) String carrier,
                               @NotBlank @Pattern(regexp="[A-Za-z0-9-]{6,64}") String tracking) {}
    public record Request(@NotBlank @Pattern(regexp="[a-f0-9]{32}") String requestId) {}
    public record User(@NotBlank @Size(max=15) String userId) {}
    public record Check(@NotBlank @Size(max=15) String userId,
                        @NotBlank @Size(max=40) String orderItemId,
                        @NotBlank @Pattern(regexp="refund|return_refund|exchange") String type) {}
    public record Create(@NotBlank @Pattern(regexp="[a-f0-9]{32}") String requestId,
                         @NotBlank @Size(max=40) String orderItemId,
                         @NotBlank @Pattern(regexp="refund|return_refund|exchange") String type,
                         @NotBlank @Size(max=300) String reason) {}
}
