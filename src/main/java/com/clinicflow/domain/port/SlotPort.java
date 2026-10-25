package com.clinicflow.domain.port;
import java.time.LocalDate;
import java.util.List;

public interface SlotPort {
    List<VisitTypeView> visitTypes();
    /** Slots for one clinic date; past slots omitted; each marked available or taken. Empty = closed / none. */
    List<SlotView> slotsFor(LocalDate date);
}
