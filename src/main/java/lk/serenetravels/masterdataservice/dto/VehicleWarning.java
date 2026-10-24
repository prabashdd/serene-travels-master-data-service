package lk.serenetravels.masterdataservice.dto;

// Something that needs attention on an active vehicle. daysLeft is signed: negative means "expired that many
// days ago"; it is null for warnings that are not about a date.
public record VehicleWarning(String code, Integer daysLeft) {
}
