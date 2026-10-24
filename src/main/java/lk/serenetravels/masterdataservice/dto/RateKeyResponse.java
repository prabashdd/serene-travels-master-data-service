package lk.serenetravels.masterdataservice.dto;

import java.util.List;

// allowedValues is non-null for a key that may only take a fixed set of values (render it as a dropdown).
public record RateKeyResponse(String name, String label, String lookup, boolean required, boolean nullMeansAll,
                              List<String> allowedValues) {
}
