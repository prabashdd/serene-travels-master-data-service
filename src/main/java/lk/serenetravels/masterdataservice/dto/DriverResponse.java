package lk.serenetravels.masterdataservice.dto;

import java.time.LocalDate;
import java.util.List;

// `vehicles` lists the vehicles this person is currently assigned to (as driver or assistant); the Ref's
// name is the registration number.
public record DriverResponse(Integer id, String title, String name, String nameWithInitials, String shortname,
                             String gender, String nic, LocalDate dateOfBirth, String address, String contact,
                             String mobile2, String licenseNo, String role, boolean isChauffeur, boolean active,
                             List<RefResponse> vehicles) {
}
