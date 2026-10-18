package lk.serenetravels.masterdataservice.dto;

public record RateKeyResponse(String name, String label, String lookup, boolean required, boolean nullMeansAll) {
}
