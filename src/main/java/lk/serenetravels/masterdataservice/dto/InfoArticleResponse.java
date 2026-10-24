package lk.serenetravels.masterdataservice.dto;

// One article with its full body (plain text, line breaks kept).
public record InfoArticleResponse(Integer id, String category, RefResponse destination, String title, String body) {
}
