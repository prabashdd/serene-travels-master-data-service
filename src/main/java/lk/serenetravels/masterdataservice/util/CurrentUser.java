package lk.serenetravels.masterdataservice.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {
    }

    // `created_by` always comes from the access token, never from the request body, so the audit
    // column cannot be spoofed by a client.
    public static String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String preferred = jwt.getClaimAsString("preferred_username");
            return preferred != null ? preferred : jwt.getSubject();
        }
        return authentication != null ? authentication.getName() : "unknown";
    }
}
