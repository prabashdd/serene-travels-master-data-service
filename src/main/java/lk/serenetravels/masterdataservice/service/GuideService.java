package lk.serenetravels.masterdataservice.service;

import lk.serenetravels.masterdataservice.dto.GuideResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.dto.RefResponse;

import java.util.List;
import java.util.Map;

// The guides (national guides, chauffeur-guides and drivers licensed as guides) and the languages each speaks.
public interface GuideService {

    // `sort` entries are "field,direction" (fields: name, guideNo, guideType, town, id).
    PageResponse<GuideResponse> list(String q, String guideType, Integer languageId, Boolean active,
                                     List<String> sort, int page, int size);

    GuideResponse get(int id);

    // The body may carry languageIds (a list of language ids) to set the languages in the same call.
    GuideResponse create(Map<String, Object> body);

    // Partial update: only the fields present in the body change. Languages have their own call.
    GuideResponse update(int id, Map<String, Object> body);

    // Soft delete (active = false). Reactivate with update({"active": true}).
    void deactivate(int id);

    // REPLACES the whole set of languages the guide speaks.
    List<RefResponse> replaceLanguages(int guideId, List<Integer> languageIds);
}
