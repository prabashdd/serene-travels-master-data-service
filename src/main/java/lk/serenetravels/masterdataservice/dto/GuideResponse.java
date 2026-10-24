package lk.serenetravels.masterdataservice.dto;

import java.util.List;

// `languages` are the languages the guide speaks (each a Ref: id to send back, name to show).
public record GuideResponse(Integer id, String guideNo, String name, String nickname, String guideType, String nic,
                            String contact, String email, String address, String town, boolean active,
                            List<RefResponse> languages) {
}
