package lk.serenetravels.masterdataservice.dto;

import java.time.LocalDate;

// One thing that needs attention. Alerts are worked out on request from the current data; nothing is stored.
// dueDate / daysLeft are set when the alert is about a date: negative daysLeft means "that many days ago".
// subjectType says what subject is: vehicle, property, attraction or rate:<family>.
public record AlertResponse(String code, String severity, String subjectType, RefResponse subject, LocalDate dueDate,
                            Integer daysLeft, String message) {
}
