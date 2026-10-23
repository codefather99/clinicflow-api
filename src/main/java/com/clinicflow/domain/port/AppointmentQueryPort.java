package com.clinicflow.domain.port;
import java.util.List;
import java.util.UUID;

public interface AppointmentQueryPort {
    PageResult<StaffView> search(AppointmentFilter filter);
    StaffView get(UUID appointmentId);
    /** Patient view by reference: derived position and labelled estimate. @throws NotFoundException */
    PatientView lookup(String reference);
    List<ActivityView> activity(UUID appointmentId);
    List<ActivityView> recent(int limit);
}
