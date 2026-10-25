package com.clinicflow.web.dto;

import com.clinicflow.domain.port.*;

import java.time.Instant;
import java.util.List;

/** Port views (domain.port) to wire shapes. The only place that knows both. */
public final class Mappers {
    private Mappers() {}

    public static Dto.VisitType visitType(VisitTypeView v) {
        return new Dto.VisitType(v.id(), v.code(), v.label(), v.durationMinutes());
    }

    public static Dto.Slot slot(SlotView s) { return new Dto.Slot(s.id(), s.startsAt(), s.endsAt(), s.available()); }

    public static List<Dto.Slot> slots(List<SlotView> s) { return s.stream().map(Mappers::slot).toList(); }

    private static Dto.SlotWindow window(Instant start, Instant end) {
        return start == null ? null : new Dto.SlotWindow(start, end);
    }

    private static Dto.Estimate estimate(EstimateView e) {
        return e == null ? null : new Dto.Estimate(e.minutes(), true, e.basis());
    }

    public static Dto.PatientAppointment patient(PatientView v) {
        return new Dto.PatientAppointment(v.reference(), v.patientName(), visitType(v.visitType()),
                window(v.slotStart(), v.slotEnd()), v.status(), v.queuePosition(), v.peopleAhead(),
                estimate(v.estimate()), v.canCancel(), v.canReschedule(), v.updatedAt());
    }

    public static Dto.StaffAppointment staff(StaffView v) {
        return new Dto.StaffAppointment(v.id(), v.reference(), v.patientName(), v.contact(), visitType(v.visitType()),
                window(v.slotStart(), v.slotEnd()), v.walkIn(), v.status(), v.queuePosition(), v.checkedInAt(),
                v.consultationStartedAt(), v.completedAt(), v.cancelledAt(), v.cancelReason(), v.version(),
                v.createdAt(), v.updatedAt());
    }

    public static Dto.AppointmentPage page(PageResult<StaffView> p) {
        return new Dto.AppointmentPage(p.items().stream().map(Mappers::staff).toList(), p.page(), p.size(), p.totalItems());
    }

    public static Dto.QueueView queue(QueueSnapshot q) {
        return new Dto.QueueView(q.date(), q.inConsultation().stream().map(Mappers::staff).toList(),
                q.waiting().stream().map(Mappers::staff).toList(), q.generatedAt());
    }

    public static Dto.Dashboard dashboard(DashboardView d) {
        return new Dto.Dashboard(d.date(), d.total(), d.booked(), d.waiting(), d.inConsultation(), d.completed(),
                d.cancelled(), d.walkIns(), d.generatedAt());
    }

    public static Dto.ActivityEntry activity(ActivityView a) {
        return new Dto.ActivityEntry(a.id(), a.appointmentId(), a.reference(), a.eventType(), a.from(), a.to(),
                a.actor(), a.actorRole(), a.occurredAt());
    }
}
