package lk.serenetravels.masterdataservice.dto;

// A foreign key as the FE wants it: the id to send back plus the name to show, so no second lookup is needed.
public record RefResponse(Integer id, String name) {
}
