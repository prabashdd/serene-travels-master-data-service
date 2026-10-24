package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.InfoArticleResponse;
import lk.serenetravels.masterdataservice.dto.InfoArticleSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;

import java.util.List;
import java.util.Map;

// Reference text for travellers: general destination write-ups, travel tips, visa and weather notes. An article
// belongs to one destination, or to none when it applies to the whole country. Hotel, activity, transport and guide
// information lives in its own data and is not duplicated here.
public interface InfoArticleService {

    // `q` searches title and body. `destinationId` keeps that destination's articles; with includeCountryWide the
    // country-wide ones (no destination) come too. countryWide=true keeps only country-wide articles.
    // Sort fields: title, category, destination, id.
    PageResponse<InfoArticleSummaryResponse> list(String q, String category, Integer destinationId,
                                                  boolean includeCountryWide, Boolean countryWide, List<String> sort,
                                                  int page, int size);

    InfoArticleResponse get(int id);

    InfoArticleResponse create(Map<String, Object> body);

    // Partial update of any field; send destinationId null to make an article country-wide.
    InfoArticleResponse update(int id, Map<String, Object> body);

    // Hard delete: nothing refers to an article.
    void delete(int id);
}
