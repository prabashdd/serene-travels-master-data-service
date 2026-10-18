package lk.serenetravels.masterdataservice.dto;

import java.util.List;

// Same JSON shape as Spring Data's Page, so every list endpoint looks the same to the FE.
public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int number, int size,
                              boolean first, boolean last, boolean empty) {

    public static <T> PageResponse<T> of(List<T> content, long totalElements, int number, int size) {
        int totalPages = (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, totalElements, totalPages, number, size,
                number == 0, number + 1 >= totalPages, content.isEmpty());
    }
}
