package lk.serenetravels.masterdataservice.dto;

import java.util.List;

public record RateFamilyResponse(String name, String label, RateUnit unit, boolean perKm,
                                 List<RateKeyResponse> keys) {
}
