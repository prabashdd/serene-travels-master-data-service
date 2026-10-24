package lk.serenetravels.masterdataservice.dto;

import java.time.LocalDate;
import java.util.List;

public record VehicleResponse(Integer id, String registrationNo, RefResponse vehicleType, int seatingCapacity,
                              String method, String ownerName, String insuranceCompany, String policyNumber,
                              LocalDate insuranceExpiryDate, LocalDate revenueLicenseExpiryDate,
                              RefResponse driver, RefResponse assistant, boolean active,
                              List<VehicleWarning> warnings) {
}
