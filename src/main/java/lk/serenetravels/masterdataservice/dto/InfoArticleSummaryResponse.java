package lk.serenetravels.masterdataservice.dto;

// A row in the article list: the body is left out (it can be long) and replaced by a short excerpt and its length.
// destination is null for an article that applies to the whole country (visa rules, general travel tips).
public record InfoArticleSummaryResponse(Integer id, String category, RefResponse destination, String title,
                                         String excerpt, int bodyLength) {
}
