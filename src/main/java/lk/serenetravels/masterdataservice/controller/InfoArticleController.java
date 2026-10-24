package lk.serenetravels.masterdataservice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lk.serenetravels.masterdataservice.dto.InfoArticleResponse;
import lk.serenetravels.masterdataservice.dto.InfoArticleSummaryResponse;
import lk.serenetravels.masterdataservice.dto.PageResponse;
import lk.serenetravels.masterdataservice.service.InfoArticleService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Tag(name = "Distances & Articles", description = "Reference text about destinations, plus road distances between them.")
@RestController
@RequestMapping("/api/v1/master-data")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class InfoArticleController {

    private final InfoArticleService articleService;

    public InfoArticleController(InfoArticleService articleService) {
        this.articleService = articleService;
    }

    @Operation(summary = "List info articles",
            description = "Rows carry a short excerpt and the body length, not the full body (fetch one article for that). "
                    + "Filter with q (searches title and body), category (TRAVEL_TIP, VISA, WEATHER, GENERAL), destinationId "
                    + "(add includeCountryWide=true to also get the articles that apply to the whole country) and "
                    + "countryWide (true = only country-wide, false = only destination-specific). Sort with sort=title,asc "
                    + "(repeatable); fields: title, category, destination, id.")
    @GetMapping("/info-articles")
    public PageResponse<InfoArticleSummaryResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer destinationId,
            @RequestParam(defaultValue = "false") boolean includeCountryWide,
            @RequestParam(required = false) Boolean countryWide,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "field,direction - repeatable, e.g. sort=category,asc&sort=title,asc")
            HttpServletRequest request) {
        // Read the raw values: a bound List<String> would split "name,asc" on the comma.
        String[] sort = request.getParameterValues("sort");
        return articleService.list(q, category, destinationId, includeCountryWide, countryWide,
                sort == null ? List.of() : Arrays.asList(sort), page, size);
    }

    @Operation(summary = "One article with its full body")
    @GetMapping("/info-articles/{id}")
    public InfoArticleResponse get(@PathVariable int id) {
        return articleService.get(id);
    }

    @Operation(summary = "Add an article",
            description = "Required: category (TRAVEL_TIP, VISA, WEATHER or GENERAL), title (at most 200 characters), body "
                    + "(plain text, at most 20,000 characters; line breaks are kept). Optional: destinationId - leave it out "
                    + "for an article that applies to the whole country. The same title in the same category for the same "
                    + "destination can only be added once (409 DUPLICATE).")
    @PostMapping("/info-articles")
    public ResponseEntity<InfoArticleResponse> create(@RequestBody Map<String, Object> body) {
        InfoArticleResponse created = articleService.create(body);
        return ResponseEntity.created(URI.create("/api/v1/master-data/info-articles/" + created.id())).body(created);
    }

    @Operation(summary = "Quick edit: send only the fields that changed",
            description = "Send destinationId null to make an article country-wide.")
    @PatchMapping("/info-articles/{id}")
    public InfoArticleResponse update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        return articleService.update(id, body);
    }

    @Operation(summary = "Delete an article")
    @DeleteMapping("/info-articles/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        articleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
